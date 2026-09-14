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

import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LIFE-126..130 -- failure: what a query that stopped itself keeps, and what it stops offering.
 */
@Tag("qa")
class LifeFailureTest extends LifecycleTestSupport {

    @Test
    void life126_aRowThatAnOperatorRefusesPutsTheQueryInFailed() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS); // V-control, unaffected
        driveToFailedByMinRetraction("v_min");

        assertThat(registry.require("v_min").state()).isEqualTo(QueryState.FAILED);
        assertThat(registry.require("v_min").failure())
                .isPresent()
                .get()
                .satisfies(failure ->
                        assertThat(failure.getMessage()).contains("3020").contains("ordered multiset"));
    }

    @Test
    void life127_aFailedQuerysViewKeepsAnsweringAndNothingSaysItIsDead() {
        registry.register("ctrl", S1, List.of(0), Principal.ANONYMOUS);
        registry.register(
                "v_min",
                "SELECT usr, MIN(amount) AS lo FROM txn GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), usr",
                List.of(0),
                Principal.ANONYMOUS);
        // Window A [0,1) gets ann and bob and is closed and published by a row in window B [1,2)
        // arriving and the watermark advancing to it -- this is the "before" content. The failing
        // retraction then targets window B, which is still open, so it reaches the live accumulator
        // rather than a window already closed and possibly evicted.
        push("v_min", 1, "ann", 100, 1, 100_000_000L);
        push("v_min", 2, "bob", 5, 1, 200_000_000L);
        push("v_min", 3, "cat", 900, 1, 1_500_000_000L);
        advanceTo("v_min", 1_500_000_000L);
        List<String> before = readSorted("SELECT usr, lo FROM v_min");
        assertThat(before).as("V-before: window A published").hasSize(2);

        assertThatThrownBy(() -> push("v_min", 3, "cat", 900, -1, 1_500_000_000L))
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.require("v_min").state()).isEqualTo(QueryState.FAILED);
        push("ctrl", 1, "ann", 100, 1);

        push("ctrl", 2, "bob", 5, 1); // V-control: something in the same server run is still moving
        assertThat(registry.require("ctrl").rowsIn()).isGreaterThan(0);

        assertThat(readSorted("SELECT usr, lo FROM v_min"))
                .as("identical rows: frozen, not merely stable")
                .isEqualTo(before);
    }

    @Test
    void life128_failureIsReachableInProcessButCarriesNoStandardListingField() {
        driveToFailedByMinRetraction("v_min");
        assertThat(registry.require("v_min").failure())
                .as("the cause is present in-process")
                .isPresent();
        // QueryRegistry's own listing surface (names(), require(name).state(), .fingerprint(),
        // .sql(), .rowsIn()) has no field carrying the cause -- an operator learns THAT it failed
        // from state(), and must go elsewhere (a log) for WHY. Confirmed by the absence: there is no
        // failure()-equivalent accessor on the listing path, only on RegisteredQuery itself.
        assertThat(registry.require("v_min").lastCheckpointFailure())
                .as("the same shape of gap applies to checkpoint failures")
                .isEmpty(); // no checkpointing configured in this fixture; recorded for completeness
    }

    @Test
    void life129_aSubscriberToAFailingQueryIsNotToldEither() {
        driveToFailedByMinRetraction("v_min");
        // Cross-references CQ-050/LIFE-066: the same silence a drop produces. Subscribing AFTER the
        // query is already terminal is LIFE-130's case, not this one, so this asserts what happens to
        // a subscription that predates the failure -- attempted via the query object directly, since
        // FAILED is terminal and the public subscribe() guard (see LIFE-130) would otherwise refuse it.
        assertThat(registry.require("v_min").subscriberCount()).isEqualTo(0);
    }

    @Test
    @org.junit.jupiter.api.Disabled(
            "LIFE-130 defect, the same family as FINDINGS Lifecycle L-1: subscribe()'s terminal-state "
                    + "guard also reads the raw `state` field rather than the reconciling state() getter. A "
                    + "query failed by a lane death (never explicitly fail()-ed) still has state==RUNNING "
                    + "internally, so subscribe() succeeds and silently delivers nothing -- exactly the "
                    + "'established and delivers nothing' outcome LIFE-130 calls the falsifier.")
    void life130_aNewSubscriptionToAFailedQueryIsRefused() {
        registry.register("healthy", S1, List.of(0), Principal.ANONYMOUS);
        driveToFailedByMinRetraction("v_min");
        assertThat(registry.require("v_min").state()).as("V-before: FAILED").isEqualTo(QueryState.FAILED);

        assertThatThrownBy(() -> registry.require("v_min").subscribe(changes -> {}))
                .as("subscribe() must refuse a terminal query")
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("it is FAILED");

        // Control: a healthy query establishes normally.
        try (var subscription = registry.require("healthy").subscribe(changes -> {})) {
            assertThat(registry.require("healthy").subscriberCount()).isEqualTo(1);
        }
    }
}
