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

    /**
     * Creates a reader, offering it work the engine would rather not do itself.
     *
     * <p>Defaults to ignoring the request, which is always correct: the engine applies every
     * predicate itself whatever a source does, so a plugin that overrides this changes how many
     * bytes cross the boundary and nothing else. That is the whole point -- rows that never arrive
     * cost nothing to filter.
     *
     * <p>A plugin that declares {@link PushdownKind#FILTER} should override this and honour what it
     * can. Honouring part of a request is fine and needs no announcement; what is never acceptable
     * is returning fewer rows than the filters allow, because the engine cannot tell the difference
     * between a row its source withheld and a row that was never there.
     */
    default PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom, ReadRequest request) {
        return createReader(partition, resumeFrom);
    }
}
