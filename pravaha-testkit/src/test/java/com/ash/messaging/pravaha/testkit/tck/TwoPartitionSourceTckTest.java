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
package com.ash.messaging.pravaha.testkit.tck;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * TCKPARTS-1: the source TCK passes a correct exactly-once source whose records are split over two
 * partitions -- two in partition 0, three in partition 1. The replay case resumed partition 0 and
 * expected the rest of the whole fixture, so this source failed it.
 */
class TwoPartitionSourceTckTest extends SourcePluginTck {

    private static final StreamSchema SCHEMA =
            StreamSchema.builder("numbers").field("n", Types.int64()).build();

    /** Partition index to its records. */
    private static final long[][] RECORDS = {{1, 2}, {3, 4, 5}};

    @Override
    protected StreamSourcePlugin createPlugin() {
        return new TwoPartitions();
    }

    @Override
    protected String streamName() {
        return "numbers";
    }

    @Override
    protected int expectedRecordCount() {
        return 5;
    }

    private static final class TwoPartitions implements StreamSourcePlugin {

        @Override
        public String name() {
            return "two-partitions";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

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
                    DeliveryGuarantee.EXACTLY_ONCE,
                    EnumSet.noneOf(PushdownKind.class),
                    Duration.ofMillis(1));
        }

        @Override
        public List<StreamSchema> discoverSchemas() {
            return List.of(SCHEMA);
        }

        @Override
        public List<SourcePartition> partitions(String streamName) {
            return List.of(SourcePartition.of(streamName, 0), SourcePartition.of(streamName, 1));
        }

        @Override
        public PartitionReader createReader(SourcePartition partition, @Nullable SourceOffset resumeFrom) {
            long[] records = RECORDS[partition.index()];
            int start = resumeFrom == null || resumeFrom.isBeginning() ? 0 : Integer.parseInt(resumeFrom.token());
            return new PartitionReader() {
                private int next = start;
                private boolean paused;

                @Override
                public int poll(RecordSink sink, int maxRecords) {
                    if (paused) {
                        return 0;
                    }
                    int produced = 0;
                    while (produced < maxRecords && next < records.length) {
                        sink.beginRow().setLong(0, records[next]).weight(1L).commit();
                        next++;
                        produced++;
                    }
                    return produced;
                }

                @Override
                public SourceOffset position() {
                    return new SourceOffset(Integer.toString(next));
                }

                @Override
                public void pause() {
                    paused = true;
                }

                @Override
                public void resume() {
                    paused = false;
                }

                @Override
                public void close() {}
            };
        }
    }
}
