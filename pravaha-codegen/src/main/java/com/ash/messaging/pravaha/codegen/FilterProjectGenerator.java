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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

/**
 * Generates one fused method for a chain of filters and projections.
 *
 * <p>Fusion is the whole point. An interpreted chain pays, per record per operator, a megamorphic
 * virtual call, a boxed round trip, and an ordinal lookup. Fused code pays none of them: the filter's
 * test and the projection's field copies become straight-line code in a single counted loop the JIT
 * can unroll and vectorise. That is the difference between roughly 100 k and roughly 1 M records per
 * second per core (design section 12.1), and it is why the 1.0 draft's `Enumerable` execution model could
 * not have met its own targets.
 *
 * <p>Scope in this wave is filter and project. Aggregates end a stage because they buffer state, and
 * joins because they are an exchange point (design section 12.2). Anything outside that scope raises
 * {@code PRV-3101} and the caller uses the interpreted path -- which is the guarantee that
 * correctness never depends on generation succeeding.
 */
public final class FilterProjectGenerator {

    /** A fused chain and the shape it emits. */
    public record Fused(String source, String className, StreamSchema inputSchema, StreamSchema outputSchema) {}

    /**
     * Generates a fused stage.
     *
     * <p>Two shapes, and only two: filters over a scan, and one projection over filters over a scan.
     * A filter above a projection reads the projection's ordinals and a second projection composes
     * with the first; neither is generated -- each would need rewriting before emission, and until
     * that is done they refuse rather than read the wrong column (C-7).
     *
     * @param plan a chain of filters and projections rooted at a scan
     * @throws PravahaException {@code PRV-3101} if the chain contains anything not yet generable
     */
    public Fused generate(PhysicalOperator plan, String className) {
        List<PhysicalOperator> chain = flatten(plan);
        ScanOperator scan = (ScanOperator) chain.get(chain.size() - 1);
        StreamSchema inputSchema = scan.outputSchema();
        StreamSchema outputSchema = plan.outputSchema();

        RowLayout inputLayout = RowLayout.of(inputSchema);
        PredicateSource predicates = new PredicateSource(inputSchema);

        // Filters are emitted innermost-first so the cheapest rejection happens earliest; the
        // ordering Calcite chose is preserved rather than second-guessed.
        List<String> tests = new ArrayList<>();
        ProjectOperator projection = plan instanceof ProjectOperator project ? project : null;
        for (int i = chain.size() - 2; i >= 0; i--) {
            PhysicalOperator operator = chain.get(i);
            if (operator instanceof FilterOperator filter) {
                tests.add(predicates.emit("row", filter.predicate()));
            } else if (operator != projection) {
                throw new PravahaException(
                        CodegenErrors.UNSUPPORTED,
                        "this chain has a projection below its top, so a filter or projection above it reads "
                                + "the projection's columns rather than the scan's. The generator emits one "
                                + "projection, at the top; the interpreted path runs this.");
            }
        }

        SourceBuilder source = new SourceBuilder();
        emitClass(source, className, predicates, tests, projection, inputLayout, inputSchema, outputSchema);
        return new Fused(source.toString(), className, inputSchema, outputSchema);
    }

    private void emitClass(
            SourceBuilder source,
            String className,
            PredicateSource predicates,
            List<String> tests,
            ProjectOperator projection,
            RowLayout inputLayout,
            StreamSchema inputSchema,
            StreamSchema outputSchema) {

        RowLayout outputLayout = RowLayout.of(outputSchema);
        // Emitted first, into its own builder, because it is where an unsupported column refuses:
        // a refusal must come before anything is written, not halfway through the class.
        SourceBuilder projectionMethods = new SourceBuilder().at(1);
        if (projection != null) {
            emitProjectionMethods(
                    projectionMethods,
                    projection.sourceOrdinals(),
                    inputLayout,
                    outputLayout,
                    inputSchema,
                    outputSchema);
        }

        source.comment("GENERATED by Pravaha. Do not edit; regenerate from the plan.")
                .comment("Fused stage: " + tests.size() + " filter(s) then "
                        + (projection == null
                                ? "no projection: a passing row goes on as it is."
                                : "a " + projection.sourceOrdinals().size() + "-column projection."))
                .blank()
                .open("public final class " + className + " implements com.ash.messaging.pravaha.codegen.FusedStage {");

        predicates.emitLiteralFields(source);
        source.blank();

        source.comment("WHERE, every filter of the chain. Field offsets are constants: a read is one load.")
                .open("public boolean test(com.ash.messaging.pravaha.common.memory.MemoryRegion region, int row) {");
        for (String test : tests) {
            source.open("if (!" + test + ") {").line("return false;").close();
        }
        source.line("return true;").close().blank();

        source.open("public boolean projects() {")
                .line("return " + (projection != null) + ";")
                .close()
                .blank();

        source.open("public void project(com.ash.messaging.pravaha.common.memory.MemoryRegion region, int row,"
                + " com.ash.messaging.pravaha.common.memory.MemoryRegion out, int outRow) {");
        if (projection == null) {
            source.line("throw new UnsupportedOperationException(\"this stage has no projection\");");
        } else {
            emitProjectBody(source, projection.sourceOrdinals().size(), outputLayout);
        }
        source.close().blank();

        source.comment("A batch: the passing rows' projections, or, with no projection, their own offsets.")
                .open("public int process(com.ash.messaging.pravaha.common.memory.MemoryRegion region,"
                        + " long[] rowOffsets, int count,"
                        + " com.ash.messaging.pravaha.common.memory.MemoryRegion out,"
                        + " long[] outOffsets, int outBase) {")
                .line("int emitted = 0;")
                .open("for (int i = 0; i < count; i++) {")
                .line("int row = (int) rowOffsets[i];")
                .open("if (!test(region, row)) {")
                .line("continue;")
                .close();
        if (projection == null) {
            source.line("outOffsets[outBase + emitted] = row;");
        } else {
            source.line("project(region, row, out, (int) outOffsets[outBase + emitted]);");
        }
        source.line("emitted++;").close().line("return emitted;").close();

        source.append(projectionMethods);
        source.close();
    }

