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

/**
 * Where a registered query is in its life.
 *
 * <p>Deliberately small. Every state here is one an operator can act on differently: a paused query
 * is one somebody stopped, a failed query is one that stopped itself, and telling them apart is the
 * difference between "resume it" and "find out why".
 */
public enum QueryState {

    /** Registered and maintaining its view. */
    RUNNING,

    /**
     * Stopped by request, state retained.
     *
     * <p>The view keeps answering reads at the frontier it reached. That is the point of pausing
     * rather than dropping: the answers stay available and stop advancing, which is a far better
     * failure mode for a dashboard than answers that disappear.
     */
    PAUSED,

    /** Stopped by an error. Terminal: see {@link RegisteredQuery#failure()} for the cause. */
    FAILED,

    /** Dropped. Terminal, and its state has been released. */
    DROPPED;

    public boolean isTerminal() {
        return this == FAILED || this == DROPPED;
    }
}
