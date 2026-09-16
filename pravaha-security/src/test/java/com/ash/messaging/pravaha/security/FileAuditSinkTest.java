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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
