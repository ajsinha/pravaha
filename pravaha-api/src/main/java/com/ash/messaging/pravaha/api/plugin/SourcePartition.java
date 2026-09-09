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

import java.util.Map;
import java.util.Objects;

/**
 * One independently-consumable slice of a source.
 *
 * <p>{@code properties} carries whatever the plugin needs to reconstruct the partition after a
 * restart -- a Kafka topic-partition, an Aerospike partition range, a Cassandra token range. It is
 * opaque to the engine, which only ever routes it back to the plugin that produced it.
 */
public record SourcePartition(String streamName, int index, Map<String, String> properties) {

    public SourcePartition {
        Objects.requireNonNull(streamName, "streamName");
        if (index < 0) {
            throw new IllegalArgumentException("partition index must be non-negative, got " + index);
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static SourcePartition of(String streamName, int index) {
        return new SourcePartition(streamName, index, Map.of());
    }

    @Override
    public String toString() {
        return streamName + "[" + index + "]";
    }
}
