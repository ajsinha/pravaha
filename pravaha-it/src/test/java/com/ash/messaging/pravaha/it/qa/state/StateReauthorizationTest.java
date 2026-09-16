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
package com.ash.messaging.pravaha.it.qa.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-077..084 -- re-authorization, drops, sharing, and ordering.
 *
 * <p>"A registration is not a standing permission." The journal replays through {@code register},
 * which re-checks the policy as it is now, not as it was.
 */
@Timeout(120)
class StateReauthorizationTest extends StateTestSupport {

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void state077_aDroppedNameStaysDroppedAcrossARestart(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("a", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            registry.register("b", "SELECT user_id, amount FROM txn WHERE amount > 1", List.of(0), DANA);
            registry.register("c", "SELECT user_id, amount FROM txn WHERE amount > 2", List.of(0), DANA);
            registry.drop("b");
        }

        ViewCatalog views2 = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(views2, TXN).journalTo(new RegistryJournal(journalFile))) {
            QueryRegistry.Recovery r = second.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).containsExactly("a", "c");
            assertThat(r.refused()).isEmpty();
            assertThat(second.names()).containsExactly("a", "c");
        }

        List<RegistryJournal.Entry> live = new RegistryJournal(journalFile).replay();
        assertThat(live).hasSize(2);
    }

    @Test
    void state078_registerDropRegisterAgainReplaysAsPresent(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("a", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            registry.drop("a");
            registry.register("a", "SELECT user_id, amount FROM txn WHERE amount > 5", List.of(0), DANA);
        }

        ViewCatalog views2 = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(views2, TXN).journalTo(new RegistryJournal(journalFile))) {
            QueryRegistry.Recovery r = second.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).containsExactly("a");
            assertThat(second.names()).containsExactly("a");
        }
    }

    @Test
    void state079_registerDropRegisterDropReplaysAsAbsent(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("a", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            registry.drop("a");
            registry.register("a", "SELECT user_id, amount FROM txn WHERE amount > 5", List.of(0), DANA);
            registry.drop("a");
        }

        List<RegistryJournal.Entry> live = new RegistryJournal(journalFile).replay();
        assertThat(live).isEmpty();

        ViewCatalog views2 = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(views2, TXN).journalTo(new RegistryJournal(journalFile))) {
            QueryRegistry.Recovery r = second.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).isEmpty();
            assertThat(r.refused()).isEmpty();
            assertThat(r.complete()).isTrue();
        }
    }

    @Test
    void state080_aPrincipalWhoHasLostReadAccessDoesNotGetTheQueryBack(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        SecurityPolicy allowsDana = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return "dana".equals(principal.id()) ? AccessDecision.allow() : AccessDecision.deny("not dana");
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(
                        views, allowsDana, com.ash.messaging.pravaha.security.AuditSink.NONE, TXN)
                .journalTo(new RegistryJournal(journalFile))) {
            registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        }

        SecurityPolicy deniesTxn = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.deny("region restriction");
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        ViewCatalog views2 = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(
                        views2, deniesTxn, com.ash.messaging.pravaha.security.AuditSink.NONE, TXN)
                .journalTo(new RegistryJournal(journalFile))) {
            QueryRegistry.Recovery r = second.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).isEmpty();
            assertThat(r.refused()).hasSize(1);
            assertThat(r.refused().get(0).toString()).startsWith("q: ");
            assertThat(r.refused().get(0).toString())
                    .contains("may not register 'q' because it reads 'txn', which they may not read: "
                            + "region restriction");
            // PRV-8007, not the generic PRV-7002 register() itself raised: replay recodes an
            // authorization denial to the code that says specifically "this is a replay refusal".
            assertThat(r.refused().get(0).code()).contains(RegistryErrors.REPLAY_UNAUTHORIZED);
            assertThat(second.names()).doesNotContain("q");
        }

        // Control: the same journal under the permissive policy recovers q.
        ViewCatalog views3 = new ViewCatalog();
        try (QueryRegistry control = new QueryRegistry(views3, TXN).journalTo(new RegistryJournal(journalFile))) {
            QueryRegistry.Recovery r = control.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).containsExactly("q");
        }
    }

    @Test
    void state081_theServersOwnerLookupGivesEveryRecordedIdARoleLessPrincipal(@TempDir Path dir) throws Exception {
        // PravahaNode::principalNamed's exact shape (PravahaNode.java:491-495): any non-blank id
        // becomes a role-less principal in tenant "unknown". Reproduced directly rather than booting
        // a PravahaNode, since the shape under test is this one static mapping function against three
        // different policies.
        java.util.function.Function<String, Optional<Principal>> principalNamed = id -> id == null || id.isBlank()
                ? Optional.empty()
                : Optional.of(new Principal(id, "unknown", Set.of(), Map.of()));

        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        journal.recordRegistration(
                "a", "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
        journal.recordRegistration(
                "b",
                "SELECT user_id, amount FROM txn WHERE amount > 1",
                List.of(0),
                "dana",
                Retention.DEFAULT,
                List.of());
        journal.recordRegistration(
                "c",
                "SELECT user_id, amount FROM txn WHERE amount > 2",
                List.of(0),
                "nosuchuser",
                Retention.DEFAULT,
                List.of());

        // Arm: permissive.
        try (QueryRegistry permissive = new QueryRegistry(new ViewCatalog(), TXN).journalTo(journal)) {
            QueryRegistry.Recovery r = permissive.recover(principalNamed);
            assertThat(r.recovered())
                    .as("permissive: all three, including the unknown owner")
                    .hasSize(3);
        }

        // Arm: authenticated-only (deny anonymous/no-role requirement is irrelevant; here "requires
        // a role" is what distinguishes it).
        SecurityPolicy roleRequired = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return principal.roles().contains("analyst")
                        ? AccessDecision.allow()
                        : AccessDecision.deny(principal.id() + " may not read " + view);
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        try (QueryRegistry roleBased = new QueryRegistry(
                        new ViewCatalog(), roleRequired, com.ash.messaging.pravaha.security.AuditSink.NONE, TXN)
                .journalTo(journal)) {
            QueryRegistry.Recovery r = roleBased.recover(principalNamed);
            assertThat(r.recovered())
                    .as("no query survives: principalNamed's reconstructed principal has no roles")
                    .isEmpty();
            assertThat(r.refused()).hasSize(3);
        }
    }

    @Test
    void state082_anOwnerRecordedAsBlankIsTheOnlyWayToReachTheUnknownOwnerRefusal(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        journal.recordRegistration(
                "q", "SELECT user_id, amount FROM txn", List.of(0), "", Retention.DEFAULT, List.of());

        java.util.function.Function<String, Optional<Principal>> principalNamed = id -> id == null || id.isBlank()
                ? Optional.empty()
                : Optional.of(new Principal(id, "unknown", Set.of(), Map.of()));

        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN).journalTo(journal)) {
            QueryRegistry.Recovery r = registry.recover(principalNamed);
            assertThat(r.recovered()).isEmpty();
            assertThat(r.refused()).hasSize(1);
            assertThat(r.refused().get(0).toString())
                    .isEqualTo("q: its owner '' is not a principal this deployment knows, so there is "
                            + "nobody to authorize it as");
            // PRV-8007 (REGISTRY_REPLAY_UNAUTHORIZED): this path (and STATE-080's) now raise it --
            // both are the same replay-time authorization refusal, only reached a different way.
            // (Grepping for the throw site is ERRC's exhaustive sweep; not repeated here.)
            assertThat(r.refused().get(0).code()).contains(RegistryErrors.REPLAY_UNAUTHORIZED);
        }
    }

    @Test
    void state083_bothNamesOfASharedComputationReplayAndShareAgain(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        Path root = dir.resolve("checkpoints");
        Path root2 = dir.resolve("checkpoints2");
        var cfg = com.ash.messaging.pravaha.common.config.Configuration.builder()
                .set("pravaha.checkpoint.interval", "1h")
                .build();

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T)
                .journalTo(new RegistryJournal(journalFile))
                .checkpointingTo(root, cfg)) {
            registry.register("alpha", WIN_SQL, List.of(0), DANA);
            registry.register("beta", WIN_SQL, List.of(0), DANA);
            assertThat(registry.size()).isEqualTo(1);
        }

        ViewCatalog views2 = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(views2, TXN_T)
                .journalTo(new RegistryJournal(journalFile))
                .checkpointingTo(root2, cfg)) {
            QueryRegistry.Recovery r = second.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).containsExactly("alpha", "beta");
            assertThat(second.size()).isEqualTo(1);
            assertThat(second.names()).containsExactly("alpha", "beta");
            assertThat(second.find("alpha").get() == second.find("beta").get())
                    .as("both names resolve to the same shared computation")
                    .isTrue();

            List<String> rootEntries;
            try (var files = Files.list(root2)) {
                rootEntries = files.map(p -> p.getFileName().toString()).toList();
            }
            assertThat(rootEntries)
                    .as("the checkpoint directory is owned by the first replayed name")
                    .containsExactly("alpha");
        }
    }

    @Test
    void state084_aDropTheClientIsToldFailedMustNotComeBackOnRestartAndOneThatSucceededMustNotSurvive(@TempDir Path dir)
            throws Exception {
        // Arm A: drop of an unregistered name.
        Path journalFileA = dir.resolve("a.journal");
        ViewCatalog viewsA = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(viewsA, TXN).journalTo(new RegistryJournal(journalFileA))) {
            registry.register("x", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            String hashBefore = Files.exists(journalFileA) ? sha256(journalFileA) : "";
            assertThatThrownBy(() -> registry.drop("nosuch"))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("no query named 'nosuch' is registered");
            String hashAfter = sha256(journalFileA);
            assertThat(hashAfter).isEqualTo(hashBefore);
        }

        // Arm B: drop whose journal write fails -- same ST-3 shape, block the journal's parent.
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "requires POSIX permissions");
        Path armBRoot = dir.resolve("armB");
        Files.createDirectories(armBRoot);
        Path journalFileB = armBRoot.resolve("journal-dir").resolve("registry.journal");
        Files.createDirectories(journalFileB.getParent());

        ViewCatalog viewsB = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(viewsB, TXN).journalTo(new RegistryJournal(journalFileB))) {
            registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            Files.setPosixFilePermissions(armBRoot, PosixFilePermissions.fromString("r--------"));
            try {
                assertThatThrownBy(() -> registry.drop("q"))
                        .isInstanceOf(PravahaException.class)
                        .hasMessageContaining("cannot append to the registry journal at");
                assertThat(registry.find("q"))
                        .as("the throw happens before byName.remove")
                        .isPresent();
            } finally {
                Files.setPosixFilePermissions(armBRoot, PosixFilePermissions.fromString("rwx------"));
            }
        }
        ViewCatalog viewsB2 = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(viewsB2, TXN).journalTo(new RegistryJournal(journalFileB))) {
            QueryRegistry.Recovery r = second.recover(id -> Optional.of(DANA));
            assertThat(r.recovered())
                    .as("q recovers: the drop never reached the journal")
                    .containsExactly("q");
        }

        // Arm C: drop whose journal write succeeds and whose release fails -- a lane occupied by an
        // infinite control task cannot stop within its shutdown timeout.
        Path journalFileC = dir.resolve("c.journal");
        ViewCatalog viewsC = new ViewCatalog();
        LaneConfig shortShutdown = LaneConfig.defaults().withShutdownTimeout(Duration.ofMillis(300));
        try (QueryRegistry registry = new QueryRegistry(viewsC, TXN_T)
                .journalTo(new RegistryJournal(journalFileC))
                .executingWith(shortShutdown, MemoryAccess.best())) {
            RegisteredQuery q = registry.register("q", WIN_SQL, List.of(0), DANA);
            QueryExecution execution = executionOf(q);
            // Occupy the lane's control-task queue forever, so close()'s join cannot complete within
            // shutdownTimeout.
            execution.lane(0).submitControlTask(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThatThrownBy(() -> registry.drop("q"))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("did not stop within");

            assertThat(registry.find("q"))
                    .as("the release (byName.remove/views.remove) already ran before close() threw")
                    .isEmpty();
        }

        List<RegistryJournal.Entry> liveC = new RegistryJournal(journalFileC).replay();
        assertThat(liveC)
                .as("the D record was written before the release was attempted; the drop is not undone")
                .isEmpty();
    }

    private static QueryExecution executionOf(RegisteredQuery query) throws Exception {
        java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("execution");
        field.setAccessible(true);
        return (QueryExecution) field.get(query);
    }
}
