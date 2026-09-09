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

/**
 * What a source can absorb on the engine's behalf.
 *
 * <p>Capability negotiation rather than assumption: the planner pushes only what a plugin declares
 * it accepts, and anything untranslatable stays as a residual filter in the engine. Silently
 * dropping a predicate the store could not honour is the classic pushdown correctness bug, and
 * declaring capabilities is how it is made impossible (design section 5.1, FR-3).
 */
public enum PushdownKind {

    /** {@code WHERE} predicates. The single biggest systemic lever in the design. */
    FILTER,

    /** Column selection, which reduces both network bytes and decode cost. */
    PROJECT,

    /** Server-side partial aggregation, where the store supports it. */
    PARTIAL_AGGREGATE,

    /** Row limits, for bounded and snapshot queries. */
    LIMIT
}
