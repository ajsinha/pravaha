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
package com.ash.messaging.pravaha.backfill;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The control loop that makes a backfill safe to run during business hours.
 *
 * <p>A backfill competes with production traffic on the same cluster. The interesting behaviours
 * are not "does the number go down" but the asymmetry -- fast down, slow up -- and the bounds, both
 * of which exist because of how the failure looks: a store already in trouble needs the load halved
 * now, and a store that looks healthy may look that way only because the backfill is currently
 * slow.
 */
class BackfillThrottleTest {

    private static final long TARGET = Duration.ofMillis(10).toNanos();
    private static final long HEALTHY = Duration.ofMillis(2).toNanos();
    private static final long STRUGGLING = Duration.ofMillis(50).toNanos();

    private static BackfillThrottle throttle() {
        return new BackfillThrottle(10_000, 100, TARGET);
    }

    @Test
    void itStartsAtTheConfiguredCeiling() {
        // Not at some cautious fraction of it. The ceiling is the operator's statement of what the
        // store can take; starting below it makes every backfill slower than asked for reasons
        // nobody can see.
        BackfillThrottle throttle = throttle();

        assertThat(throttle.rowsPerSecond()).isEqualTo(10_000);
        assertThat(throttle.observe(HEALTHY).reason()).contains("at the configured ceiling");
    }

    @Test
    void aStoreOverItsLatencyTargetHalvesTheRateAtOnce() {
        BackfillThrottle throttle = throttle();

        BackfillThrottle.Decision decision = throttle.observe(STRUGGLING);

        assertThat(decision.rowsPerSecond()).isEqualTo(5_000);
        assertThat(decision.reason()).contains("50 ms").contains("halved");
    }

    @Test
    void recoveryIsSlowerThanBackoff() {
        // The asymmetry, asserted rather than assumed. Symmetric control oscillates -- speed up,
        // hurt the store, back off, speed up -- and an oscillating backfill is worse for a store
        // than a constant one.
        BackfillThrottle throttle = throttle();
        throttle.observe(STRUGGLING);
        long afterBackoff = throttle.rowsPerSecond();

        throttle.observe(HEALTHY);

        assertThat(throttle.rowsPerSecond()).isLessThan(10_000);
        assertThat(throttle.rowsPerSecond() - afterBackoff)
                .as("one healthy observation undid a whole backoff")
                .isLessThan(afterBackoff / 2);
    }

    @Test
    void repeatedTroubleBottomsOutAtTheFloorRatherThanAtZero() {
        // A backfill driven to nothing never finishes, which is a worse outcome than one that is
        // visibly too slow -- and it looks identical to a hung process.
        BackfillThrottle throttle = throttle();

        for (int i = 0; i < 50; i++) {
            throttle.observe(STRUGGLING);
        }

        assertThat(throttle.rowsPerSecond()).isEqualTo(100);
        assertThat(throttle.observe(STRUGGLING).reason())
                .as("at the floor and still struggling, the backfill is not the cause and should say so")
                .contains("no longer the cause");
    }

    @Test
    void recoveryStopsAtTheCeiling() {
        BackfillThrottle throttle = throttle();
        throttle.observe(STRUGGLING);

        for (int i = 0; i < 200; i++) {
            throttle.observe(HEALTHY);
        }

        assertThat(throttle.rowsPerSecond()).isEqualTo(10_000);
    }

    @Test
    void aSmallRateStillRecoversRatherThanStalling() {
        // Ten per cent of a small number rounds to nothing, and a controller that multiplies its way
        // up from the floor would sit there for ever. The step is at least one row per second.
        BackfillThrottle throttle = new BackfillThrottle(10_000, 1, TARGET);
        for (int i = 0; i < 50; i++) {
            throttle.observe(STRUGGLING);
        }
        assertThat(throttle.rowsPerSecond()).isEqualTo(1);

        throttle.observe(HEALTHY);

        assertThat(throttle.rowsPerSecond()).isGreaterThan(1);
    }

    @Test
    void pinningOverridesTheControllerPermanently() {
        // An operator who pins has usually done so during an incident, with information the
        // controller does not have. Quietly resuming control would make that decision temporary
        // without telling anybody.
        BackfillThrottle throttle = throttle();
        throttle.pin(250);

        assertThat(throttle.observe(STRUGGLING).rowsPerSecond()).isEqualTo(250);
        assertThat(throttle.observe(HEALTHY).rowsPerSecond()).isEqualTo(250);
        assertThat(throttle.observe(HEALTHY).reason()).contains("pinned");
        assertThat(throttle.backoffs()).isZero();
    }

    @Test
    void everyObservationHasAReasonIncludingTheQuietOnes() {
        // A controller that only explains itself when it acts leaves an operator asking why nothing
        // is happening, which is the question that gets asked during an incident.
        BackfillThrottle throttle = throttle();

        assertThat(throttle.observe(HEALTHY).reason()).isNotBlank();
        assertThat(throttle.observe(STRUGGLING).reason()).isNotBlank();
        assertThat(throttle.observations()).isEqualTo(2);
    }

    @Test
    void theBudgetForAWindowFollowsTheRate() {
        BackfillThrottle throttle = throttle();

        assertThat(throttle.budgetFor(Duration.ofSeconds(1).toNanos())).isEqualTo(10_000);
        assertThat(throttle.budgetFor(Duration.ofMillis(100).toNanos())).isEqualTo(1_000);

        throttle.observe(STRUGGLING);

        assertThat(throttle.budgetFor(Duration.ofSeconds(1).toNanos())).isEqualTo(5_000);
    }

    @Test
    void aBudgetIsNeverZero() {
        // Zero would stall the backfill silently on any short window, and the caller would have no
        // way to distinguish it from a source with nothing to give.
        BackfillThrottle throttle = new BackfillThrottle(10, 1, TARGET);

        assertThat(throttle.budgetFor(Duration.ofMillis(1).toNanos())).isEqualTo(1);
    }

    @Test
    void nonsensicalLimitsAreRefusedAtConstruction() {
        assertThatThrownBy(() -> new BackfillThrottle(0, 1, TARGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ceiling");
        assertThatThrownBy(() -> new BackfillThrottle(100, 200, TARGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor");
        assertThatThrownBy(() -> new BackfillThrottle(100, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latency target");
        assertThatThrownBy(() -> throttle().pin(999_999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ceiling");
    }
}
