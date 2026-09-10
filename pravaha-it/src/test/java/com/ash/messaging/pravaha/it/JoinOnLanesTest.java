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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.Lane;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A join running on the lane runtime, fed from two sources.
 *
 * <p>The interpreted pipeline's join tests feed rows from the calling thread. This one is the real
 * arrangement: two plugin readers, two ingest pumps, a lane thread that owns both inboxes, and a
 * join whose two sides are filled by different producers at different rates.
 *
 * <p>That last part is why the lane grew a second inbox rather than a tag on each row. Sharing one
 * buffer means a burst on the left can fill it and leave no room for the right -- and a join starved
 * on one side does not slow down, it stops producing while still reading happily. Separate inboxes
 * make each side backpressure on its own occupancy, which is the only arrangement that survives one
 * source being faster than the other.
 */
@Timeout(120)
class JoinOnLanesTest {

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    /**
     * Deliberately not shaped like {@link #orders()}.
     *
     * <p>Wider than the left side, with the key last. That is what it took to make the test notice
     * a lane decoding every input with the first input's layout. Row layouts align each field to
     * its own width, so an eight-byte field lands on the same eight-byte grid whatever precedes it:
     * three int64 columns against two agree on every offset, a leading string agrees too because a
     * variable-width slot is eight bytes like a long, and a narrow int32 in between changes
     * nothing. Three versions of this schema passed with the bug seeded. A key in a column the
     * other layout does not have at all is the case that cannot coincide.
     */
    private static StreamSchema users() {
        return StreamSchema.builder("users")
                .field("segment", Types.string())
                .field("tier", Types.int32())
                .field("region", Types.string())
                .field("user_id", Types.int64())
                .build();
    }

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(256, 128)
                .withBatchSize(32)
                .withArena(1 << 20, 8)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("join-lane", true);
    }

    /** Emits {@code total} rows of a two- or three-column schema and then stops. */
    private static final class FiniteReader implements PartitionReader {
        private final int total;
        private final int columns;
        private final long userCount;
        private int produced;

        FiniteReader(int total, int columns, long userCount) {
            this.total = total;
            this.columns = columns;
            this.userCount = userCount;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && produced < total) {
                long id = produced;
                RowWriter writer = sink.beginRow();
                if (columns == 3) {
                    writer.setLong(0, id).setLong(1, id % userCount).setLong(2, id * 10);
                } else {
                    writer.setString(0, "seg-" + (id % 4))
                            .setInt(1, (int) (id % 3))
                            .setString(2, "region-" + (id % 2))
                            .setLong(3, id);
                }
                writer.weight(1L).eventTimestampNanos(id).sequence(id).commit();
                produced++;
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset("n=" + produced);
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    @Test
    void aJoinRunsOnALaneFedByTwoSources() {
        PhysicalOperator plan = plan();
        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();

        // One lane. The join's state lives in the lane, and spreading one join over several lanes
        // needs a shuffle on the join key first -- which is the next piece of work, not this one.
        try (QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            assertThat(execution.streams()).containsExactly("orders", "users");

            IngestPump users = execution.pumpInto(0, "users", new FiniteReader(8, 2, 8), BackpressurePolicy.defaults());
            IngestPump orders =
                    execution.pumpInto(0, "orders", new FiniteReader(200, 3, 8), BackpressurePolicy.defaults());

            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (users.rowsPumped() + orders.rowsPumped() < 208 && System.nanoTime() < deadline) {
                users.pumpOnce(64);
                orders.pumpOnce(64);
                execution.checkHealth();
            }

            assertThat(users.rowsPumped() + orders.rowsPumped()).isEqualTo(208);
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
            execution.checkHealth();

            // Eight users, 200 orders spread over those eight user ids: every order joins exactly
            // once, whichever side of it arrived first.
            assertThat(results).hasSize(200);
            assertThat(results.stream().map(row -> row.asLong(0)).distinct().count())
                    .as("each order appears once")
                    .isEqualTo(200);
            // And the right side's columns decoded as themselves rather than as whatever sits at
            // that offset in the left side's layout.
            assertThat(results.stream()
                            .map(row -> String.valueOf(row.values()[1]))
                            .distinct())
                    .containsExactlyInAnyOrder("seg-0", "seg-1", "seg-2", "seg-3");
        }
    }

    @Test
    void theRightSideArrivingLastStillDecodesAsItself() {
        // Order matters more than it looks. When the right side arrives first, its rows are read
        // back out of join state -- through the join's own view of its own schema -- and a lane
        // that decoded the input with the wrong layout is invisible. Only a right row that arrives
        // after its match is read directly, on the way into the output row.
        PhysicalOperator plan = plan();
        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();

        try (QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            IngestPump orders =
                    execution.pumpInto(0, "orders", new FiniteReader(200, 3, 8), BackpressurePolicy.defaults());
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (orders.rowsPumped() < 200 && System.nanoTime() < deadline) {
                orders.pumpOnce(64);
                execution.checkHealth();
            }
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
            assertThat(results).as("no user rows yet, so nothing joins").isEmpty();

            IngestPump users = execution.pumpInto(0, "users", new FiniteReader(8, 2, 8), BackpressurePolicy.defaults());
            while (users.rowsPumped() < 8 && System.nanoTime() < deadline) {
                users.pumpOnce(8);
                execution.checkHealth();
            }
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();

            assertThat(results).hasSize(200);
            assertThat(results.stream()
                            .map(row -> String.valueOf(row.values()[1]))
                            .distinct())
                    .containsExactlyInAnyOrder("seg-0", "seg-1", "seg-2", "seg-3");
        }
    }

    @Test
    void oneInputFillingUpLeavesTheOtherEmpty() {
        // The property the second inbox exists for, tested where it is deterministic: on a lane
        // that has been built but not started, so nothing is draining and a full inbox stays full.
        // Pumping against a running lane cannot prove this -- the lane empties the backlog while
        // the test is looking, and a pump reading the wrong inbox's occupancy then succeeds anyway.
        try (Lane lane = new Lane(
                0, config(), MemoryAccess.best(), new int[0], context -> (region, offsets, count) -> count, null, 2)) {

            RowLayout layout = RowLayout.of(orders());
            try (RowArena source = new RowArena(MemoryAccess.best(), 1 << 16, 4)) {
                BinaryRowWriter writer = new BinaryRowWriter(layout);
                long handle = source.allocate(layout.rowSize(64));
                writer.begin(source.regionOf(handle), source.offsetOf(handle));
                writer.setLong(0, 1).setLong(1, 1).setLong(2, 1);
                writer.weight(1).eventTimestampNanos(1).sequence(1).commit();
                int length = writer.sizeSoFar();

                int accepted = 0;
                while (lane.offer(0, source.regionOf(handle), source.offsetOf(handle), length)) {
                    accepted++;
                }

                assertThat(accepted).as("the left inbox never filled").isPositive();
                assertThat(lane.inboxFill(0)).isEqualTo(1.0);
                assertThat(lane.inboxFill(1))
                        .as("the right inbox filled with the left side's rows")
                        .isZero();
                assertThat(lane.offer(1, source.regionOf(handle), source.offsetOf(handle), length))
                        .as("the right side was refused because the left side is full")
                        .isTrue();
            }
        }
    }

    @Test
    void aJoinOnSeveralLanesIsRefusedRatherThanSilentlySplit() {
        // Each lane compiles its own pipeline, so four lanes would be four independent joins with a
        // quarter of the rows each -- and a pair whose halves land on different lanes is never
        // formed. Nothing fails; the query just returns less than it should.
        assertThatThrownBy(() -> QueryExecution.start(plan(), 4, config(), MemoryAccess.best(), () ->
                        (RowOutput) () -> new CapturingRowWriter(plan().outputSchema(), row -> {})))
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-3021")
                .hasMessageContaining("shuffle on the join key");
    }

    @Test
    void feedingAJoinWithoutNamingTheStreamIsRefused() {
        PhysicalOperator plan = plan();
        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();

        try (QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            assertThatThrownBy(() -> execution.pumpInto(0, new FiniteReader(1, 3, 8), BackpressurePolicy.defaults()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("name the stream a reader feeds");

            assertThatThrownBy(() ->
                            execution.pumpInto(0, "nope", new FiniteReader(1, 3, 8), BackpressurePolicy.defaults()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not an input of this query");
        }
    }

    @Test
    void aSingleSourceQueryStillTakesAnUnnamedReader() {
        // The one-input path must not have become harder to use in the course of making two work.
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(orders(), users()).plan("SELECT order_id FROM orders"));
        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();

        try (QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            IngestPump pump = execution.pumpInto(0, new FiniteReader(10, 3, 8), BackpressurePolicy.defaults());
            List<Integer> moved = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (pump.rowsPumped() < 10 && System.nanoTime() < deadline) {
                moved.add(pump.pumpOnce(16));
            }

            assertThat(pump.rowsPumped()).isEqualTo(10);
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
            assertThat(results).hasSize(10);
        }
    }

    private static PhysicalOperator plan() {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(orders(), users())
                        .plan("SELECT o.order_id, u.segment FROM orders o JOIN users u ON o.user_id = u.user_id"));
    }
}
