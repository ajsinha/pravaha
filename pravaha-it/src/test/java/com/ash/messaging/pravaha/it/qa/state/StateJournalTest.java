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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-065..076 -- the registry journal: append and replay.
 *
 * <p>Small facts (name, SQL, key columns, owner, retention, bound parameters), not state; H-JRN
 * throughout: a {@code RegistryJournal} over a file, plus a second registry over the same file for
 * the replay half.
 */
@Timeout(120)
class StateJournalTest extends StateTestSupport {

    private static boolean straceUsable() {
        try {
            Process p =
                    new ProcessBuilder("strace", "-V").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    @Test
    void state065_aRegistrationIsOnDiskFlushedBeforeRegisterReturns(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        assertThat(Files.exists(journalFile)).isFalse();

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        }

        assertThat(Files.exists(journalFile)).isTrue();
        byte[] bytes = Files.readAllBytes(journalFile);
        assertThat(bytes.length).isGreaterThan(0);
        // bytes 4..7: ControlWire.MAGIC; byte 8: VERSION=1.
        assertThat(bytes[4]).isEqualTo((byte) 0x50);
        assertThat(bytes[5]).isEqualTo((byte) 0x52);
        assertThat(bytes[6]).isEqualTo((byte) 0x56);
        assertThat(bytes[7]).isEqualTo((byte) 0x48);
        assertThat(bytes[8]).isEqualTo((byte) 1);

        Assumptions.assumeTrue(straceUsable(), "requires a usable strace (ptrace) in this environment");
        Path journalFile2 = dir.resolve("registry2.journal");
        Path traceFile = dir.resolve("trace.log");
        List<String> command = new ArrayList<>();
        command.add("strace");
        command.add("-f");
        command.add("-e");
        command.add("trace=fsync,fdatasync");
        command.add("-o");
        command.add(traceFile.toString());
        command.add(javaBinary());
        command.add("-cp");
        command.add(classpath());
        command.add(StraceJournalRunner.class.getName());
        command.add(journalFile2.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> out = readAll(process);
        assertThat(process.waitFor()).as(String.join("\n", out)).isZero();
        String trace = Files.readString(traceFile);
        long fsyncs = trace.lines().filter(l -> l.contains("fsync")).count();
        assertThat(fsyncs).as("exactly one fsync, from channel.force(true)").isEqualTo(1);
    }

    private static List<String> readAll(Process process) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    void state066_theRecordsFieldsAreExactlyWhatTheFormatSays(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        String sql = "SELECT user_id, amount FROM txn";
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            // registry.retainingFor(...) named by the case does not exist on current QueryRegistry;
            // the explicit-retention register(...) overload is the equivalent, direct substitute.
            registry.register("q", sql, List.of(0, 1), DANA, Retention.ofAge(Duration.ofHours(2)));
        }

        byte[] all = Files.readAllBytes(journalFile);
        int length = ((all[0] & 0xFF) << 24) | ((all[1] & 0xFF) << 16) | ((all[2] & 0xFF) << 8) | (all[3] & 0xFF);
        byte[] payload = Arrays.copyOfRange(all, 4, 4 + length);
        List<String> fields = ControlWire.decode(payload);

        assertThat(fields).containsExactly("R", "q", sql, "0,1", "dana", "7200000");

        int sqlBytes = utf8(sql).length;
        long expectedLength = 9L + (4 + 1) + (4 + 1) + (4 + sqlBytes) + (4 + 3) + (4 + 4) + (4 + 7);
        assertThat((long) length).as("sql utf8 length=" + sqlBytes).isEqualTo(expectedLength);
        assertThat((long) all.length).isEqualTo(4 + expectedLength);
    }

    @Test
    void state067_replayReturnsTheRegistrationVerbatim(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        String sql = "SELECT user_id, amount FROM txn";
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("q", sql, List.of(0, 1), DANA, Retention.ofAge(Duration.ofHours(2)));
        }

        List<RegistryJournal.Entry> entries = new RegistryJournal(journalFile).replay();
        assertThat(entries).hasSize(1);
        RegistryJournal.Entry e = entries.get(0);
        assertThat(e.name()).isEqualTo("q");
        assertThat(e.sql()).isEqualTo(sql);
        assertThat(e.keyColumns()).containsExactly(0, 1);
        assertThat(e.owner()).isEqualTo("dana");
        assertThat(e.retention().maxAge()).isEqualTo(Duration.ofHours(2));
        assertThat(e.retention().maxAge()).isEqualTo(Duration.ofHours(2));
        assertThat(e.parameters()).isEmpty();
    }

