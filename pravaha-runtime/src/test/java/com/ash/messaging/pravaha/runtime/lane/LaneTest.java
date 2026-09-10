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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lane loop.
 *
 * <p>Nothing here asserts a timing-dependent property. An earlier contention test in this codebase
 * did, and it was flaky on an idle machine and green on a loaded one, which is the worst of both:
 * it neither caught regressions nor stayed quiet. Throughput and scaling belong in JMH, where the
 * statistics are taken seriously; what is asserted here is behaviour.
 */
// Bounded so a regression fails rather than hangs. Found the hard way: seeding "never drain the
// inbound exchange rings" did not fail this suite, it stalled it -- the feeding loop waited for inbox
// space that a blocked lane would never free. A hanging test in CI is worse than a failing one,
// because it looks like an infrastructure problem and gets retried rather than read.
@Timeout(60)
class LaneTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    /** Reads the long every test row carries at offset 0. */
    private static final class Recorder implements LaneProcessor {
        private final List<Long> seen = new ArrayList<>();
        private volatile boolean closed;

        @Override
        public int onBatch(MemoryRegion region, long[] rowOffsets, int count) {
            for (int i = 0; i < count; i++) {
                seen.add(region.getLong((int) rowOffsets[i]));
            }
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(64, 32)
                .withBatchSize(16)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("test-lane", true);
    }

    /** Offers one long, waiting out backpressure rather than dropping it. */
    private static void offer(Lane lane, MemoryRegion scratch, long value) {
        scratch.putLong(0, value);
        while (!lane.offer(scratch, 0, 8)) {
            lane.checkHealth();
            Thread.onSpinWait();
        }
    }

    @Test
    void everyRowIsProcessedExactlyOnceAndInOrder() {
        Recorder recorder = new Recorder();
        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion scratch = access.allocate(64);
                Lane lane = new Lane(0, config(), access, context -> recorder)) {
            lane.start();
            for (long i = 0; i < 5_000; i++) {
                offer(lane, scratch, i);
            }
            assertThat(lane.awaitQuiescent(PATIENCE)).isTrue();
            lane.checkHealth();

            assertThat(recorder.seen).hasSize(5_000);
            assertThat(recorder.seen).isSorted();
            assertThat(recorder.seen.get(0)).isZero();
            assertThat(recorder.seen.get(4_999)).isEqualTo(4_999L);
        }
        assertThat(recorder.closed).isTrue();
    }

    @Test
    void inputRowsStayValidForTheWholeBatch() throws Exception {
        // The property that decides whether the inbox may be released before or after the
        // processor. Rows are flyweights into the inbox's cells, so a lane that frees them on drain
        // is handing a producer permission to overwrite a row mid-batch. Under load that is a
        // corrupted answer, not a crash, and nothing points at the cause.
        AtomicInteger mismatches = new AtomicInteger();
        AtomicInteger batchesChecked = new AtomicInteger();
        LaneProcessor paranoid = (region, rowOffsets, count) -> {
            long[] first = new long[count];
            for (int i = 0; i < count; i++) {
                first[i] = region.getLong((int) rowOffsets[i]);
            }
            // Long enough for a producer that believed the cells were free to have reused them.
            long until = System.nanoTime() + 100_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            for (int i = 0; i < count; i++) {
                if (region.getLong((int) rowOffsets[i]) != first[i]) {
                    mismatches.incrementAndGet();
                }
            }
            batchesChecked.incrementAndGet();
            return count;
        };

        MemoryAccess access = MemoryAccess.best();
        try (Lane lane = new Lane(0, config().withInbox(16, 32), access, context -> paranoid)) {
            lane.start();
            int producers = 3;
            CountDownLatch done = new CountDownLatch(producers);
            for (int p = 0; p < producers; p++) {
                long base = (long) p * 100_000L;
                Thread.ofPlatform().daemon().start(() -> {
                    try (MemoryRegion scratch = access.allocate(64)) {
                        for (long i = 0; i < 2_000; i++) {
                            scratch.putLong(0, base + i);
                            while (!lane.offer(scratch, 0, 8)) {
                                Thread.onSpinWait();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(lane.awaitQuiescent(PATIENCE)).isTrue();
            lane.checkHealth();

            assertThat(batchesChecked.get())
                    .as("the test proves nothing unless batches actually ran")
                    .isGreaterThan(10);
            assertThat(mismatches.get())
                    .as("a row changed underneath the processor: the inbox was released too early")
                    .isZero();
        }
    }

    @Test
    void theArenaIsRewoundAfterEveryBatch() {
        // A lane that never rewinds exhausts a one-slab arena within a few batches. That it does
        // not is the whole reclaim story (design section 8.5) -- and the failure mode without it is
        // an ARENA_EXHAUSTED that looks like a sizing problem rather than a missing reset.
        int rowsPerBatch = 16;
        int allocationBytes = 1024;
        LaneProcessorFactory factory = context -> (region, rowOffsets, count) -> {
            RowArena arena = context.arena();
            for (int i = 0; i < count; i++) {
                long handle = arena.allocate(allocationBytes);
                if (handle == ArenaHandle.NULL) {
                    throw new IllegalStateException("arena exhausted after " + arena.bytesInUse() + " bytes");
                }
                arena.regionOf(handle).putLong(arena.offsetOf(handle), region.getLong((int) rowOffsets[i]));
            }
            return count;
        };

        MemoryAccess access = MemoryAccess.best();
        LaneConfig config = config().withBatchSize(rowsPerBatch).withArena(64 * 1024, 1);
        try (MemoryRegion scratch = access.allocate(64);
                Lane lane = new Lane(0, config, access, factory)) {
            lane.start();
            for (long i = 0; i < 20_000; i++) {
                offer(lane, scratch, i);
            }
            assertThat(lane.awaitQuiescent(PATIENCE)).isTrue();
            lane.checkHealth();

            LaneMetrics metrics = lane.metrics();
            assertThat(metrics.rowsIn()).isEqualTo(20_000);
            assertThat(metrics.arenaHighWaterBytes())
                    .as("peak arena use is one batch's output, not the whole run's")
                    .isLessThanOrEqualTo((long) rowsPerBatch * allocationBytes * 2);
        }
    }

    @Test
    void aFullInboxIsReportedAsBackpressureRatherThanBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        LaneProcessor stalled = (region, rowOffsets, count) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return count;
        };

        MemoryAccess access = MemoryAccess.best();
        LaneConfig config = config().withInbox(8, 32).withBatchSize(1);
        try (MemoryRegion scratch = access.allocate(64);
                Lane lane = new Lane(0, config, access, context -> stalled)) {
            lane.start();
            scratch.putLong(0, 1L);

            int rejected = 0;
            for (int i = 0; i < 100; i++) {
                if (!lane.offer(scratch, 0, 8)) {
                    rejected++;
                }
            }
            assertThat(rejected)
                    .as("a full inbox refuses; it never blocks the ingest thread and never grows")
                    .isPositive();
            assertThat(lane.metrics().rejectedOffers()).isEqualTo(rejected);
            assertThat(lane.metrics().inboxFill()).isEqualTo(1.0);
            release.countDown();
            assertThat(lane.awaitQuiescent(PATIENCE)).isTrue();
        }
    }

    @Test
    void aProcessorFailureStopsTheLaneAndSaysSo() {
        LaneProcessor broken = (region, rowOffsets, count) -> {
            throw new IllegalStateException("this row is impossible");
        };

        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion scratch = access.allocate(64);
                Lane lane = new Lane(7, config(), access, context -> broken)) {
            lane.start();
            offer(lane, scratch, 1L);

            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (lane.state() != Lane.State.FAILED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(lane.state()).isEqualTo(Lane.State.FAILED);
            assertThat(lane.failure()).containsInstanceOf(IllegalStateException.class);
            assertThatThrownBy(lane::checkHealth)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("lane 7")
                    .extracting(e -> ((PravahaException) e).errorCode())
                    .isEqualTo(RuntimeErrors.LANE_FAILED);
        }
    }

    @Test
    void closingDrainsWhatWasAlreadyAccepted() {
        Recorder recorder = new Recorder();
        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion scratch = access.allocate(64)) {
            Lane lane = new Lane(0, config(), access, context -> recorder);
            lane.start();
            for (long i = 0; i < 1_000; i++) {
                offer(lane, scratch, i);
            }
            // No awaitQuiescent: closing must not lose rows the lane has already accepted.
            lane.close();
            assertThat(recorder.seen).hasSize(1_000);
            assertThat(lane.state()).isEqualTo(Lane.State.STOPPED);
        }
    }

    @Test
    void metricsCountRowsInBatchesAndWhatWasEmitted() {
        // Half the rows are dropped, as a filter would drop them.
        LaneProcessor selective = (region, rowOffsets, count) -> {
            int emitted = 0;
            for (int i = 0; i < count; i++) {
                if (region.getLong((int) rowOffsets[i]) % 2 == 0) {
                    emitted++;
                }
            }
            return emitted;
        };

        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion scratch = access.allocate(64);
                Lane lane = new Lane(3, config(), access, context -> selective)) {
            lane.start();
            for (long i = 0; i < 1_000; i++) {
                offer(lane, scratch, i);
            }
            assertThat(lane.awaitQuiescent(PATIENCE)).isTrue();

            LaneMetrics metrics = lane.metrics();
            assertThat(metrics.laneId()).isEqualTo(3);
            assertThat(metrics.rowsIn()).isEqualTo(1_000);
            assertThat(metrics.rowsOut()).isEqualTo(500);
            assertThat(metrics.batches()).isPositive();
            assertThat(metrics.averageBatchSize()).isBetween(1.0, (double) config().batchSize());
        }
    }

    @Test
    void aLaneIsGivenItsOwnContextAndNothingElse() {
        MemoryAccess access = MemoryAccess.best();
        List<LaneContext> contexts = new ArrayList<>();
        try (Lane lane = new Lane(5, config(), access, new int[] {3, 7, 11}, context -> {
            contexts.add(context);
            return (region, offsets, count) -> count;
        })) {
            assertThat(contexts).hasSize(1);
            LaneContext context = contexts.get(0);
            assertThat(context.laneId()).isEqualTo(5);
            assertThat(context.virtualPartitions()).containsExactly(3, 7, 11);
            assertThat(context.arena()).isSameAs(lane.context().arena());

            // The array is a copy: a processor cannot rewrite its own assignment.
            context.virtualPartitions()[0] = 99;
            assertThat(context.virtualPartitions()).containsExactly(3, 7, 11);
        }
    }

    @Test
    void startingTwiceIsRejected() {
        MemoryAccess access = MemoryAccess.best();
        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> count)) {
            lane.start();
            assertThatThrownBy(lane::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already");
        }
    }

    @Test
    void closingALaneThatNeverStartedReleasesWhatItAllocated() {
        Recorder recorder = new Recorder();
        MemoryAccess access = MemoryAccess.best();
        Lane lane = new Lane(0, config(), access, context -> recorder);
        lane.close();
        assertThat(lane.state()).isEqualTo(Lane.State.STOPPED);
        assertThat(recorder.closed).isTrue();
    }
}
