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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.ParameterPlacement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a continuous query's parameters have to be applied, and what that costs (ADR-032).
 *
 * <p>The most consequential thing in this codebase that looks harmless. On a request/response query
 * a parameter is bound and forgotten; on a continuous query it decides how many computations a
 * deployment runs, and the two cases are one word apart in the SQL.
 */
class ContinuousParameterTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("tier", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String KEEPS_THE_COLUMN = "SELECT user_id, amount FROM txn WHERE user_id = ?";

    private static final String AGGREGATES_IT_AWAY = "SELECT window_start, window_end, tier, SUM(amount) "
            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "WHERE user_id = ? GROUP BY window_start, window_end, tier";

    private ViewCatalog views;
    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, TXN);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void aParameterTheViewCarriesCanBeATapFilter() {
        List<ParameterPlacement> placements = registry.classify(KEEPS_THE_COLUMN);

        // user_id survives into the view, so every subscriber can filter on it and they all share
        // one computation. This is the free case.
        assertThat(placements).singleElement().satisfies(placement -> {
            assertThat(placement.column()).isEqualTo("user_id");
            assertThat(placement.placement()).isEqualTo(ParameterPlacement.Placement.TAP);
            assertThat(placement.reason()).contains("share");
        });
        assertThat(ParameterPlacement.allTappable(placements)).isTrue();
    }

    @Test
    void aParameterTheQueryAggregatesAwayHasToForkTheComputation() {
        List<ParameterPlacement> placements = registry.classify(AGGREGATES_IT_AWAY);

        // The query groups by tier, so user_id is gone from the view. Its rows already mix users,
        // and no filter applied afterwards separates them -- so the filter has to be in the query,
        // and each distinct user needs its own state.
        assertThat(placements).singleElement().satisfies(placement -> {
            assertThat(placement.column()).isEqualTo("user_id");
            assertThat(placement.placement()).isEqualTo(ParameterPlacement.Placement.REGISTRATION);
            assertThat(placement.reason()).contains("own computation");
        });
        assertThat(ParameterPlacement.allTappable(placements)).isFalse();
    }

    @Test
    void aRegistrationReportsWhatItsParametersCost() {
        RegisteredQuery query =
                registry.register("by_user", KEEPS_THE_COLUMN, List.of(0), DANA, BoundParameters.of("u1"));

        // Reported, not silent. An operator should be able to see that this registration did not
        // need to exist per user rather than learn it from a memory alarm.
        assertThat(query.parameterPlacements()).hasSize(1);
        assertThat(query.avoidableForks())
                .as("this one could have been a tap filter on a shared computation")
                .hasSize(1);
    }

    @Test
    void bindingDifferentValuesMakesDifferentComputations() {
        RegisteredQuery first = registry.register("u1", KEEPS_THE_COLUMN, List.of(0), DANA, BoundParameters.of("u1"));
        RegisteredQuery second = registry.register("u2", KEEPS_THE_COLUMN, List.of(0), DANA, BoundParameters.of("u2"));

        // Two computations, two copies of the state. That is the truth about what was asked for,
        // not a policy -- the bound values are in the plan and therefore in the fingerprint.
        assertThat(second).isNotSameAs(first);
        assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void bindingTheSameValueTwiceIsStillOneComputation() {
        RegisteredQuery first = registry.register("a", KEEPS_THE_COLUMN, List.of(0), DANA, BoundParameters.of("u1"));
        RegisteredQuery second = registry.register("b", KEEPS_THE_COLUMN, List.of(0), DANA, BoundParameters.of("u1"));

        assertThat(second).isSameAs(first);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void theSharedAlternativeReallyIsOneComputationForEverybody() {
        // The point of the whole classification: register the question once, without the parameter,
        // and let each subscriber filter at its tap.
        RegisteredQuery shared = registry.register("all_users", "SELECT user_id, amount FROM txn", List.of(0), DANA);

        assertThat(registry.size()).isEqualTo(1);
        // Ten desks, ten filters, one computation and one copy of the state.
        for (int i = 0; i < 10; i++) {
            SubscriptionFilter filter = SubscriptionFilter.matching(shared.outputSchema(), "user_id", "u" + i);
            assertThat(filter.isEmpty()).isFalse();
        }
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void aQueryWithNoParametersReportsNothing() {
        RegisteredQuery query = registry.register("plain", "SELECT user_id FROM txn", List.of(0), DANA);

        assertThat(query.parameterPlacements()).isEmpty();
        assertThat(query.avoidableForks()).isEmpty();
    }
}
