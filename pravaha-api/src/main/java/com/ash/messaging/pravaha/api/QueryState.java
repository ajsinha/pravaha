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
package com.ash.messaging.pravaha.api;

/**
 * Where a registered query is in its lifecycle (design section 11.6).
 *
 * <p>Two of these are unusual and deliberate. {@link #DEGRADED} means the query is still running but
 * something it depends on is failing -- a plugin circuit is open, a sink is erroring -- and
 * collapsing that into RUNNING or FAILED would either hide a problem or stop work that is still
 * producing correct results. {@link #SCHEMA_CONFLICT} means a source's schema changed
 * incompatibly; the query keeps running against the version it was planned with rather than
 * silently changing meaning under an operator who has not been told.
 */
public enum QueryState {
    CREATED,
    VALIDATED,
    PLANNED,
    STARTING,
    RUNNING,
    /** Historical data is being loaded; results are partial and marked as such. */
    BACKFILLING,
    PAUSED,
    /** Running, but a dependency is failing. */
    DEGRADED,
    /** A source schema changed incompatibly; still running on the pinned version. */
    SCHEMA_CONFLICT,
    UPDATING,
    STOPPING,
    STOPPED,
    FAILED,
    DROPPED;

    public boolean isActive() {
        return this == RUNNING || this == BACKFILLING || this == DEGRADED || this == SCHEMA_CONFLICT;
    }

    public boolean isTerminal() {
        return this == STOPPED || this == FAILED || this == DROPPED;
    }
}
