/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.messaging.pravaha.codegen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.Predicate;

/**
 * Emits Java source for a predicate.
 *
 * <p>Every field offset becomes a compile-time constant, so a read is one load rather than a method
 * call that resolves an ordinal. String comparison becomes a UTF-8 byte comparison against a
 * pre-encoded literal -- no {@code String} is ever materialised, which is the difference between a
 * few nanoseconds and a few hundred (design section 12.3).
 *
 * <p>Null handling follows SQL: a comparison involving NULL is UNKNOWN, and a {@code WHERE} clause
 * treats UNKNOWN as false. The generated code checks the null bit before reading.
 *
 * <p><strong>Each comparison mirrors the interpreted predicate exactly (C-7).</strong> Since the
 * generated stage became what a registered query runs, the rule is that it gives the interpreter's
 * answer or does not run. So a comparison reads with the same accessor the predicate's {@code test}
 * uses ({@code getLong} for {@code CompareLong}, {@code getInt} for {@code CompareInt}, and so on),
 * checks the null bit on every column as {@code test} does -- a NOT NULL column included -- and
 * compares with the operator {@code test} applies. Where the predicate's accessor is not the width
 * of the column it reads, the generator refuses rather than reproduce the read, and the interpreter
 * runs it. A double literal is carried by its bits, so NaN, the infinities and negative zero arrive
 * exactly.
 */
final class PredicateSource {

    private final StreamSchema schema;
    private final RowLayout layout;
    private final List<byte[]> literals = new ArrayList<>();
    private final List<Double> doubles = new ArrayList<>();

    private static final Set<TypeName> LONG_READ = EnumSet.of(TypeName.INT64, TypeName.TIME, TypeName.TIMESTAMP_LTZ);
    private static final Set<TypeName> INT_READ = EnumSet.of(TypeName.INT32, TypeName.DATE);
    private static final Set<TypeName> DOUBLE_READ = EnumSet.of(TypeName.FLOAT64);
    private static final Set<TypeName> BOOLEAN_READ = EnumSet.of(TypeName.BOOLEAN);

    PredicateSource(StreamSchema schema) {
        this.schema = schema;
        this.layout = RowLayout.of(schema);
    }

    /** UTF-8 literals the generated class needs as fields, in declaration order. */
    List<byte[]> literals() {
        return literals;
    }

    /**
     * Emits a boolean expression for a predicate.
     *
     * <p>Switches over the same sealed IR the interpreted path evaluates, which is what makes the
     * differential tests compare two implementations of one specification rather than two
     * specifications.
     */
    String emit(String rowVar, Predicate predicate) {
        return switch (predicate) {
            case Predicate.True t -> "true";
            case Predicate.False f -> "false";
            case Predicate.And and -> join(rowVar, and.parts(), " && ");
            case Predicate.Or or -> join(rowVar, or.parts(), " || ");
            case Predicate.Not not -> "!(" + emit(rowVar, not.inner()) + ")";
            case Predicate.IsNull isNull -> isNull(rowVar, isNull.ordinal(), isNull.wantNull());
            case Predicate.CompareLong c ->
                comparison(rowVar, c.ordinal(), "getLong", LONG_READ, c.op().java(), c.value() + "L");
            case Predicate.CompareInt c ->
                comparison(rowVar, c.ordinal(), "getInt", INT_READ, c.op().java(), Integer.toString(c.value()));
            case Predicate.CompareDouble c ->
                comparison(rowVar, c.ordinal(), "getDouble", DOUBLE_READ, c.op().java(), doubleLiteral(c.value()));
            case Predicate.CompareBoolean c ->
                comparison(rowVar, c.ordinal(), "getBoolean", BOOLEAN_READ, "==", Boolean.toString(c.value()));
            case Predicate.CompareDecimal c -> decimal(rowVar, c);
            case Predicate.CompareString c -> string(rowVar, c);
            case Predicate.Like like ->
                // Refused for a structural reason rather than an unfinished one. This generator's
                // whole advantage on text is that it compares UTF-8 bytes against a pre-encoded
                // literal and never materialises a String; a regex needs one. Emitting LIKE here
                // would decode every row into a String to hand to a Matcher, which is slower than
                // the interpreted path it was meant to beat.
                throw new PravahaException(
                        CodegenErrors.UNSUPPORTED,
                        "predicate '" + like.describe() + "' is a LIKE, which the generator does not emit: "
                                + "matching a pattern needs a String and this stage exists to avoid making "
                                + "one. The interpreted path evaluates it correctly.");
            case Predicate.IsNullExpression n ->
                // TY-5's IS NULL over a computed expression. Refused here for the same reason
                // CompareExpressions is: emitting it means emitting the expression's own null
                // propagation, which has to match the interpreter exactly or the differential
                // tests start comparing two specifications. The interpreted path evaluates it.
                throw new PravahaException(
                        CodegenErrors.UNSUPPORTED,
                        "predicate '" + n.describe() + "' null-checks a computed expression, which the "
                                + "generator does not emit yet. The interpreted path evaluates it correctly "
                                + "and more slowly.");
            case Predicate.CompareExpressions c ->
                // Refused rather than generated. Generating arithmetic is the natural next step and
                // is not free: null propagation, overflow checks and integer-versus-floating-point
                // promotion all have to match the interpreter exactly, or the differential tests
                // start reporting differences that are really two specifications rather than one.
                // Until that is done properly, a query using it runs interpreted -- correct, slower,
                // and honest about which.
                throw new PravahaException(
                        CodegenErrors.UNSUPPORTED,
                        "predicate '" + c.describe() + "' compares computed expressions, which the generator "
                                + "does not emit yet. The interpreted path evaluates it correctly and more "
                                + "slowly.");
        };
    }

