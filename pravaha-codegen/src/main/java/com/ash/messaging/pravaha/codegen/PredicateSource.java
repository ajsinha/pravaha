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
import java.util.List;

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
 * treats UNKNOWN as false. The generated code checks the null bit before reading, which is also why
 * a NOT NULL column is cheaper -- there is nothing to check.
 */
final class PredicateSource {

    private final StreamSchema schema;
    private final RowLayout layout;
    private final List<byte[]> literals = new ArrayList<>();

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
            case Predicate.CompareLong c -> comparison(rowVar, c.ordinal(), c.op().java(), c.value());
            case Predicate.CompareInt c -> comparison(rowVar, c.ordinal(), c.op().java(), c.value());
            case Predicate.CompareDouble c -> comparison(rowVar, c.ordinal(), c.op().java(), c.value());
            case Predicate.CompareString c ->
                comparison(rowVar, c.ordinal(), c.op() == Predicate.Op.EQ ? "=" : "!=", c.value());
            case Predicate.CompareBoolean c -> comparison(rowVar, c.ordinal(), "=", c.value());
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
     * Emits a boolean expression testing {@code column op literal}.
     *
     * @param rowVar the variable holding the row's byte offset
     */
    private String comparison(String rowVar, int ordinal, String operator, Object literal) {
        TypeName type = schema.field(ordinal).type().typeName();
        boolean nullable = schema.field(ordinal).type().nullable();
        int offset = layout.offsetOf(ordinal);

        String test =
                switch (type) {
                    case BOOLEAN ->
                        "region.getBoolean(" + rowVar + " + " + offset + ") " + ("=".equals(operator) ? "==" : "!=")
                                + " " + literal;
                    case INT8 -> "region.getByte(" + rowVar + " + " + offset + ") " + operator + " " + literal;
                    case INT16 -> "region.getShort(" + rowVar + " + " + offset + ") " + operator + " " + literal;
                    case INT32, DATE -> "region.getInt(" + rowVar + " + " + offset + ") " + operator + " " + literal;
                    case INT64, TIME, TIMESTAMP_LTZ ->
                        "region.getLong(" + rowVar + " + " + offset + ") " + operator + " " + literal + "L";
                    case FLOAT32 ->
                        "region.getFloat(" + rowVar + " + " + offset + ") " + operator + " " + literal + "f";
                    case FLOAT64 -> "region.getDouble(" + rowVar + " + " + offset + ") " + operator + " " + literal;
                    case STRING -> utf8Comparison(rowVar, ordinal, operator, String.valueOf(literal));
                    default ->
                        throw new UnsupportedOperationException(
                                "no generated comparison for " + type + " yet; the interpreted path handles it");
                };

        // Only pay for a null check on a column that can actually be null. This is the concrete
        // reason nullability is carried through the planner rather than defaulted.
        return nullable ? "(!" + nullTest(rowVar, ordinal) + " && (" + test + "))" : "(" + test + ")";
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
