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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many lanes, few threads, and one lane's failure kept to itself.
 *
 * <p>ADR-027's multiplexing, and the three things it has to get right. A node's thread count must
 * follow its cores rather than its queries; every lane must still be driven by one thread at a time,
 * because confinement is what makes the hot path lock-free; and a lane that dies must take its own
 * query down and nothing else's, which is the property a shared thread most easily loses.
 */
@Timeout(120)
final class LaneRunnerTest {

    private static final int LANES = 64;

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(64, 32)
                .withBatchSize(8)
                .withArena(64 * 1024, 2)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("hosted-lane", true);
    }

    @Test
    void manyLanesRunOnFewThreadsAndEachSeesItsOwnRows() throws Exception {
        MemoryAccess access = MemoryAccess.best();
        List<Lane> lanes = new ArrayList<>();
        List<AtomicLong> seen = new ArrayList<>();

        long threadsBefore = runnerThreads();
        try (LaneRunner runner = new LaneRunner(4, "pravaha-runner", WaitStrategy.Kind.BACKOFF_PARK);
                MemoryRegion scratch = access.allocate(64)) {

            for (int i = 0; i < LANES; i++) {
                AtomicLong count = new AtomicLong();
                seen.add(count);
                Lane lane = new Lane(i, config(), access, context -> (region, offsets, n) -> {
                    count.addAndGet(n);
                    return n;
                });
                lane.startOn(runner);
                lanes.add(lane);
            }

            assertThat(runnerThreads() - threadsBefore)
                    .as(
                            "four runner threads carrying %d lanes. A lane per thread is what ADR-027 "
                                    + "exists to remove, and what NodeScaleTest measured at 1.00 per query",
                            LANES)
                    .isEqualTo(4);

            // Every lane gets its own rows, and must see exactly those. A runner that fed the wrong
            // lane, or fed one lane twice, would still look busy.
            for (int i = 0; i < LANES; i++) {
                for (int row = 0; row <= i; row++) {
                    scratch.putLong(0, row);
                    while (!lanes.get(i).offer(scratch, 0, 8)) {
                        Thread.onSpinWait();
                    }
                }
            }

            for (int i = 0; i < LANES; i++) {
                int expected = i + 1;
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (seen.get(i).get() < expected && System.nanoTime() < deadline) {
                    Thread.sleep(5L);
                }
                assertThat(seen.get(i).get())
                        .as("lane %d was given %d rows and processed what it was given, no more", i, expected)
                        .isEqualTo(expected);
                lanes.get(i).checkHealth();
            }

            assertThat(runner.lanesPerThread().stream()
                            .mapToInt(Integer::intValue)
                            .sum())
                    .as("every lane is still hosted")
                    .isEqualTo(LANES);
        } finally {
            for (Lane lane : lanes) {
                lane.close();
            }
        }
    }

    @Test
    void oneLaneFailingDoesNotStopTheOthersSharingItsThread() throws Exception {
        // The property a shared thread most easily loses. When a lane owned its thread, a throw
        // killed that thread and that query; on a shared thread an escaping throw would kill every
        // query the runner carries, turning one bad row into an outage.
        MemoryAccess access = MemoryAccess.best();
        AtomicInteger healthyRows = new AtomicInteger();

        try (LaneRunner runner = new LaneRunner(1, "pravaha-one-runner", WaitStrategy.Kind.BACKOFF_PARK);
                MemoryRegion scratch = access.allocate(64)) {

            // One thread, so they genuinely share it -- with four threads the test could pass by
            // the two landing on different ones.
            Lane poisoned = new Lane(0, config(), access, context -> (region, offsets, n) -> {
                throw new IllegalStateException("this lane refuses row " + n);
            });
            Lane healthy = new Lane(1, config(), access, context -> (region, offsets, n) -> {
                healthyRows.addAndGet(n);
                return n;
            });
            poisoned.startOn(runner);
            healthy.startOn(runner);

            scratch.putLong(0, 1L);
            while (!poisoned.offer(scratch, 0, 8)) {
                Thread.onSpinWait();
            }

            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (poisoned.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertThat(poisoned.failure())
                    .as("the lane that threw recorded it, exactly as it would have when it owned a thread")
                    .isPresent();

            // And the survivor still works, after the other one died on the thread they share.
            for (int row = 0; row < 25; row++) {
                scratch.putLong(0, row);
                while (!healthy.offer(scratch, 0, 8)) {
                    Thread.onSpinWait();
                }
            }
            deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (healthyRows.get() < 25 && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }

            assertThat(healthyRows.get())
                    .as("the other lane on that thread kept running and processed everything it was given")
                    .isEqualTo(25);
            healthy.checkHealth();
            healthy.close();
        }
    }

    /** Threads named for a runner. The number that must not grow with lanes. */
    private static long runnerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("pravaha-runner"))
                .count();
    }
}
