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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;

/**
 * A source that declares filter pushdown and records what it was offered.
 *
 * <p>Exists because the question "does the server path offer a source the query's predicates" had no
 * test that could answer it, and the answer was no for as long as the server path existed. Pushdown
 * was exercised through {@code pravaha run} and through plugin tests calling the three-argument
 * {@code createReader} directly -- neither of which is how a deployment reads anything.
 *
 * <p>Records rather than filters. What this asserts is the offer, not a plugin's handling of it;
 * the handling is each plugin's own test, and a fake that filtered would only be testing itself.
 */
public final class RecordingPushdownPlugin implements StreamSourcePlugin {

    /** Static because ServiceLoader constructs the instance, so a test cannot hold a reference. */
    static final List<ReadRequest> OFFERED = new CopyOnWriteArrayList<>();

    static final StreamSchema SCHEMA = StreamSchema.builder("pushed")
            .field("id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    @Override
    public String name() {
        return "recording-pushdown";
    }

    @Override
    public void configure(PluginContext context) {}

    @Override
    public com.ash.messaging.pravaha.api.plugin.Version version() {
        return new com.ash.messaging.pravaha.api.plugin.Version(0, 1, 0);
    }

    @Override
    public void open() {}

    @Override
    public void close() {}

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                true,
                true,
                false,
                false,
                DeliveryGuarantee.AT_LEAST_ONCE,
                EnumSet.of(PushdownKind.FILTER),
                java.time.Duration.ofMillis(1));
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(SCHEMA);
    }

    @Override
    public List<SourcePartition> partitions(String streamName) {
        return List.of(new SourcePartition(streamName, 0, Map.of()));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        return createReader(partition, resumeFrom, ReadRequest.NOTHING);
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom, ReadRequest request) {
        OFFERED.add(request);
        return new PartitionReader() {
            @Override
            public int poll(PartitionReader.RecordSink sink, int maxRecords) {
                return 0;
            }

            @Override
            public SourceOffset position() {
                return SourceOffset.BEGINNING;
            }

            @Override
            public void pause() {}

            @Override
            public void resume() {}

            @Override
            public void close() {}
        };
    }

    /** What a store would be asked for, as a source that honours part of an offer says it. */
    @Override
    public String describePushdown(ReadRequest request) {
        return "the store applies " + request.filters().size() + " of them";
    }

    /** What this plugin was offered, oldest first. */
    static List<ReadRequest> offered() {
        return new ArrayList<>(OFFERED);
    }
}
