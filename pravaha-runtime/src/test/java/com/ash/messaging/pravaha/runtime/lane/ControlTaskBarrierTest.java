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
package com.ash.messaging.pravaha.runtime.lane;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A control task is a marker in the stream, and a marker has to be honoured exactly.
 *
 * <p>{@code submitControlTask} records where each input's producers had reached and the lane runs
 * the task when it gets there. "When it gets there" used to mean "at the end of whichever batch
 * carried it past there", and a batch is up to {@code batchSize} rows -- so the task saw the
 * marker's position plus however much of the next batch the lane happened to have drained.
 *
 * <p>For a watermark advance that is merely imprecise. For a checkpoint it is a correctness bug in
 * the direction nothing catches: the snapshot covers rows the recorded source offset says will be
 * replayed, so a restore counts them twice and reports RUNNING over the double count.
 *
 * <p>The two tests below bracket the marker from both sides. The lane may not run the task before
 * the producers reached the position it names (that half was already true), and it may not have
 * drained past the position the producers had reached by the time the submit returned (that half is
 * what this change adds). The producer thread never stops, which is the point: with the producers
 * held still there is nothing past the marker to overshoot into, and the defect cannot appear.
 */
final class ControlTaskBarrierTest {

    /** A big batch, because the overshoot this catches is bounded by exactly one batch. */
    private static final int BATCH = 512;

