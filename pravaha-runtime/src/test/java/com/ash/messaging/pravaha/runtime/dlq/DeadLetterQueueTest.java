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
package com.ash.messaging.pravaha.runtime.dlq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.state.StateErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The dead-letter queue.
 *
 * <p>Two rules pull against each other and the tests are mostly about the seam between them. A
 * record must never be dropped silently, because a query producing slightly wrong answers from
 * quietly discarded input is worse than one that fails -- nobody investigates what nobody notices.
 * And a bad record must never stop the pipeline, because one malformed message from one partner
 * cannot halt a query carrying nine others.
 */
class DeadLetterQueueTest {

    private static DeadLetter letter(String reason, String offset, byte[] raw) {
        return new DeadLetter("q-1", reason, offset, raw, "corr-42", 1_700_000_000_000_000_000L);
    }

    @Test
    void anEntryKeepsEverythingNeededToDiagnoseIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dlq/rejects.jsonl");
        byte[] raw = "id,name\n1,\"unterminated".getBytes(StandardCharsets.UTF_8);

        try (FileDeadLetterQueue dlq = new FileDeadLetterQueue(file)) {
            dlq.accept(letter("line 2 has an unterminated quoted field", "orders-01.csv:2", raw));
            assertThat(dlq.count()).isEqualTo(1);
            assertThat(dlq.failures()).isZero();
        }

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .as("the four things somebody actually needs, in one greppable line")
                .contains("\"query\":\"q-1\"")
                .contains("\"offset\":\"orders-01.csv:2\"")
                .contains("\"reason\":\"line 2 has an unterminated quoted field\"")
                .contains("\"correlationId\":\"corr-42\"");

