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
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIFE-117..125 -- restart at each lifecycle point. A restart is simulated by closing a registry and
 * replaying its {@link RegistryJournal} into a fresh one: {@code QueryRegistry.recover(...)} is
 * exactly what a real process restart calls, and the journal has no way to tell the difference.
 */
@Tag("qa")
class LifeRestartTest {

    private static final StreamSchema TXN = LifecycleTestSupport.TXN;
    private static final String S1 = LifecycleTestSupport.S1;

    @Test
    void life117_restartWithARunningQuery(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        first.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        first.close();

        ViewCatalog restartedViews = new ViewCatalog();
        QueryRegistry restarted = new QueryRegistry(restartedViews, TXN).journalTo(new RegistryJournal(journalFile));
        try {
            var recovery = restarted.recover(id -> Optional.of(Principal.of(id)));
            assertThat(recovery.recovered()).containsExactly("v1");
            assertThat(recovery.refused()).isEmpty();
            assertThat(restarted.require("v1").state()).isEqualTo(QueryState.RUNNING);
            assertThat(restarted.require("v1").rowsIn())
                    .as("ROWS IN restarts at 0")
                    .isEqualTo(0);
            assertThat(restartedViews.find("v1"))
                    .as("the immediate read finds the view, empty")
                    .isPresent();
        } finally {
            restarted.close();
        }
    }

    @Test
    void life118_restartWithAPausedQueryComesBackRunning(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        first.register("v1", S1, List.of(0), Principal.ANONYMOUS);
        first.pause("v1");
        assertThat(first.require("v1").state()).as("V-before: PAUSED").isEqualTo(QueryState.PAUSED);
        first.close();

        QueryRegistry restarted = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        try {
            restarted.recover(id -> Optional.of(Principal.of(id)));
            assertThat(restarted.require("v1").state())
                    .as("PAUSED is not journalled -- the entry carries name/SQL/keys/owner/retention only, "
                            + "so a deliberately stopped query starts consuming again after a restart")
                    .isEqualTo(QueryState.RUNNING);
        } finally {
            restarted.close();
        }
    }

    @Test
    void life119_restartWithADroppedQuery(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        first.register("a", S1, List.of(0), Principal.ANONYMOUS);
        first.register("b", S1 + " WHERE id > 0", List.of(0), Principal.ANONYMOUS);
        assertThat(first.names()).as("V-before: both listed").containsExactlyInAnyOrder("a", "b");
        first.drop("a");
        first.close();

        QueryRegistry restarted = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        try {
            var recovery = restarted.recover(id -> Optional.of(Principal.of(id)));
            assertThat(recovery.recovered()).containsExactly("b");
            assertThat(restarted.names()).containsExactly("b");
        } finally {
            restarted.close();
        }
    }

