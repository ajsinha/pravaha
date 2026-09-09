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

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A source of change records.
 *
 * <p>Sources are split into independently-consumable partitions, each read by a
 * {@link PartitionReader}. That split is where the engine's parallelism comes from, so a source
 * that cannot partition caps the query at one lane -- worth knowing at registration rather than
 * discovering from a throughput graph.
 */
public interface StreamSourcePlugin extends PravahaPlugin {

    /** What this source can and cannot do. Determines the guarantee the engine may offer. */
    SourceCapabilities capabilities();

    /** Schema discovery (design FR-1). May inspect the store, or return a declared schema. */
    List<StreamSchema> discoverSchemas();

    /** The independently-consumable partitions of a stream. */
    List<SourcePartition> partitions(String streamName);

    /**
     * Creates a reader.
     *
     * @param resumeFrom where to resume, or {@code null} to start from the configured position
     */
    PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom);
}
