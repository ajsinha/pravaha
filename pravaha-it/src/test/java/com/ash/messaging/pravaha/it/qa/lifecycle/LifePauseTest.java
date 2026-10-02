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
package com.ash.messaging.pravaha.it.qa.lifecycle;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LIFE-042..053 -- pause: rows stop being applied, the view freezes at its last frontier, and the
 * query keeps its lane.
 *
 * <p>Every case here carries {@code ctrl}, a second registration over the same stream that is never
 * paused. Round 1's own pause test passed vacuously because its source had already run dry; {@code
 * ctrl} is what tells a genuinely frozen view apart from a server that has nothing left to feed
 * either query.
 */
@Tag("qa")
class LifePauseTest extends LifecycleTestSupport {

    @Test
    void life042_aPausedQueryStopsAcceptingRows() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("ctrl", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        push("ctrl", 1, "ann", 100, 1);

        long v1Before = registry.require("v1").rowsIn();
        long ctrlBefore = registry.require("ctrl").rowsIn();

        registry.pause("v1");
        push("v1", 2, "bob", 200, 1);
        push("ctrl", 2, "bob", 200, 1);

        assertThat(registry.require("v1").rowsIn())
                .as("a paused query applies nothing")
                .isEqualTo(v1Before);
        assertThat(registry.require("ctrl").rowsIn())
                .as("V-control: the unpaused query advanced over the same interval")
                .isGreaterThan(ctrlBefore);
    }

    @Test
    void life043_aPausedQuerysViewKeepsAnsweringAtTheFrontierItReached() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        List<String> before = readSorted("SELECT usr, amount FROM v1");
        assertThat(before).as("V-before: a non-empty read to compare against").isNotEmpty();

