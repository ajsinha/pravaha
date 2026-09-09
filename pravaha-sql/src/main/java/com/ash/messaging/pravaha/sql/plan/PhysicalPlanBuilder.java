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

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.plan.*;
import com.ash.messaging.pravaha.sql.PravahaTable;
import com.ash.messaging.pravaha.sql.SqlErrors;
import com.ash.messaging.pravaha.sql.TypeMapping;

/**
 * Translates Calcite's optimised tree into Pravaha's own plan.
 *
 * <p>This class <strong>is</strong> the boundary of ADR-002. Above it Calcite has parsed, validated
 * and optimised; below it nothing imports Calcite, which is what keeps its thirty transitive
 * dependencies out of the engine core and lets the runtime be allocation-free.
 *
 * <p>Anything it cannot translate fails at <em>registration</em> with {@code PRV-2020} and the
 * operator's name. Refusing early is the whole point: a plan that converts partially and fails on
 * the first record fails in production, where the diagnosis is expensive.
 */
public final class PhysicalPlanBuilder {

    /** Builds a plan from an optimised relational tree. */
    public PhysicalOperator build(RelNode rel) {
        return switch (rel) {
            case TableScan scan -> buildScan(scan);
            case Filter filter -> buildFilter(filter);
            case Project project -> buildProject(project);
            case Aggregate aggregate -> buildAggregate(aggregate);
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_OPERATOR,
                        "the planner produced a " + rel.getRelTypeName()
                                + ", which Pravaha cannot execute yet. Supported: scan, filter, project, "
                                + "aggregate. Joins and windows arrive in later waves.");
        };
    }

    private PhysicalOperator buildScan(TableScan scan) {
        PravahaTable table = scan.getTable().unwrap(PravahaTable.class);
        if (table == null) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "scan of " + scan.getTable().getQualifiedName() + " is not backed by a Pravaha stream");
        }
        return ScanOperator.of(table.streamSchema().name(), table.streamSchema());
    }

    private PhysicalOperator buildFilter(Filter filter) {
        PhysicalOperator input = build(filter.getInput());
        Predicate predicate = new PredicateCompiler(input.outputSchema()).compile(filter.getCondition());
        return new FilterOperator(input, predicate);
    }

    private PhysicalOperator buildProject(Project project) {
        PhysicalOperator input = build(project.getInput());
        List<Integer> ordinals = new ArrayList<>(project.getProjects().size());
        for (RexNode expression : project.getProjects()) {
            if (!(expression instanceof RexInputRef ref)) {
                // Computed columns need the expression compiler that Wave 3 generates. Refusing a
                // computed projection is better than silently producing the wrong column.
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "projection '" + expression + "' is computed; only direct column references are "
                                + "supported so far. Compute it in the source query or wait for expression "
                                + "support.");
            }
            ordinals.add(ref.getIndex());
        }
        StreamSchema output = schemaOf(project, input.outputSchema().name() + "_projected");
        return new ProjectOperator(input, output, ordinals);
    }

    private PhysicalOperator buildAggregate(Aggregate aggregate) {
        PhysicalOperator input = build(aggregate.getInput());
        ImmutableBitSet groupSet = aggregate.getGroupSet();
        if (aggregate.getGroupSets().size() > 1) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR, "GROUPING SETS, CUBE and ROLLUP are not supported yet");
        }
        List<Integer> groupKeys = new ArrayList<>(groupSet.asList());

        List<AggregateOperator.AggregateCall> calls = new ArrayList<>();
        for (AggregateCall call : aggregate.getAggCallList()) {
            calls.add(new AggregateOperator.AggregateCall(
                    kindOf(call),
                    call.getArgList().isEmpty() ? -1 : call.getArgList().get(0),
                    nameOf(call)));
        }

        StreamSchema output = schemaOf(aggregate, input.outputSchema().name() + "_aggregated");
        AggregateOperator operator = new AggregateOperator(input, output, groupKeys, calls);

        // Design 9.6: an unbounded integrate over an unbounded key space never stops growing, and
        // refusing the query is the only intervention that reliably works. A global aggregate is
        // bounded by construction (one row); a keyed one is not, until windowing arrives in Wave 4.
        if (!groupKeys.isEmpty()) {
            throw new PravahaException(
                    SqlErrors.UNBOUNDED_STATE,
                    "GROUP BY " + groupKeys + " has no bound on its key space, so its state would grow "
                            + "without limit. Add a window (arriving in Wave 4) or a state TTL. "
                            + "Refusing now rather than exhausting memory later.");
        }
        return operator;
    }

    private static AggregateOperator.AggregateCall.Kind kindOf(AggregateCall call) {
        String name = call.getAggregation().getName().toUpperCase(java.util.Locale.ROOT);
        return switch (name) {
            case "COUNT" -> AggregateOperator.AggregateCall.Kind.COUNT;
            case "SUM", "SUM0" -> AggregateOperator.AggregateCall.Kind.SUM;
            case "MIN" -> AggregateOperator.AggregateCall.Kind.MIN;
            case "MAX" -> AggregateOperator.AggregateCall.Kind.MAX;
            case "AVG" -> AggregateOperator.AggregateCall.Kind.AVG;
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_OPERATOR,
                        "aggregate function " + name + " is not supported yet. "
                                + "Supported: COUNT, SUM, MIN, MAX, AVG.");
        };
    }

    private static String nameOf(AggregateCall call) {
        return call.getName() == null ? call.getAggregation().getName() : call.getName();
    }

    /** Derives a Pravaha schema from a relational node's row type. */
    private static StreamSchema schemaOf(RelNode rel, String name) {
        StreamSchema.Builder builder = StreamSchema.builder(name);
        rel.getRowType()
                .getFieldList()
                .forEach(field -> builder.field(field.getName(), TypeMapping.fromCalcite(field.getType())));
        return builder.build();
    }

    /** Renders a plan as an indented tree, for EXPLAIN and for golden-plan tests. */
    public static String explain(PhysicalOperator root) {
        StringBuilder sb = new StringBuilder();
        render(root, 0, sb);
        return sb.toString();
    }

    private static void render(PhysicalOperator operator, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(operator.label()).append('\n');
        operator.inputs().forEach(input -> render(input, depth + 1, sb));
    }
}
