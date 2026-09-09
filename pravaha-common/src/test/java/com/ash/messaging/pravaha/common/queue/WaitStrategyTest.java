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
package com.ash.messaging.pravaha.common.queue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class WaitStrategyTest {

    @ParameterizedTest
    @EnumSource(WaitStrategy.Kind.class)
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void everyStrategyReturnsPromptlyAcrossTheEscalationRange(WaitStrategy.Kind kind) {
        // Each strategy escalates on the idle count. This walks the whole range so an off-by-one in
        // a threshold, or a backoff that overflows into a very long park, shows up here rather than
        // as a lane that mysteriously stops responding.
        WaitStrategy strategy = kind.strategy();
        long start = System.nanoTime();
        for (int i = 1; i <= 300; i++) {
            strategy.idle(i);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMillis)
                .as("%s took %d ms over 300 idle calls", kind, elapsedMillis)
                .isLessThan(5_000);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void backoffIsBoundedSoAResumingStreamIsNotLeftWaiting() {
        // The shift in the exponential backoff is clamped; without the clamp a large idle count
        // would shift past 63 and produce a nonsensical park duration.
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            WaitStrategy.BACKOFF_PARK.idle(Integer.MAX_VALUE);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMillis).isLessThan(1_000);
    }

    @Test
    void busySpinNeverParks() {
        long start = System.nanoTime();
        for (int i = 1; i <= 10_000; i++) {
            WaitStrategy.BUSY_SPIN.idle(i);
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500);
    }

    @Test
    void strategiesAreStatelessAndSoSafeToShareAcrossLanes() {
        AtomicInteger calls = new AtomicInteger();
        WaitStrategy counting = idleCount -> calls.incrementAndGet();
        counting.idle(1);
        counting.idle(2);
        assertThat(calls).hasValue(2);
        assertThat(WaitStrategy.Kind.SPIN_THEN_YIELD.strategy()).isSameAs(WaitStrategy.Kind.SPIN_THEN_YIELD.strategy());
    }
}