        String encoded = lines.get(0).replaceAll(".*\"raw\":\"([^\"]*)\".*", "$1");
        assertThat(Base64.getDecoder().decode(encoded))
                .as("the bytes as received, not a re-encoding of them")
                .isEqualTo(raw);
    }

    @Test
    void aRecordFullOfQuotesAndNewlinesStillProducesOneReadableLine(@TempDir Path dir) throws IOException {
        // The DLQ is read with grep and jq by somebody under time pressure. A reason containing a
        // quote or a newline would break the line-oriented reading the format exists for -- and the
        // reasons that contain quotes are exactly the ones about malformed quoted fields.
        Path file = dir.resolve("rejects.jsonl");
        try (FileDeadLetterQueue dlq = new FileDeadLetterQueue(file)) {
            dlq.accept(letter("field \"name\" is malformed\non line 2\ttab", "off\"set", new byte[] {0, 1, 2}));
        }

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).as("one entry, one line, whatever it contains").hasSize(1);
        assertThat(lines.get(0)).contains("\\\"name\\\"").contains("\\n").contains("\\t");
    }

    @Test
    void anEntryThatCannotBeWrittenIsRefusedAndCountedNotDropped(@TempDir Path dir) throws IOException {
        // DLQFULL-1. It used to be counted and nothing else, so on a full disk the records went
        // nowhere while the source read past them. Refused now, with the code a node with an
        // unwritable queue starts with, so what fed the record stops at it.
        Path file = dir.resolve("rejects.jsonl");
        FileDeadLetterQueue dlq = new FileDeadLetterQueue(file);
        dlq.close();

        assertThatThrownBy(() -> dlq.accept(letter("after close", "orders.csv:7", new byte[0])))
                .isInstanceOf(PravahaException.class)
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(StateErrors.DLQ_UNUSABLE))
                .hasMessageContaining("q-1")
                .hasMessageContaining("orders.csv:7")
                .hasMessageContaining("stops here rather than read past it");
        assertThat(dlq.failures())
                .as("still counted, for the write-failures metric")
                .isEqualTo(1);
        assertThat(dlq.count()).isZero();
    }

    @Test
    void aFullDiskRefusesTheEntry(@TempDir Path dir) throws IOException {
        // /dev/full answers every write with ENOSPC: the full disk of ADV-GAPS QG-D04, on demand.
        Path full = Path.of("/dev/full");
        assumeTrue(Files.isWritable(full), "needs /dev/full");
        Path file = dir.resolve("v1.dlq");
        Files.createSymbolicLink(file, full);
        try (FileDeadLetterQueue dlq = new FileDeadLetterQueue(file)) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                assertThatThrownBy(() -> dlq.accept(letter("not a number", "line 3", new byte[] {'x'})))
                        .isInstanceOf(PravahaException.class)
                        .hasMessageStartingWith("PRV-4090");
                assertThat(dlq.failures()).isEqualTo(attempt);
            }
            assertThat(dlq.count()).isZero();
        }
    }

    @Test
    void anEntryCannotBeEditedAfterItIsRecorded() {
        // A DLQ entry is evidence. Evidence the caller can edit after the fact is not evidence.
        byte[] raw = {1, 2, 3};
        DeadLetter entry = letter("reason", "offset", raw);

        raw[0] = 99;
        assertThat(entry.raw()[0])
                .as("the copy taken in is not the caller's array")
                .isEqualTo((byte) 1);

        entry.raw()[0] = 99;
        assertThat(entry.raw()[0])
                .as("and the copy handed out is not the stored one")
                .isEqualTo((byte) 1);
    }

    @Test
    void aSteadyTrickleOfRejectsIsNotAnIncident() {
        // Every real feed rejects something. A DLQ that alerts on the first one is a DLQ whose
        // alerts are muted by week two.
        DeadLetterRate rate = new DeadLetterRate(0.01, 60_000_000_000L, 1_000);

        for (int i = 0; i < 10_000; i++) {
            if (i % 500 == 0) {
                rate.recordRejected(i);
            } else {
                rate.recordAccepted(i);
            }
        }

        assertThat(rate.rejectedFraction()).isLessThan(0.01);
        assertThat(rate.isDegraded()).isFalse();
    }

    @Test
    void aFeedThatSuddenlyRejectsMostOfItsRecordsDegradesTheQuery() {
        // A schema change nobody announced. Degraded rather than stopped: stopping would discard the
        // records that are still valid, which is a bigger loss than the ones that are not.
        DeadLetterRate rate = new DeadLetterRate(0.01, 60_000_000_000L, 1_000);

        for (int i = 0; i < 2_000; i++) {
            if (i % 3 == 0) {
                rate.recordRejected(i);
            } else {
                rate.recordAccepted(i);
            }
        }

        assertThat(rate.isDegraded()).isTrue();
        assertThat(rate.rejectedFraction()).isGreaterThan(0.3);
        assertThat(rate.degradedTransitions()).isEqualTo(1);
    }

    @Test
    void oneRejectedRecordDoesNotDegradeAQueryOnASampleOfOne() {
        // Without a minimum sample the first reject in a window is a 100 % failure rate. Every
        // window boundary would then be a potential false alarm.
        DeadLetterRate rate = new DeadLetterRate(0.01, 60_000_000_000L, 1_000);

        rate.recordRejected(0);
        rate.recordAccepted(1);

        assertThat(rate.isDegraded()).isFalse();
        assertThat(rate.rejectedFraction())
                .as("no fraction is reported below the minimum sample")
                .isZero();
    }

    @Test
    void aQueryRecoversWhenTheFeedDoes() {
        // A feed broken for an hour and fine since should not need somebody to notice and clear a
        // flag. Counts reset with the window, so recovery is automatic.
        long window = 60_000_000_000L;
        DeadLetterRate rate = new DeadLetterRate(0.01, window, 10);

        for (int i = 0; i < 100; i++) {
            rate.recordRejected(i);
        }
        assertThat(rate.isDegraded()).isTrue();

        rate.recordAccepted(window + 1);
        assertThat(rate.isDegraded())
                .as("a new window, and the feed is healthy in it")
                .isFalse();
        assertThat(rate.rejectedInWindow()).isZero();
    }

    @Test
    void aCountingQueueIsAvailableForDeploymentsThatOptOut() {
        DeadLetterQueue dlq = DeadLetterQueue.counting();
        dlq.accept(letter("reason", "offset", new byte[0]));

        assertThat(dlq.count()).isEqualTo(1);
        assertThat(dlq.failures()).isZero();
    }

    @Test
    void impossibleThresholdsAreRefused() {
        assertThatThrownBy(() -> new DeadLetterRate(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadLetterRate(1.5, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadLetterRate(0.5, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadLetterRate(0.5, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
