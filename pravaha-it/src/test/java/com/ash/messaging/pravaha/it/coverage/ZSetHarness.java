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
package com.ash.messaging.pravaha.it.coverage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

/**
 * INCR.md's procedure B for any schema: the answer maintained over a stream of weighted changes,
 * and the answer computed from scratch over the net of those changes, both as Z-sets.
 *
 * <p>A Z-set here is a map from an output row to its total weight, with zero weights removed.
 * Consolidating what a pipeline emitted -- every {@code +1}, every {@code -1} -- gives the answer
 * the pipeline claims to hold; feeding a fresh pipeline only the rows that survive the input's own
 * consolidation gives the answer it ought to hold. An operator is right when the two are equal for
 * every input, retractions included.
 *
 * <p>Columns are written from plain Java values: {@code Long} for the integer and time types,
 * {@code String} for text, {@code BigDecimal} for a DECIMAL column (at the column's scale), and
 * {@code null} for null.
 */
final class ZSetHarness {

    /** One arrival: the row, the weight it carries, and its event time in nanoseconds. */
    record Change(List<Object> values, long weight, long eventTimeNanos) {

        static Change insert(long eventTimeNanos, Object... values) {
            return new Change(Arrays.asList(values), 1, eventTimeNanos);
        }

        Change retracted() {
            return new Change(values, -weight, eventTimeNanos);
        }
    }

    private ZSetHarness() {}

    /** The pipeline's answer: every emitted row, consolidated. */
    static Map<List<Object>, Long> maintained(StreamSchema schema, String sql, List<Change> changes, long watermark) {
        return maintained(schema, sql, changes, watermark, -1);
    }

    /**
     * As {@link #maintained(StreamSchema, String, List, long)}, with a checkpoint after the first
     * {@code checkpointAfter} changes: the pipeline's state is snapshotted, the pipeline closed, and a
     * fresh one restored from the snapshot is fed the rest. Negative for no checkpoint.
     */
    static Map<List<Object>, Long> maintained(
            StreamSchema schema, String sql, List<Change> changes, long watermark, int checkpointAfter) {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema).plan(sql));
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        RowLayout layout = RowLayout.of(schema);
        RowOutput output = () -> new CapturingRowWriter(plan.outputSchema(), out::add);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 22, 8)) {
            InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, output);
            try {
                BinaryRowWriter writer = new BinaryRowWriter(layout);
                BinaryRowView reader = new BinaryRowView(layout);
                long sequence = 0;
                for (Change change : changes) {
                    if (sequence == checkpointAfter) {
                        byte[] snapshot = pipeline.snapshotState();
                        pipeline.close();
                        pipeline = InterpretedPipeline.compile(plan, output);
                        pipeline.restoreState(snapshot);
                    }
                    long handle = arena.allocate(layout.rowSize(512));
                    writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                    for (int i = 0; i < change.values().size(); i++) {
                        write(writer, schema, i, change.values().get(i));
                    }
                    writer.weight(change.weight())
                            .eventTimestampNanos(change.eventTimeNanos())
                            .sequence(++sequence)
                            .commit();
                    arena.trimTo(handle, writer.sizeSoFar());
                    pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
                }
                if (watermark != Long.MIN_VALUE) {
                    pipeline.advanceWatermark(watermark);
                }
                pipeline.finish();
            } finally {
                pipeline.close();
            }
        }
        return consolidate(out, plan.outputSchema());
    }

    /** The answer from scratch: a fresh pipeline fed only the net input. */
    static Map<List<Object>, Long> fromScratch(StreamSchema schema, String sql, List<Change> changes, long watermark) {
        return maintained(schema, sql, net(changes), watermark);
    }

    /** The input's own consolidation, in first-arrival order, each surviving row repeated by weight. */
    static List<Change> net(List<Change> changes) {
        Map<List<Object>, Long> weights = new LinkedHashMap<>();
        Map<List<Object>, Long> times = new LinkedHashMap<>();
        for (Change change : changes) {
            List<Object> key = new ArrayList<>(change.values());
            key.add(change.eventTimeNanos());
            weights.merge(key, change.weight(), Long::sum);
            times.putIfAbsent(key, change.eventTimeNanos());
        }
        List<Change> net = new ArrayList<>();
        weights.forEach((key, weight) -> {
            if (weight < 0) {
                throw new IllegalArgumentException("the input retracts " + key + " more often than it inserts it");
            }
            for (long i = 0; i < weight; i++) {
                net.add(new Change(key.subList(0, key.size() - 1), 1, times.get(key)));
            }
        });
        return net;
    }

    /** Sums weights per row and drops zeros. A DECIMAL column is decoded at its declared scale. */
    static Map<List<Object>, Long> consolidate(List<CapturingRowWriter.Captured> captured, StreamSchema output) {
        Map<List<Object>, Long> zset = new LinkedHashMap<>();
        for (CapturingRowWriter.Captured row : captured) {
            List<Object> values = new ArrayList<>(row.values().length);
            for (int i = 0; i < row.values().length; i++) {
                Object value = row.values()[i];
                values.add(
                        value instanceof long[] decimal
                                ? Decimals.toBigDecimal(
                                        decimal[0],
                                        decimal[1],
                                        ((DecimalType) output.field(i).type()).scale())
                                : value);
            }
            zset.merge(values, row.weight(), Long::sum);
        }
        zset.values().removeIf(weight -> weight == 0);
        return zset;
    }

    private static void write(BinaryRowWriter writer, StreamSchema schema, int ordinal, Object value) {
        if (value == null) {
            writer.setNull(ordinal);
            return;
        }
        switch (schema.field(ordinal).type().typeName()) {
            case STRING -> writer.setString(ordinal, (String) value);
            case INT32, DATE -> writer.setInt(ordinal, ((Number) value).intValue());
            case FLOAT64 -> writer.setDouble(ordinal, ((Number) value).doubleValue());
            case DECIMAL -> {
                int scale = ((DecimalType) schema.field(ordinal).type()).scale();
                BigInteger unscaled = ((BigDecimal) value).setScale(scale).unscaledValue();
                writer.setDecimal(ordinal, unscaled.shiftRight(64).longValue(), unscaled.longValue());
            }
            default -> writer.setLong(ordinal, ((Number) value).longValue());
        }
    }
}
