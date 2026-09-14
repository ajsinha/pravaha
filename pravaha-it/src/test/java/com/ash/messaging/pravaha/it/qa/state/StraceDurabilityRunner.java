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

import java.nio.file.Path;
import java.util.Map;

import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

/**
 * A standalone process for STATE-039, launched under {@code strace} so the launching test can read
 * the syscalls of a real, freshly-started JVM rather than of the JVM running the test suite (which
 * cannot be attached to with {@code ptrace} in this sandbox).
 *
 * <p>{@code args[0]} is a directory to store a checkpoint into; {@code args[1]} is a journal file
 * path. Does exactly one {@code FileCheckpointStore.store} and one {@code RegistryJournal.recordDrop},
 * in that order, so a single trace can contrast the two.
 */
public final class StraceDurabilityRunner {

    private StraceDurabilityRunner() {}

    public static void main(String[] args) throws Exception {
        Path checkpointDir = Path.of(args[0]);
        Path journalFile = Path.of(args[1]);

        FileCheckpointStore store = new FileCheckpointStore(checkpointDir);
        store.store(new Checkpoint(1, 1L, Map.of(), Map.of("lane-0", new byte[4096])));

        new RegistryJournal(journalFile).recordDrop("x");
    }
}