    @Test
    void life120_restartWithAFailedQueryComesBackRunningThenFailsAgain(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("journal.log");
        ViewCatalog views = new ViewCatalog();
        QueryRegistry first = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile));
        String minQuery =
                "SELECT usr, MIN(amount) AS lo FROM txn GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), usr";
        first.register("v_min", minQuery, List.of(0), Principal.ANONYMOUS);
        LifecycleTestSupport support = new LifecycleTestSupport() {};
        support.views = views;
        support.registry = first;
        support.arena = new com.ash.messaging.pravaha.common.arena.RowArena(
                com.ash.messaging.pravaha.common.memory.MemoryAccess.best(), 1 << 20, 8);
        support.push("v_min", 1, "ann", 100, 1, 100_000_000L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> support.push("v_min", 1, "ann", 100, -1, 100_000_000L))
                .isInstanceOf(RuntimeException.class);
        assertThat(first.require("v_min").state()).as("V-before: FAILED").isEqualTo(QueryState.FAILED);
        first.close();
        support.arena.close();

        ViewCatalog restartedViews = new ViewCatalog();
        QueryRegistry restarted = new QueryRegistry(restartedViews, TXN).journalTo(new RegistryJournal(journalFile));
        try {
            restarted.recover(id -> Optional.of(Principal.of(id)));
            assertThat(restarted.require("v_min").state())
                    .as("a failed query IS journalled (it registered successfully) and replay re-registers "
                            + "it fresh -- RUNNING, with no memory of the earlier failure")
                    .isEqualTo(QueryState.RUNNING);
        } finally {
            restarted.close();
        }
    }

    @Test
    void life121_restartWithTwoNamesSharingOneComputation(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        first.register("a", S1, List.of(0), Principal.ANONYMOUS);
        first.register("b", LifecycleTestSupport.S1_PRIME, List.of(0), Principal.ANONYMOUS);
        assertThat(first.require("a").fingerprint())
                .as("V-before: sharing")
                .isEqualTo(first.require("b").fingerprint());
        first.close();

        QueryRegistry restarted = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        try {
            var recovery = restarted.recover(id -> Optional.of(Principal.of(id)));
            assertThat(recovery.recovered()).containsExactlyInAnyOrder("a", "b");
            assertThat(restarted.size())
                    .as("one computation, two names, after restart")
                    .isEqualTo(1);
            assertThat(restarted.require("a").fingerprint())
                    .isEqualTo(restarted.require("b").fingerprint());
        } finally {
            restarted.close();
        }
    }

    @Test
    void life122_restartWhenTheOwnerIsNoLongerAKnownPrincipal(@TempDir Path tempDir) {
        Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        first.register("v1", S1, List.of(0), Principal.of("alice"));
        first.register("v2", S1 + " WHERE id > 0", List.of(0), Principal.of("bob"));
        first.close();

        QueryRegistry restarted = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        try {
            var recovery =
                    restarted.recover(id -> "alice".equals(id) ? Optional.empty() : Optional.of(Principal.of(id)));
            assertThat(recovery.recovered())
                    .as("bob's query recovers, proving the replay ran")
                    .containsExactly("v2");
            assertThat(recovery.refused()).hasSize(1);
            assertThat(recovery.refused().get(0).toString())
                    .contains("v1")
                    .contains("alice")
                    .contains("not a principal this deployment knows");
            assertThat(recovery.refused().get(0).code()).contains(RegistryErrors.REPLAY_UNAUTHORIZED);
            assertThat(restarted.names()).doesNotContain("v1");
        } finally {
            restarted.close();
        }
    }

    @Test
    void life123_restartWhenTheOwnerHasLostReadAccessToTheSource(@TempDir Path tempDir) {
        StreamSchema payroll = StreamSchema.builder("payroll")
                .field("id", Types.int64())
                .field("salary", Types.int64())
                .build();
        Path journalFile = tempDir.resolve("journal.log");

        SecurityPolicy open = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        QueryRegistry first = new QueryRegistry(
                        new ViewCatalog(), open, com.ash.messaging.pravaha.security.AuditSink.NONE, TXN, payroll)
                .journalTo(new RegistryJournal(journalFile));
        first.register("v_pay", "SELECT id, salary FROM payroll", List.of(0), Principal.of("hr"));
        first.register("v_txn", S1, List.of(0), Principal.of("hr"));
        first.close();

        SecurityPolicy closed = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return principal.id().equals("hr") && view.equals("payroll")
                        ? AccessDecision.deny("hr's access to payroll was revoked")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        QueryRegistry restarted = new QueryRegistry(
                        new ViewCatalog(), closed, com.ash.messaging.pravaha.security.AuditSink.NONE, TXN, payroll)
                .journalTo(new RegistryJournal(journalFile));
        try {
            var recovery = restarted.recover(id -> Optional.of(Principal.of(id)));
            assertThat(recovery.recovered())
                    .as("the still-permitted txn query recovers")
                    .containsExactly("v_txn");
            assertThat(recovery.refused()).hasSize(1);
            assertThat(recovery.refused().get(0).toString()).contains("v_pay").contains("payroll");
            assertThat(recovery.refused().get(0).code()).contains(RegistryErrors.REPLAY_UNAUTHORIZED);
            assertThat(restarted.names()).doesNotContain("v_pay");
        } finally {
            restarted.close();
        }
    }

    @Test
    void life124_oneUnrecoverableEntryDoesNotStopTheRest(@TempDir Path tempDir) {
        StreamSchema removedLater =
                StreamSchema.builder("temp_stream").field("id", Types.int64()).build();
        Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first =
                new QueryRegistry(new ViewCatalog(), TXN, removedLater).journalTo(new RegistryJournal(journalFile));
        for (int i = 0; i < 5; i++) {
            first.register("good" + i, S1 + " WHERE id > " + (-i - 1), List.of(0), Principal.of("alice"));
        }
        first.register("orphan_stream", "SELECT id FROM temp_stream", List.of(0), Principal.of("alice"));
        first.register("orphan_owner", S1 + " WHERE amount > 999", List.of(0), Principal.of("charlie"));
        first.close();

        // Restart without temp_stream declared, and without "charlie" as a known principal.
        QueryRegistry restarted = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        try {
            var recovery =
                    restarted.recover(id -> "charlie".equals(id) ? Optional.empty() : Optional.of(Principal.of(id)));
            assertThat(recovery.recovered())
                    .as("all 7 good entries recover despite the two bad ones sitting between them in the journal")
                    .containsExactlyInAnyOrder("good0", "good1", "good2", "good3", "good4");
            assertThat(recovery.refused()).hasSize(2);
            for (int i = 0; i < 5; i++) {
                assertThat(restarted.names()).contains("good" + i);
            }
        } finally {
            restarted.close();
        }
    }

    @Test
    void life125_restartMidBurstLosesNoRegistrationAndDuplicatesNone(@TempDir Path tempDir) throws Exception {
        Path journalFile = tempDir.resolve("journal.log");
        QueryRegistry first = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        for (int i = 0; i < 20; i++) {
            first.register("v" + i, S1 + " WHERE id > " + (-i - 1), List.of(0), Principal.of("alice"));
        }
        first.close();
        long sizeAfterBurst = Files.size(journalFile);

        long sizeAfterRestart1 = restartAndMeasure(journalFile, 20);
        long sizeAfterRestart2 = restartAndMeasure(journalFile, 20);
        assertThat(sizeAfterRestart1)
                .as("replay must not append: size unchanged across restart 1")
                .isEqualTo(sizeAfterBurst);
        assertThat(sizeAfterRestart2).as("...and restart 2").isEqualTo(sizeAfterBurst);

        QueryRegistry third = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        try {
            third.recover(id -> Optional.of(Principal.of(id)));
            third.register("one_more", S1 + " WHERE amount > 12345", List.of(0), Principal.of("alice"));
            long sizeAfterOneMore = Files.size(journalFile);
            assertThat(sizeAfterOneMore)
                    .as("V-control: a real new registration DOES grow the journal, proving the size "
                            + "measurement is sensitive enough to have caught unwanted growth above")
                    .isGreaterThan(sizeAfterRestart2);
        } finally {
            third.close();
        }
    }

    private long restartAndMeasure(Path journalFile, int expectedNames) throws Exception {
        QueryRegistry restarted = new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile));
        try {
            var recovery = restarted.recover(id -> Optional.of(Principal.of(id)));
            assertThat(recovery.recovered()).hasSize(expectedNames);
            return Files.size(journalFile);
        } finally {
            restarted.close();
        }
    }
}
