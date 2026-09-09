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
package com.ash.messaging.pravaha.plugin.filesystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The filesystem source, run against the conformance suite.
 *
 * <p>The reference plugin is also the reference TCK subject: if a change to the SPI breaks the
 * contract, this is where it surfaces first, before any third-party connector has to discover it.
 */
class FilesystemSourceTckTest extends SourcePluginTck {

    private static final String SCHEMA = "id:INT64,name:STRING";
    private static final int RECORDS = 5;

    private final Path fixture = createFixture();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static Path createFixture() {
        try {
            Path file = Files.createTempFile("pravaha-tck-", ".csv");
            file.toFile().deleteOnExit();
            StringBuilder content = new StringBuilder();
            for (int i = 1; i <= RECORDS; i++) {
                content.append(i).append(",row-").append(i).append('\n');
            }
            Files.writeString(file, content.toString());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected StreamSourcePlugin createPlugin() {
        FilesystemSourcePlugin plugin = new FilesystemSourcePlugin();
        plugin.configure(new Ctx("tck", Map.of("path", fixture.toString(), "schema", SCHEMA)));
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
        StreamSchema schema = plugin.discoverSchemas().get(0);
        return new ArenaCollector(schema);
    }

    /** Collects rows into an arena, the way a lane does. */
    private static final class ArenaCollector implements RowCollector {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 64);
        private final BinaryRowWriter writer;
        private final List<RowView> rows = new ArrayList<>();

        ArenaCollector(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
        }

        @Override
        public RowWriter beginRow() {
            long handle = arena.allocate(layout.rowSize(256));
            if (handle == ArenaHandle.NULL) {
                throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "TCK arena exhausted");
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            return new CollectingWriter(
                    writer,
                    () -> rows.add(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))));
        }

        @Override
        public List<RowView> rows() {
            return rows;
        }

        @Override
        public void close() {
            arena.close();
        }
    }
}
