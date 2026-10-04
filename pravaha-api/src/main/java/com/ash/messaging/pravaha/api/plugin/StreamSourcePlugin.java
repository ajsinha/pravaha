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

import org.jspecify.annotations.Nullable;

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
    PartitionReader createReader(SourcePartition partition, @Nullable SourceOffset resumeFrom);

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
    default PartitionReader createReader(
            SourcePartition partition, @Nullable SourceOffset resumeFrom, ReadRequest request) {
        return createReader(partition, resumeFrom);
    }

    /**
     * What this source asks its store for when a reader is created with {@code request}, in words:
     * which filters it applies itself and which it leaves with the engine. Shown in a query's feed
     * description beside the offer, because a source may honour part of a request and "offered two
     * filters" does not say whether the store was asked for a key or read whole. Empty by default:
     * the description then reports the offer alone.
     */
    default String describePushdown(ReadRequest request) {
        return "";
    }

    /**
     * How often the engine asks {@link #partitions} again while a query reads this source, to pick
     * up partitions added since it began: a Kafka topic scaled out, say. {@link java.time.Duration#ZERO}, the
     * default, means the list read when the query registered is the list for as long as it runs.
     *
     * <p>A source that answers more than zero must return every partition it returned before, with
     * the same index, and new ones only with higher indexes. The engine opens each new one through
     * {@link #createReaderForNewPartition} and checkpoints its position like any other.
     */
    default java.time.Duration partitionRefreshInterval() {
        return java.time.Duration.ZERO;
    }

    /**
     * A reader for a partition that did not exist when the query began reading this source -- found
     * by a refresh, or on a restore whose checkpoint has no position for it. Such a partition has no
     * history the query chose to skip, so it is read from its first record, whatever the binding's
     * configured start position says. Defaults to {@link SourceOffset#BEGINNING}, which is right for
     * a source whose configured start is its beginning.
     */
    default PartitionReader createReaderForNewPartition(SourcePartition partition, ReadRequest request) {
        return createReader(partition, SourceOffset.BEGINNING, request);
    }

    /**
     * How this binding's positions are ordered, or {@code null} when they are not (ADR-054).
     *
     * <p>Non-null promises two things: {@link OrderedPositions#compare} orders any two positions this
     * plugin's readers hand out for one partition, and every reader {@link #createReader} returns is a
     * {@link BoundedPartitionReader}. Together they let one reader be shared by queries at different
     * positions, each receiving each record once and in order, even from a source that promises
     * exactly-once or order. {@code null}, the default, keeps each such query on a reader of its own.
     */
    default @Nullable OrderedPositions orderedPositions() {
        return null;
    }

    /**
     * Why a second reader of this binding cannot be opened beside a running one to replay from an
     * earlier position, in a sentence naming what stands in the way, or empty when it can.
     *
     * <p>A replacement's backfill and a debug fork each open readers of their own on a binding a
     * running query is already reading, and read from a position the running query has passed. A
     * source whose read is a single server-side consumer -- a PostgreSQL replication slot, which
     * streams to one connection at a time and keeps nothing before its confirmed position -- can do
     * neither, whatever its capabilities say about resuming one reader from its own checkpoint
     * (CDCREPL-1). Asked of a configured plugin, before {@code open}, so the refusal costs no
     * connection. Empty by default.
     *
     * <p>Present also means one consumer at all: the binding layer lets one query at a time read
     * such a binding and refuses a second, different query at registration ({@code PRV-8028},
     * CDCREPL-2), where it used to open a second reader that could only wait for the first.
     */
    default java.util.Optional<String> secondReaderRefusal() {
        return java.util.Optional.empty();
    }
}
