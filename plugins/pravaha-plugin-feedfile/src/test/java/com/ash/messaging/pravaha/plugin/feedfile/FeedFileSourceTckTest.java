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
package com.ash.messaging.pravaha.plugin.feedfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The feed-file source, run against the conformance suite.
 *
 * <p>Configured with a completion marker and no archiving, which is the configuration that claims
 * exactly-once -- so the TCK's replay check is testing the claim this plugin actually makes, rather
 * than a weaker configuration where it would pass for free.
 */
class FeedFileSourceTckTest extends SourcePluginTck {

    private static final int RECORDS = 5;

    private final Path dir = createFixture();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static Path createFixture() {
        try {
            Path feed = Files.createTempDirectory("pravaha-feed-tck-");
            feed.toFile().deleteOnExit();
            StringBuilder content = new StringBuilder();
            for (int i = 1; i <= RECORDS; i++) {
                content.append(i).append(",row-").append(i).append('\n');
            }
            Files.writeString(feed.resolve("feed-01.csv"), content.toString());
            Files.writeString(feed.resolve("feed-01.csv.done"), "");
            return feed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected StreamSourcePlugin createPlugin() {
        FeedFileSourcePlugin plugin = new FeedFileSourcePlugin();
        plugin.configure(new Ctx(
                "tck",
                Map.of(
                        "dir", dir.toString(),
                        "schema", "id:INT64,name:STRING",
                        "stream", "tck",
                        "completion", "marker")));
        plugin.open();
        return plugin;
    }

    @Override
    protected String streamName() {
        return "tck";
    }

    @Override
    protected int expectedRecordCount() {
        return RECORDS;
    }

    @Override
    protected RowCollector newCollector(StreamSourcePlugin plugin) {
        return new FeedCollector(plugin.discoverSchemas().get(0));
    }
}
