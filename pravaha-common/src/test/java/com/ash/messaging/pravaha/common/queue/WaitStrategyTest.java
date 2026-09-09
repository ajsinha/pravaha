/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
