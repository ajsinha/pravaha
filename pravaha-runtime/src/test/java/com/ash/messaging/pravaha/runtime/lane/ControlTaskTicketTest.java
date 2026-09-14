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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A control-task ticket must identify <em>that</em> task, not count how many have run.
 *
 * <p>Four things submit control tasks to a lane and then wait on them: {@code advanceWatermark},
 * {@code publishContinuousAggregates}, {@code checkpoint} and {@code restore}. A watermark tick runs
 * on a scheduler and a checkpoint on the checkpointer's thread, so two of them are routinely in
 * flight at once.
 *
 * <p>The ticket used to be the value of a completion counter read at submit time, and the wait was
 * "has the counter passed it". That is satisfied by <em>any</em> task completing, not by the one
 * that was submitted -- so a checkpoint whose ticket was retired by a concurrent watermark advance
 * returned before its snapshot had been taken, and stored a null where the operator state should
 * have been. {@code restore} then found no state under {@code lane-0}, skipped it, and reported
 * success over a pipeline it had restored nothing into.
 *
 * <p>Found from {@code StateRestoreTest.state060} failing once under a full-reactor run and passing
 * alone, at class scope, at package scope and across a whole-module run: with no second task in
 * flight there is nothing to retire the ticket early.
 */
final class ControlTaskTicketTest {

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(64, 32)
                .withBatchSize(16)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("ticket-lane", true);
    }

    @Test
    @Timeout(60)
    void awaitingOneTaskIsNotSatisfiedByAnotherTaskFinishingFirst() {
        AtomicBoolean secondHasRun = new AtomicBoolean();

        try (Lane lane = new Lane(0, config(), MemoryAccess.best(), context -> (region, offsets, count) -> count)) {
            lane.start();

            // First: slow, and submitted first so it runs first. This is the watermark advance in
            // the real shape of the bug.
            lane.submitControlTask(() -> sleep(Duration.ofMillis(600)));

            // Second: the one actually being waited on. Its ticket was read before the first had
            // completed, so the first's completion used to retire it.
            long ticket = lane.submitControlTask(() -> {
                sleep(Duration.ofMillis(600));
                secondHasRun.set(true);
            });

            assertThat(lane.awaitControlTask(ticket, Duration.ofSeconds(20)))
                    .as("the wait reported success")
                    .isTrue();
            assertThat(secondHasRun)
                    .as("a ticket that reports success for a task which has not run is how a "
                            + "checkpoint stores a null snapshot and a restore silently restores nothing")
                    .isTrue();
        }
    }

    @Test
    @Timeout(60)
    void everyTicketIsSatisfiedInOrderAndOnlyByItsOwnTask() {
        int tasks = 8;
        boolean[] ran = new boolean[tasks];

        try (Lane lane = new Lane(0, config(), MemoryAccess.best(), context -> (region, offsets, count) -> count)) {
            lane.start();
            long[] tickets = new long[tasks];
            for (int i = 0; i < tasks; i++) {
                int index = i;
                tickets[i] = lane.submitControlTask(() -> {
                    sleep(Duration.ofMillis(60));
                    ran[index] = true;
                });
            }
            for (int i = 0; i < tasks; i++) {
                assertThat(lane.awaitControlTask(tickets[i], Duration.ofSeconds(30)))
                        .as("ticket %d reported success", i)
                        .isTrue();
                assertThat(ran[i]).as("and task %d had actually run by then", i).isTrue();
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted inside a control task", e);
        }
    }
}
