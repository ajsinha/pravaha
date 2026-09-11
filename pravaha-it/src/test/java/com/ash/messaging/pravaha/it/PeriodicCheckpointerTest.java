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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.CheckpointStore;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkpoints are taken on a schedule, and old ones are deleted.
 *
 * <p>This closes a gap that had been open since checkpointing was written. {@code prune} existed and
 * was called from nothing but its own unit test, and nothing took checkpoints periodically at all,
 * so a long-running query either kept none or kept every one it had ever taken -- and which of those
 * happened depended on whether whoever embedded the engine remembered to call {@code checkpoint()}
 * in a loop of their own.
 */
class PeriodicCheckpointerTest {

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
                .withThreads("periodic-checkpoint-lane", true);
    }

    private static QueryExecution execution() {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));
        return QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {}));
    }

    private static void awaitUntil(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, interrupted);
            }
        }
    }

    /** Wraps a real store so stores and prunes can be counted, and a store made to fail once. */
    private static final class RecordingStore implements CheckpointStore {
        private final CheckpointStore delegate;
        private final List<Integer> pruneCalls = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger stored = new AtomicInteger();
        private volatile RuntimeException failNextStore;

        RecordingStore(CheckpointStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void store(Checkpoint checkpoint) {
            RuntimeException failure = failNextStore;
            if (failure != null) {
                failNextStore = null;
                throw failure;
            }
            delegate.store(checkpoint);
            stored.incrementAndGet();
        }

        @Override
        public Optional<Checkpoint> latest() {
            return delegate.latest();
        }

        @Override
        public List<Long> availableIds() {
            return delegate.availableIds();
        }

        @Override
        public Optional<Checkpoint> load(long id) {
            return delegate.load(id);
        }

        @Override
        public int prune(int keep) {
            pruneCalls.add(keep);
            return delegate.prune(keep);
        }
    }

    @Test
    @Timeout(60)
    void checkpointsAreTakenOnAScheduleAndOldOnesDeleted(@TempDir Path directory) {
        RecordingStore store = new RecordingStore(new FileCheckpointStore(directory));

        try (QueryExecution execution = execution();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        execution, store, Duration.ofMillis(50), 3, Duration.ofSeconds(10), null)) {
            checkpointer.start();

            awaitUntil(() -> store.stored.get() >= 6, "six checkpoints");

            // Six taken, three kept. The directory settles rather than growing, which is the whole
            // point: before this, a query checkpointing every minute left a file per minute for ever.
            assertThat(store.availableIds()).hasSize(3);
            assertThat(store.pruneCalls).isNotEmpty().allMatch(keep -> keep == 3);
        }
    }

    @Test
    @Timeout(60)
    void idsKeepCountingUpAcrossARestart(@TempDir Path directory) {
        FileCheckpointStore store = new FileCheckpointStore(directory);
        long highestBefore;

        try (QueryExecution execution = execution();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        execution, store, Duration.ofHours(1), 5, Duration.ofSeconds(10), null)) {
            checkpointer.checkpointNow();
            checkpointer.checkpointNow();
            highestBefore = store.availableIds().stream().max(Long::compare).orElseThrow();
        }

        try (QueryExecution execution = execution();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        execution, store, Duration.ofHours(1), 5, Duration.ofSeconds(10), null)) {
            // Restarting must not reuse an id, or a checkpoint written after recovery can overwrite
            // one written before it and "checkpoint 2" means two different things in one directory.
            assertThat(checkpointer.checkpointNow().id()).isGreaterThan(highestBefore);
        }
    }

    @Test
    @Timeout(60)
    void aFailedCheckpointDoesNotStopTheSchedule(@TempDir Path directory) {
        RecordingStore store = new RecordingStore(new FileCheckpointStore(directory));
        List<String> logged = Collections.synchronizedList(new ArrayList<>());

        try (QueryExecution execution = execution();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        execution, store, Duration.ofMillis(50), 3, Duration.ofSeconds(10), logged::add)) {
            store.failNextStore = new IllegalStateException("disk full");
            checkpointer.start();

            // A full disk that is later emptied should degrade recovery, not end it. Dying on the
            // first failure would mean the query runs for days with no checkpoints and no complaint.
            awaitUntil(() -> store.stored.get() >= 3, "three checkpoints after one failure");

            assertThat(checkpointer.stats().failed()).isEqualTo(1);
            assertThat(logged).anySatisfy(line -> assertThat(line).contains("checkpoint failed"));
        }
    }

    @Test
    @Timeout(60)
    void retentionIsCountedRatherThanTimed(@TempDir Path directory) {
        FileCheckpointStore store = new FileCheckpointStore(directory);

        try (QueryExecution execution = execution();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        execution, store, Duration.ofHours(1), 2, Duration.ofSeconds(10), null)) {
            checkpointer.checkpointNow();
            checkpointer.checkpointNow();
            checkpointer.checkpointNow();
        }

        // Served-view retention is deliberately time-based, because a view holds data and streaming
        // data is about what is true now. A checkpoint is not data, it is a fallback, and an idle
        // system takes no new ones -- so after a quiet night a time rule would delete every
        // checkpoint there is, precisely when recovery is most likely to be wanted.
        assertThat(store.availableIds()).hasSize(2);
    }

    @Test
    @Timeout(60)
    void keepingNoneIsRefused(@TempDir Path directory) {
        try (QueryExecution execution = execution()) {
            FileCheckpointStore store = new FileCheckpointStore(directory);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PeriodicCheckpointer(
                            execution, store, Duration.ofMinutes(1), 0, Duration.ofSeconds(10), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("every restart starts from nothing");
        }
    }
}
