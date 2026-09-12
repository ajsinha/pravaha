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
package com.ash.messaging.pravaha.common.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Files that hold data are created for their owner and nobody else.
 *
 * <p>Three files in this engine hold what a customer would call their data — the registry journal,
 * checkpoints, and the dead-letter queue — and all three were created at whatever the process umask
 * happened to be. Two carried a comment telling the operator to permission them like data, which is
 * an instruction to somebody who may never read it rather than a control.
 */
class SensitiveFilesTest {

    @Test
    void aCreatedFileIsReadableOnlyByItsOwner(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("holds-data.bin");

        SensitiveFiles.createOwnerOnly(file);

        assumeTrue(posix(file), "POSIX permissions unsupported here");
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void theDirectoryIsNarrowedToo(@TempDir Path directory) throws IOException {
        // A file nobody else may read, in a directory anybody may list, still tells them the
        // query names and how many there are.
        Path nested = directory.resolve("state").resolve("holds-data.bin");

        SensitiveFiles.createOwnerOnly(nested);

        assumeTrue(posix(nested), "POSIX permissions unsupported here");
        assertThat(Files.getPosixFilePermissions(nested.getParent()))
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    void itIsNarrowedBeforeTheFirstWriteRatherThanAfter(@TempDir Path directory) throws IOException {
        // A file that is world-readable for the duration of one write has been world-readable.
        // Asserting the permission on an empty file is what proves the ordering.
        Path file = directory.resolve("ordering.bin");

        SensitiveFiles.createOwnerOnly(file);

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isZero();
        assumeTrue(posix(file), "POSIX permissions unsupported here");
        assertThat(Files.getPosixFilePermissions(file)).doesNotContain(PosixFilePermission.OTHERS_READ);
    }

    @Test
    void narrowingAnExistingFileDoesNotTruncateIt(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("already-there.bin");
        Files.writeString(file, "rows that are already recorded");

        SensitiveFiles.createOwnerOnly(file);

        assertThat(Files.readString(file)).isEqualTo("rows that are already recorded");
    }

    @Test
    void syncingADirectoryIsHarmlessWhereItIsNotSupported(@TempDir Path directory) {
        // Best effort by design: the rename is already atomic for a reader, and failing a write
        // because a directory could not be opened would trade durability for availability.
        SensitiveFiles.syncDirectory(directory);
        SensitiveFiles.syncDirectory(directory.resolve("does-not-exist"));
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Guards against the set being widened by accident. */
    @Test
    void ownerOnlyMeansExactlyTwoPermissions(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("exact.bin");
        SensitiveFiles.createOwnerOnly(file);

        assumeTrue(posix(file), "POSIX permissions unsupported here");
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(file);
        assertThat(actual).hasSize(2);
    }
}
