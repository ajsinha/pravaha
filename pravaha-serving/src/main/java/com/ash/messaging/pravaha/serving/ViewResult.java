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
package com.ash.messaging.pravaha.serving;

import java.util.Optional;

/**
 * An answer, and how much to trust it.
 *
 * <p>Design section 17.3: staleness comes back <em>with</em> the answer. Every streaming system is
 * eventually consistent; the difference is whether an application can find out by how much. Here it
 * can, so it can decide -- rather than guessing, or assuming freshness it has no basis for.
 *
 * @param values the row, or empty if the key is not in the view
 * @param logicalFrontier the input position this answer reflects
 * @param stalenessNanos how far behind the view's newest work this answer is. Zero for a read that
 *     saw everything the view had
 * @param frontierComplete whether the answer is as of a committed frontier. False means it may
 *     include work that has not been committed and could still be revised
 */
public record ViewResult(
        Optional<Object[]> values, long logicalFrontier, long stalenessNanos, boolean frontierComplete) {

    /** Whether the key was present at all. */
    public boolean found() {
        return values.isPresent();
    }

    /** A miss, still carrying the position it was a miss as of -- which is the useful part. */
    static ViewResult missing(long frontier, long stalenessNanos, boolean complete) {
        return new ViewResult(Optional.empty(), frontier, stalenessNanos, complete);
    }
}
