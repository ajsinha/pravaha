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

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexSubQuery;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * The one sentence this engine has about a correlated subquery, and the one test for being one.
 *
 * <p><strong>Finding X-7.</strong> {@link PhysicalPlanBuilder}'s refusal for a {@code Correlate}
 * node names the alternative -- {@code JOIN dim FOR SYSTEM_TIME AS OF <time>} -- and neither shape
 * a person actually writes reached it. A correlated {@code EXISTS} was refused by
 * {@link PredicateCompiler} as "cannot compile the expression (EXISTS)", and a correlated scalar
 * subquery in the select list by {@link ExpressionCompiler} as "function '$SCALAR_QUERY' is not
 * supported in a projection". Both arms are generic and both are reached first, so the best-written
 * refusal in the tree was unreachable from any query that deserved it.
 *
 * <p>Centralised rather than copied into three arms, so the three cannot drift: whichever of them
 * catches a given query, the person is told the same thing.
 */
final class CorrelatedSubqueries {

    private CorrelatedSubqueries() {}

    /**
     * True when {@code node} is a subquery that reads a column of the row around it.
     *
     * <p>Decided by asking Calcite which correlation variables the subquery's own tree uses, rather
     * than by looking for {@code $cor} in the rendered text: the rendering is a debugging aid and
     * has changed spelling between Calcite versions.
     */
    static boolean isCorrelated(RexNode node) {
        return node instanceof RexSubQuery sub
                && !RelOptUtil.getVariablesUsed(sub.rel).isEmpty();
    }

    /** The refusal, worded once. */
    static PravahaException refusal(RexNode node) {
        return new PravahaException(
                SqlErrors.UNSUPPORTED_OPERATOR,
                "'" + shortly(node) + "' is a correlated subquery: it reads a column of the row around it, so "
                        + "it is one query per row rather than one computation kept up to date. The only "
                        + "correlated form Pravaha runs is a join against a lookup table, written as "
                        + "'JOIN dim FOR SYSTEM_TIME AS OF <time>'. An uncorrelated subquery -- one that names "
                        + "no column of the outer row -- is a separate question with its own answer; register "
                        + "it as its own continuous query and join the two. "
                        + "See docs/guides/CONTINUOUS_QUERIES.md for what this engine executes and what it refuses.");
    }

    /** One line, so a refusal about a clause does not print a whole relational tree. */
    private static String shortly(RexNode node) {
        String text = node.toString().replace('\n', ' ').replaceAll(" +", " ").strip();
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