    private String join(String rowVar, List<Predicate> parts, String operator) {
        List<String> emitted = new ArrayList<>(parts.size());
        parts.forEach(part -> emitted.add(emit(rowVar, part)));
        return "(" + String.join(operator, emitted) + ")";
    }

    /**
     * Emits {@code column op literal} as the interpreted predicate evaluates it: null bit first, then
     * the predicate's own accessor, then the operator.
     *
     * @param getter the {@code MemoryRegion} accessor the predicate's {@code RowView} read resolves to
     * @param widths the column types that accessor reads at their own width; any other is refused
     */
    private String comparison(
            String rowVar, int ordinal, String getter, Set<TypeName> widths, String operator, String literal) {
        TypeName type = schema.field(ordinal).type().typeName();
        if (!widths.contains(type)) {
            throw new PravahaException(
                    CodegenErrors.UNSUPPORTED,
                    "the predicate reads column '" + schema.field(ordinal).name() + "' (" + type + ") with "
                            + getter + ", which is not that column's width. The generator emits only reads "
                            + "that match the column; the interpreted path runs this.");
        }
        int offset = layout.offsetOf(ordinal);
        String test = "region." + getter + "(" + rowVar + " + " + offset + ") " + operator + " " + literal;
        return "(!" + nullTest(rowVar, ordinal) + " && (" + test + "))";
    }

    /**
     * A decimal column against a literal at its scale (CG-1): the two 64-bit limbs the interpreted
     * predicate reads ({@code getDecimalHigh} at the slot, {@code getDecimalLow} eight bytes on),
     * compared by the same signed 128-bit order.
     */
    private String decimal(String rowVar, Predicate.CompareDecimal c) {
        int ordinal = c.ordinal();
        TypeName type = schema.field(ordinal).type().typeName();
        if (type != TypeName.DECIMAL) {
            throw new PravahaException(
                    CodegenErrors.UNSUPPORTED,
                    "the predicate compares column '" + schema.field(ordinal).name() + "' (" + type
                            + ") as a decimal; the interpreted path runs this.");
        }
        int offset = layout.offsetOf(ordinal);
        String test = "com.ash.messaging.pravaha.runtime.plan.Predicate.CompareDecimal.compare128(region.getLong("
                + rowVar + " + " + offset + "), region.getLong(" + rowVar + " + " + (offset + 8) + "), "
                + c.high() + "L, " + c.low() + "L) " + c.op().java() + " 0";
        return "(!" + nullTest(rowVar, ordinal) + " && (" + test + "))";
    }

