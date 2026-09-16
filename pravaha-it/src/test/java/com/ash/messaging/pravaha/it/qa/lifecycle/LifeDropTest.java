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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LIFE-062..075 -- drop: the refcount that makes sharing safe, and what releasing costs. */
@Tag("qa")
class LifeDropTest extends LifecycleTestSupport {

    @Test
    void life062_droppingTheSoleNameReleasesTheComputation() {
        long before = computations(registry);
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        assertThat(awaitComputations(registry, before + 1, Duration.ofSeconds(2)))
                .isEqualTo(before + 1);

        registry.drop("v1");

        assertThat(registry.names()).isEmpty();
        assertThatThrownBy(() -> rows("SELECT usr FROM v1")).isInstanceOf(RuntimeException.class);
        assertThat(awaitComputations(registry, before, Duration.ofSeconds(2))).isEqualTo(before);
    }

    @Test
    void life063_aDroppedViewStopsAnsweringImmediately() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("v1", 1, "ann", 100, 1);
        assertThat(rows("SELECT usr, amount FROM v1")).as("V-before: non-empty").hasSize(1);

        registry.drop("v1");
        assertThatThrownBy(() -> rows("SELECT usr FROM v1"))
                .as("no window in which stale rows are served")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void life064_droppingOneOfSeveralSharedNamesLeavesTheOthersCorrect() {
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1_PRIME, List.of(0), Principal.ANONYMOUS);
        push("a", 1, "ann", 100, 1);
        List<String> before = readSorted("SELECT usr, amount FROM b");
        assertThat(before).isNotEmpty();

        registry.drop("a");

        assertThatThrownBy(() -> rows("SELECT usr FROM a")).isInstanceOf(RuntimeException.class);
        assertThat(readSorted("SELECT usr, amount FROM b")).isEqualTo(before);
        assertThat(registry.names()).containsExactly("b");
        assertThat(registry.size()).isEqualTo(1);

