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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
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
 * A query that dies halfway and comes back.
 *
 * <p>Correctness invariant 8, and the one thing a checkpoint has to prove: a run interrupted and
 * recovered must produce the same answers as a run that was never interrupted. Not similar answers,
 * and not answers that differ only in rows nobody looks at -- the same ones.
 *
 * <p>What makes this worth testing rather than reasoning about is that every plausible partial
 * implementation produces plausible output. Restore the state and forget to rewind, and every record
 * between the checkpoint and the failure is counted twice. Rewind and forget the state, and every
 * record before the checkpoint is lost. Both give a number.
 */
@Timeout(120)
class CheckpointRecoveryTest {

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
                .withArena(1 << 20, 4)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("checkpoint-lane", true);
    }

    /** One input record. */
    private record Txn(long user, long amount, long eventTime) {}

    private static List<Txn> input() {
        List<Txn> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            // Three users, spread across six ten-second windows.
            rows.add(new Txn(i % 3, i + 1, (long) i * SECOND));
        }
        return rows;
    }

    @Test
    void aRunInterruptedAndRecoveredProducesTheSameAnswersAsAnUninterruptedOne(@TempDir Path dir) {
        List<Txn> all = input();
        List<String> uninterrupted = runToCompletion(all);

        // The interrupted run: feed the first half, checkpoint, throw the execution away entirely,
        // start a new one, restore, and feed the rest.
        FileCheckpointStore store = new FileCheckpointStore(dir.resolve("checkpoints"));
        List<String> beforeCrash = new ArrayList<>();
        Checkpoint checkpoint;

        Harness first = new Harness(beforeCrash);
        all.subList(0, 30).forEach(first::feed);
        assertThat(first.execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
        checkpoint = first.execution.checkpoint(1, Duration.ofSeconds(30));
        store.store(checkpoint);
        // A crash, not a shutdown. Closing gracefully would emit every held window on the way out --
        // after the checkpoint was taken, so the recovered run re-emits them and every early window
        // appears twice. That is what the first version of this test did, and every number in the
        // duplicates was right, which is precisely why it took a moment to see.
        first.abort();

        assertThat(store.latest()).isPresent();
        List<String> afterRecovery = new ArrayList<>(beforeCrash);

        try (Harness second = new Harness(afterRecovery)) {
            second.execution.restore(store.latest().orElseThrow(), Duration.ofSeconds(30));
            // The source rewinds to the checkpoint's position, which here is "the first thirty
            // records were consumed". Restoring state without rewinding would double-count; the
            // reverse would lose everything before the checkpoint.
            all.subList(30, all.size()).forEach(second::feed);
            assertThat(second.execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
        }

        assertThat(afterRecovery.stream().sorted().toList())
                .as("recovered run must agree with the uninterrupted one, exactly")
                .isEqualTo(uninterrupted.stream().sorted().toList());
    }

    @Test
    void restoringWithoutTheStateWouldLoseTheEarlyWindows(@TempDir Path dir) {
        // The control: the same interrupted run, without restoring. Included because a recovery test
        // that passes whether or not the state is restored is testing nothing, and this is how to
        // know the difference is real.
        List<Txn> all = input();
        List<String> withoutRestore = new ArrayList<>();

        Harness first = new Harness(new ArrayList<>());
        all.subList(0, 30).forEach(first::feed);
        first.execution.awaitQuiescent(Duration.ofSeconds(30));
        first.abort();
        try (Harness second = new Harness(withoutRestore)) {
            all.subList(30, all.size()).forEach(second::feed);
            second.execution.awaitQuiescent(Duration.ofSeconds(30));
        }

        assertThat(withoutRestore)
                .as("a second run with no restored state knows nothing of the first thirty records")
                .hasSizeLessThan(runToCompletion(all).size());
    }

    /** Runs the whole input in one go. */
    private static List<String> runToCompletion(List<Txn> rows) {
        List<String> results = new ArrayList<>();
        try (Harness harness = new Harness(results)) {
            rows.forEach(harness::feed);
            assertThat(harness.execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
        }
        return results;
    }

    /** A one-lane execution with a captured output. */
    private static final class Harness implements AutoCloseable {
        final QueryExecution execution;
        private final RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        private final RowLayout layout = RowLayout.of(schema());
        private final BinaryRowWriter writer = new BinaryRowWriter(layout);
        private final BinaryRowView view = new BinaryRowView(layout);
        private final MemoryAccess access = MemoryAccess.best();

        Harness(List<String> results) {
            PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(schema()).plan(SQL));
            this.execution = QueryExecution.start(
                    plan,
                    1,
                    config(),
                    access,
                    () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                        synchronized (results) {
                            results.add(row.asLong(0) / SECOND + "-" + row.asLong(1) / SECOND + " user=" + row.asLong(2)
                                    + " sum=" + row.asLong(3) + " w=" + row.weight());
                        }
                    }));
        }

        void feed(Txn txn) {
            long handle = feed.allocate(layout.rowSize(128));
            writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
            writer.setLong(0, txn.user())
                    .setLong(1, txn.amount())
                    .setLong(2, txn.eventTime())
                    .weight(1L)
                    .eventTimestampNanos(txn.eventTime())
                    .sequence(txn.eventTime())
                    .commit();

            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!execution.lane(0).offer(feed.regionOf(handle), feed.offsetOf(handle), layout.fixedEnd())) {
                execution.checkHealth();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("lane 0 stopped accepting rows");
                }
                Thread.onSpinWait();
            }
        }

        /** Stops as a crash would: nothing held is emitted on the way out. */
        void abort() {
            execution.abort();
            feed.close();
        }

        @Override
        public void close() {
            execution.close();
            feed.close();
        }
    }
}
