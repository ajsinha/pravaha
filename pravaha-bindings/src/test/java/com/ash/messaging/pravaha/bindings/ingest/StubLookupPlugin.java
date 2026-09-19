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

import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;

/** A dimension table on the classpath, so discovery has something to discover. */
public final class StubLookupPlugin implements LookupSourcePlugin {

    static final StreamSchema SCHEMA = StreamSchema.builder("users")
            .field("user_id", Types.int64())
            .field("segment", Types.string())
            .build();

    /** What the plugin was configured with, so a test can prove the options reached it. */
    static volatile Map<String, String> lastConfig = Map.of();

    static volatile int opens;

    @Override
    public String name() {
        return "stub-lookup";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        lastConfig = Map.copyOf(context.config());
    }

    @Override
    public void open() {
        opens++;
    }

    @Override
    public StreamSchema schema() {
        return SCHEMA;
    }

    @Override
    public List<String> keyColumns() {
        return List.of("user_id");
    }

    @Override
    public int lookup(Object[] key, PartitionReader.RecordSink sink) {
        sink.beginRow()
                .setLong(0, ((Number) key[0]).longValue())
                .setString(1, "gold")
                .commit();
        return 1;
    }

    @Override
    public void close() {}
}
