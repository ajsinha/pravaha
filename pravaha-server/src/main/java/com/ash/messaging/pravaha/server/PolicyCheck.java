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
package com.ash.messaging.pravaha.server;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.catalog.CatalogErrors;
import com.ash.messaging.pravaha.catalog.CatalogObject;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.catalog.PolicyDefinition;
import com.ash.messaging.pravaha.catalog.PolicyExpression;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.sql.plan.NarrowingPlan;

/**
 * Checks a row filter or mask against the stream or view it is being bound to, before the binding is
 * recorded (ADR-059 §4): the catalogue module knows neither schemas nor the planner, and this node knows
 * both.
 *
 * <p>The expression is planned as {@link PolicyExpression#probe()} spells it -- every claim {@code '0'},
 * every membership FALSE -- over the object's own columns: a filter naming a column the object does not
 * carry, a filter true for every row, a mask whose column is missing or whose value changes its type,
 * are each refused with {@code PRV-7038} here rather than at every read afterwards.
 */
final class PolicyCheck {

    private final QueryRegistry registry;
    private final StreamCatalog streams;

    PolicyCheck(QueryRegistry registry, StreamCatalog streams) {
        this.registry = registry;
        this.streams = streams;
    }

    void check(PolicyDefinition policy, CatalogObject target) {
        StreamSchema schema = schemaOf(target)
                .orElseThrow(() -> invalid(policy, target, "the node has no columns for it to check against"));
        String probe = policy.parsed().probe();
        Narrowing narrowing;
        if (policy.type() == PolicyDefinition.Type.MASK) {
            boolean present = schema.fields().stream().anyMatch(f -> f.name().equalsIgnoreCase(policy.column()));
            if (!present) {
                throw invalid(policy, target, "it has no column " + policy.column() + " to mask");
            }
            narrowing = new Narrowing(Optional.empty(), Map.of(policy.column(), probe), List.of());
        } else {
            narrowing = new Narrowing(Optional.of(probe), Map.of(), List.of());
        }
        try {
            NarrowingPlan.compile(schema, narrowing);
        } catch (PravahaException e) {
            if (e.errorCode().number() == CatalogErrors.POLICY_INVALID.number()) {
                throw e;
            }
            throw invalid(policy, target, e.getMessage());
        }
    }

    private Optional<StreamSchema> schemaOf(CatalogObject target) {
        if (target.kind() == ObjectKind.VIEW) {
            return registry.find(target.engineName()).map(RegisteredQuery::outputSchema);
        }
        return streams.find(target.engineName());
    }

    private static PravahaException invalid(PolicyDefinition policy, CatalogObject target, String why) {
        return new PravahaException(
                CatalogErrors.POLICY_INVALID,
                policy.type().words() + " " + policy.fullName() + " cannot be bound to " + target.fullName() + ": "
                        + why);
    }
}
