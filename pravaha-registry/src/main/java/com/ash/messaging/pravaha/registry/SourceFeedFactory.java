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
package com.ash.messaging.pravaha.registry;

import java.util.List;

import com.ash.messaging.pravaha.runtime.exec.QueryExecution;

/**
 * Opens a {@link SourceFeed} for a query that has just started.
 *
 * <p>Called once per computation rather than once per name: a query registered twice under two names
 * is one execution behind one fingerprint, and feeding it twice would double every row.
 */
@FunctionalInterface
public interface SourceFeedFactory {

    /** A factory that attaches nothing, which is the default and correct for an embedded engine. */
    SourceFeedFactory NONE = (queryName, execution, sourceStreams, afterDelivery, resumeFrom) -> SourceFeed.NONE;

    /**
     * Attaches data to this execution's inputs.
     *
     * @param sourceStreams the streams the query reads, in plan order
     * @param resumeFrom the source offsets a restored checkpoint recorded, keyed {@code
     *     partition-N} in the order the feed created its readers. Empty for a fresh registration.
     *     Restoring state without rewinding the sources double-counts every record between the
     *     checkpoint and the failure, which is the exact failure a checkpoint exists to prevent
     * @param afterDelivery run after rows have been handed over, to publish what the query has
     *     applied. Without it the rows arrive, the lanes process them, and every reader sees an
     *     empty view: a served view shows its committed frontier, and nothing on the ingest path
     *     moves that frontier. An end-to-end run reported five thousand rows in and zero rows out
     * @return a feed, or {@link SourceFeed#NONE} when nothing is bound to any of them. Never null:
     *     a registry that had to null-check every feed would eventually forget to
     */
    SourceFeed open(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            java.util.Map<String, String> resumeFrom);

    /**
     * Opens a feed that reads a bounded range of history before joining the live stream at the
     * positions the running version has reached (ADR-046, design section 16.1).
     *
     * <p>The seam is per partition and is in {@code plan}. Everything else is exactly {@link #open}:
     * the same pumps, the same backpressure, the same checkpointed offsets -- a backfill is a
     * routine capability rather than a special mode, which is what makes a blue/green replacement
     * possible at all.
     *
     * <p>Refused by default, because a factory that silently opened an ordinary feed instead would
     * start the new version from the present with empty state and report it caught up.
     */
    default SourceFeed openBackfill(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            java.util.Map<String, String> resumeFrom,
            com.ash.messaging.pravaha.backfill.BackfillPlan plan) {
        throw new com.ash.messaging.pravaha.api.PravahaException(
                com.ash.messaging.pravaha.backfill.BackfillErrors.SOURCE_UNSUPPORTED,
                "nothing here can read history for '" + queryName + "': this engine's rows are pushed in by "
                        + "its embedder rather than read from a bound source, so there is no history to "
                        + "replay and no live stream to splice onto. A replacement would start from empty "
                        + "state and call itself caught up.");
    }

    /**
     * Why a replacement's backfill cannot read {@code stream}, or empty when it can.
     *
     * <p>Asked before a replacement starts, so the refusal names the stream and the reason rather
     * than arriving as a backfill that never finishes. Refused by default: an unbound stream is fed
     * by hand and has no history anybody can replay.
     */
    default java.util.Optional<String> backfillRefusal(String stream) {
        return java.util.Optional.of("nothing is bound to '" + stream + "', so its rows are pushed in rather than "
                + "read from a source: there is no history to replay and no position to splice at");
    }

    /**
     * Whether rows from this stream's source can carry a negative weight -- a delete, or the old
     * half of an update.
     *
     * <p>Asked at registration, so {@code PRV-2041} can refuse an append-only sink for a query over
     * such a stream (HLP-3). False by default and for a stream nothing is bound to: rows pushed by
     * hand are the caller's to vouch for.
     */
    default boolean retracts(String stream) {
        return false;
    }

    /**
     * The plugin bound to this stream, when its source repeats rows -- delivers, in normal running, a
     * row it has already delivered without retracting the earlier copy.
     *
     * <p>Asked at registration, so {@code PRV-2042} can refuse a query whose answer depends on how
     * many times a row arrived (SCAN-1). Empty by default and for a stream nothing is bound to, as
     * {@link #retracts} is.
     */
    default java.util.Optional<String> repeatingSource(String stream) {
        return java.util.Optional.empty();
    }
}
