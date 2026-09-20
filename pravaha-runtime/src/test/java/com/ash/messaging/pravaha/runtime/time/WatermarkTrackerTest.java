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
package com.ash.messaging.pravaha.runtime.time;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lane watermarks, and the idle partition that stops every window in a query.
 *
 * <p>{@link #oneQuietPartitionDoesNotFreezeEveryWindow} is the reason this class exists. Without
 * idle handling the symptom is not an error: records keep arriving, the query keeps accepting them,
 * every window stops firing, and nothing anywhere says why. Design section 15.2 calls it the single
 * most common streaming production incident, and it presents as a hang.
 *
 * <p>Time is passed in rather than read from a clock, so every test here is deterministic and none
 * of them sleeps.
 */
class WatermarkTrackerTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long IDLE_TIMEOUT = 30 * SECOND;

    private static WatermarkTracker trackerWith(String... partitions) {
        WatermarkTracker tracker = new WatermarkTracker(IDLE_TIMEOUT);
        for (String partition : partitions) {
            tracker.addPartition(partition, WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0L);
        }
        return tracker;
    }

    @Test
    void theLaneWatermarkIsTheMinimumAcrossItsPartitions() {
        WatermarkTracker tracker = trackerWith("p0", "p1");
        tracker.observe("p0", 100 * SECOND, 0L);
        tracker.observe("p1", 50 * SECOND, 0L);

        // The slowest partition decides: a window may only fire when every partition has passed it.
        assertThat(tracker.advance(0L)).isEqualTo(48 * SECOND);
    }

    @Test
    void aPartitionThatHasProducedNothingHoldsTheWatermarkBack() {
        // It is not idle and not ready. It may still deliver an old record, so firing on the
        // strength of the others would be firing on an assumption nobody made.
        WatermarkTracker tracker = trackerWith("p0", "p1");
        tracker.observe("p0", 100 * SECOND, 0L);

        assertThat(tracker.advance(0L)).isEqualTo(WatermarkGenerator.NOT_YET);
    }

    @Test
    void oneQuietPartitionDoesNotFreezeEveryWindow() {
        // The incident this whole class exists to prevent. p1 goes quiet -- a region that trades
        // only in the morning, a rarely-updated key range -- and without idle handling it pins the
        // lane watermark at 48 s forever while p0 races ahead. No error, no output, and it looks
        // like a hang.
        WatermarkTracker tracker = trackerWith("p0", "p1");
        tracker.observe("p0", 100 * SECOND, 0L);
        tracker.observe("p1", 50 * SECOND, 0L);
        assertThat(tracker.advance(0L)).isEqualTo(48 * SECOND);

        tracker.observe("p0", 200 * SECOND, 20 * SECOND);
        assertThat(tracker.advance(20 * SECOND))
                .as("p1 is quiet but not yet idle, so it still holds the watermark")
                .isEqualTo(48 * SECOND);

        // Past the idle timeout, p1 stops holding the lane back.
        long later = 40 * SECOND;
        assertThat(tracker.advance(later)).isEqualTo(198 * SECOND);
        assertThat(tracker.isIdle("p1")).isTrue();
        assertThat(tracker.idleExclusions()).isEqualTo(1);
    }

    @Test
    void anIdlePartitionRejoinsAsSoonAsItProducesAgain() {
        // Idleness is an exclusion, not a removal. A partition that wakes up must hold the lane back
        // again, or a source that resumes would have its records treated as late.
        //
        // p0 is kept alive deliberately. The first version of this test let both partitions go idle
        // and then expected the watermark p0 would have produced -- which is a different scenario
        // wearing this one's name, and it failed for the right reason.
        WatermarkTracker tracker = trackerWith("p0", "p1");
        tracker.observe("p0", 100 * SECOND, 0L);
        tracker.observe("p1", 50 * SECOND, 0L);
        tracker.observe("p0", 100 * SECOND, 39 * SECOND);
        tracker.advance(40 * SECOND);
        assertThat(tracker.isIdle("p1")).isTrue();
        assertThat(tracker.isIdle("p0")).isFalse();
        assertThat(tracker.watermark()).as("p1 excluded, so p0 alone decides").isEqualTo(98 * SECOND);

        tracker.observe("p1", 60 * SECOND, 41 * SECOND);
        assertThat(tracker.isIdle("p1")).isFalse();
        assertThat(tracker.advance(41 * SECOND))
                .as("p1 holds the lane back again, but the watermark it already reached stands")
                .isEqualTo(98 * SECOND);
    }

    @Test
    void anOutOfOrderRecordCannotPullTheWatermarkBack() {
        // The ordinary case, and it is handled by the generator rather than the tracker: bounded
        // out-of-orderness tracks the maximum event time seen, so a late record simply does not move
        // it. Worth asserting because it is the behaviour every window depends on.
        WatermarkTracker tracker = trackerWith("p0");
        tracker.observe("p0", 100 * SECOND, 0L);
        assertThat(tracker.advance(0L)).isEqualTo(98 * SECOND);

        tracker.observe("p0", 10 * SECOND, SECOND);
        assertThat(tracker.advance(SECOND)).isEqualTo(98 * SECOND);
        assertThat(tracker.regressions())
                .as("no regression: the built-in generators cannot produce one")
                .isZero();
    }

    @Test
    void aGeneratorThatRegressesIsCountedRatherThanObeyed() {
        // The counter exists for custom generators, which are an SPI surface: a plugin may supply
        // its own, and one that goes backwards after a rewind would un-fire windows that have
        // already fired. Neither built-in generator can regress, which is why this test has to
        // supply one that does -- and which is also worth knowing, because it means the counter
        // stays at zero in every ordinary deployment.
        long[] falling = {100 * SECOND};
        WatermarkGenerator misbehaving = new WatermarkGenerator() {
            @Override
            public void observe(long eventTimeNanos) {
                falling[0] -= 10 * SECOND;
            }

            @Override
            public long watermark() {
                return falling[0];
            }
        };

        WatermarkTracker tracker = new WatermarkTracker(IDLE_TIMEOUT);
        tracker.addPartition("odd", misbehaving, 0L);
        assertThat(tracker.advance(0L)).isEqualTo(100 * SECOND);

        tracker.observe("odd", SECOND, 0L);
        assertThat(tracker.advance(0L)).as("held at what it reached").isEqualTo(100 * SECOND);
        assertThat(tracker.regressions()).isEqualTo(1);
    }

    @Test
    void whenEveryPartitionIsIdleTheWatermarkHolds() {
        // Jumping to infinity because the source went quiet would fire every open window at once --
        // turning a lull into a flood of premature results, each of which is a wrong answer.
        WatermarkTracker tracker = trackerWith("p0");
        tracker.observe("p0", 100 * SECOND, 0L);
        assertThat(tracker.advance(0L)).isEqualTo(98 * SECOND);

        assertThat(tracker.advance(Duration.ofHours(1).toNanos())).isEqualTo(98 * SECOND);
    }

    @Test
    void anAscendingPartitionTrailsByNothing() {
        WatermarkTracker tracker = new WatermarkTracker(IDLE_TIMEOUT);
        tracker.addPartition("ordered", WatermarkGenerator.ascending(), 0L);
        tracker.observe("ordered", 100 * SECOND, 0L);

        assertThat(tracker.advance(0L)).isEqualTo(100 * SECOND);
    }

    @Test
    void aPunctuatedPartitionMovesOnlyWhenTheSourceSaysSo() {
        // The source is the authority. Inferring progress from records would reintroduce exactly the
        // guess this strategy was chosen to avoid.
        WatermarkGenerator.PunctuatedWatermarkGenerator generator = WatermarkGenerator.punctuated();
        WatermarkTracker tracker = new WatermarkTracker(IDLE_TIMEOUT);
        tracker.addPartition("cdc", generator, 0L);

        tracker.observe("cdc", 100 * SECOND, 0L);
        assertThat(tracker.advance(0L))
                .as("records do not move a punctuated watermark")
                .isEqualTo(WatermarkGenerator.NOT_YET);

        generator.declare(90 * SECOND);
        assertThat(tracker.advance(0L)).isEqualTo(90 * SECOND);

        generator.declare(80 * SECOND);
        assertThat(generator.watermark())
                .as("a declaration cannot move it backwards")
                .isEqualTo(90 * SECOND);
    }

    @Test
    void anUnknownPartitionIsARegistrationMistakeAndSaysSo() {
        WatermarkTracker tracker = trackerWith("p0");
        assertThatThrownBy(() -> tracker.observe("p9", SECOND, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no partition named 'p9'");
    }

    @Test
    void negativeConfigurationIsRefused() {
        assertThatThrownBy(() -> new WatermarkTracker(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatermarkGenerator.boundedOutOfOrderness(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void time8TheThreeNumbersThatExplainAStuckWatermarkAreReadableInOneCall() {
        // TIME-8. isIdle, idleExclusions() and regressions() each existed, each were documented --
        // the second as "the metric that explains a moving watermark" -- and none of them had a
        // caller outside this class. On a live node with a stalled query and a healthy one side by
        // side, every shipped surface showed the same thing: `pravaha queries` gives name, state,
        // fingerprint and rows in, /actuator/prometheus had seven pravaha_* gauges and none of
        // these, and /api/v1/status has none. A quiet partition stopping every window in a query is
        // the commonest streaming incident there is and it presents as a hang.
        //
        // One call rather than three getters, because the three mislead apart: an exclusion count
        // with no idle count says a partition went quiet at some point, and the pair says whether
        // it is quiet now.
        WatermarkTracker tracker = trackerWith("p0", "p1");
        tracker.observe("p0", 50 * SECOND, 0L);
        tracker.observe("p1", 50 * SECOND, 0L);
        tracker.advance(0L);

        assertThat(tracker.diagnostics()).isEqualTo(new WatermarkTracker.Diagnostics(2, 0, 0, 0));

        // p1 goes quiet past the idle timeout: excluded now, and counted for having been.
        long later = IDLE_TIMEOUT + SECOND;
        tracker.observe("p0", 100 * SECOND, later);
        tracker.advance(later);
        assertThat(tracker.diagnostics())
                .as("one of two excluded, and the exclusion recorded")
                .isEqualTo(new WatermarkTracker.Diagnostics(2, 1, 1, 0));

        // It comes back, which the gauge forgets a moment later and the counter does not -- which
        // is why both are published.
        tracker.observe("p1", 100 * SECOND, later + SECOND);
        tracker.advance(later + SECOND);
        assertThat(tracker.diagnostics().idleNow()).isZero();
        assertThat(tracker.diagnostics().idleExclusions())
                .as("the reason a window fired early and a row then arrived late")
                .isEqualTo(1);

        // And a partition reporting a watermark below the lane's is counted, never applied. The
        // way to reach it is the one an operator does: a partition goes quiet, the lane's
        // watermark runs on without it, and it comes back where it left off -- behind. Every row
        // it sends from here is late, which is exactly what this counter is for.
        WatermarkTracker regressing = trackerWith("p0", "p1");
        regressing.observe("p0", 100 * SECOND, 0L);
        regressing.observe("p1", 100 * SECOND, 0L);
        assertThat(regressing.advance(0L)).isEqualTo(98 * SECOND);

        regressing.observe("p0", 500 * SECOND, later);
        assertThat(regressing.advance(later))
                .as("p1 excluded, so p0 alone decides")
                .isEqualTo(498 * SECOND);

        regressing.observe("p1", 100 * SECOND, later + SECOND);
        assertThat(regressing.advance(later + SECOND))
                .as("the watermark never moves backwards")
                .isEqualTo(498 * SECOND);
        assertThat(regressing.diagnostics().regressions()).isEqualTo(1);

        assertThat(WatermarkTracker.Diagnostics.NONE)
                .as("a query that derives no watermarks is not a query with none idle")
                .isEqualTo(new WatermarkTracker.Diagnostics(0, 0, 0, 0));
    }

    @Test
    void time11ATickThisTimerCannotCountIsRefusedRatherThanClamped() {
        // TIME-11. Three configurations were accepted on live nodes and all three became one
        // millisecond: 0s (a thousand passes a second over every lane, for ever, on a daemon
        // thread), PT0.0005S (an operator who asked for 2000 ticks a second got 1000), and -1s
        // (nothing looked at the sign, because tick.compareTo(idleAfter) > 0 is false for a
        // negative). The bounds belong here beside the idle timeout's, which have refused rather
        // than clamped from the start, for the reason this class already states: a value quietly
        // changed to something the operator did not ask for is how a tuned setting becomes a
        // mystery later.
        Duration idle = Duration.ofSeconds(30);
        assertThatThrownBy(() -> WatermarkTracker.requireTick(Duration.ZERO, idle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a period");
        assertThatThrownBy(() -> WatermarkTracker.requireTick(Duration.ofSeconds(-1), idle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a period");
        assertThatThrownBy(() -> WatermarkTracker.requireTick(Duration.ofNanos(500_000), idle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below the minimum of PT0.001S");

        // And the boundary is live in the other direction: exactly a millisecond is accepted.
        WatermarkTracker.requireTick(WatermarkTracker.MINIMUM_TICK, idle);
    }

    @Test
    void time5ATickLongerThanTheIdleTimeoutIsRefusedByTheSameCheck() {
        // TIME-5. The ordering rule existed and lived in QueryExecution.generatingWatermarks, so it
        // fired once per *registration*: `tick: 5m` with `idle-after: 30s` started a node that
        // logged both settings as in force, reported UP, recovered its journal and then refused
        // every query. Moving the bound here is what lets PravahaNode.start apply it once, where
        // one bad value costs one startup failure.
        assertThatThrownBy(() -> WatermarkTracker.requireTick(Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longer than the idle timeout");
        assertThatThrownBy(() -> WatermarkTracker.requireTick(Duration.ofSeconds(31), Duration.ofSeconds(30)))
                .as("the boundary is live, so only the placement was ever wrong")
                .isInstanceOf(IllegalArgumentException.class);
        WatermarkTracker.requireTick(Duration.ofSeconds(30), Duration.ofSeconds(30));
    }
}