    @SuppressWarnings("NullAway") // nulls passed on purpose
    @Test
    void state068_everyRetentionEncodingRoundTrips(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        journal.recordRegistration(
                "a", "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
        journal.recordRegistration(
                "b", "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.forever(), List.of());
        journal.recordRegistration(
                "c",
                "SELECT user_id, amount FROM txn",
                List.of(0),
                "dana",
                Retention.ofAge(Duration.ofMillis(1)),
                List.of());
        // (d): a hand-written record with an empty retention field.
        journal.recordRegistration("d", "SELECT user_id, amount FROM txn", List.of(0), "dana", null, List.of());

        List<RegistryJournal.Entry> entries = journal.replay();
        assertThat(entries).hasSize(4);
        assertThat(byName(entries, "a").retention().maxAge()).isEqualTo(Duration.ofHours(24));
        assertThat(byName(entries, "b").retention().isForever()).isTrue();
        assertThat(byName(entries, "c").retention().maxAge()).isEqualTo(Duration.ofMillis(1));
        assertThat(byName(entries, "d").retention())
                .as("a null/empty retention field decodes as the 24h default, silently")
                .isEqualTo(Retention.DEFAULT);
    }

    private static RegistryJournal.Entry byName(List<RegistryJournal.Entry> entries, String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void state069_everyBoundParameterTypeTagRoundTrips() {
        record Case(Object in, String encoded) {}
        @SuppressWarnings("NullAway") // nulls passed on purpose
        List<Case> cases = List.of(
                new Case(null, "n:"),
                new Case(new byte[] {1, 2, -1}, "b:AQL/"),
                new Case(42L, "i:42"),
                new Case(42, "i:42"),
                new Case((short) 42, "i:42"),
                new Case(3.5d, "d:3.5"),
                new Case(3.5f, "d:3.5"),
                new Case(true, "z:true"),
                new Case(false, "z:false"),
                new Case("hello", "s:hello"),
                new Case("", "s:"),
                new Case("i:12", "s:i:12"),
                new Case("x:y", "s:x:y"),
                new Case("s".repeat(65536), "s:" + "s".repeat(65536)));

        for (Case c : cases) {
            String encoded = RegistryJournal.encodeParameter(c.in());
            assertThat(encoded).as("encoding " + describe(c.in())).isEqualTo(c.encoded());
            Object decoded = RegistryJournal.decodeParameter(encoded);
            if (c.in() instanceof byte[] bytes) {
                assertThat((byte[]) decoded).isEqualTo(bytes);
            } else if (c.in() instanceof Long || c.in() instanceof Integer || c.in() instanceof Short) {
                assertThat(decoded).isEqualTo(((Number) c.in()).longValue());
                assertThat(decoded).isInstanceOf(Long.class);
            } else if (c.in() instanceof Double || c.in() instanceof Float) {
                assertThat(decoded).isEqualTo(((Number) c.in()).doubleValue());
                assertThat(decoded).isInstanceOf(Double.class);
            } else {
                assertThat(decoded).isEqualTo(c.in());
            }
        }
    }

    private static String describe(Object o) {
        return o instanceof byte[] b ? Arrays.toString(b) : String.valueOf(o);
    }

    @Test
    void state070_narrowIntegersWidenOnReplayWhichCanSplitASharedComputation(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        String sql = "SELECT user_id, amount FROM txn WHERE amount > ?";
        ViewCatalog views = new ViewCatalog();
        boolean beforeShared;
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("a", sql, List.of(0), DANA, BoundParameters.of(Integer.valueOf(50)));
            registry.register("b", sql, List.of(0), DANA, BoundParameters.of(Long.valueOf(50)));
            beforeShared = registry.size() == 1;
        }

        ViewCatalog views2 = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(views2, TXN).journalTo(new RegistryJournal(journalFile))) {
            QueryRegistry.Recovery r =
                    second.recover(id -> Optional.of(new com.ash.messaging.pravaha.security.Principal(
                            id, "public", java.util.Set.of("analyst"), java.util.Map.of())));
            assertThat(r.recovered()).containsExactlyInAnyOrder("a", "b");
            assertThat(second.names()).containsExactly("a", "b");
            boolean afterShared = second.size() == 1;
            // Record whichever way it goes; the point is whether it changed.
            assertThat(second.find("a")).isPresent();
            assertThat(second.find("b")).isPresent();
            assertThat(second.find("a").get() == second.find("b").get()).isEqualTo(afterShared);
            // Integer/Long both bind the same numeric value 50, so if the pre-restart fingerprint
            // shared (an Integer(50) and a Long(50) hashing identically), the post-restart one -- both
            // now Long after decodeParameter widens -- must share too, and cannot have gone the other
            // way.
            if (beforeShared) {
                assertThat(afterShared)
                        .as("widening cannot turn a shared computation into two")
                        .isTrue();
            }
        }
    }

    @Test
    void state071_manyRegistrationsReplayInRegistrationOrder(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        for (int i = 0; i < 50; i++) {
            journal.recordRegistration(
                    String.format("q%02d", i),
                    "SELECT user_id, amount FROM txn WHERE amount > " + i,
                    List.of(0),
                    "dana",
                    Retention.DEFAULT,
                    List.of());
        }
        List<String> names =
                journal.replay().stream().map(RegistryJournal.Entry::name).toList();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            expected.add(String.format("q%02d", i));
        }
        assertThat(names).containsExactlyElementsOf(expected);

        // Reversed: registration order q49..q00 must replay q49..q00, not lexical order.
        Path journalFile2 = dir.resolve("registry2.journal");
        RegistryJournal journal2 = new RegistryJournal(journalFile2);
        for (int i = 49; i >= 0; i--) {
            journal2.recordRegistration(
                    String.format("q%02d", i),
                    "SELECT user_id, amount FROM txn WHERE amount > " + i,
                    List.of(0),
                    "dana",
                    Retention.DEFAULT,
                    List.of());
        }
        List<String> names2 =
                journal2.replay().stream().map(RegistryJournal.Entry::name).toList();
        List<String> expected2 = new ArrayList<>(expected);
        java.util.Collections.reverse(expected2);
        assertThat(names2).containsExactlyElementsOf(expected2);
    }

