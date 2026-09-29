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
package com.ash.messaging.pravaha.bindings.ingest;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.runtime.dlq.DeadLetterFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060: a view another tenant registered before names were per tenant takes its dead letters to
 * its engine name at recovery, so a default-tenant view later registered under the same bare name is
 * never shown them.
 */
class DeadLetterRenameTest {

    @TempDir
    Path directory;

    @Test
    void aRenamedQuerysQueueGoesWithItAndNothingIsOverwritten() throws Exception {
        Files.writeString(DeadLetterFiles.letters(directory, "orders"), "{\"raw\":\"acme's\"}\n");
        Files.writeString(DeadLetterFiles.replays(directory, "orders"), "r\n");
        Files.writeString(DeadLetterFiles.letters(directory, "totals"), "old\n");
        Files.writeString(DeadLetterFiles.letters(directory, "acme.default.totals"), "new\n");
        PluginSourceFeeds feeds = new PluginSourceFeeds().deadLetteringTo(directory);

        feeds.renamed("orders", "acme.default.orders");
        feeds.renamed("totals", "acme.default.totals");

        assertThat(DeadLetterFiles.letters(directory, "orders")).doesNotExist();
        assertThat(DeadLetterFiles.letters(directory, "acme.default.orders")).hasContent("{\"raw\":\"acme's\"}");
        assertThat(DeadLetterFiles.replays(directory, "acme.default.orders")).hasContent("r");
        assertThat(DeadLetterFiles.letters(directory, "acme.default.totals"))
                .as("a queue the new name already has is not overwritten")
                .hasContent("new");
    }

    @Test
    void withNoDeadLetterDirectoryThereIsNothingToMove() {
        new PluginSourceFeeds().renamed("orders", "acme.default.orders");
    }
}