        long bBefore = registry.require("b").rowsIn();
        push("b", 2, "bob", 200, 1);
        assertThat(registry.require("b").rowsIn())
                .as("V-rows: b is alive, not merely listed")
                .isGreaterThan(bBefore);
    }

    @Test
    void life066_droppingAQueryWithALiveSubscriberEndsTheStreamWithoutAnError() {
        // Cross-references CQ-050: a subscriber attached in-process is not told the query was
        // dropped. Verified again here from the lifecycle side.
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        List<Object> received = new java.util.ArrayList<>();
        var subscription = registry.require("v1").subscribe(received::addAll);

        registry.drop("v1");

        assertThat(subscription.isClosed())
                .as("CQ-050: dropping the query does not close an in-process subscriber -- it just goes quiet")
                .isFalse();
        assertThat(subscription.failure()).isEmpty();
        subscription.close();
    }

    @Test
    void life067_droppingMidIngestDoesNotCorruptTheShutdownOrdering() throws Exception {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("ctrl", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);

        AtomicBoolean keepGoing = new AtomicBoolean(true);
        AtomicBoolean sawException = new AtomicBoolean(false);
        Thread feeder = new Thread(() -> {
            long id = 0;
            while (keepGoing.get()) {
                try {
                    push("v1", ++id, "u" + id, id, 1);
                } catch (RuntimeException e) {
                    // Expected once the query is dropped mid-flight (require() then fails to find it).
                    sawException.set(true);
                    break;
                }
            }
        });
        feeder.start();
        Thread.sleep(50); // let ingest get going: ROWS IN climbing
        registry.drop("v1");
        keepGoing.set(false);
        feeder.join(Duration.ofSeconds(5).toMillis());

        assertThat(feeder.isAlive())
                .as("the drop must not hang the feeder thread")
                .isFalse();
        assertThat(registry.names()).containsExactly("ctrl");
        push("ctrl", 1, "ann", 100, 1);
        assertThat(registry.require("ctrl").rowsIn())
                .as("an unrelated query is unaffected by a concurrent drop")
                .isGreaterThan(0);
    }

    @Test
    void life068_droppingTwiceReportsNoSuchQueryTheSecondTime() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.drop("v1");
        assertThatThrownBy(() -> registry.drop("v1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no query named 'v1'");
    }

    @Test
    void life069_droppingANameThatNeverExistedDoesNotListWhatIsKnown() {
        // LIFE-069 as authored expected the refusal to name what does exist. Withdrawn by STRM-9:
        // a principal denied read on every view learned the node's catalogue by misspelling one
        // name. QueryRegistry sits below the policy and holds no principal, so it cannot decide who
        // may be told which names, and enumerating unconditionally is the wrong default for the
        // layer that cannot ask. `pravaha queries` and the Flight LIST action still answer it, and
        // both go through policy.mayRead.
        registry.register("a", S1, List.of(0), Principal.ANONYMOUS);
        registry.register("b", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        assertThatThrownBy(() -> registry.drop("nope"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no query named 'nope'")
                .hasMessageNotContaining("[a")
                .hasMessageNotContaining("b]");
    }

    @Test
    void life070_theDropIsJournalledBeforeAnythingIsReleased(@TempDir Path tempDir) throws Exception {
        Path journalFile = tempDir.resolve("journal.log");
        RegistryJournal journal = new RegistryJournal(journalFile);
        QueryRegistry journalled = new QueryRegistry(views, TXN).journalTo(journal);
        try {
            journalled.register("v1", S1, List.of(0), Principal.ANONYMOUS);
            journalled.drop("v1");
            // The journal is a binary framed format, not text -- replay() is the honest way to ask
            // "is the drop recorded", since that is the only operation that reads it back.
            assertThat(new RegistryJournal(journalFile).replay())
                    .as("(a) a normal drop's record suppresses v1 on replay")
                    .extracting(RegistryJournal.Entry::name)
                    .doesNotContain("v1");

            // (b) make the journal unwritable and drop a different, still-live query. A plain
            // chmod does not survive the append path: RegistryJournal.append() calls
            // SensitiveFiles.createOwnerOnly(file) before every write, which re-narrows the file
            // (and its parent directory) to owner-read-write regardless of what a test set them to
            // -- silently undoing a chmod-based sabotage before the write it was meant to block.
            // Pointing the journal at a path that is already a *directory* survives that: narrowing
            // permissions on a directory is harmless, but FileChannel.open(..., WRITE) on a
            // directory path fails unconditionally, on every filesystem.
            journalled.register("v2", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
            Path unwritableJournal = tempDir.resolve("journal-is-a-directory.log");
            Files.createDirectory(unwritableJournal);
            journalled.journalTo(new RegistryJournal(unwritableJournal));

            assertThatThrownBy(() -> journalled.drop("v2"))
                    .as("PRV-8006: the journal append failed, so the drop must not have happened either")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("8006");
            assertThat(journalled.names())
                    .as("v2 is still registered: the throw precedes byName.remove")
                    .contains("v2");
            assertThat(rows("SELECT usr FROM v2")).as("and still answering").isNotNull();
        } finally {
            journalled.close();
        }
    }

    @Test
    void life071_aDropSurvivesARestart(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("journal.log");
        RegistryJournal journal = new RegistryJournal(journalFile);
        QueryRegistry first = new QueryRegistry(views, TXN).journalTo(journal);
        first.register("a", S1, List.of(0), Principal.ANONYMOUS);
        first.register("b", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        first.drop("a");
        first.close();

        ViewCatalog restartedViews = new ViewCatalog();
        QueryRegistry restarted = new QueryRegistry(restartedViews, TXN).journalTo(new RegistryJournal(journalFile));
        try {
            var recovery = restarted.recover(id -> java.util.Optional.of(Principal.of(id)));
            assertThat(recovery.recovered()).containsExactly("b");
            assertThat(recovery.refused()).isEmpty();
            assertThat(restarted.names()).containsExactly("b");
        } finally {
            restarted.close();
        }
    }

    @Test
    void life072_droppingAPausedQueryWorks() {
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        registry.pause("v1");
        assertThat(registry.require("v1").state()).as("V-before: PAUSED").isEqualTo(QueryState.PAUSED);

        registry.drop("v1");
        assertThat(registry.names()).isEmpty();
        assertThatThrownBy(() -> rows("SELECT usr FROM v1")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void life073_droppingAFailedQueryWorksAndReleasesIt() {
        driveToFailedByMinRetraction("v_min");
        assertThat(registry.require("v_min").state()).isEqualTo(QueryState.FAILED);
        // E-13 changed what "before" looks like: a failed query's view refuses rather than answering
        // as though it were live. The subject of this case is DROP, not that refusal -- what it
        // needs to establish is that the view is still *there* before the drop, and after the drop
        // it is gone. Both are refusals now, so they are told apart by their codes: PRV-8004 says
        // "this view exists and its producer died", PRV-4023 says "no such view".
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rows("SELECT usr FROM v_min"))
                .as("V-before: the view is present, and refuses because its query failed")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("PRV-8004");

        registry.drop("v_min");
        assertThat(registry.names()).isEmpty();
        assertThatThrownBy(() -> rows("SELECT usr FROM v_min"))
                .as("FAILED -> DROPPED: the view is gone, which is a different refusal from the one above")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("PRV-4023");
    }

    @Test
    void life074_fiftyRegisterDropCyclesLeakNothing() {
        long threadsBefore = computations(registry);
        for (int cycle = 0; cycle < 50; cycle++) {
            String name = "v_" + cycle;
            registry.register(name, S1 + " WHERE id > " + (-cycle - 1), List.of(0), Principal.ANONYMOUS);
            push(name, 1, "u", 1, 1);
            assertThat(registry.require(name).rowsIn())
                    .as("V-rows: cycle %d built real state before dropping it", cycle)
                    .isGreaterThan(0);
            registry.drop(name);
        }
        assertThat(registry.names()).isEmpty();
        assertThat(awaitComputations(registry, threadsBefore, Duration.ofSeconds(3)))
                .isEqualTo(threadsBefore);
    }

    @Test
    void life075_droppingDoesNotDisturbAnUnrelatedQueryMidWindow() {
        String windowed =
                "SELECT usr, SUM(amount) AS total FROM txn " + "GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), usr";
        registry.register("ctrl_win", windowed, List.of(0), Principal.ANONYMOUS);
        registry.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        push("ctrl_win", 1, "ann", 10, 1, 1_000_000_000L);
        push("ctrl_win", 2, "ann", 5, 1, 2_000_000_000L); // mid-window, not yet closed

        long generationBefore = views.generation();
        registry.drop("v1");
        assertThat(views.generation())
                .as("dropping bumps the catalogue generation by exactly one")
                .isEqualTo(generationBefore + 1);

        push("ctrl_win", 3, "cat", 999, 1, 15_000_000_000L);
        advanceTo("ctrl_win", 15_000_000_000L);
        assertThat(rows("SELECT usr, total FROM ctrl_win"))
                .as("ann's window closes with 10 + 5 = 15, unaffected by the unrelated drop")
                .hasSize(1)
                .first()
                .satisfies(row -> assertThat(row[1]).isEqualTo(15L));
    }
}
