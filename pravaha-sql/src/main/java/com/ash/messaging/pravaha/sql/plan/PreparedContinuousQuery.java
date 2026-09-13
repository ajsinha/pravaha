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
package com.ash.messaging.pravaha.sql.plan;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;

/**
 * A continuous query, planned once: the operator tree and what its parameters cost.
 *
 * <p>This exists so that nothing outside {@code pravaha-sql} has to name a Calcite type. The plan IR
 * is the contract between the SQL front end and everything else, and Calcite must not reach the
 * runtime or the registry -- a rule the build enforces, and one worth keeping because it is what
 * allows the front end to be replaced without touching an operator.
 *
 * @param plan the physical plan, with any bound parameters already substituted
 * @param placements where each {@code ?} had to be applied and what that costs (ADR-032)
 */
public record PreparedContinuousQuery(PhysicalOperator plan, List<ParameterPlacement> placements) {

    public PreparedContinuousQuery {
        placements = List.copyOf(placements);
    }

    /** Plans {@code sql} over {@code streams}, binding {@code parameters} into it. */
    public static PreparedContinuousQuery of(String sql, BoundParameters parameters, StreamSchema... streams) {
        return of(sql, parameters, java.util.List.of(streams), java.util.List.of());
    }

    /**
     * Plans {@code sql} over consumed streams and dimension tables.
     *
     * <p>The distinction is the planner's, not decoration: a schema registered as a stream is
     * something the query reads and advances event time from, and one registered as a lookup is a
     * table it joins against per record. Registering a dimension as a stream does not fail -- it
     * plans as a stream-to-stream join and waits for rows that a dimension table never sends. That
     * is exactly what every registration did, because the one caller of the lookup-aware planner in
     * shipped code did not exist.
     */
    public static PreparedContinuousQuery of(
            String sql,
            BoundParameters parameters,
            java.util.List<StreamSchema> streams,
            java.util.List<StreamSchema> lookups) {
        var logical = plannerFor(streams, lookups).plan(sql);
        return new PreparedContinuousQuery(
                new PhysicalPlanBuilder().bind(parameters).build(logical), ParameterPlacement.of(logical));
    }

    private static SqlPlanner plannerFor(java.util.List<StreamSchema> streams, java.util.List<StreamSchema> lookups) {
        if (lookups.isEmpty()) {
            return SqlPlanner.withStreams(streams.toArray(new StreamSchema[0]));
        }
        com.ash.messaging.pravaha.sql.PravahaSchema catalog = new com.ash.messaging.pravaha.sql.PravahaSchema();
        for (StreamSchema stream : streams) {
            catalog.register(stream);
        }
        for (StreamSchema lookup : lookups) {
            catalog.registerLookup(lookup);
        }
        return new SqlPlanner(catalog);
    }

    /** Classifies a query's parameters without building anything runnable. */
    public static List<ParameterPlacement> classify(String sql, StreamSchema... streams) {
        return ParameterPlacement.of(SqlPlanner.withStreams(streams).plan(sql));
    }

    /** True when every parameter could be a tap filter, so one computation would serve all of them. */
    public boolean everyParameterIsTappable() {
        return ParameterPlacement.allTappable(placements);
    }
}