        registry.pause("v1");
        assertThat(readSorted("SELECT usr, amount FROM v1")).isEqualTo(before);
        assertThat(readSorted("SELECT usr, amount FROM v1"))
                .as("still identical a moment later")
                .isEqualTo(before);
        assertThat(registry.require("v1").state()).isEqualTo(QueryState.PAUSED);
    }

    @Test
    void life044_aPausedQuerysCommittedFrontierDoesNotAdvance() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("ctrl", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        push("ctrl", 1, "ann", 100, 1);

        long v1Frontier = registry.require("v1").view().committedFrontier();
        long ctrlFrontier = registry.require("ctrl").view().committedFrontier();

        registry.pause("v1");
        for (long t = 2; t <= 11; t++) {
            push("v1", t, "u" + t, t, 1, t); // dropped: accept() refuses while paused
            advanceTo("v1", t);
            push("ctrl", t, "u" + t, t, 1, t);
            advanceTo("ctrl", t);
        }

        assertThat(registry.require("v1").view().committedFrontier())
                .as("the paused view's frontier is frozen")
                .isEqualTo(v1Frontier);
        assertThat(registry.require("ctrl").view().committedFrontier())
                .as("V-control: the running query's frontier moved over the same ten advances")
                .isGreaterThan(ctrlFrontier);
    }

    @Test
    void life045_rowsArrivingDuringAPauseAreLostNotReplayedOnResume() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("ctrl", S1 + " WHERE amount > 0", List.of(0), Principal.ANONYMOUS);

        registry.pause("v1");
        long lostRows = 5;
        for (long i = 1; i <= lostRows; i++) {
            push("v1", i, "u" + i, i, 1); // dropped
            push("ctrl", i, "u" + i, i, 1); // accepted: ctrl was never paused
        }
        registry.resume("v1");

        assertThat(registry.require("v1").rowsIn())
                .as("v1 accepted none of the five rows pushed while paused")
                .isEqualTo(0);
        assertThat(registry.require("ctrl").rowsIn())
                .as("V-control: ctrl accepted all five, giving the shortfall its meaning")
                .isEqualTo(lostRows);
    }

    @Test
    void life046_pausingAnAlreadyPausedQueryIsAcceptedSilently() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        assertThat(registry.require("v1").state()).as("V-before: RUNNING").isEqualTo(QueryState.RUNNING);

        registry.pause("v1");
        registry.pause("v1");
        assertThat(registry.require("v1").state())
                .as("PAUSED is not terminal, so a second pause is a no-op that still succeeds")
                .isEqualTo(QueryState.PAUSED);
    }

    @Test
    void life047_pausingADroppedQueryReportsNoSuchQuery() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.drop("v1");

        assertThatThrownBy(() -> registry.pause("v1"))
                .as("the name is gone before the state check runs -- NO_SUCH_QUERY, not ILLEGAL_TRANSITION")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no query named 'v1'");
        // The ILLEGAL_TRANSITION branch for a dropped query (reached by holding a RegisteredQuery
        // reference across the drop and calling its package-private pause() directly) is not
        // reachable from this module: RegisteredQuery.pause() has no access modifier, and
        // pravaha-it is outside com.ash.messaging.pravaha.registry. BLOCKED, recorded in the QA log.
    }

    @Test
    void life048_aLaneFailureIsVisibleThroughStateBeforeAnyPauseIsAttempted() {
        driveToFailedByMinRetraction("v_min");
        assertThat(registry.require("v_min").state()).as("V-before: FAILED").isEqualTo(QueryState.FAILED);
    }

    @Test
    void life048_pausingAFailedQueryIsRefusedWithIllegalTransition() {
        driveToFailedByMinRetraction("v_min");
        assertThat(registry.require("v_min").state()).isEqualTo(QueryState.FAILED);

        assertThatThrownBy(() -> registry.pause("v_min"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("it is FAILED");
    }

    @Test
    void pausingAPausedQueryAndResumingARunningOneBothSucceed() {
        // Idempotent on purpose. These verbs name the state the caller wants, not a transition they
        // are asserting, so a script that pauses before maintenance does not have to know whether
        // somebody already did. QA recorded this as an illegal transition going unrefused; it is
        // the intended behaviour, and the defect was that nothing said so.
        registry.register("idem", S1, List.of(0), Principal.ANONYMOUS);

        registry.pause("idem");
        registry.pause("idem");
        assertThat(registry.require("idem").state()).isEqualTo(QueryState.PAUSED);

        registry.resume("idem");
        registry.resume("idem");
        assertThat(registry.require("idem").state()).isEqualTo(QueryState.RUNNING);

        // A terminal state is different: the state asked for is not reachable, and it is refused.
        driveToFailedByMinRetraction("v_min");
        assertThatThrownBy(() -> registry.pause("v_min"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("it is FAILED");
    }

    @Test
    void life049_pausingANameThatDoesNotExist() {
        // The "[a]" half of this expectation is withdrawn by STRM-9 -- see LIFE-069 for why the
        // registry no longer enumerates in a refusal.
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        assertThatThrownBy(() -> registry.pause("nope"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no query named 'nope'")
                .hasMessageNotContaining("[a]");
    }

    @Test
    void life051_aNewSubscriptionToAPausedQueryIsAccepted() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.pause("v1");

        java.util.List<Object> changes = new java.util.ArrayList<>();
        try (var _ = registry.require("v1").subscribe(changes::addAll)) {
            assertThat(registry.require("v1").subscriberCount())
                    .as("subscriberCount moves 0 -> 1 on subscribe, so 'no changes' below is not 'no subscriber'")
                    .isEqualTo(1);
            assertThat(changes).as("no changes arrive while paused").isEmpty();
        }
    }

    @Test
    void life052_aPausedQueryStillHoldsItsLane() {
        long before = computations(registry);
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        assertThat(awaitComputations(registry, before + 1, Duration.ofSeconds(2)))
                .isEqualTo(before + 1);

        registry.pause("v1");
        assertThat(computations(registry))
                .as("pause stops work, not the lane thread itself")
                .isEqualTo(before + 1);
    }

    @Test
    void life053_pauseAndResumeFiftyTimesLeaksNothing() {
        long threadsBefore = computations(registry);
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        long id = 0;
        for (int cycle = 0; cycle < 50; cycle++) {
            registry.pause("v1");
            registry.resume("v1");
            push("v1", ++id, "u" + id, id, 1);
        }

        assertThat(registry.require("v1").state()).isEqualTo(QueryState.RUNNING);
        assertThat(registry.require("v1").rowsIn())
                .as("V-rows: the source kept advancing across all 50 cycles")
                .isEqualTo(50);
        assertThat(computations(registry))
                .as("still exactly one lane thread after 50 pause/resume cycles")
                .isEqualTo(threadsBefore + 1);
    }
}
