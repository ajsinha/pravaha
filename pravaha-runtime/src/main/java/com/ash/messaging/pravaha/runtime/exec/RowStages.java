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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;

/**
 * The interpreted stages that write a new row: a projection, a computed projection, and the field
 * copy every row-building operator shares.
 *
 * <p>Moved out of {@link InterpretedPipeline} when that class reached the line limit, unchanged. The
 * generated-stage fallback ({@link GeneratedChains}) builds its projection from here too, so the
 * interpreted projection and the one a refused generation falls back to are the same code.
 */
final class RowStages {

    private RowStages() {}

    /**
     * Evaluates an expression per output column.
     *
     * <p>The evaluators are resolved once, here, rather than per row: an expression tree is a
     * chain of virtual calls, and re-deciding which branch to take for every column of every
     * row would put the interpreter's dispatch cost on top of the arithmetic it is performing.
     */
    static RowProcessor computer(ComputeOperator compute, RowArena arena, RowProcessor downstream) {
        RowLayout layout = RowLayout.of(compute.outputSchema());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        List<Expression> expressions = compute.expressions();

        return row -> {
            long handle = arena.allocate(layout.rowSize(1024));
            if (handle == ArenaHandle.NULL) {
                throw new PravahaException(
                        RuntimeErrors.ARENA_EXHAUSTED,
                        "the compute stage's arena is full; raise pravaha.lane.arena.slab-bytes or reduce pravaha.lane.batch-size");
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            try {
                for (int out = 0; out < expressions.size(); out++) {
                    Expression expression = expressions.get(out);
                    if (expression.isNull(row)) {
                        // SQL's rule, not Java's: null in, null out. Writing a zero here would make
                        // a downstream SUM produce a number that looks entirely reasonable.
                        writer.setNull(out);
                        continue;
                    }
                    writeComputed(writer, out, expression, row, compute.outputSchema());
                }
            } catch (RuntimeException failure) {
                // A row whose evaluation failed is dead-lettered and the next one computed
                // (DLQPROJ-1), so the half-written row must not stay open on the writer.
                writer.abort();
                throw failure;
            }
            writer.weight(row.weight())
                    .eventTimestampNanos(row.eventTimestampNanos())
                    .sequence(row.sequence())
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        };
    }

    /**
     * Writes one computed column.
     *
     * <p>A plain column reference is copied rather than evaluated. It began as a correctness
     * fix -- the expression tree could produce only numbers, so a projection mixing {@code ts +
     * INTERVAL '10' SECOND} with a text column beside it wrote the string's bytes as a long and
     * failed at the writer, which is where the README's own query landed. The tree can evaluate
     * text now, so the short-circuit is what its name says instead: {@link
     * #copyField} moves the bytes across, where evaluating the column would
     * decode them into a {@code String} and immediately encode them back.
     */
    private static void writeComputed(
            RowWriter writer, int ordinal, Expression expression, RowView row, StreamSchema schema) {
        if (expression instanceof Expression.Column column) {
            copyField(row, column.ordinal(), writer, ordinal, schema);
            return;
        }
        switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> writer.setBoolean(ordinal, expression.evaluateLong(row) != 0);
            // NARROWINT-1: the expression is evaluated in 64 bits and checked against its own type
            // where it is computed (Expression.fitNarrow); the column is checked again here, so no
            // expression the planner typed differently from its column can be written as its low
            // bits. A wrapped value is never published.
            case INT8 -> writer.setByte(ordinal, (byte) narrow(expression, row, TypeName.INT8));
            case INT16 -> writer.setShort(ordinal, (short) narrow(expression, row, TypeName.INT16));
            case INT32, DATE -> writer.setInt(ordinal, (int) narrow(expression, row, TypeName.INT32));
            case FLOAT32 -> writer.setFloat(ordinal, (float) expression.evaluateDouble(row));
            case FLOAT64 -> writer.setDouble(ordinal, expression.evaluateDouble(row));
            case STRING -> writer.setString(ordinal, expression.evaluateString(row));
            case DECIMAL -> {
                // At the column's declared scale, which never rounds: the expression already
                // holds its value at that scale, and Decimals refuses rather than rounds.
                int scale = ((com.ash.messaging.pravaha.api.data.DecimalType)
                                schema.field(ordinal).type())
                        .scale();
                java.math.BigDecimal value = expression.evaluateDecimal(row);
                writer.setDecimal(
                        ordinal,
                        com.ash.messaging.pravaha.common.row.Decimals.high(value, scale),
                        com.ash.messaging.pravaha.common.row.Decimals.low(value, scale));
            }
            default -> writer.setLong(ordinal, expression.evaluateLong(row));
        }
    }

