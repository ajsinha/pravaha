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
    SourceFeedFactory NONE = (queryName, execution, sourceStreams) -> SourceFeed.NONE;

    /**
     * Attaches data to this execution's inputs.
     *
     * @param sourceStreams the streams the query reads, in plan order
     * @return a feed, or {@link SourceFeed#NONE} when nothing is bound to any of them. Never null:
     *     a registry that had to null-check every feed would eventually forget to
     */
    SourceFeed open(String queryName, QueryExecution execution, List<String> sourceStreams);
}
