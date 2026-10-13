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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.runtime.plan.LookupJoinOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * Which registered queries use a declared stream, dimension table or sink (ADR-064).
 *
 * <p>Asked before a configuration file's change or removal is applied: an object a query uses is not
 * changed or removed out from under it. Taken from each query's plan, as {@link PlanSources} is, so a
 * stream the SQL names and the planner optimised away is not counted, and a lookup is counted only where
 * the plan really joins it.
 */
final class DeclarationUses {

    private DeclarationUses() {}

    /** The names of the registered queries using {@code name} as {@code use}, in registration order. */
    static List<String> of(
            QueryRegistry.Use use,
            String name,
            Map<String, RegisteredQuery> byName,
            Map<String, SinkDelivery> deliveries) {
        List<String> users = new ArrayList<>();
        byName.forEach((query, registered) -> {
            boolean uses =
                    switch (use) {
                        case STREAM -> registered.sourceStreams().contains(name);
                        case LOOKUP -> joins(registered.plan(), name);
                        case SINK -> {
                            SinkDelivery delivery = deliveries.get(query);
                            yield delivery != null && delivery.sinkName().equals(name);
                        }
                    };
            if (uses) {
                users.add(query);
            }
        });
        return users;
    }

    private static boolean joins(PhysicalOperator operator, String lookup) {
        if (operator instanceof LookupJoinOperator join && join.lookupStream().equals(lookup)) {
            return true;
        }
        return operator.inputs().stream().anyMatch(input -> joins(input, lookup));
    }
}
