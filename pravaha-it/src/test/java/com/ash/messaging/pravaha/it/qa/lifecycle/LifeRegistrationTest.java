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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIFE-001..006 -- registration, the happy path.
 *
 * <p>The base case every other LIFE test is measured against: a registration that runs and is
 * readable under its own name, with the object's own bookkeeping (fingerprint, key columns, the
 * listing, the journal) checked rather than assumed.
 */
@Tag("qa")
class LifeRegistrationTest extends LifecycleTestSupport {

    @Test
    void life001_aValidRegistrationRunsAndIsReadableUnderItsOwnName() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);

        // Five users, four rows each: 20 rows in, and rowsIn() is a counter of what was accepted,
        // not the view's size, so it stays 20 even though the view collapses to 5 keys.
        for (long id = 1; id <= 20; id++) {
            push("v1", id, "u" + (((id - 1) % 5) + 1), id * 10, 1);
        }

        assertThat(registry.require("v1").rowsIn()).as("ROWS IN, exactly 20").isEqualTo(20);
        assertThat(readSorted("SELECT usr, amount FROM v1"))
                .as("the read must return rows, or the count above proves nothing")
                .isNotEmpty();
    }

    @Test
    void life002_theRegistrationsOwnNameIsTheNameInAFromClause() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);

        assertThat(readSorted("SELECT usr, amount FROM v1"))
                .as("the registered name resolves and answers")
                .containsExactly("ann=100");
        assertThatThrownByReading("SELECT usr, amount FROM txn_projected")
                .as("a name nobody registered must not resolve to anything")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void life003_aRegistrationsKeyColumnsAreTheOutputOrdinals() {
        // Five rows, distinct SQL per registration (LIFE-035: key columns are not in the
        // fingerprint, so identical SQL with different --keys would share one computation instead
        // of exercising three separate keyings).
        registry.register("v_k0", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        registry.register("v_k1", S1 + " WHERE id > -1", List.of(1), Principal.ANONYMOUS);
        registry.register("v_k01", S1 + " WHERE id > -2", List.of(0, 1), Principal.ANONYMOUS);

        assertThat(java.util.Set.of(
                        registry.require("v_k0").fingerprint(),
                        registry.require("v_k1").fingerprint(),
                        registry.require("v_k01").fingerprint()))
                .as("V-distinct: three different fingerprints, or the three keyings never took effect")
                .hasSize(3);

        long[][] fixture = {{1, 0, 10}, {2, 0, 20}, {3, 1, 10}, {4, 1, 30}, {5, 2, 10}};
        String[] usr = {"u1", "u1", "u2", "u2", "u3"};
        for (int i = 0; i < fixture.length; i++) {
            long id = fixture[i][0];
            push("v_k0", id, usr[i], fixture[i][2], 1);
            push("v_k1", id, usr[i], fixture[i][2], 1);
            push("v_k01", id, usr[i], fixture[i][2], 1);
        }

        // usr: u1,u1,u2,u2,u3 -> 3 distinct.
        assertThat(rows("SELECT usr, amount FROM v_k0"))
                .as("keyed on usr: one row per distinct user")
                .hasSize(3);
        // amount: 10,20,10,30,10 -> 3 distinct (10,20,30).
        assertThat(rows("SELECT usr, amount FROM v_k1"))
                .as("keyed on amount: one row per distinct amount")
                .hasSize(3);
        // (usr,amount) pairs: (u1,10)(u1,20)(u2,10)(u2,30)(u3,10) -> all 5 distinct.
        assertThat(rows("SELECT usr, amount FROM v_k01"))
                .as("keyed on both: one row per distinct pair")
                .hasSize(5);
    }

    @Test
    void life004_aWindowedAggregateRegistersAndItsViewFillsAsWindowsClose() {
        String s2 =
                "SELECT usr, SUM(amount) AS total FROM txn " + "GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), usr";
        registry.register("v_win", s2, List.of(0), Principal.ANONYMOUS);

        long tenSeconds = 10_000_000_000L;
        push("v_win", 1, "ann", 100, 1, 1_000_000_000L);
        push("v_win", 2, "ann", 50, 1, 2_000_000_000L);
        push("v_win", 3, "bob", 900, 1, 15_000_000_000L);

        assertThat(readSorted("SELECT usr, total FROM v_win"))
                .as("the first ten-second window has not closed yet: nothing published")
                .isEmpty();

        advanceTo("v_win", tenSeconds + 1);
        assertThat(readSorted("SELECT usr, total FROM v_win"))
                .as("ann's 100 + 50, published because a later row's time closed her window")
                .containsExactly("ann=150");
    }

    @Test
    void life005_pravahaQueriesReportsStateFingerprintAndRowsForEveryName() {
        // The registry's own surface is names()/queries()/rowsIn() -- what `pravaha queries`
        // wraps over Flight (fact 2: lifecycle is Flight-only, and Flight's LIST action is this
        // same insertion-ordered map).
        registry.register("a", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1 + " WHERE id > 1", List.of(0), Principal.ANONYMOUS);
        registry.register("c", S1 + " WHERE id > 2", List.of(0), Principal.ANONYMOUS);

        assertThat(registry.names()).as("registration order is preserved").containsExactly("a", "b", "c");
        assertThat(java.util.Set.of(
                        registry.require("a").fingerprint(),
                        registry.require("b").fingerprint(),
                        registry.require("c").fingerprint()))
                .as("V-distinct: three computations, not one aliased three times")
                .hasSize(3);

        // A second read must report the same order -- the listing is not reshuffled between calls.
        assertThat(registry.names()).containsExactly("a", "b", "c");
    }

    @Test
    void life006_aRegistrationAcknowledgedIsARegistrationJournalled(@TempDir Path tempDir) throws Exception {
        Path journalFile = tempDir.resolve("qa-life-journal.log");
        RegistryJournal journal = new RegistryJournal(journalFile);
        QueryRegistry journalled = new QueryRegistry(views, TXN).journalTo(journal);
        try {
            assertThat(Files.exists(journalFile) ? Files.readString(journalFile) : "")
                    .as("V-before: nothing journalled for v_j yet")
                    .doesNotContain("v_j");

            journalled.register("v_j", S1, List.of(0), Principal.ANONYMOUS);

            String contents = Files.readString(journalFile);
            assertThat(contents)
                    .as("the journal names the registration before the call to register() returns")
                    .contains("v_j")
                    .contains(S1);
        } finally {
            journalled.close();
        }
    }

    private org.assertj.core.api.ThrowableAssert.ThrowingCallable readingCallable(String sql) {
        return () -> new com.ash.messaging.pravaha.serving.ViewQuery(views).execute(sql);
    }

    private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownByReading(String sql) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(readingCallable(sql));
    }
}
