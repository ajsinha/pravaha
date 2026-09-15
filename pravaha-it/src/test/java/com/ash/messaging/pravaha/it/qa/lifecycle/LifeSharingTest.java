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

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIFE-083..100 -- sharing by fingerprint: the case file's own headline structural finding.
 *
 * <p>Key columns and retention are not in {@code QueryFingerprint} (LIFE-035), and the shared path
 * ({@code register}'s early-return branch) never calls {@code start(...)}, which is the only place
 * that validates or applies either. So a second registrant of the same plan gets a computation keyed
 * and retained however the *first* registrant asked, silently. LIFE-089..091 are that finding,
 * isolated.
 */
@Tag("qa")
class LifeSharingTest extends LifecycleTestSupport {

    @Test
    void life083_twoIdenticalQueriesAreOneComputationWithTwoNames() {
        long before = computations(registry);
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        assertThat(awaitComputations(registry, before + 1, Duration.ofSeconds(2)))
                .isEqualTo(before + 1);

        registry.register("b", S1, List.of(0), Principal.ANONYMOUS);
        assertThat(computations(registry))
                .as("the second name costs zero additional threads")
                .isEqualTo(before + 1);
        assertThat(registry.require("a").fingerprint())
                .isEqualTo(registry.require("b").fingerprint());
        assertThat(registry.names()).containsExactly("a", "b");
        assertThat(registry.size()).isEqualTo(1);

        registry.register("c", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        assertThat(computations(registry))
                .as("a genuinely different query raises the thread count again, proving the counter moves")
                .isEqualTo(before + 2);
    }

    @Test
    void life084_differentTextSamePlanIsTheSameComputation() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        registry.register("c", "SELECT\n  usr,\n     amount\nFROM   txn", List.of(0), Principal.ANONYMOUS);

        assertThat(registry.require("b").fingerprint())
                .isEqualTo(registry.require("a").fingerprint());
        assertThat(registry.require("c").fingerprint())
                .isEqualTo(registry.require("a").fingerprint());

        // CONCEPTS.md claims "reordered AND operands all land on the same computation". Measured: they
        // do not -- the planner keeps predicates in the order the SQL text gives them, so this is a
        // documentation defect rather than an engine one (recorded in FINDINGS, not treated as a
        // product FAIL, since sharing being *conservative* -- two computations instead of one -- is
        // never a correctness problem, only a missed efficiency).
        registry.register("d", "SELECT usr FROM txn WHERE id > 0 AND amount > 5", List.of(0), Principal.ANONYMOUS);
        registry.register("e", "SELECT usr FROM txn WHERE amount > 5 AND id > 0", List.of(0), Principal.ANONYMOUS);
        assertThat(registry.require("d").fingerprint())
                .as("CONCEPTS.md's claim does not hold: reordered AND operands get different fingerprints")
                .isNotEqualTo(registry.require("e").fingerprint());

        registry.register("f", "SELECT usr FROM txn WHERE amount > 6", List.of(0), Principal.ANONYMOUS);
        assertThat(registry.require("f").fingerprint())
                .as("a genuinely different predicate must still get a different fingerprint")
                .isNotEqualTo(registry.require("a").fingerprint());
    }

    @Test
    void life085_rowCountsDoNotDoubleWhenAQueryIsShared() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        for (long id = 1; id <= 20; id++) {
            push("a", id, "u" + (((id - 1) % 4) + 1), id, 1);
        }
        registry.register("b", S1, List.of(0), Principal.ANONYMOUS);

        assertThat(registry.require("a").rowsIn())
                .as("one counter, read twice: 20, not 40")
                .isEqualTo(20);
        assertThat(registry.require("b").rowsIn()).isEqualTo(20);
        assertThat(rows("SELECT usr, amount FROM a")).as("4 distinct users").hasSize(4);
        assertThat(rows("SELECT usr, amount FROM b")).hasSize(4);
    }

    @Test
    void life086_eachSharedNameIsIndependentlyReadable() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        push("a", 1, "ann", 100, 1);
        push("a", 2, "bob", 5, 1);

        List<String> viaA = readSorted("SELECT usr, amount FROM a");
        assertThat(viaA).as("V-before: the first read must work").isNotEmpty();
        assertThat(readSorted("SELECT usr, amount FROM b")).isEqualTo(viaA);
        assertThat(readSorted("SELECT usr, amount FROM b WHERE amount > 10"))
                .as("b's schema is resolvable by column name, not only by *")
                .containsExactly("ann=100");
    }

    @Test
    void life087_theSecondNamesSchemaIsRenamedToIt() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);

        java.util.Map<String, ?> schemas = views.schemas();
        assertThat(schemas).containsKeys("a", "b");
        assertThat(schemas.get("a")).isNotNull();
        assertThat(schemas.get("b")).isNotNull();
    }

    @Test
    void life088_droppingOneSharedNameLeavesTheOtherCorrectUnderLoad() throws Exception {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);

        // Feeds through whichever name is currently valid: "a" until it is dropped, "b" afterwards --
        // the point being that the *computation* (reachable through either) keeps accepting rows
        // across the drop, not that a specific name does. Pushing through a dropped name is refused
        // by design (LIFE-062); that is not the race this case is about.
        java.util.concurrent.atomic.AtomicBoolean useB = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean keepGoing = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread feeder = new Thread(() -> {
            long id = 0;
            while (keepGoing.get()) {
                try {
                    push(useB.get() ? "b" : "a", ++id, "u" + (id % 10), id, 1);
                } catch (RuntimeException refused) {
                    // The drop lands while this thread is mid-push through "a", and pushing through
                    // a dropped name is refused by design. Unhandled, that killed the feeder: it
                    // never reached the line that switches to "b", so nothing fed the surviving
                    // name and the case failed reporting that the computation had stopped -- which
                    // was true of the test's own thread, not of the engine.
                    if (!keepGoing.get()) {
                        return;
                    }
                }
            }
        });
        feeder.start();
        Thread.sleep(50);
        long bBefore = registry.require("b").rowsIn();
        registry.drop("a");
        useB.set(true);

        // Waited for, not slept through. A fixed pause asserts that the feeder got a turn inside
        // it, which on a loaded machine it does not: this failed with 258 rows against 258 -- the
        // count had not gone backwards, it had simply not moved yet. A test that needs the machine
        // to be quiet is a test that fails for a reason that has nothing to do with the product.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && registry.require("b").rowsIn() <= bBefore) {
            Thread.sleep(10);
        }
        keepGoing.set(false);
        feeder.join(Duration.ofSeconds(5).toMillis());

        assertThat(registry.require("b").rowsIn())
                .as("V-rows: b kept advancing across the drop, within ten seconds")
                .isGreaterThan(bBefore);
        assertThat(rows("SELECT usr FROM b")).isNotNull();
    }

    @Test
    void life089_keys1And01AreTwoComputationsAndEachCallerGetsTheKeyingItAsked() {
        // I-3, fixed. This is the case that showed the defect producing a *wrong answer* rather than
        // a surprise: kb asked to key on (usr, amount), was handed ka's amount-only view, and read
        // 2 rows where its own keying gives 3. Inverted rather than deleted.
        registry.register("ka", S1, List.of(1), Principal.ANONYMOUS); // keyed on amount
        registry.register("kb", S1, List.of(0, 1), Principal.ANONYMOUS); // asks for the pair, and gets it

        assertThat(registry.require("ka").fingerprint())
                .as("different keying is a different computation")
                .isNotEqualTo(registry.require("kb").fingerprint());
        assertThat(registry.size()).isEqualTo(2);

        push("ka", 1, "u1", 100, 1);
        push("ka", 2, "u2", 100, 1); // same amount as u1: collides under the amount-only key
        push("ka", 3, "u3", 7, 1);

        assertThat(rows("SELECT usr, amount FROM ka"))
                .as("keyed on amount alone, which is what ka asked for: 2 rows")
                .hasSize(2);

        push("kb", 1, "u1", 100, 1);
        push("kb", 2, "u2", 100, 1);
        push("kb", 3, "u3", 7, 1);
        assertThat(rows("SELECT usr, amount FROM kb"))
                .as("kb has its own view now, keyed the way it asked: 3 distinct (usr, amount) pairs, "
                        + "which is the answer it was silently denied")
                .hasSize(3);
    }

    @Test
    void life090_theKeyColumnBoundsCheckAppliesOnEveryPath() {
        // I-3's third consequence. The bounds check lives in start(), and sharing returned before
        // it -- so --keys 99 was refused on a fresh registration and accepted silently on a shared
        // one. With the keys in the fingerprint there is no longer a path that skips it: an
        // out-of-range key cannot match an existing computation, so start() always runs.
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> registry.register("fresh", S1 + " WHERE id > 0", List.of(99), Principal.ANONYMOUS))
                .as("the fresh path always refused this")
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> registry.register("b", S1, List.of(99), Principal.ANONYMOUS))
                .as("and so does what used to be the shared path: this registration was accepted "
                        + "silently, leaving a name pointing at a view keyed nothing like it asked")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key column 99");
    }

    @Test
    void life091_eachRegistrationGetsTheRetentionItAsked() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS, Retention.ofAge(Duration.ofHours(8)));
        registry.register("b", S1, List.of(0), Principal.ANONYMOUS, Retention.ofAge(Duration.ofMinutes(5)));

        assertThat(registry.require("b").view().retention())
                .as("b asked for 5 minutes and gets 5 minutes. It used to receive a's 8 hours silently, "
                        + "because the shared path returns before the retention is applied (I-3)")
                .isEqualTo(Retention.ofAge(Duration.ofMinutes(5)));
        assertThat(registry.require("b").fingerprint())
                .as("a different retention is a different computation, which is what makes that possible")
                .isNotEqualTo(registry.require("a").fingerprint());

        registry.register(
                "c", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS, Retention.ofAge(Duration.ofMinutes(5)));
        assertThat(registry.require("c").view().retention())
                .as("the setter works on a fresh registration -- c is not sharing with a/b")
                .isEqualTo(Retention.ofAge(Duration.ofMinutes(5)));
    }

    @Test
    void life092_twoPrincipalsWithDifferentRowFiltersDoNotShare() {
        SecurityPolicy filtering = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return principal.id().equals("p1")
                        ? AccessDecision.allowWithRowFilter("usr = 'u1'")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        QueryRegistry policed = new QueryRegistry(views, filtering, AuditSink.NONE, TXN);
        try {
            Principal p1 = Principal.of("p1");
            Principal p2 = Principal.of("p2");
            policed.register("as_p1", S1, List.of(0), p1);
            policed.register("as_p2", S1, List.of(0), p2);
            policed.register("as_p2_again", S1, List.of(0), p2);

            assertThat(policed.require("as_p1").fingerprint())
                    .as("a restricted and an unrestricted principal must not land on one computation")
                    .isNotEqualTo(policed.require("as_p2").fingerprint());
            assertThat(policed.require("as_p2_again").fingerprint())
                    .as("but sharing still works when the filters match (here: no filter, twice)")
                    .isEqualTo(policed.require("as_p2").fingerprint());
            assertThat(policed.size()).isEqualTo(2);
        } finally {
            policed.close();
        }
    }

    @Test
    void life093_aFailedComputationIsNotSharedWith() {
        driveToFailedByMinRetraction("a");
        String fingerprintBefore = registry.require("a").fingerprint().toString();
        assertThat(registry.require("a").state()).as("V-before: FAILED").isEqualTo(QueryState.FAILED);

        registry.register(
                "b",
                "SELECT usr, MIN(amount) AS lo FROM txn GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), usr",
                List.of(0),
                Principal.ANONYMOUS);

        assertThat(registry.require("b").state())
                .as("b takes the fresh path: RUNNING")
                .isEqualTo(QueryState.RUNNING);
        assertThat(registry.require("b").fingerprint().toString())
                .as("the same fingerprint string, now naming two different computations")
                .isEqualTo(fingerprintBefore);
        assertThat(registry.require("a").state())
                .as("a is still FAILED under the same fingerprint b just registered")
                .isEqualTo(QueryState.FAILED);
    }

    @Test
    void life094_aDroppedComputationIsNotSharedWith() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        registry.drop("a"); // computation survives, held by b
        registry.drop("b"); // released: fingerprint entry removed

        registry.register("c", S1, List.of(0), Principal.ANONYMOUS);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.require("c").rowsIn())
                .as("a fresh computation, restarting from 0")
                .isEqualTo(0);
    }

    @Test
    void life095_tenRegistrationsOfOneQueryCostOneLane() {
        long before = computations(registry);
        for (int i = 1; i <= 10; i++) {
            registry.register("n" + i, S1, List.of(0), Principal.ANONYMOUS);
        }
        assertThat(awaitComputations(registry, before + 1, Duration.ofSeconds(2)))
                .as("ten names, one lane")
                .isEqualTo(before + 1);

        push("n1", 1, "ann", 100, 1);
        for (int i = 1; i <= 10; i++) {
            assertThat(registry.require("n" + i).rowsIn()).isEqualTo(1);
            assertThat(rows("SELECT usr FROM n" + i)).hasSize(1);
        }

        for (int i = 1; i <= 10; i++) {
            registry.register("m" + i, S1 + " WHERE id > " + (-i - 1), List.of(0), Principal.ANONYMOUS);
        }
        assertThat(awaitComputations(registry, before + 11, Duration.ofSeconds(2)))
                .as("ten genuinely different queries: threads rise to eleven")
                .isEqualTo(before + 11);
    }

    @Test
    void life096_tenSharesDroppedOneAtATimeReleaseOnTheTenth() {
        long before = computations(registry);
        for (int i = 1; i <= 10; i++) {
            registry.register("n" + i, S1, List.of(0), Principal.ANONYMOUS);
        }
        push("n1", 1, "ann", 100, 1);
        assertThat(awaitComputations(registry, before + 1, Duration.ofSeconds(2)))
                .isEqualTo(before + 1);

        for (int i = 1; i <= 9; i++) {
            registry.drop("n" + i);
            assertThat(computations(registry))
                    .as("the lane survives drops 1..9")
                    .isEqualTo(before + 1);
            String survivor = "n" + (i + 1);
            assertThat(rows("SELECT usr FROM " + survivor))
                    .as("a surviving name still reads after drop %d", i)
                    .hasSize(1);
        }
        registry.drop("n10");
        assertThat(awaitComputations(registry, before, Duration.ofSeconds(2)))
                .as("released on the tenth drop")
                .isEqualTo(before);
    }

    @Test
    void life097_theSharedSecondNameIsJournalledAndRecovers(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        java.nio.file.Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first = new QueryRegistry(views, TXN)
                .journalTo(new com.ash.messaging.pravaha.registry.RegistryJournal(journalFile));
        first.register("a", S1, List.of(0), Principal.ANONYMOUS);
        first.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        push(first, "a", 1, "ann", 100, 1, 1);
        assertThat(readSorted("SELECT usr, amount FROM b"))
                .as("V-before: both readable")
                .isNotEmpty();
        first.close();

        ViewCatalog restartedViews = new ViewCatalog();
        QueryRegistry restarted = new QueryRegistry(restartedViews, TXN)
                .journalTo(new com.ash.messaging.pravaha.registry.RegistryJournal(journalFile));
        try {
            var recovery = restarted.recover(id -> java.util.Optional.of(Principal.of(id)));
            assertThat(recovery.recovered())
                    .as("Recovery[2 recovered, 0 refused]")
                    .containsExactlyInAnyOrder("a", "b");
            assertThat(recovery.refused()).isEmpty();
            assertThat(restarted.size()).isEqualTo(1);
            assertThat(restarted.names()).containsExactlyInAnyOrder("a", "b");
            assertThat(readSorted(restartedViews, "SELECT usr, amount FROM a")).isNotNull();
            assertThat(readSorted(restartedViews, "SELECT usr, amount FROM b")).isNotNull();
        } finally {
            restarted.close();
        }
    }

    @Test
    void life098_aJournalAppendFailureOnTheSharedPathUnwindsTheName(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry journalled = new QueryRegistry(views, TXN)
                .journalTo(new com.ash.messaging.pravaha.registry.RegistryJournal(journalFile));
        try {
            journalled.register("a", S1, List.of(0), Principal.ANONYMOUS);
            // This case has its own registry; the shared field is not the one under test here.
            long before = computations(journalled);

            java.nio.file.Path brokenJournal = tempDir.resolve("broken-is-a-dir.log");
            java.nio.file.Files.createDirectory(brokenJournal);
            journalled.journalTo(new com.ash.messaging.pravaha.registry.RegistryJournal(brokenJournal));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> journalled.register("b", S1, List.of(0), Principal.ANONYMOUS))
                    .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                    .hasMessageContaining("8006");
            assertThat(journalled.names()).as("only a is listed").containsExactly("a");

            // Critical: dropping a must release the lane. If b's name were still in
            // RegisteredQuery.names, removeName would return false and the computation would be
            // pinned open forever.
            journalled.journalTo(new com.ash.messaging.pravaha.registry.RegistryJournal(journalFile));
            journalled.drop("a");
            assertThat(awaitComputations(journalled, before - 1, Duration.ofSeconds(2)))
                    .isEqualTo(before - 1);
        } finally {
            journalled.close();
        }
    }

    @Test
    void life099_aFreshPathJournalFailureLeavesNothingRunning(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        java.nio.file.Path brokenJournal = tempDir.resolve("broken-is-a-dir.log");
        try {
            java.nio.file.Files.createDirectory(brokenJournal);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        QueryRegistry journalled = new QueryRegistry(views, TXN)
                .journalTo(new com.ash.messaging.pravaha.registry.RegistryJournal(brokenJournal));
        try {
            long before = computations(registry);
            for (int i = 0; i < 5; i++) {
                String name = "v" + i;
                String sql = S1 + " WHERE id > " + (-i - 1);
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> journalled.register(name, sql, List.of(0), Principal.ANONYMOUS))
                        .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                        .hasMessageContaining("8006");
            }
            assertThat(journalled.names()).isEmpty();
            assertThat(awaitComputations(registry, before, Duration.ofSeconds(2)))
                    .as("no lane thread survives any of the five failed registrations")
                    .isEqualTo(before);
        } finally {
            journalled.close();
        }
    }

    @Test
    void life100_sharingIsVisibleByComparingFingerprintsAcrossTheListing() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        registry.register("c", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);

        assertThat(registry.require("a").fingerprint())
                .as("a and b visibly equal")
                .isEqualTo(registry.require("b").fingerprint());
        assertThat(registry.require("c").fingerprint())
                .as("V-control: c's differing fingerprint is what makes the equality above meaningful")
                .isNotEqualTo(registry.require("a").fingerprint());
        // No shipped surface reports QueryRegistry.size() (the computation count) as distinct from
        // names().size() (the name count) -- confirmed by reading, not re-asserted here since it is
        // an absence rather than a behaviour to exercise.
    }
}
