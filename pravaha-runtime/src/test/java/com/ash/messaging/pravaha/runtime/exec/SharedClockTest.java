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
package com.ash.messaging.pravaha.runtime.exec;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The process's timer, and the one period it used to accept by changing it.
 *
 * <p>Nothing here waits for a firing: the assertions are about what the scheduler agrees to
 * schedule, which is decided before the first tick.
 */
class SharedClockTest {

    @Test
    void time11APeriodThisTimerCannotCountIsRefusedRatherThanClampedToAMillisecond() {
        // TIME-11. The clamp travelled: it was Math.max(1, tick.toMillis()) in QueryExecution when
        // the finding was written and Math.max(1L, period.toMillis()) here after W9-3 shared the
        // clock, and in both places 0s, PT0.0005S and -1s were accepted and all became one
        // millisecond -- while the caller logged the configured value, so the only surface that
        // mentions the tick actively misreported what the engine was doing. A zero period is a
        // thousand passes over every lane a second, for ever, on a daemon thread.
        for (Duration period : java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(500_000))) {
            assertThatThrownBy(() -> SharedClock.every(period, "a test", () -> {}))
                    .as("period %s", period)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be scheduled")
                    .hasMessageContaining("at least PT0.001S");
        }
        assertThatThrownBy(() -> SharedClock.every(null, "a test", () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPeriodTheTimerCanCountIsScheduled() {
        // The boundary is live in the other direction, and the handle is the caller's to cancel.
        ScheduledFuture<?> handle = SharedClock.every(Duration.ofMillis(1), "a test", () -> {});
        assertThat(handle != null).isTrue();
        handle.cancel(false);
    }
}