    @Test
    void state072_aRegistrationThatCannotStartIsNotJournalled(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            assertThat(Files.exists(journalFile)).isFalse();

            assertThatThrownBy(() -> registry.register("a", "SELECT * FROM nosuch", List.of(0), DANA))
                    .isNotNull();
            assertThatThrownBy(() -> registry.register("b", "SELEKT 1", List.of(0), DANA))
                    .isNotNull();
            registry.register("dup", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            assertThatThrownBy(() -> registry.register("dup", "SELECT user_id FROM txn", List.of(0), DANA))
                    .isNotNull();

            assertThat(new RegistryJournal(journalFile)
                            .replay().stream().map(RegistryJournal.Entry::name).toList())
                    .as("only the one valid registration reached the journal")
                    .containsExactly("dup");
        }
    }

    @Test
    void state073_anUnwritableJournalFailsTheRegistrationRatherThanAcknowledgingIt(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "requires POSIX permissions");
        // As STATE-044/046 found for checkpoints (FINDINGS.md ST-3): RegistryJournal.append also
        // opens with SensitiveFiles.createOwnerOnly(file), which unconditionally narrows the
        // journal's own directory to rwx------ on every append -- so chmod'ing that directory
        // read-only is silently self-healed before the write is attempted. Blocking the *parent* of
        // that directory (so it cannot even be traversed to) is what genuinely fails the append.
        Path lockedParent = dir.resolve("locked-parent");
        Path lockedDir = lockedParent.resolve("journal-dir");
        Files.createDirectories(lockedDir);
        Path journalFile = lockedDir.resolve("registry.journal");
        Files.setPosixFilePermissions(
                lockedParent, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            assertThatThrownBy(() -> registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA))
                    .hasMessageContaining("cannot append to the registry journal at")
                    .hasMessageContaining("refused now rather than acknowledged and forgotten");
            // The append happens after byName.put; record what that means rather than assuming it.
            boolean nameLeaked = registry.names().contains("q");
            boolean findsIt = registry.find("q").isPresent();
            assertThat(nameLeaked).isEqualTo(findsIt);
            if (nameLeaked) {
                // A finding worth a comment, not a failure: the client got a failure for a
                // registration the node is, in fact, serving.
                System.err.println("STATE-073: registry.names() contains 'q' despite the journal append throwing -- "
                        + "the client was told it failed while the node is serving it");
            }
        } finally {
            Files.setPosixFilePermissions(
                    lockedParent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void state074_aNameRegisteredDroppedAndRegisteredAgainAppearsOnceWithTheLatestDefinition(@TempDir Path dir) {
        Path journalFile = dir.resolve("registry.journal");
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("a", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            registry.register("b", "SELECT user_id, amount FROM txn WHERE amount > 1", List.of(0), DANA);
            registry.drop("a");
            registry.register("a", "SELECT user_id, amount FROM txn WHERE amount > 2", List.of(0), DANA);
        }

        List<RegistryJournal.Entry> entries = new RegistryJournal(journalFile).replay();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(RegistryJournal.Entry::name).toList())
                .as("re-registering moves the name to the tail, contradicting the javadoc's own claim")
                .containsExactly("b", "a");
        assertThat(byName(entries, "a").sql()).contains("amount > 2");
    }

    @Test
    void state075_anAbsentJournalFileReplaysAsEmptyNotAsAnError(@TempDir Path dir) {
        Path journalFile = dir.resolve("does-not-exist.journal");
        assertThat(new RegistryJournal(journalFile).replay()).isEmpty();

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            QueryRegistry.Recovery r = registry.recover(id -> Optional.of(DANA));
            assertThat(r.recovered()).isEmpty();
            assertThat(r.refused()).isEmpty();
            assertThat(r.complete()).isTrue();
        }

        Path missingParent = dir.resolve("noparent/does-not-exist.journal");
        assertThat(new RegistryJournal(missingParent).replay()).isEmpty();
    }

    @Test
    void state076_replayDoesNotModifyTheJournalAndIsRepeatable(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        for (int i = 0; i < 5; i++) {
            journal.recordRegistration(
                    "q" + i, "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
        }
        journal.recordDrop("q1");
        journal.recordDrop("q3");

        long sizeBefore = Files.size(journalFile);
        String hashBefore = sha256(journalFile);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry second = new QueryRegistry(views, TXN).journalTo(journal)) {
            // journalTo before recover suspends the journal during replay (registerWithoutJournalling);
            // recover is what replay+re-register goes through in the product.
            second.recover(id -> Optional.of(DANA));
        }

        assertThat(Files.size(journalFile)).isEqualTo(sizeBefore);
        assertThat(sha256(journalFile)).isEqualTo(hashBefore);

        List<RegistryJournal.Entry> first = new RegistryJournal(journalFile).replay();
        List<RegistryJournal.Entry> secondReplay = new RegistryJournal(journalFile).replay();
        assertThat(first).isEqualTo(secondReplay);

        // A third cycle: recover again, then replay again -- hash still unchanged.
        ViewCatalog views2 = new ViewCatalog();
        try (QueryRegistry third = new QueryRegistry(views2, TXN).journalTo(new RegistryJournal(journalFile))) {
            third.recover(id -> Optional.of(DANA));
        }
        assertThat(sha256(journalFile)).isEqualTo(hashBefore);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