    /**
     * The projection's body: the row a {@code BinaryRowWriter} would write, byte for byte.
     *
     * <p>The writer zeroes the fixed region, stamps the output schema's stream id, writes each
     * column, then the weight, event time and sequence, and last the total length. This writes the
     * same bytes -- the stream id was missing before C-7 wired this in, which a reader of the row's
     * schema id would have seen as an unassigned stream.
     */
    private void emitProjectBody(SourceBuilder source, int columns, RowLayout outputLayout) {
        source.comment("The fixed region cleared, as BinaryRowWriter.begin clears it; arena space is reused.")
                .line("out.setMemory(outRow, " + outputLayout.fixedEnd() + ", (byte) 0);")
                .line("out.putInt(outRow + " + RowLayout.OFFSET_SCHEMA_ID + ", "
                        + outputLayout.schema().streamId() + ");");
        for (int chunk = 0; chunk < chunkCount(columns); chunk++) {
            source.line("project" + chunk + "(region, row, out, outRow);");
        }
        source.comment("Header: the Z-set weight travels with the row (design 9.2).")
                .line("out.putLong(outRow + " + RowLayout.OFFSET_WEIGHT + ", region.getLong(row + "
                        + RowLayout.OFFSET_WEIGHT + "));")
                .line("out.putLong(outRow + " + RowLayout.OFFSET_EVENT_TIME + ", region.getLong(row + "
                        + RowLayout.OFFSET_EVENT_TIME + "));")
                .line("out.putLong(outRow + " + RowLayout.OFFSET_SEQUENCE + ", region.getLong(row + "
                        + RowLayout.OFFSET_SEQUENCE + "));")
                .line("out.putInt(outRow + " + RowLayout.OFFSET_TOTAL_LENGTH + ", " + outputLayout.fixedEnd() + ");");
    }

    /**
     * How many columns one generated method copies.
     *
     * <p>The JVM refuses to JIT a method over 8 kB of bytecode, and a projection emits a handful of
     * instructions per column, so a wide enough projection would push {@code process} past the limit
     * and it would run interpreted -- slower than the interpreter it was generated to replace, with
     * nothing to indicate why. Sixty-four columns is comfortably inside the limit while keeping the
     * call overhead at one invocation per sixty-four field copies.
     */
    static final int COLUMNS_PER_METHOD = 64;

    private static int chunkCount(int columns) {
        return Math.max(1, (columns + COLUMNS_PER_METHOD - 1) / COLUMNS_PER_METHOD);
    }

