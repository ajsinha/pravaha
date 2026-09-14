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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Files that hold data rather than configuration.
 *
 * <p>Three files in this engine contain what customers would call their data, and all three were
 * created at whatever the process umask happened to be — which on most systems is world-readable.
 * The registry journal holds query text and the values clients filtered on, which are account
 * numbers and customer ids. Checkpoints hold serialised operator state, which is the aggregated
 * data itself. The dead-letter queue holds the raw bytes of every record that failed.
 *
 * <p>Two of the three carried a comment telling the operator to permission the file like data. An
 * instruction to somebody who may never read it is not a control; this is.
 *
 * <p>Best effort, deliberately. A POSIX permission cannot be set on every filesystem — Windows, and
 * some network mounts — and refusing to write at all there would trade a confidentiality problem for
 * an availability one. Where it cannot be applied it is reported at WARNING, so the gap is visible
 * rather than assumed closed.
 */
public final class SensitiveFiles {

    private static final System.Logger LOG = System.getLogger(SensitiveFiles.class.getName());

    /** Owner read and write, nothing for anybody else. */
    private static final String OWNER_ONLY = "rw-------";

    /** Owner read, write and traverse. A directory needs execute to be entered at all. */
    private static final String OWNER_ONLY_DIRECTORY = "rwx------";

    private SensitiveFiles() {}

    /**
     * Creates {@code file} if absent and narrows it to its owner.
     *
     * <p>Called before the first write rather than after, because a file that is world-readable for
     * the duration of one write has been world-readable.
     */
    public static void createOwnerOnly(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                narrow(parent, OWNER_ONLY_DIRECTORY);
            }
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
            narrow(file, OWNER_ONLY);
        } catch (IOException cannot) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "could not create " + file + " with owner-only permissions (" + cannot
                            + "). It holds data rather than configuration, so check its mode by hand.");
        }
    }

    /**
     * Removes every permission outside {@code mode}, and adds none.
     *
     * <p>It used to call {@code setPosixFilePermissions} with {@code mode} directly, which sets
     * permissions absolutely. That closes the hole it was written for -- a journal created at the
     * umask and therefore world-readable -- and on anything already tighter than {@code mode} it
     * does the opposite of its name. {@code createOwnerOnly} runs on every append and every
     * checkpoint, so an operator who {@code chmod 400}'d a journal to stop writes had it put back to
     * {@code 600} within milliseconds, and the registration that should have been refused was
     * accepted instead. The lock was not overridden by a decision; it was erased by a helper.
     *
     * <p>Intersecting with what is already there keeps the guarantee -- nothing outside {@code mode}
     * survives -- while leaving a deliberate restriction alone. A caller wanting to widen has to say
     * so somewhere that reads like widening.
     */
    private static void narrow(Path target, String mode) {
        if (!target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> ceiling = PosixFilePermissions.fromString(mode);
            java.util.Set<java.nio.file.attribute.PosixFilePermission> current = Files.getPosixFilePermissions(target);
            java.util.Set<java.nio.file.attribute.PosixFilePermission> narrowed =
                    java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
            narrowed.addAll(current);
            narrowed.retainAll(ceiling);
            if (narrowed.equals(current)) {
                return; // already at or inside the ceiling
            }
            Files.setPosixFilePermissions(target, narrowed);
        } catch (IOException | UnsupportedOperationException cannot) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "could not set " + mode + " on " + target + " (" + cannot
                            + "). It holds data rather than configuration, so check its mode by hand.");
        }
    }

    /**
     * Forces a directory's own entries to disk.
     *
     * <p>Renaming a file into place is atomic with respect to a reader, and is not durable until the
     * directory entry itself is on disk. Without this, a checkpoint that was written, fsynced and
     * renamed can still be absent after power loss — the file's contents survived and the name
     * pointing at them did not, which is the failure that looks like a checkpoint that was never
     * taken.
     *
     * <p>Not supported everywhere; where opening a directory is refused, that is not an error worth
     * failing a write over.
     */
    public static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException cannot) {
            LOG.log(System.Logger.Level.DEBUG, "could not fsync the directory " + directory + " (" + cannot + ")");
        }
    }
}
