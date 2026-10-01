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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexFieldCollation;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.sql.SqlKind;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.TopNOperator;
import com.ash.messaging.pravaha.sql.SqlErrors;
import com.ash.messaging.pravaha.sql.TypeMapping;

/**
 * {@code ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)} filtered to the first N, planned as a
 * {@link TopNOperator} (Nexmark q18, q19, q9).
 *
 * <p>Calcite plans the pattern as a filter over a projection that computes the number:
 *
 * <pre>
 * Filter(rn &lt;= N)
 *   Project($0, ..., ROW_NUMBER() OVER (PARTITION BY p ORDER BY o))
 * </pre>
 *
 * and this turns the pair into a top-N over the projection's input, a projection that puts the
 * columns in the order the query named them, and a filter for whatever else the {@code WHERE} said.
 *
 * <p><strong>Only the bounded form.</strong> {@code ROW_NUMBER} with no bound on the number is
 * refused: every arrival that sorts ahead of a row changes that row's number, so maintaining it
 * would retract and re-emit a whole partition per row -- correct, and never a thing anybody wants
 * running continuously. Every other {@code OVER} function, and a numbering with no {@code ORDER BY}
 * (which SQL leaves arbitrary), is refused by name too.
 */
final class TopNPlanner {

    /** Rows a top-N may hold across all its partitions before it is refused; see TopNOperator. */
    static final long MAX_ROWS = 1_000_000;

    private final PhysicalPlanBuilder builder;
    private final BoundParameters parameters;

    TopNPlanner(PhysicalPlanBuilder builder, BoundParameters parameters) {
        this.builder = builder;
        this.parameters = parameters;
    }

    /** Whether this filter sits over a projection that numbers rows. */
    static boolean ranks(Filter filter) {
        return filter.getInput() instanceof Project project && overOrdinal(project) >= 0;
    }

    /**
     * Refuses a projection that computes an {@code OVER} function without the bound a top-N needs.
     * Called for every projection; returns quietly when there is none.
     */
    static void refuseUnbounded(Project project) {
        int ordinal = overOrdinal(project);
        if (ordinal < 0) {
            return;
        }
        RexOver over = (RexOver) project.getProjects().get(ordinal);
        refuseAllButRowNumber(over);
        throw refusal("'" + over + "' numbers rows without a bound. ROW_NUMBER() OVER is maintained as a top-N: "
                + "filter it in an outer query -- SELECT * FROM (SELECT *, ROW_NUMBER() OVER (...) AS rn FROM t) "
                + "WHERE rn <= 10. Without the bound, every row that sorts ahead of another changes that "
                + "row's number, so one arrival would retract and re-emit its whole partition.");
    }

    PhysicalOperator build(Filter filter) {
        Project project = (Project) filter.getInput();
        int rankOrdinal = overOrdinal(project);
        RexOver over = (RexOver) project.getProjects().get(rankOrdinal);
        refuseAllButRowNumber(over);
        for (int i = 0; i < project.getProjects().size(); i++) {
            RexNode expression = project.getProjects().get(i);
            if (i != rankOrdinal && !(expression instanceof RexInputRef)) {
                throw refusal("the query that numbers rows also computes '" + expression + "'. A top-N is planned "
                        + "over columns as they are; compute the expression in an outer query, or in a "
                        + "subquery below the numbering.");
            }
        }

        List<RexNode> residual = new ArrayList<>();
        Long limit = null;
        for (RexNode conjunct : RelOptUtil.conjunctions(filter.getCondition())) {
            if (!RelOptUtil.InputFinder.bits(conjunct).get(rankOrdinal)) {
                residual.add(conjunct);
                continue;
            }
            Long bound = boundOf(conjunct, rankOrdinal);
            if (bound == null || limit != null) {
                throw refusal("'" + conjunct + "' constrains the row number in a way a top-N cannot keep. The "
                        + "number is bounded with one condition of the form rn <= N, rn < N or rn = 1.");
            }
            limit = bound;
        }
        if (limit == null) {
            refuseUnbounded(project);
        }
        if (limit < 1) {
            throw refusal("'" + filter.getCondition() + "' keeps no row: row numbers start at 1.");
        }

        PhysicalOperator input = builder.build(project.getInput());
        StreamSchema in = input.outputSchema();
        for (int i = 0; i < in.fieldCount(); i++) {
            TypeName type = in.field(i).type().typeName();
            if (type == TypeName.ARRAY || type == TypeName.MAP || type == TypeName.ROW) {
                throw refusal("column '" + in.field(i).name() + "' is " + type + ", and a top-N holds its rows; " + "a "
                        + type + " column cannot be held. Project it away below the numbering.");
            }
        }

        List<Integer> partition = new ArrayList<>();
        for (RexNode key : over.getWindow().partitionKeys) {
            partition.add(columnOf(key, over, "PARTITION BY"));
        }
        List<TopNOperator.SortKey> order = new ArrayList<>();
        for (RexFieldCollation key : over.getWindow().orderKeys) {
            boolean descending = key.getDirection() == RelFieldCollation.Direction.DESCENDING;
            RelFieldCollation.NullDirection nulls = key.getNullDirection();
            boolean nullsFirst = nulls == RelFieldCollation.NullDirection.UNSPECIFIED
                    ? descending // Calcite's default: nulls sort high, so first when descending
                    : nulls == RelFieldCollation.NullDirection.FIRST;
            order.add(new TopNOperator.SortKey(columnOf(key.left, over, "ORDER BY"), descending, nullsFirst));
        }
        if (order.isEmpty()) {
            throw refusal("'" + over + "' has no ORDER BY, so which rows are first is left to chance by SQL. Say "
                    + "which rows a top-N keeps: ROW_NUMBER() OVER (PARTITION BY ... ORDER BY price DESC).");
        }

        String rankName = project.getRowType().getFieldNames().get(rankOrdinal);
        StreamSchema.Builder ranked = StreamSchema.builder(in.name() + "_ranked");
        in.fields().forEach(field -> ranked.field(field.name(), field.type()));
        ranked.field(uniqueName(in, rankName), Types.int64());
        in.eventTimeOrdinal()
                .ifPresent(ordinal -> ranked.eventTime(in.field(ordinal).name()));
        PhysicalOperator plan = new TopNOperator(input, ranked.build(), partition, order, limit, MAX_ROWS);

        List<Integer> ordinals = new ArrayList<>();
        for (int i = 0; i < project.getProjects().size(); i++) {
            ordinals.add(
                    i == rankOrdinal
                            ? in.fieldCount()
                            : ((RexInputRef) project.getProjects().get(i)).getIndex());
        }
        StreamSchema.Builder named = StreamSchema.builder(in.name() + "_projected");
        project.getRowType()
                .getFieldList()
                .forEach(field ->
                        named.field(field.getName(), TypeMapping.fromCalcite(field.getType(), field.getName())));
        String eventTime = in.eventTimeOrdinal().isPresent()
                ? in.field(in.eventTimeOrdinal().getAsInt()).name()
                : null;
        if (eventTime != null && project.getRowType().getFieldNames().contains(eventTime)) {
            named.eventTime(eventTime);
        }
        plan = new ProjectOperator(plan, named.build(), ordinals);

        if (!residual.isEmpty()) {
            RexNode rest = org.apache.calcite.rex.RexUtil.composeConjunction(
                    filter.getCluster().getRexBuilder(), residual);
            plan = new FilterOperator(plan, new PredicateCompiler(plan.outputSchema(), parameters).compile(rest));
        }
        return plan;
    }

