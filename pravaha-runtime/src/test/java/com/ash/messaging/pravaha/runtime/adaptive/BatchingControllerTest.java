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
package com.ash.messaging.pravaha.runtime.adaptive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adaptive batching controller.
 *
 * <p>The acceptance criterion is that the same query meets its latency target at ten records a
 * second and at a million, so the two tests that matter are the two ends of that: a quiet stream
 * must not sit waiting for a batch to fill, and a busy one must be allowed to build batches large
 * enough to amortise the work.
 *
 * <p>Everything else here tests section 18.1's four rules, which are the reason to trust a
 * controller at all. Auto-tuning that cannot be bounded, reversed or pinned is worse than none.
 */
class BatchingControllerTest {

    private static final long TARGET = 250_000L; // 250 microseconds, section 18.2's example

    private static BatchingController controller() {
        return new BatchingController(BatchingLimits.defaults(), TARGET);
    }

    @Test
    void aBusyStreamWithHeadroomGrowsItsBatch() {
        BatchingController controller = controller();
        int initial = controller.batchSize();

        // Comfortably inside the target at a high rate: there is room to trade latency for
        // throughput, which is the whole point of batching.
        BatchingController.Decision decision = controller.observe(TARGET / 5, 1_000_000);

        assertThat(decision.batchSize()).isGreaterThan(initial);
        assertThat(decision.reason()).contains("grown for throughput");
    }

    @Test
    void aQuietStreamFlushesRatherThanWaits() {
        // The latency cliff section 18.2 names. At ten rows a second, a 512-row batch is a
        // fifty-second wait, and no batch size fixes that -- only flushing does.
        BatchingController controller = controller();
        BatchingController.Decision decision = controller.observe(TARGET / 10, 10);

        assertThat(decision.lingerNanos()).isEqualTo(BatchingLimits.defaults().minLingerNanos());
        assertThat(decision.reason()).contains("linger dropped to its floor");
    }

    @Test
    void breachingTheTargetShrinksTheBatchAndTheLinger() {
        BatchingController controller = controller();
        int initial = controller.batchSize();

        BatchingController.Decision decision = controller.observe(TARGET * 2, 1_000_000);

        assertThat(decision.batchSize()).isLessThan(initial);
        assertThat(decision.lingerNanos()).isLessThan(BatchingLimits.defaults().maxLingerNanos());
    }

    @Test
    void aChangeThatMadeThingsWorseIsUndone() {
        // Rule 3, and the one that stops a controller chasing an unreachable target from walking the
        // setting to a limit and staying there.
        BatchingController controller = controller();
        controller.observe(TARGET / 5, 1_000_000); // grows
        int afterGrowth = controller.batchSize();

        BatchingController.Decision worse = controller.observe(TARGET * 3, 1_000_000);
        assertThat(worse.reason()).contains("reverted");
        assertThat(controller.batchSize()).isLessThan(afterGrowth);
        assertThat(controller.reversions()).isEqualTo(1);
    }

    @Test
    void theBatchNeverLeavesItsBounds() {
        // Rule 2. One anomalous measurement must not be able to produce a batch of one, or of a
        // million.
        BatchingController controller = controller();
        BatchingLimits limits = BatchingLimits.defaults();

        for (int i = 0; i < 100; i++) {
            controller.observe(1L, 1_000_000); // absurdly good: grow, grow, grow
        }
        assertThat(controller.batchSize()).isEqualTo(limits.maxBatchSize());

        for (int i = 0; i < 200; i++) {
            controller.observe(TARGET * 100, 1_000_000); // absurdly bad: shrink, shrink, shrink
        }
        assertThat(controller.batchSize()).isEqualTo(limits.minBatchSize());
    }

    @Test
    void oneAdjustmentCannotJumpTheSettingByAnOrderOfMagnitude() {
        BatchingLimits limits = new BatchingLimits(512, 1, 100_000, 256, 1_000L, 200_000L, 1_000.0);
        BatchingController controller = new BatchingController(limits, TARGET);

        controller.observe(1L, 1_000_000);
        assertThat(controller.batchSize())
                .as("the step cap holds even when doubling would be allowed by the maximum")
                .isEqualTo(512 + 256);
    }

    @Test
    void pinningIsPermanentAndObservationsAreStillRecorded() {
        // Rule 4. An operator who pins has made a decision; overriding it later, however good the
        // reason looks to the controller, is how trust in auto-tuning is lost.
        BatchingController controller = controller();
        controller.pin(64);

        for (int i = 0; i < 50; i++) {
            BatchingController.Decision decision = controller.observe(TARGET * 10, 1_000_000);
            assertThat(decision.batchSize()).isEqualTo(64);
            assertThat(decision.reason()).contains("pinned");
        }
        assertThat(controller.observations()).isEqualTo(50);
        assertThat(controller.adjustments())
                .as("a pinned controller adjusts nothing")
                .isZero();
        assertThat(controller.isPinned()).isTrue();
    }

    @Test
    void everyDecisionSaysWhatItSawAndWhatItDid() {
        // Rule 1. A timeline showing that the batch size moved is much less useful than one showing
        // why, and "why" has to be captured at the moment of the decision or it is gone.
        BatchingController controller = controller();

        assertThat(controller.observe(TARGET / 5, 1_000_000).reason()).contains("% of target");
        assertThat(controller.observe(TARGET * 2, 1_000_000).reason()).isNotBlank();
        assertThat(controller.observe((long) (TARGET * 0.75), 1_000_000).reason())
                .contains("within the target band");
    }

    @Test
    void aBatchAtItsMinimumSaysTheCostIsElsewhere() {
        // Shrinking to no effect and reporting an adjustment would send whoever is debugging in the
        // wrong direction for an afternoon.
        BatchingController controller =
                new BatchingController(new BatchingLimits(1, 1, 8192, 2048, 1_000L, 200_000L, 1_000.0), TARGET);

        assertThat(controller.observe(TARGET * 5, 1_000_000).reason()).contains("the cost is elsewhere");
        assertThat(controller.batchSize()).isEqualTo(1);
    }

    @Test
    void limitsThatCannotBeSatisfiedAreRefusedAtConstruction() {
        assertThatThrownBy(() -> new BatchingLimits(512, 1024, 8192, 256, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> new BatchingLimits(512, 1, 8192, 256, 200_000L, 1_000L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inverted");
        assertThatThrownBy(() -> new BatchingController(BatchingLimits.defaults(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameQueryConvergesAtBothEndsOfItsRateRange() {
        // The acceptance criterion, as a simulation: latency is modelled as proportional to batch
        // size at a high rate, and dominated by the wait for a batch to fill at a low one.
        BatchingController fast = controller();
        for (int i = 0; i < 40; i++) {
            long latency = 400L * fast.batchSize(); // 400 ns a row through the pipeline
            fast.observe(latency, 1_000_000);
        }
        assertThat(400L * fast.batchSize())
                .as("at a million rows a second the controller settles inside the target")
                .isLessThanOrEqualTo(TARGET);
        assertThat(fast.batchSize())
                .as("and not by collapsing to a batch of one")
                .isGreaterThan(1);

        BatchingController slow = controller();
        for (int i = 0; i < 40; i++) {
            // At ten rows a second the batch never fills, so latency is the linger.
            slow.observe(slow.lingerNanos(), 10);
        }
        assertThat(slow.lingerNanos())
                .as("a quiet stream ends up flushing at the floor rather than waiting")
                .isEqualTo(BatchingLimits.defaults().minLingerNanos());
    }
}
