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
package com.ash.messaging.pravaha.sdk;

/**
 * What a read of a served view is allowed to see.
 *
 * <p>Declared per read rather than configured once, because the right answer differs by call within
 * one application: a dashboard tile wants the freshest number available, and a reconciliation job
 * wants a coherent one. Every response carries its own staleness, so the caller can decide whether
 * the answer is fresh enough rather than guessing (design section 17.3).
 */
public enum Consistency {

    /** Whatever the owning lane holds right now, including uncommitted work. Lowest latency. */
    LATEST,

    /**
     * As of the latest globally committed frontier.
     *
     * <p>The default, and the only mode under which reading two views returns numbers that
     * reconcile: both reflect exactly the same prefix of their shared input. Anything a person acts
     * on, or that joins views, wants this.
     */
    CONSISTENT,

    /** As of a supplied logical time, within checkpoint retention. For audit and reconciliation. */
    AS_OF,

    /** Blocks until the frontier reaches a supplied time, then reads. For read-your-writes. */
    AT_LEAST
}
