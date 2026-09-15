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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A FLOAT64 literal has to survive being compiled into a predicate.
 *
 * <p>TY-13. {@code WHERE f64 = 1.7976931348623157E308} and the equivalent {@code >=} both returned
 * zero rows against a row holding exactly that value. The finding reproduced it repeatedly and never
 * isolated it — "root cause not yet isolated to a specific source line".
 *
 * <p><strong>Both forms returning nothing is the clue.</strong> If the compiled literal were
 * {@code Double.MAX_VALUE}, {@code >=} would match even if {@code =} somehow did not. Both failing
 * says the literal is larger than any finite double — that it became {@code Infinity}. So this
 * asserts the compiled constant rather than the query result: a row count of zero is the same
 * observation for three different causes, and the number in the predicate tells them apart.
 */
class DoubleLiteralTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("num")
                .field("id", Types.int64())
                .field("f64", Types.float64().withNullable(true))
                .build();
    }

    /** The literal as the engine actually compiled it. */
    private static double compiledLiteral(String sql) {
        PhysicalOperator root =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(sql));
        PhysicalOperator node = root;
        while (node != null && !(node instanceof FilterOperator)) {
            node = node.inputs().isEmpty() ? null : node.inputs().get(0);
        }
        assertThat(node).as("the plan should contain a filter for %s", sql).isNotNull();
        Predicate predicate = ((FilterOperator) node).predicate();
        // Two shapes, because the planner does not always fold a literal comparison the same way:
        // 1.7976931348623157E308 arrives as CompareDouble and 1.5 as CompareExpressions over a
        // Literal. Both carry the compiled double, and TY-13 is about that number rather than about
        // which node holds it -- a test that accepted only one shape would report a plan difference
        // as a literal bug.
        if (predicate instanceof Predicate.CompareDouble compare) {
            return compare.value();
        }
        if (predicate instanceof Predicate.CompareExpressions compare
                && compare.right() instanceof Expression.Literal literal) {
            return literal.doubleValue();
        }
        throw new AssertionError("no double literal in the compiled predicate: " + predicate);
    }

    @Test
    void theLargestFiniteDoubleSurvivesBeingCompiled() {
        assertThat(compiledLiteral("SELECT id FROM num WHERE f64 = 1.7976931348623157E308"))
                .as("if this is Infinity, every comparison against it is false and the query returns "
                        + "zero rows under exit 0 — which is what TY-13 observed")
                .isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void theGreaterOrEqualFormCompilesToTheSameLiteral() {
        assertThat(compiledLiteral("SELECT id FROM num WHERE f64 >= 1.7976931348623157E308"))
                .isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void theSmallestSubnormalSurvivesToo() {
        // The control the finding already established as working: every other FLOAT64 comparison
        // tested was correct, including this one. If it fails here the cause is broader than TY-13.
        assertThat(compiledLiteral("SELECT id FROM num WHERE f64 = 4.9E-324")).isEqualTo(Double.MIN_VALUE);
    }

    @Test
    void anOrdinaryDoubleIsUnaffected() {
        // The control: a value nowhere near the representable edge must be untouched by the fix.
        assertThat(compiledLiteral("SELECT id FROM num WHERE f64 = 1.5")).isEqualTo(1.5d);
    }
}
