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
package com.ash.messaging.pravaha.api.plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a source that knows nothing of partitions added later gets: a fixed list, and a reader for a
 * partition the engine calls new that starts at the beginning.
 */
class StreamSourcePluginDefaultsTest {

    /** Records every reader it is asked for. */
    private static final class Fixed implements StreamSourcePlugin {

        final List<String> asked = new ArrayList<>();

        @Override
        public SourceCapabilities capabilities() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public List<StreamSchema> discoverSchemas() {
            return List.of();
        }

        @Override
        public List<SourcePartition> partitions(String streamName) {
            return List.of(new SourcePartition(streamName, 0, java.util.Map.of()));
        }

        @Override
        public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
            asked.add(partition.index() + " from " + resumeFrom);
            return null;
        }

        @Override
        public String name() {
            return "fixed";
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
        public HealthStatus health() {
            return HealthStatus.unhealthy("not needed");
        }

        @Override
        public void close() {}
    }

    @Test
    void theListIsFixedAndANewPartitionIsReadFromItsBeginning() {
        Fixed source = new Fixed();

        assertThat(source.partitionRefreshInterval())
                .as("zero: the engine never asks again")
                .isEqualTo(Duration.ZERO);
        source.createReaderForNewPartition(new SourcePartition("s", 3, java.util.Map.of()), ReadRequest.NOTHING);

        assertThat(source.asked).containsExactly("3 from SourceOffset[beginning]");
    }
}