    /**
     * Emits the projection as one method per chunk of columns.
     *
     * <p>Always split, even for two columns, rather than splitting only when a threshold is crossed.
     * A conditional split means the wide case takes a code path the narrow case never exercises, so
     * the first time it runs is on somebody's thousand-column table -- and the JIT inlines a small
     * private method called once per row without being asked, so the narrow case pays nothing for
     * the uniformity.
     */
    private void emitProjectionMethods(
            SourceBuilder source,
            List<Integer> projection,
            RowLayout inputLayout,
            RowLayout outputLayout,
            StreamSchema inputSchema,
            StreamSchema outputSchema) {

        int chunks = chunkCount(projection.size());
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * COLUMNS_PER_METHOD;
            int to = Math.min(projection.size(), from + COLUMNS_PER_METHOD);
            source.blank()
                    .comment("Columns " + from + " to " + (to - 1) + " of " + projection.size() + ".")
                    .open("private void project" + chunk
                            + "(com.ash.messaging.pravaha.common.memory.MemoryRegion region, int row,"
                            + " com.ash.messaging.pravaha.common.memory.MemoryRegion out, int outRow) {");
            emitProjection(
                    source, projection.subList(from, to), from, inputLayout, outputLayout, inputSchema, outputSchema);
            source.close();
        }
    }

    /**
     * Copies each selected column, <strong>and its null bit</strong>, as {@code RowStages.copyField}
     * does.
     *
     * <p><strong>Finding C-5.</strong> This once emitted the value and nothing else, so every NULL a
     * generated stage produced read back as the zero bytes underneath it.
     *
     * <p><strong>C-7</strong> tightened it to the interpreter's exact bytes, because this is now what
     * a registered query runs. {@code copyField} tests the null bit of every input column, NOT NULL
     * included; for a null it sets the output bit and writes no value, so the slot stays zero; and a
     * null bound for a NOT NULL output column is refused by {@code BinaryRowWriter.setNull} with an
     * {@code IllegalArgumentException}. All three are emitted here. A column whose input and output
     * types differ is refused: {@code copyField} reads by the output type, and the generator does not
     * reproduce a read of the wrong width.
     */
    private void emitProjection(
            SourceBuilder source,
            List<Integer> projection,
            int firstOutputOrdinal,
            RowLayout inputLayout,
            RowLayout outputLayout,
            StreamSchema inputSchema,
            StreamSchema outputSchema) {

        for (int index = 0; index < projection.size(); index++) {
            int out = firstOutputOrdinal + index;
            int in = projection.get(index);
            int from = inputLayout.offsetOf(in);
            int to = outputLayout.offsetOf(out);
            TypeName type = outputSchema.field(out).type().typeName();
            if (inputSchema.field(in).type().typeName() != type) {
                throw new PravahaException(
                        CodegenErrors.UNSUPPORTED,
                        "column '" + outputSchema.field(out).name() + "' is " + type + " out of a "
                                + inputSchema.field(in).type().typeName() + " column. The generator copies a "
                                + "column only into its own type; the interpreted path runs this.");
            }
            String copy =
                    switch (type) {
                        case BOOLEAN -> "out.putBoolean(outRow + " + to + ", region.getBoolean(row + " + from + "));";
                        case INT8 -> "out.putByte(outRow + " + to + ", region.getByte(row + " + from + "));";
                        case INT16 -> "out.putShort(outRow + " + to + ", region.getShort(row + " + from + "));";
                        case INT32, DATE -> "out.putInt(outRow + " + to + ", region.getInt(row + " + from + "));";
                        case INT64, TIME, TIMESTAMP_LTZ ->
                            "out.putLong(outRow + " + to + ", region.getLong(row + " + from + "));";
                        case FLOAT32 -> "out.putFloat(outRow + " + to + ", region.getFloat(row + " + from + "));";
                        case FLOAT64 -> "out.putDouble(outRow + " + to + ", region.getDouble(row + " + from + "));";
                        default ->
                            throw new PravahaException(
                                    CodegenErrors.UNSUPPORTED,
                                    "cannot generate a projection of " + type + " yet (column '"
                                            + outputSchema.field(out).name()
                                            + "'). The interpreted path handles it; this stage falls back.");
                    };

            int inNullByte = inputLayout.nullByteOffset(in);
            int inMask = inputLayout.nullBitMask(in) & 0xFF;
            source.comment("column " + outputSchema.field(out).name() + ": its null bit, then its value (C-5, C-7)")
                    .open("if ((region.getByte(row + " + inNullByte + ") & " + inMask + ") != 0) {");
            if (outputSchema.field(out).type().nullable()) {
                int outNullByte = outputLayout.nullByteOffset(out);
                int outMask = outputLayout.nullBitMask(out) & 0xFF;
                source.line("out.putByte(outRow + " + outNullByte + ", (byte) (out.getByte(outRow + " + outNullByte
                        + ") | " + outMask + "));");
            } else {
                source.line("throw new IllegalArgumentException(\"field " + out + " ('"
                        + outputSchema.field(out).name() + "') is NOT NULL and cannot be set null\");");
            }
            source.orElse().line(copy).close();
        }
    }

    /** Flattens a linear chain, root first, scan last. */
    private static List<PhysicalOperator> flatten(PhysicalOperator plan) {
        List<PhysicalOperator> chain = new ArrayList<>();
        PhysicalOperator current = plan;
        while (true) {
            chain.add(current);
            if (current instanceof ScanOperator) {
                return chain;
            }
            if (!(current instanceof FilterOperator || current instanceof ProjectOperator)) {
                throw new PravahaException(
                        CodegenErrors.UNSUPPORTED,
                        current.label() + " ends a fused stage and is not generable in this wave. "
                                + "The interpreted path runs it (design 12.4).");
            }
            current = current.inputs().get(0);
        }
    }
}
