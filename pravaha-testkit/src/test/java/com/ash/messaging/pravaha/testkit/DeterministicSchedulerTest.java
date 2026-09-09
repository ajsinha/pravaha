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
package com.ash.messaging.pravaha.testkit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicSchedulerTest {

    @Test
    void runsEveryStepUntilNoneCanProgress() {
        AtomicInteger a = new AtomicInteger(3);
        AtomicInteger b = new AtomicInteger(5);
        int executed = new DeterministicScheduler(1L)
                .register("a", () -> a.getAndUpdate(v -> Math.max(0, v - 1)) > 0)
                .register("b", () -> b.getAndUpdate(v -> Math.max(0, v - 1)) > 0)
                .runToCompletion();

        assertThat(executed).isEqualTo(8);
        assertThat(a).hasValue(0);
        assertThat(b).hasValue(0);
    }

    @Test
    void theSameSeedReproducesTheSameOrder() {
        // Without this a failure found at seed N could not be reproduced, which would make the
        // whole approach pointless.
        assertThat(traceFor(42L)).isEqualTo(traceFor(42L)).isNotEmpty();
    }

    @Test
    void differentSeedsExploreDifferentOrders() {
        Set<String> traces = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) {
            traces.add(String.join(",", traceFor(seed)));
        }
        assertThat(traces).hasSizeGreaterThan(20);
    }

    @Test
    void aStepThatNeverProgressesIsSimplySkipped() {
        AtomicInteger ran = new AtomicInteger();
        int executed = new DeterministicScheduler(1L)
                .register("idle", () -> false)
                .register("work", () -> ran.incrementAndGet() < 3)
                .runToCompletion();
        assertThat(executed).isEqualTo(2);
    }

    @Test
    void anEmptyScheduleTerminatesImmediately() {
        assertThat(new DeterministicScheduler(1L).runToCompletion()).isZero();
    }

    @Test
    void aRunawayStepTripsTheGuardRatherThanHangingTheSuite() {
        // A step that always claims progress would otherwise hang CI with no diagnosis.
        assertThatThrownBy(() -> new DeterministicScheduler(1L, 100)
                        .register("forever", () -> true)
                        .runToCompletion())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded 100 iterations")
                // The seed must be in the message, or the failure cannot be reproduced.
                .hasMessageContaining("with seed 1");
    }

    @Test
    void duplicateStepNamesAreRejected() {
        assertThatThrownBy(() -> new DeterministicScheduler(1L)
                        .register("a", () -> false)
                        .register("a", () -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void traceIsEmptyUnlessRecordingWasEnabled() {
        DeterministicScheduler s = new DeterministicScheduler(1L);
        AtomicInteger n = new AtomicInteger(3);
        s.register("a", () -> n.decrementAndGet() > 0).runToCompletion();
        assertThat(s.trace()).isEmpty();
    }

    @Test
    void toStringNamesTheSeedAndSteps() {
        assertThat(new DeterministicScheduler(7L).register("x", () -> false).toString())
                .contains("seed=7")
                .contains("x");
    }

    private static java.util.List<String> traceFor(long seed) {
        Deque<Integer> a = new ArrayDeque<>(java.util.List.of(1, 2, 3, 4));
        Deque<Integer> b = new ArrayDeque<>(java.util.List.of(1, 2, 3, 4));
        DeterministicScheduler s = new DeterministicScheduler(seed).recordTrace();
        s.register("a", () -> a.poll() != null)
                .register("b", () -> b.poll() != null)
                .runToCompletion();
        return s.trace();
    }
}
