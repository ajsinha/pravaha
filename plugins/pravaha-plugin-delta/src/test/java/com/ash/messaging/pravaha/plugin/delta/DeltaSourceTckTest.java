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
package com.ash.messaging.pravaha.plugin.delta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The Delta source, run against the conformance suite.
 *
 * <p>This is the first connector with a real external dependency to take the TCK, which is the point
 * of it: the filesystem plugin proves the suite is satisfiable by something with no store behind it,
 * and a connector built on somebody else's library is where a declaration and its behaviour actually
 * drift apart.
 */
class DeltaSourceTckTest extends SourcePluginTck {

    private static final int RECORDS = 5;

    private final Path table = createFixture();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static Path createFixture() {
        try {
            Path dir = Files.createTempDirectory("pravaha-delta-tck-");
            dir.toFile().deleteOnExit();
            Path tablePath = dir.resolve("tck");
            DeltaTableFixture.withRows(tablePath.toString(), 1, RECORDS, "row");
            return tablePath;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected StreamSourcePlugin createPlugin() {
        DeltaSourcePlugin plugin = new DeltaSourcePlugin();
        plugin.configure(new Ctx("tck", Map.of("path", table.toString(), "stream", "tck")));
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
        return new DeltaCollector(plugin.discoverSchemas().get(0));
    }
}
