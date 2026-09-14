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

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-085..091 -- corruption, truncation, and permissions.
 *
 * <p>H-JRN throughout. Several cases build raw journal bytes by hand with {@link ControlWire#encode}
 * to reach malformed shapes {@code recordRegistration} itself would never write.
 */
@Timeout(120)
class StateCorruptionTest extends StateTestSupport {

    private static byte[] rawRecord(String... fields) {
        byte[] payload = ControlWire.encode(List.of(fields));
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    private static Path journalWithAbc(Path dir) throws Exception {
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
                "dana",
                Retention.DEFAULT,
                List.of());
        return journalFile;
    }

    @Test
    void state085_aHalfWrittenFinalRecordIsToleratedAndEverythingBeforeItSurvives(@TempDir Path dir) throws Exception {
        Path journalFile = journalWithAbc(dir);
        byte[] full = Files.readAllBytes(journalFile);

        // Arm (a): remove 1 byte.
        assertArmAB(journalFile, Arrays.copyOf(full, full.length - 1));
        // Arm (b): remove half of c's payload -- c is the last record; find its start by replaying
        // the first two records' framed lengths.
        int cStart = recordStart(full, 2);
        int cHalf = cStart + (full.length - cStart) / 2;
        assertArmAB(journalFile, Arrays.copyOf(full, cHalf));
        // Arm (c): remove all of c's payload, leaving its 4-byte length.
        assertArmAB(journalFile, Arrays.copyOf(full, cStart + 4));
        // Arm (d): exactly 4 bytes of a fourth, never-written record's length field, nothing after.
        byte[] armD = ByteBuffer.allocate(full.length + 4).put(full).putInt(999).array();
        Files.write(journalFile, armD);
        List<RegistryJournal.Entry> entries = new RegistryJournal(journalFile).replay();
        assertThat(entries.stream().map(RegistryJournal.Entry::name).toList())
                .as("the trailing 4 bytes are not enough to enter the loop")
                .containsExactly("a", "b", "c");
        QueryRegistry.Recovery r = recoverFresh(journalFile);
        assertThat(r.recovered()).containsExactly("a", "b", "c");
        assertThat(r.refused()).isEmpty();
    }

    private static void assertArmAB(Path journalFile, byte[] truncated) throws Exception {
        Files.write(journalFile, truncated);
        List<RegistryJournal.Entry> entries = new RegistryJournal(journalFile).replay();
        assertThat(entries.stream().map(RegistryJournal.Entry::name).toList())
                .as("record c lost, a and b intact, no exception")
                .containsExactly("a", "b");
        QueryRegistry.Recovery r = recoverFresh(journalFile);
        assertThat(r.recovered()).containsExactly("a", "b");
        assertThat(r.refused()).isEmpty();
    }

    private static int recordStart(byte[] all, int recordIndex) {
        ByteBuffer buffer = ByteBuffer.wrap(all);
        int position = 0;
        for (int i = 0; i < recordIndex; i++) {
            int length = buffer.getInt(position);
            position += 4 + length;
        }
        return position;
    }

    private static QueryRegistry.Recovery recoverFresh(Path journalFile) {
        try (QueryRegistry registry =
                new QueryRegistry(new ViewCatalog(), TXN).journalTo(new RegistryJournal(journalFile))) {
            return registry.recover(id -> Optional.of(DANA));
        }
    }

    @Test
    void state086_anUndecodableRecordInTheMiddleRefusesTheWholeJournalWithTheRecordNumber(@TempDir Path dir)
            throws Exception {
        Path journalFile = journalWithAbc(dir);
        byte[] full = Files.readAllBytes(journalFile);
        int record1Start = recordStart(full, 0);
        int record2Start = recordStart(full, 1);
        assertThat(new String(full, record1Start, record2Start - record1Start, java.nio.charset.StandardCharsets.UTF_8))
                .as("record 1's bytes are intact before we touch record 2")
                .contains("a");

        // Corrupt record 2's ControlWire magic (the 4 bytes right after its own length prefix).
        byte[] corrupted = full.clone();
        corrupted[record2Start + 4] = 0;
        corrupted[record2Start + 5] = 0;
        corrupted[record2Start + 6] = 0;
        corrupted[record2Start + 7] = 0;
        Files.write(journalFile, corrupted);

        RegistryJournal journal = new RegistryJournal(journalFile);
        assertThatThrownBy(journal::replay)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("record 2 of the registry journal at")
                .hasMessageContaining("cannot be decoded")
                .hasMessageContaining("replaying past it would silently drop whatever it said");
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN).journalTo(journal)) {
            assertThatThrownBy(() -> registry.recover(id -> Optional.of(DANA)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("record 2");
        }
    }

    @Test
    void state087_aRecordWithAnUnknownKindOrAnRWithTooFewFieldsIsRefusedWithTheCount(@TempDir Path dir)
            throws Exception {
        // (a) R with 3 fields.
        assertRefused(dir.resolve("a.journal"), rawRecord("R", "q", "sql"), "'R' with 3 fields");
        // (b) unknown kind X with 2 fields.
        assertRefused(dir.resolve("b.journal"), rawRecord("X", "q"), "'X' with 2 fields");
        // (c) D with no name -- falls through DROP's fields.size()>=2 guard into the R check.
        assertRefused(dir.resolve("c.journal"), rawRecord("D"), "'D' with 1 fields");
        // (d) empty field list -- silently skipped, no exception.
        Path journalFileD = dir.resolve("d.journal");
        Files.write(journalFileD, rawRecord());
        List<RegistryJournal.Entry> entries = new RegistryJournal(journalFileD).replay();
        assertThat(entries)
                .as("a zero-field record is silently skipped, contradicting the class's own refuse-not-skip rule")
                .isEmpty();
    }

    private static void assertRefused(Path journalFile, byte[] rawBytes, String expectedFragment) throws Exception {
        Files.write(journalFile, rawBytes);
        assertThatThrownBy(() -> new RegistryJournal(journalFile).replay())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("record 1 of the registry journal at")
                .hasMessageContaining(expectedFragment)
                .hasMessageContaining("this version does not understand")
                .hasMessageContaining("a skipped registration is a view a client expects and will not find");
    }

    @Test
    void state088_aJournalThatCannotBeReadAtAllIsAnUncheckedIOExceptionNotPrv8005(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "requires POSIX permissions");
        Assumptions.assumeTrue(
                !"root".equals(System.getProperty("user.name")), "cannot exercise this as root: chmod is ignored");

        Path journalFile = dir.resolve("registry.journal");
        Files.writeString(journalFile, "irrelevant");
        Files.setPosixFilePermissions(journalFile, PosixFilePermissions.fromString("---------"));
        Assumptions.assumeTrue(
                !Files.isReadable(journalFile), "chmod 0000 did not take effect (root-like environment)");

        try {
            assertThatThrownBy(() -> new RegistryJournal(journalFile).replay())
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("cannot read the registry journal at")
                    .hasMessageNotContaining("PRV-")
                    .cause()
                    .isInstanceOf(java.nio.file.AccessDeniedException.class);
        } finally {
            Files.setPosixFilePermissions(journalFile, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void state089_theJournalFileIs0600AndItsParent0700(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "requires POSIX permissions");
        Path newDir = dir.resolve("newdir");
        assertThat(Files.exists(newDir)).isFalse();
        Path journalFile = newDir.resolve("registry.journal");

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).journalTo(new RegistryJournal(journalFile))) {
            registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        }

        assertThat(Files.getPosixFilePermissions(journalFile)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(newDir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void state090_anExistingJournalWithLoosePermissionsIsNarrowedOnTheNextAppend(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "requires POSIX permissions");
        Path journalFile = dir.resolve("registry.journal");
        RegistryJournal journal = new RegistryJournal(journalFile);
        journal.recordRegistration(
                "a", "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());

        Files.setPosixFilePermissions(journalFile, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        journal.recordRegistration(
                "b",
                "SELECT user_id, amount FROM txn WHERE amount > 1",
                List.of(0),
                "dana",
                Retention.DEFAULT,
                List.of());

        assertThat(Files.getPosixFilePermissions(journalFile)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));

        List<RegistryJournal.Entry> entries = journal.replay();
        assertThat(entries.stream().map(RegistryJournal.Entry::name).toList())
                .as("narrowing did not truncate: both entries survive")
                .containsExactly("a", "b");

        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void state091_aCompactingLeftoverIsNotMistakenForTheJournalAndIsNotCleanedUpEither(@TempDir Path dir)
            throws Exception {
        Path journalFile = journalWithAbc(dir);
        Path compactingFile = dir.resolve("registry.journal.compacting");
        RegistryJournal ghostJournal = new RegistryJournal(compactingFile);
        ghostJournal.recordRegistration(
                "ghost", "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
        assertThat(Files.exists(compactingFile)).isTrue();

        List<RegistryJournal.Entry> entries = new RegistryJournal(journalFile).replay();
        assertThat(entries.stream().map(RegistryJournal.Entry::name).toList()).containsExactly("a", "b", "c");
        assertThat(entries.stream().map(RegistryJournal.Entry::name)).doesNotContain("ghost");

        QueryRegistry.Recovery r = recoverFresh(journalFile);
        assertThat(r.recovered()).containsExactly("a", "b", "c");

        assertThat(Files.exists(compactingFile))
                .as("nothing in the product removes the stale .compacting file, and nothing warns about it "
                        + "at startup outside of a failed compaction")
                .isTrue();
    }
}
