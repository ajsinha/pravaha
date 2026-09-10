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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Placeholders: what they are, what they are not, and what they compile to (ADR-032).
 *
 * <p>The property worth proving is not that binding works. It is that a bound value and a written
 * literal produce the <em>same predicate</em>. If they produced different ones, the parameterised
 * form would be a second code path with its own bugs, and it would be the path that only shows up
 * in production, because tests are written with literals.
 */
class ParameterBindingTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static Predicate predicateOf(String sql, Object... values) {
        var rel = SqlPlanner.withStreams(SCHEMA).plan(sql);
        var plan = new PhysicalPlanBuilder().bind(BoundParameters.of(values)).build(rel);
        return find(plan);
    }

    private static Predicate find(com.ash.messaging.pravaha.runtime.plan.PhysicalOperator operator) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.FilterOperator filter) {
            return filter.predicate();
        }
        for (var input : operator.inputs()) {
            Predicate found = find(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aBoundValueCompilesToTheSamePredicateAsAWrittenLiteral() {
        Predicate bound = predicateOf("SELECT user_id FROM user_volume WHERE user_id = ?", "u1");
        Predicate written = predicateOf("SELECT user_id FROM user_volume WHERE user_id = 'u1'");

        // Not merely equivalent -- equal. One code path, one set of bugs, and the pushdown
        // negotiation cannot treat them differently because it cannot tell them apart.
        assertThat(bound).isEqualTo(written);
    }

    @Test
    void numbersBindTheSameWay() {
        assertThat(predicateOf("SELECT user_id FROM user_volume WHERE total > ?", 100L))
                .isEqualTo(predicateOf("SELECT user_id FROM user_volume WHERE total > 100"));
    }

    @Test
    void anIntBindsToABigintColumn() {
        // A caller who writes 100 rather than 100L should not have to care.
        assertThat(predicateOf("SELECT user_id FROM user_volume WHERE total > ?", 100))
                .isEqualTo(predicateOf("SELECT user_id FROM user_volume WHERE total > 100"));
    }

    @Test
    void severalPlaceholdersBindPositionally() {
        Predicate bound = predicateOf("SELECT user_id FROM user_volume WHERE user_id = ? AND total > ?", "u1", 100L);
        Predicate written = predicateOf("SELECT user_id FROM user_volume WHERE user_id = 'u1' AND total > 100");

        assertThat(bound).isEqualTo(written);
    }

    @Test
    void aValueBoundOnTheLeftBindsToo() {
        assertThat(predicateOf("SELECT user_id FROM user_volume WHERE ? < total", 100L))
                .isEqualTo(predicateOf("SELECT user_id FROM user_volume WHERE 100 < total"));
    }

    @Test
    void bindingNullFollowsThreeValuedLogicRatherThanGuessing() {
        // `tier = NULL` is UNKNOWN for every row, so the answer is empty. A client library that
        // silently rewrote this to IS NULL would be changing the meaning of a comparison, which is a
        // worse surprise than an empty result.
        Predicate bound = predicateOf("SELECT user_id FROM user_volume WHERE tier = ?", new Object[] {null});

        assertThat(bound).isInstanceOf(Predicate.False.class);
    }

    @Test
    void aMissingValueIsRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> predicateOf("SELECT user_id FROM user_volume WHERE user_id = ?"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2060");
    }

    @Test
    void aValueOfTheWrongTypeIsRefusedWithThePlaceholderNumber() {
        assertThatThrownBy(() -> predicateOf("SELECT user_id FROM user_volume WHERE total > ?", "not a number"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2062")
                // The placeholder number, because "a String was bound" without saying which one is
                // useless in a statement with six of them.
                .hasMessageContaining("?1");
    }

    @Test
    void theParameterTypesAreInferredFromContext() {
        var rel =
                SqlPlanner.withStreams(SCHEMA).plan("SELECT user_id FROM user_volume WHERE user_id = ? AND total > ?");
        ParameterMetadata metadata = ParameterMetadata.of(rel);

        // The caller declares nothing; the planner already knows, because the column says so.
        assertThat(metadata.count()).isEqualTo(2);
        assertThat(metadata.typeOf(0)).isEqualTo(TypeName.STRING);
        assertThat(metadata.typeOf(1)).isEqualTo(TypeName.INT64);
    }

    @Test
    void aStatementWithNoPlaceholdersNeedsNoValues() {
        var rel = SqlPlanner.withStreams(SCHEMA).plan("SELECT user_id FROM user_volume WHERE total > 100");

        assertThat(ParameterMetadata.of(rel).isEmpty()).isTrue();
    }

    @Test
    void aPlaceholderInTheSelectListIsRefused() {
        // A parameter selects rows. `SELECT total * ?` computes a different answer from the same
        // rows, which is a different query rather than a different binding of one.
        assertThatThrownBy(() ->
                        ParameterMetadata.of(SqlPlanner.withStreams(SCHEMA).plan("SELECT total * ? FROM user_volume")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2063")
                .hasMessageContaining("WHERE clause");
    }

    @Test
    void aPlaceholderInAHavingClauseIsAParameter() {
        // HAVING is a filter above the aggregate, so it selects rows and qualifies. This is not a
        // special case in the code -- it falls out of "a filter condition is where values go".
        ParameterMetadata metadata = ParameterMetadata.of(SqlPlanner.withStreams(SCHEMA)
                .plan("SELECT tier, SUM(total) FROM user_volume GROUP BY tier HAVING SUM(total) > ?"));

        assertThat(metadata.count()).isEqualTo(1);
    }

    @Test
    void aParameterisedWindowSizeIsRefused() {
        SqlPlanner planner = SqlPlanner.withStreams(StreamSchema.builder("events")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .build());

        // Two window sizes have no rows in common, so they cannot share a computation. Binding one
        // would create a query per size, and the first anyone would know of it is a memory alarm.
        // `INTERVAL ? MINUTE` is not valid SQL, so the parser refuses that spelling before we get a
        // say; `TUMBLE(event_time, ?)` is the form that reaches us, and it is refused here.
        assertThatThrownBy(() -> ParameterMetadata.of(planner.plan(
                        "SELECT user_id, SUM(amount) FROM events GROUP BY user_id, TUMBLE(event_time, ?)")))
                .isInstanceOf(PravahaException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("refused as a non-value position, or refused by the parser -- either is a refusal")
                        .containsAnyOf("PRV-2063", "PRV-2001", "PRV-2002"));
    }
}
