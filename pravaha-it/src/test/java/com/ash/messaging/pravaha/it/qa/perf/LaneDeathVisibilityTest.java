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
package com.ash.messaging.pravaha.it.qa.perf;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF-041. A lane that dies on the <em>feed</em> path, where nothing calls {@code accept()}.
 *
 * <p>This is the defect the QA cycle started from: a lane thread died and ten operator surfaces went
 * on reporting RUNNING, because each of them asked the registry and the registry only learned of a
 * failure when somebody pushed a row through {@code accept()}. A server-fed query uses neither
 * {@code accept()} nor {@code advanceWatermark()} -- rows arrive through {@code PumpingFeed} to
 * {@code IngestPump.pumpOnce} to {@code lane.claim()/publish()} -- so on the one path that carries
 * production traffic, nobody ever asked.
 *
 * <p>It is now fixed: {@code RegisteredQuery.state()} polls {@code QueryExecution.laneFailure()} and
 * latches FAILED. But every existing test covering it drives {@code accept()}, which is the path
 * that was already working -- {@code LifeFailureTest.life126}, {@code life129}, {@code life130} and
 * {@code ContinuousQueryAnswerTest.cq053} all push rows in and catch the throw. This one offers rows
 * straight into the lane and never calls {@code accept()}, so a regression that reconnected the
 * recording to the caller's thread would fail here and pass everywhere else.
 */
final class LaneDeathVisibilityTest {

    private static final StreamSchema TXN_T = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    /** MIN cannot invert a retraction: restoring a previous extreme needs an ordered multiset. */
    private static final String MIN_SQL =
            "SELECT user_id, MIN(amount) AS lo FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '1' SECOND)";

    @Test
    @DisplayName("PERF-041: a lane that dies with nothing calling accept() still records why")
    void aLaneThatDiesOnTheFeedPathIsAskableAbout() {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(TXN_T).plan(MIN_SQL));
        RowLayout layout = RowLayout.of(TXN_T);

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                QueryExecution execution = QueryExecution.start(
                        plan,
                        1,
                        LaneConfig.defaults()
                                .withInbox(1024, 128)
                                .withBatchSize(32)
                                .withArena(1 << 20, 4)
                                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                                .withThreads("perf-qa-lane", true),
                        MemoryAccess.best(),
                        () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {}))) {

            // Establishes the extreme. Offered into the lane, never accept()ed: this is what the
            // server's ingest pump does, and it is why the original defect was invisible.
            offer(execution, arena, layout, "ann", 100L, 100_000_000L, 1L);
            execution.awaitQuiescent(Duration.ofSeconds(10));

            // The retraction MIN cannot invert. The refusal happens on the lane thread, so it has
            // no caller to travel back to. It has to be recorded, or it is lost.
            offer(execution, arena, layout, "ann", 100L, 100_000_000L, -1L);

            Throwable failure = awaitLaneFailure(execution, Duration.ofSeconds(30));
            assertThat(failure.toString())
                    .as("the lane recorded the real cause, with nobody having pushed a row")
                    .contains("MIN cannot handle a retraction");
        }
    }

    private static void offer(
            QueryExecution execution,
            RowArena arena,
            RowLayout layout,
            String user,
            long amount,
            long ts,
            long weight) {
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount).setLong(2, ts);
        writer.weight(weight).eventTimestampNanos(ts).sequence(ts).commit();

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!execution.lane(0).offer(arena.regionOf(handle), arena.offsetOf(handle), writer.sizeSoFar())) {
            if (execution.laneFailure().isPresent()) {
                return; // the lane is already dead; that is what the caller is waiting to see
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("lane 0 stopped accepting rows without recording a failure");
            }
            Thread.onSpinWait();
        }
    }

    private static Throwable awaitLaneFailure(QueryExecution execution, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Throwable> failure = execution.laneFailure();
            if (failure.isPresent()) {
                return failure.get();
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the lane to record a failure", e);
            }
        }
        throw new AssertionError("the lane never recorded a failure within " + within
                + " -- either it did not die, or it died without recording, and the second is the "
                + "defect PERF-041 exists for");
    }
}
