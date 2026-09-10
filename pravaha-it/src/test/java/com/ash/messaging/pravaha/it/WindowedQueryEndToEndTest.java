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
package com.ash.messaging.pravaha.it;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A windowed query, from SQL text to window results.
 *
 * <p>Every part of this has its own tests -- the slicing arithmetic, the aggregate state, the
 * planner's recognition of the window function -- and none of that proves they fit together. This
 * does: SQL in, rows through the interpreted pipeline, one result per window per key out, with the
 * numbers checked against what the input actually contains.
 *
 * <p>It is also the first test in the codebase where a keyed {@code GROUP BY} is <em>allowed</em>.
 * Until Wave 4 every one was refused, because unbounded state is how incremental engines die; this
 * is the shape that is bounded, and the shape the refusal has been pointing at all along.
 */
class WindowedQueryEndToEndTest {

    private static final long SECOND = 1_000_000_000L;

    private static StreamSchema sourceSchema() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    /** One input row. */
    private record Txn(long id, long user, long amount, long eventTime) {}

    @Test
    void aTumblingCountAndSumPerUserPerWindow() {
        // Two users, three ten-second windows, amounts chosen so a mistake in window assignment or
        // in combining slices produces a different number rather than a near-miss.
        List<Txn> input = List.of(
                new Txn(1, 100, 10, 1 * SECOND),
                new Txn(2, 100, 20, 5 * SECOND),
                new Txn(3, 200, 30, 7 * SECOND),
                new Txn(4, 100, 40, 11 * SECOND),
                new Txn(5, 200, 50, 19 * SECOND),
                new Txn(6, 100, 60, 25 * SECOND));

        List<long[]> results = run(
                "SELECT window_start, window_end, user_id, COUNT(*), SUM(amount) FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                input);

        // Expected, worked out by hand from the input above:
        //   [0,10)  user 100: 2 rows, 30    user 200: 1 row, 30
        //   [10,20) user 100: 1 row,  40    user 200: 1 row, 50
        //   [20,30) user 100: 1 row,  60
        assertThat(results).as("five (window, user) groups have data").hasSize(5);

        List<String> rendered = results.stream()
                .map(row -> row[0] / SECOND + "-" + row[1] / SECOND + " user=" + row[2] + " count=" + row[3] + " sum="
                        + row[4])
                .sorted()
                .toList();
        assertThat(rendered)
                .containsExactly(
                        "0-10 user=100 count=2 sum=30",
                        "0-10 user=200 count=1 sum=30",
                        "10-20 user=100 count=1 sum=40",
                        "10-20 user=200 count=1 sum=50",
                        "20-30 user=100 count=1 sum=60");
    }

    @Test
    void aHoppingWindowCountsEachRecordInEveryWindowItBelongsTo() {
        // A 20-second window hopping every 10: each record is in two windows, and the sliced state
        // must produce that without the record being stored twice.
        List<Txn> input = List.of(
                new Txn(1, 100, 10, 5 * SECOND), new Txn(2, 100, 20, 15 * SECOND), new Txn(3, 100, 30, 25 * SECOND));

        List<long[]> results = run(
                "SELECT window_start, window_end, user_id, COUNT(*), SUM(amount) FROM "
                        + "TABLE(HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND, "
                        + "INTERVAL '20' SECOND)) GROUP BY window_start, window_end, user_id",
                input);

        long totalCounted = results.stream().mapToLong(row -> row[3]).sum();
        assertThat(totalCounted)
                .as("three records, each in two windows, counted once per window and no more")
                .isEqualTo(6);

        // The window covering [10,30) holds the second and third records.
        assertThat(results.stream()
                        .filter(row -> row[0] == 10 * SECOND)
                        .map(row -> row[4])
                        .toList())
                .containsExactly(50L);
    }

    /**
     * Plans the SQL, pushes the rows through, and returns what came out -- <em>copied</em>.
     *
     * <p>Copied because output rows are flyweights into the collector's arena, and returning them
     * across the arena's close is a use-after-free. The first version of this test did exactly that
     * and failed with "region is closed" -- which is the design's own rule (section 8.5: anything
     * outliving a batch is copied, explicitly) catching the person who wrote it down.
     */
    private static List<long[]> run(String sql, List<Txn> input) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(sourceSchema()).plan(sql));

        RowLayout inputLayout = RowLayout.of(sourceSchema());
        Collector collector = new Collector(plan.outputSchema());
        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, collector)) {

            BinaryRowWriter writer = new BinaryRowWriter(inputLayout);
            BinaryRowView view = new BinaryRowView(inputLayout);
            for (Txn txn : input) {
                long handle = feed.allocate(inputLayout.rowSize(256));
                assertThat(handle).isNotEqualTo(ArenaHandle.NULL);
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, txn.id())
                        .setLong(1, txn.user())
                        .setLong(2, txn.amount())
                        .setLong(3, txn.eventTime())
                        .weight(1L)
                        .eventTimestampNanos(txn.eventTime())
                        .sequence(txn.id())
                        .commit();
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            // End of input. A bounded source must not leave its final windows unemitted -- that
            // looks exactly like the query being wrong about its last period.
            pipeline.finish();

            List<long[]> copied = new ArrayList<>(collector.rows.size());
            for (RowView row : collector.rows) {
                long[] values = new long[row.schema().fieldCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = row.getLong(i);
                }
                copied.add(values);
            }
            return copied;
        } finally {
            collector.close();
        }
    }

    /** Holds pipeline output. */
    private static final class Collector implements RowOutput, AutoCloseable {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        private final BinaryRowWriter writer;
        private final List<RowView> rows = new ArrayList<>();

        Collector(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
        }

        @Override
        public RowWriter begin() {
            long handle = arena.allocate(layout.rowSize(512));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            return new Recording(
                    writer,
                    () -> rows.add(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))));
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    /** Delegates to the writer and records the row on commit. */
    private record Recording(BinaryRowWriter delegate, Runnable onCommit) implements RowWriter {

        @Override
        public StreamSchema schema() {
            return delegate.schema();
        }

        @Override
        public RowWriter setNull(int ordinal) {
            delegate.setNull(ordinal);
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            delegate.setBoolean(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            delegate.setByte(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            delegate.setShort(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            delegate.setInt(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            delegate.setLong(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            delegate.setFloat(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            delegate.setDouble(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            delegate.setDecimal(ordinal, high, low);
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            delegate.setBytes(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            delegate.setString(ordinal, value);
            return this;
        }

        @Override
        public RowWriter weight(long weight) {
            delegate.weight(weight);
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            delegate.eventTimestampNanos(nanos);
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            delegate.sequence(sequence);
            return this;
        }

        @Override
        public int commit() {
            int size = delegate.commit();
            onCommit.run();
            return size;
        }

        @Override
        public void abort() {
            delegate.abort();
        }
    }
}