    /** The expression's value, refused as an overflow if it does not fit a column of {@code type}. */
    private static long narrow(Expression expression, RowView row, TypeName type) {
        return Expression.fitNarrow(expression.evaluateLong(row), type, expression);
    }

    static RowProcessor projector(ProjectOperator project, RowArena arena, RowProcessor downstream) {
        RowLayout layout = RowLayout.of(project.outputSchema());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        List<Integer> ordinals = project.sourceOrdinals();

        return row -> {
            long handle = arena.allocate(layout.rowSize(1024));
            if (handle == ArenaHandle.NULL) {
                throw new PravahaException(
                        RuntimeErrors.ARENA_EXHAUSTED,
                        "the projection's arena is full; raise pravaha.lane.arena.slab-bytes or reduce pravaha.lane.batch-size");
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            for (int out = 0; out < ordinals.size(); out++) {
                copyField(row, ordinals.get(out), writer, out, project.outputSchema());
            }
            writer.weight(row.weight())
                    .eventTimestampNanos(row.eventTimestampNanos())
                    .sequence(row.sequence())
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        };
    }

    /** Copies one field, preserving nulls. */
    static void copyField(RowView from, int fromOrdinal, RowWriter to, int toOrdinal, StreamSchema toSchema) {
        if (from.isNull(fromOrdinal)) {
            to.setNull(toOrdinal);
            return;
        }
        TypeName type = toSchema.field(toOrdinal).type().typeName();
        switch (type) {
            case BOOLEAN -> to.setBoolean(toOrdinal, from.getBoolean(fromOrdinal));
            case INT8 -> to.setByte(toOrdinal, from.getByte(fromOrdinal));
            case INT16 -> to.setShort(toOrdinal, from.getShort(fromOrdinal));
            case INT32, DATE -> to.setInt(toOrdinal, from.getInt(fromOrdinal));
            case INT64, TIME, TIMESTAMP_LTZ -> to.setLong(toOrdinal, from.getLong(fromOrdinal));
            case FLOAT32 -> to.setFloat(toOrdinal, from.getFloat(fromOrdinal));
            case FLOAT64 -> to.setDouble(toOrdinal, from.getDouble(fromOrdinal));
            case DECIMAL -> to.setDecimal(toOrdinal, from.getDecimalHigh(fromOrdinal), from.getDecimalLow(fromOrdinal));
            case STRING -> to.setString(toOrdinal, from.getString(fromOrdinal));
            // BYTES had no case, so it fell to the default and was read as text. A projection of a
            // binary column therefore produced a String, and the Arrow writer -- correctly
            // expecting byte[] for the type the schema declares -- threw ClassCastException at
            // serialisation. A BYTES column could not cross a projection, which is every query.
            case BYTES -> {
                com.ash.messaging.pravaha.api.data.MutableSlice slice =
                        new com.ash.messaging.pravaha.api.data.MutableSlice();
                from.getBytes(fromOrdinal, slice);
                byte[] bytes = new byte[slice.length()];
                if (bytes.length > 0 && from instanceof com.ash.messaging.pravaha.common.row.BinaryRowView binary) {
                    binary.region().getBytes(slice.offset(), bytes, 0, bytes.length);
                }
                to.setBytes(toOrdinal, bytes);
            }
            default ->
                // Refused rather than stringified. Reading an unhandled type as text is what turned
                // BYTES into a String here and hid the gap: the copy succeeded and the failure
                // surfaced two layers away, as a cast error naming neither the column nor the type.
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "column '" + toSchema.field(toOrdinal).name() + "' is " + type
                                + ", which this engine cannot yet copy between rows. It is declared, and moving "
                                + "it through a projection is not built.");
        }
    }

    static void copyInto(RowOutput output, RowView row, StreamSchema schema) {
        RowWriter writer = output.begin();
        for (int i = 0; i < schema.fieldCount(); i++) {
            copyField(row, i, writer, i, schema);
        }
        writer.weight(row.weight())
                .eventTimestampNanos(row.eventTimestampNanos())
                .sequence(row.sequence())
                .commit();
    }
}
