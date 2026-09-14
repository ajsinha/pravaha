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
 * A standalone process for STATE-065, launched under {@code strace} for the same reason
 * {@link StraceDurabilityRunner} is: {@code ptrace} attach is refused in this sandbox, so a fresh
 * process is launched under strace directly rather than attached to after the fact.
 *
 * <p>{@code args[0]} is the journal file. Does exactly one {@code recordRegistration}.
 */
public final class StraceJournalRunner {

    private StraceJournalRunner() {}

    public static void main(String[] args) throws Exception {
        Path journalFile = Path.of(args[0]);
        new RegistryJournal(journalFile)
                .recordRegistration(
                        "q", "SELECT user_id, amount FROM txn", List.of(0), "dana", Retention.DEFAULT, List.of());
    }
}
