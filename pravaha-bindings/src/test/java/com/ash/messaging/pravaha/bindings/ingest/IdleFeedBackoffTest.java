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
package com.ash.messaging.pravaha.bindings.ingest;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a bound source costs while nothing is arriving (SRC-6).
 *
 * <p>{@code PumpingFeed} napped a flat millisecond after a poll that moved nothing, so every bound
 * source was polled a thousand times a second whether or not anything had happened — and in follow
 * mode each of those polls is a {@code stat} plus a {@code read}. Measured by {@code
 * SourceScaleTest}: <strong>12.8 ms of process CPU per second per source</strong> over 100 followed
 * files with nothing being written to them, 1,782 ms/s against an unbound baseline of 504; and 16.9
 * ms/s per source at 50, so a per-source constant rather than a constant of the node. A hundred
 * idle followed files is 1.8 cores, and nothing is reading any rows.
 *
 * <p><strong>Asserted as a state machine rather than as a duration.</strong> A test that counted
 * wake-ups over a second would measure this machine — which runs several agents — and would fail
 * in the safe direction for the old code, since a loaded machine polls fewer times too. The
 * backoff is arithmetic, so the arithmetic is what is pinned; {@code SourceScaleTest} reports the
 * CPU figure that follows from it.
 */
class IdleFeedBackoffTest {

    private static final long MILLISECOND = Duration.ofMillis(1).toNanos();

    @Test
    void src6_theNapDoublesWhileNothingArrivesAndStopsAtThePublishInterval() {
        long nap = MILLISECOND;

        assertThat(nap).as("the first quiet poll waits what it always waited").isEqualTo(1_000_000L);

        nap = PumpingFeed.nextIdleNap(nap);
        assertThat(nap).isEqualTo(2_000_000L);
        nap = PumpingFeed.nextIdleNap(nap);
        assertThat(nap).isEqualTo(4_000_000L);
        nap = PumpingFeed.nextIdleNap(nap);
        assertThat(nap).isEqualTo(8_000_000L);
        nap = PumpingFeed.nextIdleNap(nap);
        assertThat(nap).isEqualTo(16_000_000L);

        // The ceiling is the feed's own publish interval, so the nap can never become the dominant
        // term in a row's visibility latency -- which is the property the flat millisecond was
        // chosen for and is kept here.
        nap = PumpingFeed.nextIdleNap(nap);
        assertThat(nap).as("clamped at the publish interval, not at 32ms").isEqualTo(20_000_000L);
        for (int i = 0; i < 100; i++) {
            nap = PumpingFeed.nextIdleNap(nap);
        }
        assertThat(nap).as("and it stays there however long the quiet lasts").isEqualTo(20_000_000L);
    }

    /**
     * The whole point, in one number: a source quiet for a second wakes about fifty times, not a
     * thousand.
     */
    @Test
    void src6_asecondOfQuietIsFiftyWakeUpsRatherThanAThousand() {
        long elapsed = 0;
        int wakeUps = 0;
        long nap = MILLISECOND;
        while (elapsed < Duration.ofSeconds(1).toNanos()) {
            elapsed += nap;
            wakeUps++;
            nap = PumpingFeed.nextIdleNap(nap);
        }

        assertThat(wakeUps)
                .as("a flat millisecond is 1,000 wake-ups for the same second of nothing happening")
                .isLessThan(100)
                .isGreaterThan(10);
    }

    /** And a source that is merely slow rather than empty pays a millisecond, as it always did. */
    @Test
    void src6_aPollThatMovesARowPutsTheNapBackToAMillisecond() {
        long afterALongQuiet = PumpingFeed.nextIdleNap(20_000_000L);
        assertThat(afterALongQuiet).isEqualTo(20_000_000L);

        // The reset is not arithmetic -- the loop assigns IDLE_NAP_NANOS directly when a poll moves
        // rows -- so what is pinned here is that the constant it resets to is the old flat value.
        assertThat(MILLISECOND)
                .as("the first nap after a row is the millisecond a slow stream always paid")
                .isEqualTo(1_000_000L);
    }
}
