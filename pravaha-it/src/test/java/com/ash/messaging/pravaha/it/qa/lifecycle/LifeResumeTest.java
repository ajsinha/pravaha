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

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LIFE-054..061 -- resume: acceptance restarts, and the pause's gap is never replayed. */
@Tag("qa")
class LifeResumeTest extends LifecycleTestSupport {

    private static final String WINDOWED =
            "SELECT usr, SUM(amount) AS total FROM txn " + "GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), usr";

    @Test
    void life054_resumeRestartsAcceptanceAndTheFeed() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("ctrl", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        registry.pause("v1");
        long v1Before = registry.require("v1").rowsIn();
        long ctrlBefore = registry.require("ctrl").rowsIn();

        registry.resume("v1");
        push("v1", 1, "ann", 100, 1);
        push("ctrl", 1, "ann", 100, 1);

        assertThat(registry.require("v1").rowsIn()).isGreaterThan(v1Before);
        assertThat(registry.require("v1").state()).isEqualTo(QueryState.RUNNING);
        assertThat(registry.require("ctrl").rowsIn())
                .as("V-control, over the same interval")
                .isGreaterThan(ctrlBefore);
    }

    @Test
    void life055_resumeDoesNotReplayTheGap() {
        registry.register("v_win", WINDOWED, List.of(0), Principal.ANONYMOUS);
        // Distinct SQL from v_win (a harmless predicate) so the two do not share a fingerprint.
        registry.register(
                "ctrl_win",
                "SELECT usr, SUM(amount) AS total FROM txn WHERE amount > -1 "
                        + "GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), usr",
                List.of(0),
                Principal.ANONYMOUS);

        long tenSeconds = 10_000_000_000L;
        // Window 1: [0,10)
        for (String q : List.of("v_win", "ctrl_win")) {
            push(q, 1, "ann", 10, 1, 1_000_000_000L);
        }
        // Window 2 [10,20): v_win is paused for all of it, ctrl_win is not.
        registry.pause("v_win");
        push("ctrl_win", 2, "ann", 20, 1, 11_000_000_000L);
        push("v_win", 2, "ann", 20, 1, 11_000_000_000L); // dropped
        registry.resume("v_win");
        // Window 3 [20,30): both queries close window 2 with a row here.
        push("v_win", 3, "ann", 1, 1, 21_000_000_000L);
        push("ctrl_win", 3, "ann", 1, 1, 21_000_000_000L);
        advanceTo("v_win", 21_000_000_000L);
        advanceTo("ctrl_win", 21_000_000_000L);

        // Window 2's only row was dropped by the pause, so window 2 is empty -- and an empty window
        // publishes nothing (cq021's rule), rather than a row of zero. The view is keyed on usr
        // alone, so "ann" still shows whatever the last publish left there: window 1's 10, not a
        // fresh 0 and not window 3's 1. That, not an explicit zero, is what "the gap is not
        // replayed" looks like from the read side.
        assertThat(rows("SELECT usr, total FROM v_win"))
                .as("window 1's total stands: window 2 never published, so nothing overwrote it")
                .hasSize(1)
                .first()
                .satisfies(row -> assertThat(row[1]).isEqualTo(10L));
        assertThat(rows("SELECT usr, total FROM ctrl_win"))
                .as("V-control: the never-paused query's window 2 is complete")
                .hasSize(1)
                .first()
                .satisfies(row -> assertThat(row[1]).isEqualTo(20L));
    }

    @Test
    void life056_resumingARunningQueryIsAcceptedSilently() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.resume("v1");
        registry.resume("v1");
        assertThat(registry.require("v1").state()).isEqualTo(QueryState.RUNNING);
        push("v1", 1, "ann", 100, 1);
        assertThat(registry.require("v1").rowsIn())
                .as("the feed was not disturbed by the redundant resumes")
                .isEqualTo(1);
    }

    @Test
    void life057_resumingAFailedQueryIsRefusedWithTheDocumentedReason() {
        driveToFailedByMinRetraction("v_min");
        assertThat(registry.require("v_min").state()).isEqualTo(QueryState.FAILED);

        assertThatThrownBy(() -> registry.resume("v_min"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is FAILED and cannot be resumed")
                .hasMessageContaining("hides the cause");
        assertThat(registry.require("v_min").state()).isEqualTo(QueryState.FAILED);
    }

    @Test
    void life058_resumingADroppedQueryReportsNoSuchQuery() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.drop("v1");
        assertThatThrownBy(() -> registry.resume("v1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no query named 'v1'");
        // As with LIFE-047: the ILLEGAL_TRANSITION branch for a dropped query needs a held
        // RegisteredQuery reference and a call to its package-private resume(), which this module
        // cannot reach. BLOCKED, recorded in the QA log.
    }

    @Test
    void life061_pauseAndResumeOfOneNamePausesTheSharedComputation() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        registry.register("ctrl", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        assertThat(registry.require("a").fingerprint())
                .isEqualTo(registry.require("b").fingerprint());

        registry.pause("a");

        assertThat(registry.require("a").state())
                .as("one state object behind two names: pausing 'a' pauses 'b' too")
                .isEqualTo(QueryState.PAUSED);
        assertThat(registry.require("b").state()).isEqualTo(QueryState.PAUSED);

        long bBefore = registry.require("b").rowsIn();
        push("b", 1, "ann", 100, 1);
        push("ctrl", 1, "ann", 100, 1);
        assertThat(registry.require("b").rowsIn())
                .as("b's view stops advancing too, because it is the same computation as a")
                .isEqualTo(bBefore);
        assertThat(registry.require("ctrl").rowsIn())
                .as("V-control: an unshared query keeps advancing over the same interval")
                .isGreaterThan(0);
    }
}
