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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A SQL query running on the lane runtime.
 *
 * <p>Until this test the engine was two halves that never touched: a lane runtime with its own
 * threads, arenas and backpressure, exercised only by its own tests, and a SQL path driven from
 * whichever thread called it. Both worked. Neither was the engine.
 *
 * <p>What this checks is the join. Records go in through a plugin reader, cross the ingest pump into
 * a lane's inbox, are read in place by that lane's thread, run through a pipeline compiled from SQL,
 * and come out the other side -- with nothing shared between lanes and no row lost on the way.
 */
@Timeout(120)
class QueryOnLanesTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(1024, 128)
                .withBatchSize(64)
                .withArena(1 << 20, 4)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("query-lane", true);
    }

    /** A reader that produces a fixed number of rows and then stops. */
    private static final class FiniteReader implements PartitionReader {
        private final int total;
        private final long firstId;
        private int produced;

        FiniteReader(long firstId, int total) {
            this.firstId = firstId;
            this.total = total;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && produced < total) {
                long id = firstId + produced;
                RowWriter writer = sink.beginRow();
                writer.setLong(0, id)
                        .setLong(1, id % 7)
                        .setLong(2, id * 10)
                        .weight(1L)
                        .eventTimestampNanos(id)
                        .sequence(id)
                        .commit();
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
    void aFilteredQueryRunsAcrossFourLanesAndLosesNothing() {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(schema()).plan("SELECT user_id, amount FROM txn WHERE amount > 100"));

        // One output per lane, collected concurrently: the lanes write from four different threads,
        // so anything shared here would be a race in the test rather than in the engine.
        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();
        MemoryAccess access = MemoryAccess.best();

        try (QueryExecution execution = QueryExecution.start(plan, 4, config(), access, () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            // One reader per lane, each producing a disjoint range of ids.
            List<IngestPump> pumps = new java.util.ArrayList<>();
            for (int lane = 0; lane < 4; lane++) {
                pumps.add(execution.pumpInto(
                        lane, new FiniteReader(lane * 1_000L, 1_000), BackpressurePolicy.defaults()));
            }

            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            long pumped = 0;
            while (pumped < 4_000 && System.nanoTime() < deadline) {
                pumped = 0;
                for (IngestPump pump : pumps) {
                    pump.pumpOnce(256);
                    pumped += pump.rowsPumped();
                }
                execution.checkHealth();
            }

            assertThat(pumped).as("every row was accepted by a lane").isEqualTo(4_000);
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
            execution.checkHealth();

            // 4 000 rows with ids 0..3999; amount is id * 10, so amount > 100 keeps id >= 11.
            assertThat(results).hasSize(4_000 - 11);
            assertThat(results.stream().mapToLong(row -> row.asLong(1)).min().orElseThrow())
                    .isEqualTo(110);

            // Every lane did some of the work: a query that ran entirely on one lane would pass
            // every assertion above and prove nothing about the runtime.
            assertThat(execution.metrics().stream().filter(m -> m.rowsIn() > 0))
                    .as("all four lanes processed records: %s", execution.metrics())
                    .hasSize(4);
            assertThat(execution.metrics().stream().mapToLong(m -> m.rowsIn()).sum())
                    .isEqualTo(4_000);
        }
    }

    @Test
    void eachLaneHasItsOwnPipelineAndArena() {
        // The reason a pipeline is per lane rather than shared: it holds mutable operator state, and
        // one shared between four threads would need a lock on every record -- which is exactly what
        // the single-writer model exists to remove.
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan("SELECT user_id FROM txn"));

        MemoryAccess access = MemoryAccess.best();
        java.util.Set<Object> arenas = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        try (QueryExecution execution = QueryExecution.start(plan, 4, config(), access, () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), captured -> {}))) {
            for (int lane = 0; lane < execution.laneCount(); lane++) {
                arenas.add(execution.lane(lane).context().arena());
            }
            assertThat(arenas).as("four lanes, four arenas, nothing shared").hasSize(4);
        }
    }
}
