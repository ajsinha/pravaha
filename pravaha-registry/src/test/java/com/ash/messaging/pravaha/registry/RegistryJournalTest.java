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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Registrations survive a restart.
 *
 * <p>Before the journal, a registry was entirely in memory. Restart a server and every continuous
 * query a client had registered was gone -- no error, nothing in a log, nothing to look at. The
 * client found out at its next subscribe, as "no such view", which is a long way from the cause.
 *
 * <p>What is written down is the registration and not the state, and the tests are mostly about the
 * consequences of that choice: queries come back empty and refill, drops stay dropped, and a
 * registration is re-authorized rather than trusted.
 */
class RegistryJournalTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("status", Types.string())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal ROB = new Principal("rob", "acme", Set.of("contractor"), Map.of());

    private static Optional<Principal> lookUp(String id) {
        return switch (id) {
            case "dana" -> Optional.of(DANA);
            case "rob" -> Optional.of(ROB);
            default -> Optional.empty();
        };
    }

    private static QueryRegistry registry(Path journalFile, SecurityPolicy policy) {
        return new QueryRegistry(new ViewCatalog(), policy, AuditSink.NONE, TXN)
                .journalTo(new RegistryJournal(journalFile));
    }

    @Test
    void theJournalIsReadableOnlyByItsOwner(@TempDir Path directory) throws Exception {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry registry = registry(journal, SecurityPolicy.PERMISSIVE)) {
            registry.register(
                    "sensitive_view",
                    "SELECT user_id, amount FROM txn WHERE user_id = ?",
                    List.of(0),
                    DANA,
                    BoundParameters.of("4111111111111111"));
        }

        // The file holds query text and the values clients filtered on -- account numbers, customer
        // ids. It used to be created at whatever the umask was, which on most systems is
        // world-readable, while the javadoc instructed the operator to permission it like data. An
        // instruction is not a control.
        assumeTrue(
                journal.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");
        assertThat(Files.getPosixFilePermissions(journal))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void registrationsComeBackAfterARestart(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register("by_user", "SELECT user_id, amount, status FROM txn", List.of(0), DANA);
            first.register("all_txn", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        }

        try (QueryRegistry second = registry(journal, SecurityPolicy.PERMISSIVE)) {
            QueryRegistry.Recovery recovery = second.recover(RegistryJournalTest::lookUp);

            assertThat(recovery.complete()).isTrue();
            assertThat(recovery.recovered()).containsExactly("by_user", "all_txn");
            assertThat(second.names()).contains("by_user", "all_txn");
        }
    }

    @Test
    void whatComesBackIsTheRegistrationAndNotTheState(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register("by_user", "SELECT user_id, amount, status FROM txn", List.of(0), DANA);
        }

        try (QueryRegistry second = registry(journal, SecurityPolicy.PERMISSIVE)) {
            second.recover(RegistryJournalTest::lookUp);

            // A restart costs a warm-up, not an outage. The view exists straight away and fills as
            // data arrives; it does not pretend to hold what it held before. State is large and can
            // be rebuilt from the stream, which is exactly why it is not what gets written down.
            assertThat(second.require("by_user").view().size()).isZero();
            assertThat(second.require("by_user").rowsIn()).isZero();
        }
    }

    @Test
    void aDroppedQueryStaysDropped(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register("temporary", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            first.register("kept", "SELECT user_id, status FROM txn", List.of(0), DANA);
            first.drop("temporary");
        }

        try (QueryRegistry second = registry(journal, SecurityPolicy.PERMISSIVE)) {
            QueryRegistry.Recovery recovery = second.recover(RegistryJournalTest::lookUp);

            // A journal that only recorded registrations would resurrect everything ever registered,
            // and a deployment could never get rid of a query.
            assertThat(recovery.recovered()).containsExactly("kept");
            assertThat(second.find("temporary")).isEmpty();
        }
    }

    @Test
    void retentionAndKeyColumnsSurvive(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register(
                    "short_lived",
                    "SELECT user_id, amount FROM txn",
                    List.of(0),
                    DANA,
                    Retention.ofAge(Duration.ofMinutes(5)));
        }

        try (QueryRegistry second = registry(journal, SecurityPolicy.PERMISSIVE)) {
            second.recover(RegistryJournalTest::lookUp);

            // Recovering a query with the default retention instead of its own would silently change
            // what it holds, and the difference would only show up as memory.
            assertThat(second.require("short_lived").view().retention().maxAge())
                    .isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Test
    void boundParametersSurvive(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register(
                    "large_only",
                    "SELECT user_id, amount FROM txn WHERE amount > ?",
                    List.of(0),
                    DANA,
                    BoundParameters.of(1000L));
        }

        try (QueryRegistry second = registry(journal, SecurityPolicy.PERMISSIVE)) {
            second.recover(RegistryJournalTest::lookUp);

            // The bound value is part of the plan and therefore part of the fingerprint. Recovering
            // without it would be a different query wearing the same name.
            assertThat(second.require("large_only").sql()).contains("?");
            assertThat(second.require("large_only").fingerprint()).isEqualTo(first(journal, "large_only"));
        }
    }

    private static QueryFingerprint first(Path journal, String name) {
        try (QueryRegistry reference =
                new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)) {
            return reference
                    .register(
                            name,
                            "SELECT user_id, amount FROM txn WHERE amount > ?",
                            List.of(0),
                            DANA,
                            BoundParameters.of(1000L))
                    .fingerprint();
        }
    }

    @Test
    void aRevokedOwnerDoesNotGetTheirQueriesBack(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register("robs", "SELECT user_id, amount FROM txn", List.of(0), ROB);
            first.register("danas", "SELECT user_id, status FROM txn", List.of(0), DANA);
        }

        // Rob's contract ended between the two runs.
        SecurityPolicy afterRevocation = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return principal.id().equals("rob") ? AccessDecision.deny("contract ended") : AccessDecision.allow();
            }
        };

        try (QueryRegistry second = registry(journal, afterRevocation)) {
            QueryRegistry.Recovery recovery = second.recover(RegistryJournalTest::lookUp);

            // A registration is not a standing permission. Replaying blindly would make the journal a
            // way to keep an entitlement after it was revoked, by having registered before it was.
            assertThat(recovery.recovered()).containsExactly("danas");
            assertThat(recovery.refused()).hasSize(1);
            assertThat(recovery.refused().get(0).toString()).contains("robs").contains("contract ended");
            // The registration was refused for authorization, replaying it -- PRV-8007, not the
            // generic PRV-7002 a live registration would raise for the same policy denial.
            assertThat(recovery.refused().get(0).code()).contains(RegistryErrors.REPLAY_UNAUTHORIZED);
            assertThat(second.find("robs")).isEmpty();
        }
    }

    @Test
    void anOwnerTheDeploymentNoLongerKnowsIsRefusedRatherThanGuessed(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register(
                    "orphan",
                    "SELECT user_id, amount FROM txn",
                    List.of(0),
                    new Principal("someone-who-left", "acme", Set.of("analyst"), Map.of()));
        }

        try (QueryRegistry second = registry(journal, SecurityPolicy.PERMISSIVE)) {
            QueryRegistry.Recovery recovery = second.recover(RegistryJournalTest::lookUp);

            // Recovering it as nobody, or as an administrator, would run a query under an authority
            // it was never granted.
            assertThat(recovery.recovered()).isEmpty();
            assertThat(recovery.refused().get(0).toString()).contains("is not a principal this deployment knows");
            assertThat(recovery.refused().get(0).code()).contains(RegistryErrors.REPLAY_UNAUTHORIZED);
        }
    }

    @Test
    void oneUnrecoverableQueryDoesNotStopTheOthers(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register("good", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        }
        // A query against a stream this deployment no longer has.
        new RegistryJournal(journal)
                .recordRegistration(
                        "gone", "SELECT a FROM removed_stream", List.of(0), "dana", Retention.DEFAULT, List.of());

        try (QueryRegistry second = registry(journal, SecurityPolicy.PERMISSIVE)) {
            QueryRegistry.Recovery recovery = second.recover(RegistryJournalTest::lookUp);

            // Losing thirty-nine queries because the fortieth names a removed stream would turn a
            // small configuration problem into an outage.
            assertThat(recovery.recovered()).containsExactly("good");
            assertThat(recovery.refused()).hasSize(1);
            assertThat(recovery.complete()).isFalse();
        }
    }

    @Test
    void aHalfWrittenLastRecordIsIgnoredAndTheRestIsKept(@TempDir Path directory) throws Exception {
        Path journal = directory.resolve("registry.journal");

        try (QueryRegistry first = registry(journal, SecurityPolicy.PERMISSIVE)) {
            first.register("one_view", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            first.register("two", "SELECT user_id, status FROM txn", List.of(0), DANA);
        }
        // A crash during an append leaves a truncated tail. That is the expected shape of a crash,
        // not corruption, and everything before it is intact.
        byte[] whole = Files.readAllBytes(journal);
        Files.write(journal, java.util.Arrays.copyOf(whole, whole.length - 12));

        List<RegistryJournal.Entry> replayed = new RegistryJournal(journal).replay();

        assertThat(replayed).hasSize(1);
        assertThat(replayed.get(0).name()).isEqualTo("one_view");
    }

    @Test
    void aRecordThisVersionCannotUnderstandIsRefusedRatherThanSkipped(@TempDir Path directory) throws Exception {
        Path journal = directory.resolve("registry.journal");
        new RegistryJournal(journal)
                .recordRegistration(
                        "one_view", "SELECT user_id FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
        // A record of a kind this version does not know.
        byte[] payload = com.ash.messaging.pravaha.api.wire.ControlWire.encode(List.of("X", "something-new"));
        java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
        framed.write(java.nio.ByteBuffer.allocate(4).putInt(payload.length).array());
        framed.write(payload);
        Files.write(journal, framed.toByteArray(), java.nio.file.StandardOpenOption.APPEND);

        // Skipping it would drop whatever it said, and the consequence -- a view a client expects
        // and cannot find -- would surface at subscribe time with no connection to the cause.
        assertThatThrownBy(() -> new RegistryJournal(journal).replay())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8005")
                .hasMessageContaining("this version does not understand");
    }

    @Test
    void compactionKeepsWhatIsLiveAndForgetsTheRest(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");
        RegistryJournal writer = new RegistryJournal(journal);
        for (int i = 0; i < 50; i++) {
            writer.recordRegistration(
                    "q" + i, "SELECT user_id FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
            if (i % 2 == 0) {
                writer.recordDrop("q" + i);
            }
        }
        long before = journal.toFile().length();

        writer.compact(writer.replay());

        // A journal that only ever grows becomes the thing it was meant to protect against: a file
        // too large to replay at the moment you most need to.
        assertThat(new RegistryJournal(journal).replay()).hasSize(25);
        assertThat(journal.toFile().length()).isLessThan(before);
    }

    @Test
    void aRegistrationThatCannotBeWrittenDownIsRefused(@TempDir Path directory) throws Exception {
        // A regular file where the journal's parent directory would have to be, so it cannot be made.
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "not a directory");

        try (QueryRegistry registry = registry(blocked.resolve("registry.journal"), SecurityPolicy.PERMISSIVE)) {

            // Acknowledging a registration that will not survive a restart tells the client something
            // untrue, and nothing will correct it later.
            assertThatThrownBy(() -> registry.register("doomed", "SELECT user_id, amount FROM txn", List.of(0), DANA))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8006");
        }
    }
}
