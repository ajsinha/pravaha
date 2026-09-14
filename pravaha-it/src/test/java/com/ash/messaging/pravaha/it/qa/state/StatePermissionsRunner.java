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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

/**
 * A standalone process for STATE-036/037, launched under a controlled {@code umask} (a per-process
 * attribute this JVM cannot change once started) via {@code bash -c 'umask 0022; exec ...'}.
 *
 * <p>{@code args[0]} is the checkpoint directory. Prints {@code DIR-BEFORE:<perm>} right after the
 * directory is created (by the {@code FileCheckpointStore} constructor, which does not narrow it),
 * then stores one checkpoint and prints {@code DIR-AFTER:<perm>} and {@code FILE:<perm>}.
 */
public final class StatePermissionsRunner {

    private StatePermissionsRunner() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        FileCheckpointStore store = new FileCheckpointStore(dir);
        System.out.println("DIR-BEFORE:" + perms(dir));

        store.store(new Checkpoint(1, 1L, Map.of(), Map.of("lane-0", new byte[16])));

        System.out.println("DIR-AFTER:" + perms(dir));
        System.out.println("FILE:" + perms(dir.resolve("checkpoint-1.bin")));
    }

    private static String perms(Path path) throws Exception {
        java.util.Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        return PosixFilePermissions.toString(perms);
    }
}
