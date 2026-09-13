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
}
