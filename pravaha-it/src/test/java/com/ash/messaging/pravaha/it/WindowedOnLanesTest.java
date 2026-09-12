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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A windowed aggregate across more than one lane.
 *
 * <p>The lane model is the engine's whole concurrency story and windowed {@code GROUP BY} is its
 * most common operation, and until this test the two had never met: every windowed test in the suite
 * runs on one lane, and every multi-lane test is a join.
 */
class WindowedOnLanesTest {

    private static final long SECOND = 1_000_000_000L;

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static final String SQL = "SELECT window_start, window_end, user_id, SUM(amount) FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(1024, 128)
                .withBatchSize(32)
                .withArena(1 << 21, 8)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("windowed-lanes", true);
    }

    /** A finite source: twelve users over three windows, so every lane sees several keys. */
    private static final class Rows implements PartitionReader {
        private int produced;
        private final int total;

        Rows(int total) {
            this.total = total;
        }

        @Override
        public int poll(RecordSink sink, int max) {
            int emitted = 0;
            while (emitted < max && produced < total) {
                long id = produced;
                long at = (id / 12) * 10 * SECOND;
                RowWriter writer = sink.beginRow();
                writer.setLong(0, id % 12).setLong(1, 1).setLong(2, at);
                writer.weight(1L).eventTimestampNanos(at).sequence(id).commit();
                produced++;
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(Integer.toString(produced));
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    private static List<CapturingRowWriter.Captured> runOn(int lanes) throws Exception {
        List<CapturingRowWriter.Captured> out = java.util.Collections.synchronizedList(new ArrayList<>());
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));

        try (QueryExecution execution = QueryExecution.start(plan, lanes, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {
            var pump = execution.pumpInto(0, new Rows(36), BackpressurePolicy.defaults());
            int moved;
            do {
                moved = pump.pumpOnce(64);
                execution.checkHealth();
            } while (moved > 0);
            execution.awaitQuiescent(java.time.Duration.ofSeconds(30));
        }
        return out;
    }

    @Test
    @Timeout(60)
    void aKeyedAggregateOnSeveralLanesIsRefused() {
        // Before this guard the combination ran and produced eighteen groups as thirty-six rows:
        // each key landed on both lanes, each lane kept its own partial total, and both were
        // emitted. Nothing errored. A consumer reading one row per key silently got half of it.
        //
        // Worse than the join equivalent, which the engine already refused: a join short of pairs
        // produces too little, and this produces too much, which looks like data rather than
        // absence.
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));

        try (QueryExecution execution = QueryExecution.start(plan, 2, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {}))) {
            assertThatThrownBy(() -> execution.pumpInto(0, new Rows(18), BackpressurePolicy.defaults()))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3020")
                    .hasMessageContaining("nothing routes a row to the lane that owns its group")
                    .hasMessageContaining("run this query on one lane");
        }
    }

    @Test
    @Timeout(60)
    void aRowCanBeHandedStraightToTheEngine() throws Exception {
        // The seam the registry needs. It has a row and no source plugin, and today it bypasses
        // QueryExecution entirely and drives an InterpretedPipeline of its own -- which is why
        // registered queries get no lanes, no checkpointing and no watermarks. This is the way in.
        List<CapturingRowWriter.Captured> out = java.util.Collections.synchronizedList(new ArrayList<>());
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                        (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {

            RowLayout layout = RowLayout.of(schema());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (int i = 0; i < 12; i++) {
                long at = (i / 4) * 10 * SECOND;
                long handle = arena.allocate(layout.rowSize(64));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setLong(0, i % 4).setLong(1, 1).setLong(2, at);
                writer.weight(1L).eventTimestampNanos(at).sequence(i).commit();
                arena.trimTo(handle, writer.sizeSoFar());

                boolean taken = execution.accept("txn", view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
                assertThat(taken).as("the inbox has room for twelve rows").isTrue();
            }
            execution.awaitQuiescent(java.time.Duration.ofSeconds(20));
        }

        // Four users across three windows. The rows were applied on the lane's thread, which is
        // why awaitQuiescent is here and not decoration: reading straight after accept would race
        // the engine rather than test it.
        assertThat(out).hasSize(12);
        assertThat(out).allSatisfy(row -> assertThat(row.isNull(0)).isFalse());
    }

    @Test
    @Timeout(60)
    void aRowForAStreamTheQueryDoesNotReadIsRefused() {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 18, 4);
                QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                        (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {}))) {

            RowLayout layout = RowLayout.of(schema());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            long handle = arena.allocate(layout.rowSize(64));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setLong(0, 1)
                    .setLong(1, 1)
                    .setLong(2, 0)
                    .weight(1L)
                    .eventTimestampNanos(0)
                    .sequence(0)
                    .commit();
            RowView row = view.wrap(arena.regionOf(handle), arena.offsetOf(handle));

            assertThatThrownBy(() -> execution.accept("nowhere", row))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not an input of this query");
        }
    }

    @Test
    @Timeout(60)
    void oneLaneIsTheReference() throws Exception {
        List<CapturingRowWriter.Captured> out = runOn(1);

        // Twelve users across three windows, each summing one row.
        assertThat(out).hasSize(36);
        assertThat(out).allSatisfy(row -> assertThat(row.isNull(0)).isFalse());
    }

    @Test
    @Timeout(60)
    void aWindowedAggregateIsSingleLaneToday() {
        // Recording the limit rather than asserting a capability. Partitioning by a grouping key is
        // not built: pumpPartitionedInto routes by *join* keys and refuses a query without a join,
        // so there is no way to spread an aggregate across lanes correctly. It is single-lane until
        // there is, and the guard above is what stops that being discovered as wrong numbers.
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));

        try (QueryExecution execution = QueryExecution.start(plan, 2, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {}))) {
            assertThatThrownBy(() -> execution.pumpPartitionedInto("txn", new Rows(18), BackpressurePolicy.defaults()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no join, so there is no key to partition by");
        }
    }
}