    private static final int ATTEMPTS = 200;

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(4096, 64)
                .withBatchSize(BATCH)
                .withArena(1 << 20, 4)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("barrier-lane", true);
    }

    @Test
    @Timeout(180)
    void aTaskSeesTheStreamAtItsMarkerAndNotABatchBeyondIt() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread[] load = ControlTaskBarrierTest.startContention(stop);
        int ranPastTheMarker = 0;
        int ranBeforeTheMarker = 0;
        int attemptsMeasured = 0;

        Counting[] processor = new Counting[1];
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 2);
                Lane lane = new Lane(0, config(), MemoryAccess.best(), context -> processor[0] = new Counting())) {
            long handle = arena.allocate(32);
            lane.start();

            AtomicBoolean producerStop = new AtomicBoolean();
            Thread producer = Thread.ofPlatform().daemon().start(() -> {
                while (!producerStop.get()) {
                    // Never blocks: a full inbox is the lane being behind, which is exactly the
                    // condition that makes an overshoot large.
                    lane.offer(arena.regionOf(handle), arena.offsetOf(handle), 32);
                }
            });

            try {
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    long[] seen = new long[1];
                    // The marker is read inside submitControlTask, so it lies somewhere in
                    // [before, after] -- a window a few hundred nanoseconds wide. The overshoot
                    // being tested for is up to BATCH rows wide.
                    long before = lane.producerCursor(0);
                    long ticket = lane.submitControlTask(() -> seen[0] = processor[0].rowsSeen);
                    long after = lane.producerCursor(0);
                    if (!lane.awaitControlTask(ticket, Duration.ofSeconds(10))) {
                        continue;
                    }
                    lane.checkHealth();
                    attemptsMeasured++;
                    if (seen[0] < before) {
                        ranBeforeTheMarker++;
                    }
                    if (seen[0] > after) {
                        ranPastTheMarker++;
                    }
                }
            } finally {
                producerStop.set(true);
                producer.join(Duration.ofSeconds(5).toMillis());
            }
        } finally {
            stop.set(true);
            for (Thread thread : load) {
                thread.join(Duration.ofSeconds(5).toMillis());
            }
        }

        assertThat(attemptsMeasured)
                .as("the lane has to have answered often enough for the counts below to mean anything")
                .isGreaterThan(ATTEMPTS / 2);
        assertThat(ranBeforeTheMarker)
                .as(
                        "%d of %d tasks ran over fewer rows than had been handed to the lane before they were "
                                + "submitted; a watermark advance that misses rows it was given closes a window "
                                + "over a partial second",
                        ranBeforeTheMarker, attemptsMeasured)
                .isZero();
        assertThat(ranPastTheMarker)
                .as(
                        "%d of %d tasks ran over rows that arrived after their marker; for a checkpoint that is "
                                + "state the recorded source offset says will be replayed, and replaying it is a "
                                + "double count with nothing to catch it",
                        ranPastTheMarker, attemptsMeasured)
                .isZero();
    }

    @Test
    @Timeout(180)
    void everyInputOfAJoinIsCutAtItsOwnMarker() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread[] load = startContention(stop);
        int violations = 0;
        int attemptsMeasured = 0;

        CountingPerInput[] sides = new CountingPerInput[1];
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 2);
                Lane lane = new Lane(
                        0,
                        config(),
                        MemoryAccess.best(),
                        new int[0],
                        context -> sides[0] = new CountingPerInput(),
                        null,
                        2)) {
            long handle = arena.allocate(32);
            lane.start();

            AtomicBoolean producerStop = new AtomicBoolean();
            Thread[] producers = new Thread[2];
            for (int i = 0; i < 2; i++) {
                int input = i;
                producers[i] = Thread.ofPlatform().daemon().start(() -> {
                    while (!producerStop.get()) {
                        lane.offer(input, arena.regionOf(handle), arena.offsetOf(handle), 32);
                    }
                });
            }

            try {
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    long[] seen = new long[2];
                    long beforeLeft = lane.producerCursor(0);
                    long beforeRight = lane.producerCursor(1);
                    long ticket = lane.submitControlTask(() -> {
                        seen[0] = sides[0].left;
                        seen[1] = sides[0].right;
                    });
                    long afterLeft = lane.producerCursor(0);
                    long afterRight = lane.producerCursor(1);
                    if (!lane.awaitControlTask(ticket, Duration.ofSeconds(10))) {
                        continue;
                    }
                    lane.checkHealth();
                    attemptsMeasured++;
                    // A join's two sides are drained in turn within one iteration. Without the cut
                    // being honoured per input, the side drained first runs ahead of its own marker
                    // while the second is still at its own -- so the snapshot holds a left-hand row
                    // with no right-hand row it could ever have been paired against.
                    if (seen[0] < beforeLeft || seen[0] > afterLeft || seen[1] < beforeRight || seen[1] > afterRight) {
                        violations++;
                    }
                }
            } finally {
                producerStop.set(true);
                for (Thread each : producers) {
                    each.join(Duration.ofSeconds(5).toMillis());
                }
            }
        } finally {
            stop.set(true);
            for (Thread thread : load) {
                thread.join(Duration.ofSeconds(5).toMillis());
            }
        }

        assertThat(attemptsMeasured).isGreaterThan(ATTEMPTS / 2);
        assertThat(violations)
                .as(
                        "%d of %d markers were honoured on one input and not the other; a cut that holds for the "
                                + "left side of a join and not the right is not a cut",
                        violations, attemptsMeasured)
                .isZero();
    }

    @Test
    @Timeout(180)
    void twoThreadsSubmittingAtOnceBothGetTicketsTheyCanWaitOn() throws Exception {
        // A ticket means "the last completed id is at least mine", which is only "mine has run"
        // while the queue's order is the ids' order. Allocating the id and queueing the task as two
        // steps lets two submitters interleave so that id 1 is queued ahead of id 0: completion
        // then runs 1, 0, and a waiter for id 1 that arrives after both have finished reads
        // controlCompleted as 0 and waits out its whole timeout on a task that is long done.
        // The same shape as PF-5 -- a ticket that does not name its task -- one level down.
        AtomicBoolean stop = new AtomicBoolean();
        Thread[] load = startContention(stop);
        AtomicLong reportedAsNotRun = new AtomicLong();

        try (Lane lane = new Lane(0, config(), MemoryAccess.best(), context -> new Counting())) {
            lane.start();
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                Thread[] submitters = new Thread[4];
                for (int i = 0; i < submitters.length; i++) {
                    submitters[i] = Thread.ofPlatform().daemon().start(() -> {
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        long ticket = lane.submitControlTask(() -> {});
                        // Deliberately not waiting immediately: the inversion shows when the waiter
                        // asks after both tasks have already run, because only then is there no
                        // later completion to mask it.
                        LockSupportSleep.millis(2);
                        if (!lane.awaitControlTask(ticket, Duration.ofSeconds(2))) {
                            reportedAsNotRun.incrementAndGet();
                        }
                    });
                }
                go.countDown();
                for (Thread each : submitters) {
                    each.join(Duration.ofSeconds(10).toMillis());
                }
            }
        } finally {
            stop.set(true);
            for (Thread thread : load) {
                thread.join(Duration.ofSeconds(5).toMillis());
            }
        }

        assertThat(reportedAsNotRun.get())
                .as(
                        "%d waits reported a task that had already run as one that never ran; the caller turns "
                                + "that into a timeout, and QueryExecution.checkpoint turns a timeout into an "
                                + "abandoned checkpoint",
                        reportedAsNotRun.get())
                .isZero();
    }

    /** Enough runnable threads that the lane is liable to lose its core mid-batch. */
    private static Thread[] startContention(AtomicBoolean stop) {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        Thread[] started = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            started[i] = Thread.ofPlatform().daemon().start(() -> {
                long spin = 0;
                while (!stop.get()) {
                    spin += 1; // keeps the scheduler busy; the value is never read
                    if ((spin & 0xFFFF) == 0) {
                        Thread.onSpinWait();
                    }
                }
            });
        }
        return started;
    }

    private static final class LockSupportSleep {
        private LockSupportSleep() {}

        static void millis(long millis) {
            java.util.concurrent.locks.LockSupport.parkNanos(
                    Duration.ofMillis(millis).toNanos());
        }
    }

    /**
     * Counts rows, which for a lane's only input is the drain cursor by another name.
     *
     * <p>A plain field rather than an atomic: it is written and read only on the lane thread -- the
     * read happens inside a control task, which is the lane thread too. That is the whole point of
     * reading it there rather than from the test.
     */
    private static final class Counting implements LaneProcessor {
        long rowsSeen;

        @Override
        public int onBatch(com.ash.messaging.pravaha.common.memory.MemoryRegion region, long[] offsets, int count) {
            rowsSeen += count;
            return count;
        }

        @Override
        public void close() {}
    }

    private static final class CountingPerInput implements LaneProcessor {
        long left;
        long right;

        @Override
        public int onBatch(com.ash.messaging.pravaha.common.memory.MemoryRegion region, long[] offsets, int count) {
            return onBatch(0, region, offsets, count);
        }

        @Override
        public int onBatch(
                int input, com.ash.messaging.pravaha.common.memory.MemoryRegion region, long[] offsets, int count) {
            if (input == 0) {
                left += count;
            } else {
                right += count;
            }
            return count;
        }

        @Override
        public void close() {}
    }
}
