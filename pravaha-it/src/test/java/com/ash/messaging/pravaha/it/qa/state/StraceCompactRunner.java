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
import java.util.List;

import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.serving.Retention;

/**
 * A standalone process for STATE-092, launched under {@code strace -f} for the same reason
 * {@link StraceJournalRunner} is. {@code args[0]} is the journal file. Writes ten registrations, five
 * drops, replays, then compacts -- enough to observe the directory {@code fsync}
 * {@code SensitiveFiles.syncDirectory} does after the atomic rename.
 */
public final class StraceCompactRunner {

    private StraceCompactRunner() {}

    public static void main(String[] args) throws Exception {
        Path journalFile = Path.of(args[0]);
        RegistryJournal journal = new RegistryJournal(journalFile);
        for (int i = 0; i < 10; i++) {
            journal.recordRegistration(
                    "q" + i, "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
        }
        for (int i = 0; i < 5; i++) {
            journal.recordDrop("q" + i);
        }
        List<RegistryJournal.Entry> live = journal.replay();
        journal.compact(live);
    }
}
