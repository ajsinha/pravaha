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
package com.ash.messaging.pravaha.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The audit trail somebody can actually read (CFG-23).
 *
 * <p>{@code AuditSink.InMemory} records every decision correctly and exposes them to nobody --
 * nothing in any {@code src/main} calls {@code events()} -- so an operator asked "who read payroll"
 * could not answer it from a running node. These cases are that question, asked of a file.
 */
class FileAuditSinkTest {

    private static final Principal BOB = new Principal("bob", "acme", Set.of("analyst"), Map.of("email", "bob@x.io"));

    private static AuditEvent read(String target, AccessDecision decision, String sql) {
        return AuditEvent.of(BOB, "query", target, decision, sql);
    }

    @Test
    void whoReadPayrollIsAnswerableFromTheFile(@TempDir Path directory) throws Exception {
        Path trail = directory.resolve("audit.jsonl");

        try (FileAuditSink sink = new FileAuditSink(trail)) {
            sink.record(read("payroll", AccessDecision.allow(), "SELECT * FROM payroll"));
            sink.record(read("sales_view", AccessDecision.deny("not an operator"), "SELECT * FROM sales_view"));
            sink.flush(Duration.ofSeconds(5));
        }

        List<String> lines = Files.readAllLines(trail, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .as("the question the register says gets asked, answered by grep")
                .contains("\"principal\":\"bob\"")
                .contains("\"target\":\"payroll\"")
                .contains("\"result\":\"ALLOW\"");
        assertThat(lines.get(1)).contains("\"result\":\"DENY\"").contains("not an operator");
    }

    @Test
    void theTrailNeverCarriesTheClaims(@TempDir Path directory) throws Exception {
        Path trail = directory.resolve("audit.jsonl");

        try (FileAuditSink sink = new FileAuditSink(trail)) {
            sink.record(read("payroll", AccessDecision.allow(), "SELECT 1 FROM payroll"));
            sink.flush(Duration.ofSeconds(5));
        }

        // Principal.toString does not print claims because they carry whatever the identity provider
        // put in a token -- email addresses and sometimes worse. A file written for retention is the
        // last place they should start appearing.
        assertThat(Files.readString(trail)).doesNotContain("bob@x.io").contains("\"roles\":[\"analyst\"]");
    }

    @Test
    void theSqlTextIsEscapedRatherThanBreakingTheLine(@TempDir Path directory) throws Exception {
        Path trail = directory.resolve("audit.jsonl");

        try (FileAuditSink sink = new FileAuditSink(trail)) {
            sink.record(read("payroll", AccessDecision.allow(), "SELECT \"a\"\nFROM payroll WHERE id = 'ACC\\007'"));
            sink.flush(Duration.ofSeconds(5));
        }

        // One event is one line. A newline in the SQL that reached the file unescaped would split an
        // event in two and make every line-counting tool downstream wrong about how much happened.
        assertThat(Files.readAllLines(trail, StandardCharsets.UTF_8)).hasSize(1);
        assertThat(Files.readString(trail)).contains("\\\"a\\\"").contains("\\n");
    }

    @Test
    void theFileIsOwnerReadableOnly(@TempDir Path directory) throws Exception {
        Path trail = directory.resolve("audit.jsonl");

        try (FileAuditSink sink = new FileAuditSink(trail)) {
            sink.record(read("payroll", AccessDecision.allow(), "SELECT 1 FROM payroll"));
            sink.flush(Duration.ofSeconds(5));
        }

        // This file is the disclosure surface an endpoint would have been: every principal id that
        // asked for anything, and the SQL they asked with. World-readable under a default umask
        // would hand that to every login on the box.
        if (Files.getFileStore(trail).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
            assertThat(Files.getPosixFilePermissions(trail))
                    .containsExactlyInAnyOrder(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void anUnwritablePathIsRefusedAtStartupAndNotAtTheFirstDecision(@TempDir Path directory) throws Exception {
        Path blocked = directory.resolve("not-a-directory");
        Files.writeString(blocked, "this is a file");

        assertThatThrownBy(() -> new FileAuditSink(blocked.resolve("audit.jsonl")))
                .isInstanceOf(PravahaException.class)
                // PRV-7004, the code that carries a security setting this node refuses to start
                // with. A node that starts believing it is auditing and writes nowhere has no record
                // at all -- the outcome CFG-5 and CFG-23 both produced, from opposite ends.
                .hasMessageContaining("PRV-7004")
                .hasMessageContaining("audit.jsonl");
    }

    @Test
    void thePreviousTrailIsAppendedToRatherThanTruncated(@TempDir Path directory) throws Exception {
        Path trail = directory.resolve("audit.jsonl");

        try (FileAuditSink first = new FileAuditSink(trail)) {
            first.record(read("payroll", AccessDecision.allow(), "before the restart"));
            first.flush(Duration.ofSeconds(5));
        }
        try (FileAuditSink second = new FileAuditSink(trail)) {
            second.record(read("payroll", AccessDecision.allow(), "after the restart"));
            second.flush(Duration.ofSeconds(5));
        }

        // A restart must not erase the record of what happened before it. That is most of what a
        // file buys over the in-memory sink.
        assertThat(Files.readAllLines(trail, StandardCharsets.UTF_8)).hasSize(2);
    }

    @Test
    void thefileRotatesAndKeepsABoundedNumberOfGenerations(@TempDir Path directory) throws Exception {
        Path trail = directory.resolve("audit.jsonl");

        try (FileAuditSink sink = new FileAuditSink(trail, 4096, 2, message -> {})) {
            for (int i = 0; i < 400; i++) {
                sink.record(read("payroll_" + i, AccessDecision.allow(), "SELECT * FROM payroll WHERE id = " + i));
            }
            sink.flush(Duration.ofSeconds(10));

            // An audit trail that fills the disk stops the node it was auditing, so the generations
            // are bounded -- and the live file is always the newest, which is where an operator
            // looks first.
            assertThat(Files.exists(trail)).isTrue();
            assertThat(Files.exists(directory.resolve("audit.jsonl.1"))).isTrue();
            assertThat(Files.exists(directory.resolve("audit.jsonl.3")))
                    .as("keep=2 means two rotated generations and no third")
                    .isFalse();
            assertThat(sink.writtenEvents()).isEqualTo(400);
        }
    }

    @Test
    void recordingNeverThrowsAtTheCaller(@TempDir Path directory) {
        Path trail = directory.resolve("audit.jsonl");
        FileAuditSink sink = new FileAuditSink(trail);
        sink.close();

        // An audit sink that failed a query it is auditing would turn an observability outage into a
        // production one. Recording into a closed sink counts a drop and returns.
        sink.record(read("payroll", AccessDecision.allow(), "SELECT 1 FROM payroll"));

        assertThat(sink.droppedEvents()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ AUDITROTATE-1

    private static final Set<PosixFilePermission> READ_ONLY_DIRECTORY = PosixFilePermissions.fromString("r-x------");
    private static final Set<PosixFilePermission> WRITABLE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> READ_ONLY_FILE = PosixFilePermissions.fromString("r--------");
    private static final Set<PosixFilePermission> WRITABLE_FILE = PosixFilePermissions.fromString("rw-------");

    /** Fills the file to just under its 4096-byte bound, so the next events rotate it. */
    private static void fillToTheBound(FileAuditSink sink, Path trail) throws Exception {
        int i = 0;
        while (Files.size(trail) < 3900) {
            sink.record(read("payroll", AccessDecision.allow(), "SELECT * FROM payroll WHERE id = " + i++));
            sink.flush(Duration.ofSeconds(5));
        }
    }

    /** False on a filesystem without POSIX permissions, or as root, who writes anywhere. */
    private static boolean permissionsBite(Path directory) throws Exception {
        if (!Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return false;
        }
        Files.setPosixFilePermissions(directory, READ_ONLY_DIRECTORY);
        boolean bites = !Files.isWritable(directory);
        Files.setPosixFilePermissions(directory, WRITABLE_DIRECTORY);
        return bites;
    }

    @Test
    void aRotationThatCannotMoveTheFileKeepsWritingTheCurrentOneAndSaysSo(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("trail"));
        assumeTrue(permissionsBite(directory), "POSIX permissions, and not root");
        Path trail = directory.resolve("audit.jsonl");
        List<String> problems = new CopyOnWriteArrayList<>();

        try (FileAuditSink sink = new FileAuditSink(trail, 4096, 2, problems::add, Duration.ZERO)) {
            fillToTheBound(sink, trail);
            long before = sink.writtenEvents();
            Files.setPosixFilePermissions(directory, READ_ONLY_DIRECTORY);
            try {
                for (int i = 0; i < 20; i++) {
                    sink.record(read("payroll", AccessDecision.allow(), "SELECT * FROM payroll WHERE id = " + i));
                }
                sink.flush(Duration.ofSeconds(5));

                // The rename was refused, and the file it was renaming is still writable: every
                // decision goes on into it, past its bound, rather than into nothing.
                assertThat(sink.writtenEvents()).isEqualTo(before + 20);
                assertThat(sink.lostEvents()).isZero();
                assertThat(Files.exists(directory.resolve("audit.jsonl.1"))).isFalse();
                assertThat(sink.failure())
                        .hasValueSatisfying(failure -> assertThat(failure).contains("could not rotate"));
                assertThat(problems)
                        .as("reported once when it starts, not once per event")
                        .hasSize(1);
            } finally {
                Files.setPosixFilePermissions(directory, WRITABLE_DIRECTORY);
            }

            sink.record(read("payroll", AccessDecision.allow(), "after the directory is writable again"));
            sink.flush(Duration.ofSeconds(5));

            assertThat(sink.failure()).isEmpty();
            assertThat(Files.exists(directory.resolve("audit.jsonl.1"))).isTrue();
            assertThat(problems.get(problems.size() - 1)).contains("rotates again");
        }
    }

    @Test
    void aTrailThatCannotBeReopenedCountsEveryLostEventAndRecordsTheGapOnceItCan(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("trail"));
        assumeTrue(permissionsBite(directory), "POSIX permissions, and not root");
        Path trail = directory.resolve("audit.jsonl");
        List<String> problems = new CopyOnWriteArrayList<>();

        try (FileAuditSink sink = new FileAuditSink(trail, 4096, 2, problems::add, Duration.ZERO)) {
            fillToTheBound(sink, trail);
            long before = sink.writtenEvents();
            // Neither renamable nor reopenable: the rotation closes the stream and cannot open one.
            // This was the state in which every later event was discarded without a count or a word.
            Files.setPosixFilePermissions(directory, READ_ONLY_DIRECTORY);
            Files.setPosixFilePermissions(trail, READ_ONLY_FILE);
            try {
                for (int i = 0; i < 20; i++) {
                    sink.record(read("payroll", AccessDecision.allow(), "SELECT * FROM payroll WHERE id = " + i));
                }
                sink.flush(Duration.ofSeconds(5));

                assertThat(sink.writtenEvents()).isEqualTo(before);
                assertThat(sink.lostEvents()).isEqualTo(20);
                assertThat(sink.unrecorded()).isEqualTo(20);
                assertThat(sink.failedWrites()).isGreaterThanOrEqualTo(20);
                assertThat(sink.failure())
                        .hasValueSatisfying(failure -> assertThat(failure).contains("cannot write the audit trail"));
                assertThat(problems.stream().filter(problem -> problem.contains("cannot write the audit trail")))
                        .as("one ERROR when the failure starts, not one per lost event")
                        .hasSize(1);
            } finally {
                Files.setPosixFilePermissions(directory, WRITABLE_DIRECTORY);
                Files.setPosixFilePermissions(trail, WRITABLE_FILE);
            }

            // Writable again: the next event opens the file by itself, and the gap is in the record.
            sink.record(read("payroll", AccessDecision.allow(), "after the trail is writable again"));
            sink.flush(Duration.ofSeconds(5));

            assertThat(sink.failure()).isEmpty();
            assertThat(sink.writtenEvents()).isEqualTo(before + 1);
            String all = Files.readString(trail);
            if (Files.exists(directory.resolve("audit.jsonl.1"))) {
                all = Files.readString(directory.resolve("audit.jsonl.1")) + all;
            }
            assertThat(all)
                    .contains("\"event\":\"audit.lost\",\"count\":20")
                    .contains("after the trail is writable again");
            assertThat(problems.get(problems.size() - 1)).contains("is being written again");
        }
    }

    @Test
    void writesFailingWhileTheTrailRotatesAreEachWrittenOnceOrCountedLost(@TempDir Path root) throws Exception {
        // J21-6. A write failure in the writer thread was handled outside the monitor guarding the
        // stream, so a concurrent flush() or close() could reopen it in between. Here the trail
        // rotates every few dozen events while its directory and file flip unwritable and back, and
        // another thread flushes throughout: every event must be written exactly once or counted
        // lost, every lost one must reach the file as an audit.lost count, and the sink must end
        // healthy.
        Path directory = Files.createDirectory(root.resolve("trail"));
        assumeTrue(permissionsBite(directory), "POSIX permissions, and not root");
        Path trail = directory.resolve("audit.jsonl");
        int producers = 4;
        int perProducer = 500;
        // Enough generations that none is deleted: every line ever written is still on disk.
        try (FileAuditSink sink = new FileAuditSink(trail, 4096, 10_000, message -> {}, Duration.ZERO)) {
            AtomicBoolean running = new AtomicBoolean(true);
            Thread flipper = Thread.ofPlatform().start(() -> {
                while (running.get()) {
                    flip(directory, trail, READ_ONLY_DIRECTORY, READ_ONLY_FILE);
                    pause();
                    flip(directory, trail, WRITABLE_DIRECTORY, WRITABLE_FILE);
                    pause();
                }
            });
            Thread flusher = Thread.ofPlatform().start(() -> {
                while (running.get()) {
                    sink.flush(Duration.ofMillis(1));
                }
            });
            List<Thread> threads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                String producer = "p" + p;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        sink.record(read("payroll", AccessDecision.allow(), "event " + producer + "-" + i));
                        if (i % 50 == 0) {
                            pause();
                        }
                    }
                }));
            }
            for (Thread t : threads) {
                t.join();
            }
            sink.flush(Duration.ofSeconds(10));
            running.set(false);
            flipper.join();
            flusher.join();
            flip(directory, trail, WRITABLE_DIRECTORY, WRITABLE_FILE);

            sink.record(read("payroll", AccessDecision.allow(), "event final"));
            sink.flush(Duration.ofSeconds(10));

            int total = producers * perProducer + 1;
            // Both paths ran: some events were written and some could not be (about a quarter and
            // three quarters respectively, when this was written).
            assertThat(sink.writtenEvents()).isPositive();
            assertThat(sink.lostEvents()).as("the flips made writes fail").isPositive();
            assertThat(sink.writtenEvents() + sink.lostEvents() + sink.droppedEvents())
                    .as("every event accounted for")
                    .isEqualTo(total);
            assertThat(sink.failure()).as("the stream ends open and healthy").isEmpty();

            List<String> lines = new ArrayList<>();
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
                }
            }
            List<String> events = lines.stream()
                    .filter(line -> line.contains("\"principal\""))
                    .toList();
            assertThat(events).as("written once each, none doubled").doesNotHaveDuplicates();
            assertThat(events).hasSize((int) sink.writtenEvents());
            assertThat(markerCount(lines, "audit.lost"))
                    .as("every lost event is in the file as a count")
                    .isEqualTo(sink.lostEvents());
            assertThat(markerCount(lines, "audit.dropped")).isEqualTo(sink.droppedEvents());
            assertThat(Files.readAllLines(trail, StandardCharsets.UTF_8))
                    .last()
                    .asString()
                    .contains("event final");
        } finally {
            flip(directory, trail, WRITABLE_DIRECTORY, WRITABLE_FILE);
        }
    }

    private static void flip(
            Path directory, Path trail, Set<PosixFilePermission> directoryMode, Set<PosixFilePermission> fileMode) {
        try {
            Files.setPosixFilePermissions(directory, directoryMode);
            Files.setPosixFilePermissions(trail, fileMode);
        } catch (java.io.IOException racedWithARotation) {
            // The file was moved aside between the two calls; the next flip catches up.
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long markerCount(List<String> lines, String event) {
        Pattern marker = Pattern.compile("\"event\":\"" + Pattern.quote(event) + "\",\"count\":(\\d+)");
        long sum = 0;
        for (String line : lines) {
            Matcher matcher = marker.matcher(line);
            if (matcher.find()) {
                sum += Long.parseLong(matcher.group(1));
            }
        }
        return sum;
    }

    @Test
    void theReadableTrailReportsItsDurableSinksFailure(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("trail"));
        assumeTrue(permissionsBite(directory), "POSIX permissions, and not root");
        Path trail = directory.resolve("audit.jsonl");
        try (FileAuditSink sink = new FileAuditSink(trail, 4096, 2, message -> {}, Duration.ZERO)) {
            AuditTrail readable = new AuditTrail(sink, "file", 16);
            assertThat(readable.failure()).isEmpty();
            fillToTheBound(sink, trail);
            Files.setPosixFilePermissions(directory, READ_ONLY_DIRECTORY);
            Files.setPosixFilePermissions(trail, READ_ONLY_FILE);
            try {
                readable.record(read("payroll", AccessDecision.allow(), "SELECT * FROM payroll WHERE id = 1"));
                sink.flush(Duration.ofSeconds(5));

                // What the node's health reads to report DEGRADED.
                assertThat(readable.failure()).isPresent();
                assertThat(readable.unrecorded()).isEqualTo(1);
            } finally {
                Files.setPosixFilePermissions(directory, WRITABLE_DIRECTORY);
                Files.setPosixFilePermissions(trail, WRITABLE_FILE);
            }
        }
    }
}