    /** A double literal by its bits, as a field, so no value is lost to printing and parsing. */
    private String doubleLiteral(double value) {
        doubles.add(value);
        return "DBL_" + (doubles.size() - 1);
    }

    /**
     * {@code column = 'literal'} or {@code <>}, as a byte comparison that agrees with {@code
     * CompareString.test}, which decodes the column and compares Strings.
     *
     * <p>The two agree whenever the literal survives a UTF-8 round trip and does not contain the
     * replacement character: valid stored bytes decode to the literal exactly when they are its
     * encoding, and invalid ones decode to something containing U+FFFD, which such a literal cannot
     * equal. A literal outside that -- an unpaired surrogate, or U+FFFD itself -- is refused.
     */
    private String string(String rowVar, Predicate.CompareString c) {
        int ordinal = c.ordinal();
        TypeName type = schema.field(ordinal).type().typeName();
        if (type != TypeName.STRING) {
            throw new PravahaException(
                    CodegenErrors.UNSUPPORTED,
                    "the predicate compares column '" + schema.field(ordinal).name() + "' (" + type
                            + ") as text. The generator compares text only on a STRING column; the "
                            + "interpreted path runs this.");
        }
        String value = c.value();
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (value.indexOf('\uFFFD') >= 0 || !new String(utf8, StandardCharsets.UTF_8).equals(value)) {
            throw new PravahaException(
                    CodegenErrors.UNSUPPORTED,
                    "the literal compared with '" + schema.field(ordinal).name() + "' does not survive a UTF-8 "
                            + "round trip, so a byte comparison could disagree with the interpreter's String "
                            + "comparison. The interpreted path runs this.");
        }
        String test = utf8Comparison(rowVar, ordinal, c.op() == Predicate.Op.EQ ? "=" : "!=", value);
        return "(!" + nullTest(rowVar, ordinal) + " && (" + test + "))";
    }

    /**
     * A UTF-8 byte comparison.
     *
     * <p>The length check first is not an optimisation, it is what makes the comparison correct: two
     * strings of different byte length are never equal, and comparing only the prefix would match
     * {@code "COMPLETE"} against {@code "COMPLETED"}.
     */
    private String utf8Comparison(String rowVar, int ordinal, String operator, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        int index = literals.size();
        literals.add(utf8);
        int slot = layout.offsetOf(ordinal);
        String equal = "(region.getInt(" + rowVar + " + " + (slot + 4) + ") == " + utf8.length
                + " && region.equalsBytes(" + rowVar + " + region.getInt(" + rowVar + " + " + slot + "), LIT_"
                + index + "))";
        return "=".equals(operator) ? equal : "!" + equal;
    }

    private String nullTest(String rowVar, int ordinal) {
        int byteOffset = layout.nullByteOffset(ordinal);
        int mask = layout.nullBitMask(ordinal) & 0xFF;
        return "((region.getByte(" + rowVar + " + " + byteOffset + ") & " + mask + ") != 0)";
    }

    private String isNull(String rowVar, int ordinal, boolean wantNull) {
        return wantNull ? nullTest(rowVar, ordinal) : "!" + nullTest(rowVar, ordinal);
    }

    /** Emits the literal fields the generated class declares. */
    void emitLiteralFields(SourceBuilder source) {
        for (int i = 0; i < doubles.size(); i++) {
            long bits = Double.doubleToRawLongBits(doubles.get(i));
            source.comment("the double " + doubles.get(i) + ", by its bits")
                    .line("private static final double DBL_" + i + " = Double.longBitsToDouble(0x"
                            + Long.toHexString(bits) + "L);");
        }
        for (int i = 0; i < literals.size(); i++) {
            byte[] literal = literals.get(i);
            StringBuilder bytes = new StringBuilder();
            for (int b = 0; b < literal.length; b++) {
                if (b > 0) {
                    bytes.append(", ");
                }
                bytes.append(literal[b]);
            }
            source.comment("UTF-8 of a string literal, encoded once at generation rather than per row")
                    .line("private static final byte[] LIT_" + i + " = {" + bytes + "};");
        }
    }

    RowLayout layout() {
        return layout;
    }
}
