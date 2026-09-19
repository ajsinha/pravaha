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
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a dead-letter queue back, and the bound on how much of it is kept.
 *
 * <p>The file was written by the node and read by nothing, which is why "inspect a dead letter"
 * ended at a shell prompt. These are the properties every surface above this one depends on:
 * newest first, addressable by id, whole when fetched, and honest about what retention took.
 */
class DeadLetterStoreTest {

    private static DeadLetter letter(String query, int n, long wallMillis) {
        return new DeadLetter(
                query,
                "column 'amount' (INT64): cannot read '12.5" + n + "' as a number",
                "PRV-5040",
                "txn",
                "txn/1(txn_id INT64,amount INT64)",
                "line " + n,
                ("row-" + n + ",acme,12.5" + n).getBytes(StandardCharsets.UTF_8),
                "corr-" + n,
                n * 1_000_000L,
                wallMillis);
    }

    private static Path write(Path dir, String query, int count) throws IOException {
        Path file = DeadLetterFiles.letters(dir, query);
        try (FileDeadLetterQueue queue = new FileDeadLetterQueue(file, DeadLetterRetention.unbounded())) {
            for (int n = 1; n <= count; n++) {
                queue.accept(letter(query, n, 1_700_000_000_000L + n));
            }
        }
        return file;
    }

    @Test
    void anEntryCarriesItsCodeStreamSchemaAndWallClockThroughTheFile(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 1);

        DeadLetterEntry entry =
                new FileDeadLetterStore(dir).find("big_txn", "corr-1").orElseThrow();

