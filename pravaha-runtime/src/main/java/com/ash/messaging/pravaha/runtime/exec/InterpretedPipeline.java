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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.runtime.plan.SinkOperator;

/**
 * Compiles a physical plan into a chain of interpreted stages.
 *
 * <p>This is the interpreted half of design section 12.4's guarantee: <strong>every operator has a
 * correct, slow implementation, and correctness never depends on code generation succeeding</strong>.
 * Wave 3 adds the generated path; this one stays, both as the fallback when generation fails or a
 * stage exceeds the JIT's method-size limit, and as the independent implementation the differential
 * tests compare against.
 *
 * <p>Stages are built from the leaf upward and wired downward, so each holds a direct reference to
 * the next. No lookup, no dispatch table, no map.
 */
public final class InterpretedPipeline implements AutoCloseable {

    private final RowArena arena;
    private final RowProcessor head;
    private final List<Runnable> finishers = new ArrayList<>();
    private final ScanOperator scan;

    private InterpretedPipeline(RowArena arena, RowProcessor head, ScanOperator scan) {
        this.arena = arena;
        this.head = head;
        this.scan = scan;
    }

    /**
     * Builds a pipeline.
     *
     * @param plan the physical plan, whose root must be a sink
     * @param sink where the terminal stage writes
     */
    public static InterpretedPipeline compile(PhysicalOperator plan, RowOutput sink) {
        RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
        Builder builder = new Builder(arena, sink);
        RowProcessor head = builder.build(plan);
        InterpretedPipeline pipeline = new InterpretedPipeline(arena, head, builder.scan);
        pipeline.finishers.addAll(builder.finishers);
        return pipeline;
    }

    /** The stream this pipeline reads. */
    public String sourceStream() {
        return scan.streamName();
    }

    /** The schema rows must arrive in. */
    public StreamSchema inputSchema() {
        return scan.outputSchema();
    }

    /** Feeds one row through the pipeline. */
    public void accept(RowView row) {
        head.process(row);
    }

    /** Signals end of input, so stateful stages emit. */
    public void finish() {
        finishers.forEach(Runnable::run);
    }

    /** The arena stages allocate their output rows in. */
    public RowArena arena() {
        return arena;
    }

    @Override
    public void close() {
        arena.close();
    }

    /** Wires the stages, leaf first. */
    private static final class Builder {
        private final RowArena arena;
        private final RowOutput sink;
        private final List<Runnable> finishers = new ArrayList<>();
        private ScanOperator scan;

        Builder(RowArena arena, RowOutput sink) {
            this.arena = arena;
            this.sink = sink;
        }

        RowProcessor build(PhysicalOperator operator) {
            return switch (operator) {
                case SinkOperator s -> {
                    RowProcessor terminal = row -> copyInto(sink, row, s.outputSchema());
                    yield buildInput(s.inputs().get(0), terminal);
                }
                case ScanOperator s -> {
                    scan = s;
                    yield row -> {};
                }
                default -> {
                    // A plan whose root is not a sink still has to run for tests and EXPLAIN; treat
                    // the root's output as the result.
                    RowProcessor terminal = row -> copyInto(sink, row, operator.outputSchema());
                    yield buildInput(operator, terminal);
                }
            };
        }

        /** Builds {@code operator} pushing into {@code downstream}, returning the chain's head. */
        private RowProcessor buildInput(PhysicalOperator operator, RowProcessor downstream) {
            return switch (operator) {
                case ScanOperator s -> {
                    scan = s;
                    yield downstream;
                }
                case FilterOperator f -> {
                    RowProcessor self = row -> {
                        if (f.predicate().test(row)) {
                            downstream.process(row);
                        }
                    };
                    yield buildInput(f.input(), self);
                }
                case ProjectOperator p -> {
                    RowProcessor self = projector(p, downstream);
                    yield buildInput(p.input(), self);
                }
                case AggregateOperator a -> {
                    GlobalAggregate aggregate = new GlobalAggregate(a, arena, downstream);
                    finishers.add(aggregate::emit);
                    yield buildInput(a.input(), aggregate);
                }
                case SinkOperator s -> buildInput(s.input(), downstream);
            };
        }

        private RowProcessor projector(ProjectOperator project, RowProcessor downstream) {
            RowLayout layout = RowLayout.of(project.outputSchema());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            List<Integer> ordinals = project.sourceOrdinals();

            return row -> {
                long handle = arena.allocate(layout.rowSize(1024));
                if (handle == ArenaHandle.NULL) {
                    throw new PravahaException(
                            RuntimeErrors.ARENA_EXHAUSTED,
                            "the projection's arena is full; raise arena.slab.size or reduce the batch size");
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
            default -> to.setString(toOrdinal, from.getString(fromOrdinal));
        }
    }

    private static void copyInto(RowOutput output, RowView row, StreamSchema schema) {
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