    /** The one OVER call in a projection, or -1. */
    private static int overOrdinal(Project project) {
        int found = -1;
        for (int i = 0; i < project.getProjects().size(); i++) {
            if (RexOver.containsOver(project.getProjects().get(i))) {
                if (!(project.getProjects().get(i) instanceof RexOver)) {
                    // AVG(x) OVER (...) arrives expanded into SUM and COUNT over the same window, so
                    // the window aggregate is refused as one before the expression around it is.
                    RexOver[] inner = new RexOver[1];
                    project.getProjects().get(i).accept(new org.apache.calcite.rex.RexVisitorImpl<Void>(true) {
                        @Override
                        public Void visitOver(RexOver over) {
                            inner[0] = inner[0] == null ? over : inner[0];
                            return super.visitOver(over);
                        }
                    });
                    refuseAllButRowNumber(inner[0]);
                    throw refusal("'" + project.getProjects().get(i) + "' computes with an OVER function inside a "
                            + "larger expression. A top-N is planned from ROW_NUMBER() OVER (...) standing alone "
                            + "in the select list.");
                }
                if (found >= 0) {
                    throw refusal("a query may number rows with one OVER function, and this one has more.");
                }
                found = i;
            }
        }
        return found;
    }

    private static void refuseAllButRowNumber(RexOver over) {
        if (over.getOperator().getKind() != SqlKind.ROW_NUMBER) {
            throw refusal("'" + over + "' is a window aggregate over a row frame, which is not built. The one OVER "
                    + "function maintained is ROW_NUMBER() filtered to a top-N; a running aggregate is written "
                    + "as a GROUP BY over a window -- TUMBLE, HOP or SESSION.");
        }
    }

    /** N for {@code rn <= N}, {@code rn < N + 1} or {@code rn = 1}, either way round; null otherwise. */
    private static Long boundOf(RexNode conjunct, int rankOrdinal) {
        if (!(conjunct instanceof RexCall call) || call.getOperands().size() != 2) {
            return null;
        }
        RexNode left = call.getOperands().get(0);
        RexNode right = call.getOperands().get(1);
        SqlKind kind = call.getKind();
        if (isRank(right, rankOrdinal) && literal(left) != null) {
            kind = kind.reverse();
            RexNode swap = left;
            left = right;
            right = swap;
        }
        Long value = literal(right);
        if (!isRank(left, rankOrdinal) || value == null) {
            return null;
        }
        return switch (kind) {
            case LESS_THAN_OR_EQUAL -> value;
            case LESS_THAN -> value - 1;
            case EQUALS -> value == 1 ? 1L : null;
            default -> null;
        };
    }

    private static boolean isRank(RexNode node, int rankOrdinal) {
        RexNode bare = node;
        while (bare instanceof RexCall cast && cast.getKind() == SqlKind.CAST) {
            bare = cast.getOperands().get(0);
        }
        return bare instanceof RexInputRef ref && ref.getIndex() == rankOrdinal;
    }

    private static Long literal(RexNode node) {
        if (node instanceof RexLiteral literal && literal.getValue4() instanceof BigDecimal value) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException fractional) {
                return null;
            }
        }
        return null;
    }

    private static int columnOf(RexNode key, RexOver over, String clause) {
        if (key instanceof RexInputRef ref) {
            return ref.getIndex();
        }
        throw refusal("'" + over + "' has the " + clause + " key '" + key + "', which is an expression. A top-N "
                + "partitions and orders by columns; compute the key in a subquery below the numbering.");
    }

    private static String uniqueName(StreamSchema schema, String wanted) {
        boolean clash = schema.fields().stream().anyMatch(field -> field.name().equals(wanted));
        return clash ? wanted + "$rank" : wanted;
    }

    private static PravahaException refusal(String detail) {
        return new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                detail + " See docs/guides/CONTINUOUS_QUERIES.md for what this engine executes and what it refuses.");
    }
}
