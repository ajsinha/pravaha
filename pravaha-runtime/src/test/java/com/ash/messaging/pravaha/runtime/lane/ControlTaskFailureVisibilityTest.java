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
 * A control task that throws must have recorded the failure before its ticket is released.
 *
 * <p>{@code awaitControlTask} returns as soon as the ticket is retired, and the coordinator's next
 * act is {@code checkHealth()} to find out whether the task worked. {@code QueryExecution.restore}
 * is exactly that shape: submit {@code restoreState}, await, then {@code lane.checkHealth()}.
 *
 * <p>The failure used to be recorded by {@code run()}'s outer {@code catch}, which the exception
 * only reaches after unwinding the control loop and the batch loop. The ticket, meanwhile, was
 * released in a {@code finally} on the way out. Between the two the lane has failed and does not say
 * so, and a coordinator scheduled in that window reads a healthy lane and carries on -- so a
 * snapshot whose magic number or version was rejected is accepted in silence, which is the one
 * outcome those checks exist to prevent.
 *
 * <p>It surfaced as {@code StateRestoreTest.state060} and {@code state061} failing under full-reactor
 * verifies and passing under every narrower scope. The window is nanoseconds wide on an idle
 * machine: an earlier version of this test found 0 hits in 300 attempts and wrongly cleared the
 * ordering. The load below is the point of the test, not decoration -- it is what makes the lane
 * thread liable to be descheduled between the throw and the recording.
 */
final class ControlTaskFailureVisibilityTest {

    private static final int ATTEMPTS = 400;

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(64, 32)
                .withBatchSize(16)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("control-failure-lane", true);
    }

    @Test
    @Timeout(180)
    void aThrowingControlTaskHasRecordedItsFailureBeforeTheTicketIsReleased() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread[] load = startContention(stop);
        int releasedWithoutSayingSo = 0;

        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try (Lane lane =
                        new Lane(0, config(), MemoryAccess.best(), context -> (region, offsets, count) -> count)) {
                    lane.start();
                    long ticket = lane.submitControlTask(() -> {
                        throw new IllegalStateException("this control task refuses");
                    });
                    if (!lane.awaitControlTask(ticket, Duration.ofSeconds(10))) {
                        continue; // never released: a different condition from the one under test
                    }
                    // Released, so the task has run, and it threw. This is the instant the
                    // coordinator asks. Nothing here sleeps or retries -- a fix that only narrowed
                    // the window would still be caught, given enough attempts under load.
                    if (lane.failure().isEmpty()) {
                        releasedWithoutSayingSo++;
                    }
                }
            }
        } finally {
            stop.set(true);
            for (Thread thread : load) {
                thread.join(Duration.ofSeconds(5).toMillis());
            }
        }

        assertThat(releasedWithoutSayingSo)
                .as(
                        "%d of %d releases handed back a lane that had already failed without saying so; a "
                                + "coordinator asking at that moment -- QueryExecution.restore does -- reads the "
                                + "failure as a success",
                        releasedWithoutSayingSo, ATTEMPTS)
                .isZero();
    }

    @Test
    @Timeout(180)
    void aWaiterOnAThrowingTaskIsToldItRanRatherThanThatItTimedOut() throws Exception {
        // The other half of the ordering, and a bug I introduced fixing the first half. The waiter
        // gives up early when it sees State.FAILED, returning whether the ticket has moved -- so
        // marking the lane failed *before* retiring the ticket makes it answer "no", and
        // QueryExecution.restore turns that into "lane 0 did not restore its state within PT30S".
        // The caller is then told the snapshot timed out when it was actually refused: a wrong
        // diagnosis of a correct rejection, which is how a version check gets blamed on the disk.
        AtomicBoolean stop = new AtomicBoolean();
        Thread[] load = startContention(stop);
        int reportedAsNotRun = 0;

        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try (Lane lane =
                        new Lane(0, config(), MemoryAccess.best(), context -> (region, offsets, count) -> count)) {
                    lane.start();
                    long ticket = lane.submitControlTask(() -> {
                        throw new IllegalStateException("this control task refuses");
                    });
                    if (!lane.awaitControlTask(ticket, Duration.ofSeconds(10))) {
                        reportedAsNotRun++;
                    }
                }
            }
        } finally {
            stop.set(true);
            for (Thread thread : load) {
                thread.join(Duration.ofSeconds(5).toMillis());
            }
        }

        assertThat(reportedAsNotRun)
                .as(
                        "%d of %d waits reported a task that had run and thrown as one that never ran; the "
                                + "caller then reports a timeout instead of the refusal",
                        reportedAsNotRun, ATTEMPTS)
                .isZero();
    }

    /** Enough runnable threads that the lane is liable to lose its core mid-unwind. */
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
}
