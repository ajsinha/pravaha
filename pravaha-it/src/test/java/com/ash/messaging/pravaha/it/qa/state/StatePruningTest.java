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
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-013..022 -- pruning: to {@code keep}, never below one.
 *
 * <p>{@code prune} was written and called only from its own test until {@code PeriodicCheckpointer}
 * existed. Retention here is counted, not timed: H-CS, the store alone, checkpoints built by hand.
 */
@Timeout(120)
class StatePruningTest extends StateTestSupport {

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void state013_withKeep3AndSixCheckpointsTheNewestThreeSurvive(@TempDir Path dir) throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        for (long id = 1; id <= 6; id++) {
            store.store(cp(id));
        }
        int removed = store.prune(3);
        assertThat(removed).isEqualTo(3);
        assertThat(store.availableIds()).containsExactly(6L, 5L, 4L);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("checkpoint-6.bin", "checkpoint-5.bin", "checkpoint-4.bin");
        }
        assertThat(store.load(1)).isEmpty();
        assertThat(store.load(2)).isEmpty();
        assertThat(store.load(3)).isEmpty();
    }

    @Test
    void state014_pruningIsByIdNotByFileModificationTime(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        // Stored in this order, so mtime order is 5,1,4,2,3 -- by mtime the newest three would be
        // 4, 2, 3; by id they are 5, 4, 3.
        for (long id : new long[] {5, 1, 4, 2, 3}) {
            store.store(cp(id));
        }
        store.prune(3);
        assertThat(store.availableIds()).containsExactly(5L, 4L, 3L);
    }

    @Test
    void state015_pruneWithFewerThanKeepFilesRemovesNothing(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cp(1));
        store.store(cp(2));
        int removed = store.prune(3);
        assertThat(removed).isZero();
        assertThat(store.availableIds()).containsExactly(2L, 1L);
    }

    @Test
    void state016_prune0IsRefusedBeforeDeletingAnything(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cp(1));
        store.store(cp(2));
        store.store(cp(3));

        assertThatThrownBy(() -> store.prune(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one checkpoint must be kept, asked to keep 0");
        assertThatThrownBy(() -> store.prune(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asked to keep -5");

        assertThat(store.availableIds()).containsExactly(3L, 2L, 1L);
        assertThat(store.load(1)).isPresent();
        assertThat(store.load(2)).isPresent();
        assertThat(store.load(3)).isPresent();
    }

    @Test
    void state017_keep1KeepsExactlyOneWhichIsTheNewest(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cp(10));
        store.store(cp(11));
        store.store(cp(12));

        int removed = store.prune(1);
        assertThat(removed).isEqualTo(2);
        assertThat(store.availableIds()).containsExactly(12L);
        assertThat(store.load(12)).isPresent();
        assertThat(store.load(11)).isEmpty();
        assertThat(store.load(10)).isEmpty();
    }

    @Test
    void state018_theCheckpointerPrunesAfterEveryCheckpointAndTheCountAccumulates(@TempDir Path dir) {
        try (RawExecution win = rawWindowed();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution,
                        new FileCheckpointStore(dir),
                        Duration.ofHours(1),
                        2,
                        Duration.ofSeconds(5),
                        m -> {})) {
            for (int call = 1; call <= 6; call++) {
                checkpointer.checkpointNow();
                List<Long> ids = new FileCheckpointStore(dir).availableIds();
                assertThat(ids).as("after call " + call).hasSize(Math.min(call, 2));
                assertThat(checkpointer.stats().pruned())
                        .as("pruned after call " + call)
                        .isEqualTo(Math.max(0, call - 2));
            }
            assertThat(checkpointer.stats().taken()).isEqualTo(6);
            assertThat(checkpointer.stats().pruned()).isEqualTo(4);
            assertThat(new FileCheckpointStore(dir).availableIds()).containsExactly(6L, 5L);
        }
    }

    @Test
    void state019_pruningNeverRemovesTheCheckpointLatestWouldReturn(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        for (long i = 1; i <= 50; i++) {
            long id = i;
            store.store(cp(id));
            store.prune(3);
            assertThat(store.latest())
                    .as("iteration " + id)
                    .hasValueSatisfying(c -> assertThat(c.id()).isEqualTo(id));
            assertThat(store.availableIds()).as("iteration " + id).hasSize((int) Math.min(id, 3));
        }
        assertThat(store.availableIds()).containsExactly(50L, 49L, 48L);
    }

    @Test
    void state020_deleteQuietlySwallowsAnUndeletableFileAndPruningStillReportsItRemoved(@TempDir Path dir)
            throws Exception {
        Assumptions.assumeTrue(posix(dir), "requires POSIX permissions");
        // Running as root defeats a read-only directory's protection against unlink.
        Assumptions.assumeTrue(
                !"root".equals(System.getProperty("user.name")), "cannot exercise this as root: chmod is ignored");

        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cp(1));
        store.store(cp(2));
        store.store(cp(3));
        store.store(cp(4));

        Set<PosixFilePermission> readOnly = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(dir, readOnly);
        try {
            int removed = store.prune(3);
            assertThat(removed)
                    .as("prune claims one removed, even though it could not unlink it")
                    .isEqualTo(1);
            assertThat(store.availableIds())
                    .as("checkpoint-1.bin is still there: the directory could not be written to")
                    .containsExactly(4L, 3L, 2L, 1L);
        } finally {
            Files.setPosixFilePermissions(
                    dir,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void state021_aForeignFileInTheCheckpointDirectoryDoesNotConfusePruning(@TempDir Path dir) throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cp(1));
        store.store(cp(2));
        store.store(cp(3));
        store.store(cp(4));
        Files.writeString(dir.resolve("README.txt"), "notes");
        Files.writeString(dir.resolve("checkpoint-9.tmp"), "leftover from an interrupted store");
        Files.writeString(dir.resolve("notes.bin"), "not a checkpoint");

        assertThat(store.availableIds()).containsExactly(4L, 3L, 2L, 1L);
        int removed = store.prune(2);
        assertThat(removed).isEqualTo(2);

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(
                            "checkpoint-4.bin", "checkpoint-3.bin", "README.txt", "checkpoint-9.tmp", "notes.bin");
        }
    }

    @Test
    void state022_aCheckpointFileWithANonNumericIdMakesTheWholeDirectoryUnusable(@TempDir Path dir) throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cp(1));
        store.store(cp(2));
        store.store(cp(3));
        Files.copy(dir.resolve("checkpoint-3.bin"), dir.resolve("checkpoint-old.bin"));

        assertThatThrownBy(store::availableIds)
                .isInstanceOf(NumberFormatException.class)
                .hasMessageContaining("For input string: \"old\"");
        assertThatThrownBy(store::latest)
                .isInstanceOf(NumberFormatException.class)
                .hasMessageContaining("For input string: \"old\"");
        assertThatThrownBy(() -> store.prune(2))
                .isInstanceOf(NumberFormatException.class)
                .hasMessageContaining("For input string: \"old\"");
        try (RawExecution win = rawWindowed()) {
            assertThatThrownBy(() -> new PeriodicCheckpointer(
                            win.execution, store, Duration.ofSeconds(1), 3, Duration.ofSeconds(5), m -> {}))
                    .isInstanceOf(NumberFormatException.class)
                    .hasMessageContaining("For input string: \"old\"");
        }
    }
}