        assertThat(entry.letter().code())
                .as("a dead letter is addressable by a PRV code like every other failure here")
                .isEqualTo("PRV-5040");
        assertThat(entry.letter().stream()).isEqualTo("txn");
        assertThat(entry.letter().schema()).isEqualTo("txn/1(txn_id INT64,amount INT64)");
        assertThat(entry.letter().sourceOffset()).isEqualTo("line 1");
        assertThat(entry.letter().at()).isPresent();
        assertThat(new String(entry.letter().raw(), StandardCharsets.UTF_8))
                .as("the bytes as received: a record that failed to decode cannot be described any other way")
                .isEqualTo("row-1,acme,12.51");
        assertThat(entry.replay()).isEqualTo(DeadLetterEntry.Replay.NEW);
    }

    @Test
    void aFileWrittenBeforeTheNewFieldsExistedStillReads(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = DeadLetterFiles.letters(dir, "old");
        Files.writeString(
                file,
                "{\"timestamp\":83218734112093,\"query\":\"old\",\"correlationId\":\"corr-old\","
                        + "\"offset\":\"line 812\",\"reason\":\"bad number\",\"raw\":\"ODEy\"}\n",
                StandardCharsets.UTF_8);

        DeadLetterEntry entry =
                new FileDeadLetterStore(dir).find("old", "corr-old").orElseThrow();

        assertThat(entry.letter().reason()).isEqualTo("bad number");
        assertThat(entry.letter().code()).as("not recorded, and not invented").isEmpty();
        assertThat(entry.letter().stream()).isEmpty();
        assertThat(entry.letter().at()).isEmpty();
    }

    @Test
    void pagesComeBackNewestFirst(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 10);

        DeadLetterPage page = new FileDeadLetterStore(dir).page("big_txn", 0, 3);

        assertThat(page.total()).isEqualTo(10);
        assertThat(page.entries().stream().map(DeadLetterEntry::id))
                .as("a queue is read because something has just started failing")
                .containsExactly("corr-10", "corr-9", "corr-8");
        assertThat(page.more()).isTrue();
    }

    @Test
    void anOffsetWalksBackwardsThroughTheQueue(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 10);

        DeadLetterPage page = new FileDeadLetterStore(dir).page("big_txn", 8, 5);

        assertThat(page.entries().stream().map(DeadLetterEntry::id)).containsExactly("corr-2", "corr-1");
        assertThat(page.more()).isFalse();
    }

    @Test
    void aPageSizeIsClampedSoOneCallCannotAskForTheWholeFile(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 3);

        assertThat(new FileDeadLetterStore(dir).page("big_txn", 0, 100_000).limit())
                .isEqualTo(DeadLetterPage.MAX_LIMIT);
        assertThat(new FileDeadLetterStore(dir).page("big_txn", 0, 0).limit()).isEqualTo(DeadLetterPage.DEFAULT_LIMIT);
    }

    @Test
    void aQueryThatHasNeverRejectedAnythingReadsAsZeroRatherThanFailing(@TempDir Path dir) {
        FileDeadLetterStore store = new FileDeadLetterStore(dir);

        assertThat(store.counts("never").entries()).isZero();
        assertThat(store.page("never", 0, 10).entries()).isEmpty();
        assertThat(store.find("never", "corr-1")).isEmpty();
        assertThat(store.queries()).isEmpty();
    }

    @Test
    void countsNameTheDepthTheSizeAndTheSpanOfWhatIsHeld(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 4);

        DeadLetterCounts counts = new FileDeadLetterStore(dir).counts("big_txn");

        assertThat(counts.entries()).isEqualTo(4);
        assertThat(counts.bytes()).isPositive();
        assertThat(counts.oldestMillis()).isEqualTo(1_700_000_000_001L);
        assertThat(counts.newestMillis()).isEqualTo(1_700_000_000_004L);
        assertThat(counts.lossy()).isFalse();
    }

    @Test
    void aReplayIsRecordedBesideTheFileAndShowsOnTheEntry(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 2);
        FileDeadLetterStore store = new FileDeadLetterStore(dir);

        store.recordReplay("big_txn", "corr-1", DeadLetterEntry.Replay.REPLAYED);
        store.recordReplay("big_txn", "corr-2", DeadLetterEntry.Replay.FAILED_AGAIN);

        assertThat(store.find("big_txn", "corr-1").orElseThrow().replay()).isEqualTo(DeadLetterEntry.Replay.REPLAYED);
        assertThat(store.find("big_txn", "corr-2").orElseThrow().replay())
                .isEqualTo(DeadLetterEntry.Replay.FAILED_AGAIN);
        assertThat(store.counts("big_txn").replayed()).isEqualTo(1);
        assertThat(store.counts("big_txn").failedAgain()).isEqualTo(1);
        assertThat(Files.readAllLines(DeadLetterFiles.letters(dir, "big_txn")))
                .as("the .dlq file stays one JSON object per rejected record; jq recipes do not break")
                .hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("\"raw\":\""));
    }

    @Test
    void theByteBoundEvictsTheOldestAndTheLossIsWrittenDown(@TempDir Path dir) throws IOException {
        Path file = DeadLetterFiles.letters(dir, "big_txn");
        // Small enough that a handful of entries passes it; the compaction target is four fifths.
        DeadLetterRetention bound = new DeadLetterRetention(900, 0, Duration.ZERO);
        try (FileDeadLetterQueue queue = new FileDeadLetterQueue(file, bound)) {
            for (int n = 1; n <= 40; n++) {
                queue.accept(letter("big_txn", n, 1_700_000_000_000L + n));
            }
            assertThat(queue.evicted())
                    .as("the bound is enforced by code, not by an instruction to the operator")
                    .isPositive();
        }

        assertThat(Files.size(file)).isLessThanOrEqualTo(900);
        FileDeadLetterStore store = new FileDeadLetterStore(dir, bound);
        DeadLetterCounts counts = store.counts("big_txn");
        assertThat(counts.lossy()).as("never a silent loss").isTrue();
        assertThat(counts.evicted()).isEqualTo(40 - counts.entries());
        assertThat(store.page("big_txn", 0, 1).entries().get(0).id())
                .as("the newest survive, because they are the ones somebody is looking at")
                .isEqualTo("corr-40");
        assertThat(Files.readString(DeadLetterFiles.evicted(dir, "big_txn")))
                .as("the loss outlives the process that caused it")
                .contains("\"entries\":")
                .contains("\"bound\":\"900 bytes\"");
    }

    @Test
    void theCountBoundEvictsTheOldestToo(@TempDir Path dir) throws IOException {
        Path file = DeadLetterFiles.letters(dir, "big_txn");
        DeadLetterRetention bound = new DeadLetterRetention(0, 10, Duration.ZERO);
        try (FileDeadLetterQueue queue = new FileDeadLetterQueue(file, bound)) {
            for (int n = 1; n <= 25; n++) {
                queue.accept(letter("big_txn", n, 1_700_000_000_000L + n));
            }
        }

        DeadLetterCounts counts = new FileDeadLetterStore(dir, bound).counts("big_txn");
        assertThat(counts.entries()).isLessThanOrEqualTo(10);
        assertThat(counts.evicted()).isEqualTo(25 - counts.entries());
    }

    @Test
    void theAgeBoundRemovesWhatIsOlderThanItWhateverTheFileSize(@TempDir Path dir) throws IOException {
        Path file = DeadLetterFiles.letters(dir, "big_txn");
        DeadLetterRetention bound = new DeadLetterRetention(0, 0, Duration.ofHours(1));
        long now = System.currentTimeMillis();
        try (FileDeadLetterQueue queue = new FileDeadLetterQueue(file, bound)) {
            queue.accept(letter("big_txn", 1, now - Duration.ofDays(2).toMillis()));
            queue.accept(letter("big_txn", 2, now));
        }

        List<DeadLetterEntry> left =
                new FileDeadLetterStore(dir, bound).page("big_txn", 0, 10).entries();
        assertThat(left.stream().map(DeadLetterEntry::id))
                .as("an age bound is a promise about how long data is held, not a hint about size")
                .containsExactly("corr-2");
    }

    @Test
    void anEntryWithNoWallClockIsNeverEvictedForBeingOld(@TempDir Path dir) throws IOException {
        Path file = DeadLetterFiles.letters(dir, "old");
        DeadLetterRetention bound = new DeadLetterRetention(0, 0, Duration.ofSeconds(1));
        try (FileDeadLetterQueue queue = new FileDeadLetterQueue(file, bound)) {
            queue.accept(new DeadLetter("old", "bad", "line 1", "812".getBytes(StandardCharsets.UTF_8), "corr-x", 1L));
        }

        assertThat(new FileDeadLetterStore(dir, bound).counts("old").entries())
                .as("zero is 'the writer did not record a clock', not 1970")
                .isEqualTo(1);
    }

    @Test
    void anUnboundedQueueKeepsEverything(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 200);

        assertThat(new FileDeadLetterStore(dir, DeadLetterRetention.unbounded())
                        .counts("big_txn")
                        .entries())
                .isEqualTo(200);
    }

    @Test
    void theDefaultBoundIsOnAndIsNamedAfterTheSpillTiersOwn() {
        assertThat(DeadLetterRetention.defaults().maxBytes())
                .as("a bound that defaults to off is not a bound")
                .isEqualTo(DeadLetterRetention.DEFAULT_MAX_BYTES);
        assertThat(DeadLetterRetention.defaults().bounded()).isTrue();
        assertThat(DeadLetterRetention.unbounded().bounded()).isFalse();
    }

    @Test
    void aTornLastLineDoesNotHideTheEntriesInFrontOfIt(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 3);
        Files.writeString(
                DeadLetterFiles.letters(dir, "big_txn"),
                "{\"timestamp\":1,\"query\":\"big_txn\",\"raw\":\"!!!not base64",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(new FileDeadLetterStore(dir).counts("big_txn").entries()).isEqualTo(3);
    }

    @Test
    void theStoreListsEveryQueryItHoldsEntriesFor(@TempDir Path dir) throws IOException {
        write(dir, "big_txn", 1);
        write(dir, "small_txn", 1);

        assertThat(new FileDeadLetterStore(dir).queries()).containsExactly("big_txn", "small_txn");
    }

    @Test
    void aNameThatWouldEscapeTheDirectoryCannot(@TempDir Path dir) {
        assertThat(DeadLetterFiles.letters(dir, "../../etc/passwd").normalize().getParent())
                .as("the path is built from a request, on the read side")
                .isEqualTo(dir);
    }

    @Test
    void theEmptyStoreAnswersEveryQuestionWithNothing() {
        assertThat(DeadLetterStore.NONE.configured()).isFalse();
        assertThat(DeadLetterStore.NONE.queries()).isEmpty();
        assertThat(DeadLetterStore.NONE.counts("anything")).isEqualTo(DeadLetterCounts.empty());
        assertThat(DeadLetterStore.NONE.page("anything", 0, 10).entries()).isEmpty();
        assertThat(DeadLetterStore.NONE.find("anything", "corr-1")).isEmpty();
    }
}
