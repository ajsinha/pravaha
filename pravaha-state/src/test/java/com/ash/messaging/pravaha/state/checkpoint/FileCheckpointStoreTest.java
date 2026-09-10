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
package com.ash.messaging.pravaha.state.checkpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checkpoint storage.
 *
 * <p>The tests that matter are about damage. A checkpoint is either entirely there or entirely
 * absent, because a half-written one that a restore reads is worse than none -- the engine believes
 * it, resumes from offsets that do not match the state it loaded, and produces answers that are
 * wrong in a way nothing detects. After a crash the newest file is exactly the one most likely to be
 * damaged, which is the whole reason more than one is kept.
 */
class FileCheckpointStoreTest {

    private static Checkpoint checkpoint(long id, String offset, String stateContents) {
        return new Checkpoint(
                id,
                1_700_000_000_000_000_000L + id,
                Map.of("txn-0", offset),
                Map.of("agg-1", stateContents.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void storesAndLoadsBackEverything(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(checkpoint(1, "n=100", "state-bytes"));

        assertThat(store.load(1)).hasValueSatisfying(loaded -> {
            assertThat(loaded.id()).isEqualTo(1);
            assertThat(loaded.offsets()).containsEntry("txn-0", "n=100");
            assertThat(new String(loaded.operatorState().get("agg-1"), StandardCharsets.UTF_8))
                    .isEqualTo("state-bytes");
        });
    }

    @Test
    void offsetsAndStateTravelTogether() {
        // The pairing is the whole idea: offsets without state resume the reading and lose the
        // answers; state without offsets keeps the answers and re-reads records already in them.
        // Either alone recovers into a query that is quietly wrong.
        Checkpoint checkpoint = checkpoint(7, "n=42", "accumulators");

        assertThat(checkpoint.offsets()).isNotEmpty();
        assertThat(checkpoint.operatorState()).isNotEmpty();
        assertThat(checkpoint.sizeBytes()).isEqualTo("accumulators".length());
    }

    @Test
    void theNewestCheckpointWins(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(checkpoint(1, "n=100", "old"));
        store.store(checkpoint(2, "n=200", "new"));

        assertThat(store.latest())
                .hasValueSatisfying(latest -> assertThat(latest.id()).isEqualTo(2));
        assertThat(store.availableIds()).containsExactly(2L, 1L);
    }

    @Test
    void aHalfWrittenCheckpointIsSkippedForTheOneBeforeIt(@TempDir Path dir) throws IOException {
        // What a crash mid-write leaves behind. A restore that read this would resume from offsets
        // that do not match the state it loaded -- and nothing downstream would notice.
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(checkpoint(1, "n=100", "complete"));
        store.store(checkpoint(2, "n=200", "also complete"));

        Path newest = dir.resolve("checkpoint-2.bin");
        byte[] bytes = Files.readAllBytes(newest);
        Files.write(newest, java.util.Arrays.copyOf(bytes, bytes.length / 2), StandardOpenOption.TRUNCATE_EXISTING);

        assertThat(store.load(2)).as("a truncated checkpoint does not load").isEmpty();
        assertThat(store.latest())
                .as("and the store falls back to the last complete one, which is why more than one is kept")
                .hasValueSatisfying(latest -> assertThat(latest.id()).isEqualTo(1));
    }

    @Test
    void aCheckpointMissingOnlyItsTrailerIsRejected(@TempDir Path dir) throws IOException {
        // The subtle case, and the one the trailer exists for. Cutting the file in half is caught by
        // the read running out of bytes; cutting off only the trailer leaves every record readable,
        // so the file looks complete and the only evidence that the write did not finish is the
        // missing statement that it did.
        //
        // Rejecting a file whose data happens to be intact costs one checkpoint. Accepting one whose
        // write did not finish costs a restore that silently disagrees with itself, and we cannot
        // tell the two apart from the outside -- which is the argument for the conservative choice.
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(checkpoint(1, "n=100", "complete"));
        store.store(checkpoint(2, "n=200", "also complete"));

        Path newest = dir.resolve("checkpoint-2.bin");
        byte[] bytes = Files.readAllBytes(newest);
        // Exactly the trailer: two ints and the magic.
        Files.write(newest, java.util.Arrays.copyOf(bytes, bytes.length - 12), StandardOpenOption.TRUNCATE_EXISTING);

        assertThat(store.load(2))
                .as("no trailer, no statement that the write finished")
                .isEmpty();
        assertThat(store.latest())
                .hasValueSatisfying(latest -> assertThat(latest.id()).isEqualTo(1));
    }

    @Test
    void aFileThatIsNotACheckpointIsIgnoredRatherThanMisread(@TempDir Path dir) throws IOException {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        Files.writeString(dir.resolve("checkpoint-9.bin"), "this is not a checkpoint at all");

        assertThat(store.load(9)).isEmpty();
        assertThat(store.latest()).isEmpty();
    }

    @Test
    void nothingIsPublishedUntilItIsComplete(@TempDir Path dir) throws IOException {
        // The temporary file must not be mistaken for a checkpoint while it is being written -- the
        // rename is what makes the file appear complete or not at all.
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(checkpoint(1, "n=1", "x"));

        try (var files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .as("no temporary files left behind")
                    .allMatch(name -> name.endsWith(".bin"));
        }
    }

    @Test
    void pruningKeepsTheNewestAndRemovesTheRest(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        for (long id = 1; id <= 10; id++) {
            store.store(checkpoint(id, "n=" + id, "state-" + id));
        }

        assertThat(store.prune(3)).isEqualTo(7);
        assertThat(store.availableIds()).containsExactly(10L, 9L, 8L);
        assertThat(store.latest())
                .hasValueSatisfying(latest -> assertThat(latest.id()).isEqualTo(10));
    }

    @Test
    void pruningEverythingIsRefused(@TempDir Path dir) {
        // Keeping none means the next failure has nothing to recover from, which is a decision
        // nobody makes deliberately by passing zero.
        FileCheckpointStore store = new FileCheckpointStore(dir);
        assertThatThrownBy(() -> store.prune(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void anEmptyStoreHasNothingToRestoreFrom(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);

        assertThat(store.latest()).isEqualTo(Optional.empty());
        assertThat(store.availableIds()).isEmpty();
    }
}
