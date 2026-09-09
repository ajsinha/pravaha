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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualClockTest {

    @Test
    void startsAtAFixedReadableOrigin() {
        // A fixed origin keeps timestamps in failure messages legible instead of being whatever
        // the machine's clock said that afternoon.
        assertThat(new VirtualClock().instant()).hasToString("2026-01-01T00:00:00Z");
    }

    @Test
    void movesOnlyWhenTold() {
        VirtualClock clock = new VirtualClock();
        long before = clock.nanos();
        for (int i = 0; i < 1_000_000; i++) {
            // Doing real work must not advance virtual time; that is the whole point.
            assertThat(clock.nanos()).isEqualTo(before);
        }
    }

    @Test
    void advancesByDurationAndToAnAbsolutePoint() {
        VirtualClock clock = new VirtualClock(0L);
        assertThat(clock.advanceBy(Duration.ofSeconds(30)).nanos()).isEqualTo(30_000_000_000L);
        assertThat(clock.advanceByNanos(500).nanos()).isEqualTo(30_000_000_500L);
        assertThat(clock.advanceTo(60_000_000_000L).millis()).isEqualTo(60_000L);
    }

    @Test
    void refusesToMoveBackwards() {
        // A regressing clock lets a test construct a state the engine will never encounter, so the
        // failure is worth having rather than tolerating.
        VirtualClock clock = new VirtualClock(1_000L);
        assertThatThrownBy(() -> clock.advanceTo(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
        assertThatThrownBy(() -> clock.advanceBy(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.advanceByNanos(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void advancingToTheSameInstantIsAllowed() {
        VirtualClock clock = new VirtualClock(1_000L);
        assertThat(clock.advanceTo(1_000L).nanos()).isEqualTo(1_000L);
    }

    @Test
    void notifiesListenersOnEveryAdvance() {
        // This is how timers and watermark generators will hook in.
        VirtualClock clock = new VirtualClock(0L);
        List<Long> seen = new ArrayList<>();
        clock.onAdvance(() -> seen.add(clock.nanos()));

        clock.advanceBy(Duration.ofSeconds(1));
        clock.advanceBy(Duration.ofSeconds(1));
        assertThat(seen).containsExactly(1_000_000_000L, 2_000_000_000L);
    }

    @Test
    void toStringShowsTheInstant() {
        assertThat(new VirtualClock().toString()).contains("2026-01-01");
    }
}
