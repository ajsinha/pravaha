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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.OperatorMetrics;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6 end to end: the per-operator counters on a whole {@link QueryExecution} rather than on one
 * pipeline.
 *
 * <p>Two things can only be asserted here. The first is that a query on several lanes adds up --
 * each lane compiles its own copy of the plan, so a console showing one graph is showing a sum, and
 * a sum that lost a lane would look entirely plausible. The second is that the numbers survive a
 * checkpoint and a restore: state is replaced under the operators, and counters that were reset by
 * that would make every recovery look like a query that had just started.
 */
@Timeout(120)
class OperatorObservabilityTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    @BeforeEach
    void measure() {
        InterpretedPipeline.measureOperators(true);
    }

    @AfterEach
    void stopMeasuring() {
        InterpretedPipeline.measureOperators(false);
    }

    private static LaneConfig laneConfig() {
        return LaneConfig.defaults()
                .withInbox(1024, 128)
                .withBatchSize(32)
                .withArena(1 << 20, 4)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("b6-lane", true);
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(TXN).plan(sql));
    }

    @Test
    void aQueryOnFourLanesAddsItsOperatorsUp() {
        PhysicalOperator plan = plan("SELECT user_id, amount FROM txn WHERE amount > 10");
        List<CapturingRowWriter.Captured> emitted = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                QueryExecution execution = QueryExecution.start(
                        plan,
                        4,
                        laneConfig(),
                        MemoryAccess.best(),
                        () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                            synchronized (emitted) {
                                emitted.add(row);
                            }
                        }))) {
            // Twenty rows per lane, of which ten pass the filter: amounts 1..20, keep > 10.
            for (int lane = 0; lane < 4; lane++) {
                for (long amount = 1; amount <= 20; amount++) {
                    offer(arena, execution, lane, "u" + lane, amount);
                }
            }
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();

            List<OperatorMetrics.Snapshot> operators = execution.operatorMetrics();
            assertThat(operators).isNotEmpty();
            OperatorMetrics.Snapshot root = operators.get(0);
            OperatorMetrics.Snapshot scan = operators.get(operators.size() - 1);
            assertThat(scan.operator()).isEqualTo("Scan");
            assertThat(scan.rowsIn())
                    .as("80 rows over four lanes, summed into one picture")
                    .isEqualTo(80);
            assertThat(root.rowsOut()).isEqualTo(40);
            assertThat(emitted).as("and the query really answered that many").hasSize(40);

            long laneRowsIn =
                    execution.metrics().stream().mapToLong(m -> m.rowsIn()).sum();
            assertThat(scan.rowsIn())
                    .as("the scan's rows in is the lanes' rows in: the same rows, counted one layer down")
                    .isEqualTo(laneRowsIn);
        }
    }

    @Test
    void theNumbersSurviveACheckpointAndARestore(@TempDir Path dir) {
        PhysicalOperator plan = plan(
                "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)");
        List<CapturingRowWriter.Captured> emitted = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                QueryExecution execution = QueryExecution.start(
                        plan,
                        1,
                        laneConfig(),
                        MemoryAccess.best(),
                        () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                            synchronized (emitted) {
                                emitted.add(row);
                            }
                        }))) {
            for (long i = 0; i < 100; i++) {
                offerAt(arena, execution, 0, "u" + (i % 5), 1L, i * 1_000_000_000L);
            }
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
            execution.advanceWatermark(50L * 1_000_000_000L);

            List<OperatorMetrics.Snapshot> before = execution.operatorMetrics();
            long scanBefore = before.get(before.size() - 1).rowsIn();
            assertThat(scanBefore).isEqualTo(100);
            long stateBefore = before.stream()
                    .filter(each -> each.stateBytes() != null)
                    .mapToLong(OperatorMetrics.Snapshot::stateBytes)
                    .sum();

            PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                    execution, new FileCheckpointStore(dir), Duration.ofHours(1), 3, Duration.ofSeconds(10), m -> {});
            Checkpoint taken = checkpointer.checkpointNow();
            assertThat(taken.operatorState()).isNotEmpty();

            execution.restore(taken, Duration.ofSeconds(10));

            List<OperatorMetrics.Snapshot> after = execution.operatorMetrics();
            assertThat(after.get(after.size() - 1).rowsIn())
                    .as("a restore replaces state, not the counters: a recovered query is not a new one")
                    .isEqualTo(scanBefore);
            assertThat(after)
                    .extracting(OperatorMetrics.Snapshot::watermarkNanos)
                    .as("and the watermark each operator had reached is still on it")
                    .containsOnly(50L * 1_000_000_000L);

            // And they keep counting afterwards.
            for (long i = 100; i < 150; i++) {
                offerAt(arena, execution, 0, "u" + (i % 5), 1L, i * 1_000_000_000L);
            }
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
            List<OperatorMetrics.Snapshot> later = execution.operatorMetrics();
            assertThat(later.get(later.size() - 1).rowsIn()).isEqualTo(150);

            long stateAfter = later.stream()
                    .filter(each -> each.stateBytes() != null)
                    .mapToLong(OperatorMetrics.Snapshot::stateBytes)
                    .sum();
            assertThat(stateAfter)
                    .as("state bytes are read from the store, so they follow the restore rather than a counter")
                    .isGreaterThanOrEqualTo(stateBefore);
        }
    }

    @Test
    void operatorStateBytesAgreeWithTheQuerysOwnStateGauges() {
        PhysicalOperator plan = plan(
                "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)");
        List<CapturingRowWriter.Captured> emitted = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                QueryExecution execution = QueryExecution.start(
                        plan,
                        1,
                        laneConfig(),
                        MemoryAccess.best(),
                        () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                            synchronized (emitted) {
                                emitted.add(row);
                            }
                        }))) {
            for (long i = 0; i < 500; i++) {
                offerAt(arena, execution, 0, "u" + (i % 50), 1L, i * 1_000_000_000L);
            }
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();

            List<OperatorMetrics.Snapshot> operators = execution.operatorMetrics();
            List<OperatorMetrics.Snapshot> stateful =
                    operators.stream().filter(each -> each.stateBytes() != null).toList();
            assertThat(stateful)
                    .as("exactly the windowed aggregate holds state here, and it is the only node that claims any")
                    .hasSize(1);
            assertThat(stateful.get(0).operator()).isEqualTo("WindowedAggregate");
            assertThat(stateful.get(0).stateBytes()).isPositive();
            assertThat(execution.stateUsage().held())
                    .as("and the query-level gauge sees the same operator holding state")
                    .isPositive();
        }
    }

    private static void offer(RowArena arena, QueryExecution execution, int lane, String user, long amount) {
        offerAt(arena, execution, lane, user, amount, amount * 1_000_000_000L);
    }

    private static void offerAt(
            RowArena arena, QueryExecution execution, int lane, String user, long amount, long tsNanos) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount).setLong(2, tsNanos);
        writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
        int length = writer.sizeSoFar();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!execution.lane(lane).offer(arena.regionOf(handle), arena.offsetOf(handle), length)) {
            execution.checkHealth();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("lane " + lane + " stopped accepting rows");
            }
            Thread.onSpinWait();
        }
    }
}
