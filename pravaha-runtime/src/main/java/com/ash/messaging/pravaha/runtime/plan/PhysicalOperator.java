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
package com.ash.messaging.pravaha.runtime.plan;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A node in Pravaha's own plan.
 *
 * <p>The boundary between Calcite and the engine (ADR-002). Above it, Calcite parses, validates and
 * optimises; below it, nothing knows Calcite exists. That separation is what lets the runtime be
 * allocation-free and what stops Calcite's thirty transitive dependencies reaching the engine core
 * -- {@code pravaha-runtime} does not depend on {@code pravaha-sql} at all.
 *
 * <p>Sealed, so the code generator and the interpreted fallback both switch over the full set and
 * the compiler flags any operator either of them forgets. Adding an operator without handling it
 * everywhere becomes a build failure rather than a runtime surprise in a customer's query.
 */
public sealed interface PhysicalOperator
        permits ScanOperator,
                FilterOperator,
                ProjectOperator,
                ComputeOperator,
                AggregateOperator,
                WindowAssignOperator,
                WindowedAggregateOperator,
                JoinOperator,
                LookupJoinOperator,
                TopNOperator,
                SinkOperator {

    /** The shape of rows this operator emits. */
    StreamSchema outputSchema();

    /** Upstream operators. Empty for a scan. */
    List<PhysicalOperator> inputs();

    /** A short label for plan rendering and metrics. */
    String label();

    /**
     * Everything about this operator that can change what it answers, and nothing a person needs to
     * read: what two computations must agree on to be the same computation.
     *
     * <p>Separate from {@link #label()} because the label is for a reader and summarises. The query
     * fingerprint used to be the plans' labels, hashed, and so it inherited every summary: a join's
     * time bound, INNER against LEFT, which column a projected name came from and which function an
     * aggregate applied were all absent, and two queries differing only in one of them shared one
     * computation -- the second registration read the first one's answer, with nothing to say so.
     * An identity names columns by ordinal, because after a join two input columns can share a name.
     */
    default String identity() {
        return label();
    }

    /** Whether this operator keeps state, and so needs a bound (design section 9.6). */
    default boolean isStateful() {
        return false;
    }
}
