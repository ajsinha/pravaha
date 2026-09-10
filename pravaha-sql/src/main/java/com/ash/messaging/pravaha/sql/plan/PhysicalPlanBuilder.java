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
import org.apache.calcite.rel.core.TableFunctionScan;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.plan.*;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;
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
            case TableFunctionScan windowing -> buildWindowAssign(windowing);
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
        StreamSchema output = schemaOf(project, input.outputSchema().name() + "_projected");

        // A projection of plain column references stays a Project: its generated form is a load and
        // a store at constant offsets, and putting an expression tree in that path would cost a
        // branch per column per row to answer a question the plan already knew.
        boolean allColumnReferences =
                project.getProjects().stream().allMatch(expression -> expression instanceof RexInputRef);
        if (allColumnReferences) {
            List<Integer> ordinals = new ArrayList<>(project.getProjects().size());
            for (RexNode expression : project.getProjects()) {
                ordinals.add(((RexInputRef) expression).getIndex());
            }
            return new ProjectOperator(input, output, ordinals);
        }

        ExpressionCompiler compiler = new ExpressionCompiler(input.outputSchema());
        List<com.ash.messaging.pravaha.runtime.plan.Expression> expressions =
                new ArrayList<>(project.getProjects().size());
        for (RexNode expression : project.getProjects()) {
            expressions.add(compiler.compile(expression));
        }
        return new ComputeOperator(input, output, expressions);
    }

    /**
     * Translates SQL's windowing table function into a window assignment.
     *
     * <p>{@code TABLE(TUMBLE(TABLE s, DESCRIPTOR(event_time), INTERVAL '10' SECOND))} is the SQL:2016
     * form, and Calcite gives it to us as a scan whose row type is the input's columns plus
     * {@code window_start} and {@code window_end}. That shape is exactly what the runtime wants:
     * assignment adds two columns, and a perfectly ordinary GROUP BY on them above is a windowed
     * aggregate. No special grouping machinery, and the window is visible in EXPLAIN rather than
     * buried in an aggregate's configuration.
     *
     * <p>The design's section 11.2 example uses the older grouped-function form,
     * {@code GROUP BY TUMBLE(event_time, INTERVAL ...)}. Both parse in Calcite 1.40, and the table
     * function is preferred here because it names the window columns explicitly rather than
     * requiring TUMBLE_START/TUMBLE_END to reconstruct them -- the design is corrected to match
     * rather than the code contorted to fit.
     */
    private PhysicalOperator buildWindowAssign(TableFunctionScan windowing) {
        if (windowing.getInputs().size() != 1) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "a windowing table function takes exactly one input; this one has "
                            + windowing.getInputs().size());
        }
        PhysicalOperator input = build(windowing.getInput(0));
        if (!(windowing.getCall() instanceof RexCall call)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR, "cannot read the windowing call " + windowing.getCall());
        }

        String function = call.getOperator().getName().toUpperCase(java.util.Locale.ROOT);
        List<Long> intervals = new ArrayList<>();
        int eventTimeOrdinal = -1;
        for (RexNode operand : call.getOperands()) {
            if (operand instanceof RexCall descriptor
                    && descriptor.getOperator().getName().equalsIgnoreCase("DESCRIPTOR")) {
                eventTimeOrdinal = descriptorOrdinal(descriptor, input.outputSchema());
            } else if (operand instanceof RexLiteral literal && literal.getValue() != null) {
                // Calcite normalises INTERVAL literals to milliseconds; the engine works in
                // nanoseconds throughout (ADR-012), so the conversion happens once, here.
                intervals.add(((java.math.BigDecimal) literal.getValue4()).longValue() * 1_000_000L);
            }
        }
        if (eventTimeOrdinal < 0) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "the windowing function names no time column. Pass one with DESCRIPTOR(event_time).");
        }

        WindowSpec spec =
                switch (function) {
                    case "TUMBLE" -> {
                        requireIntervals(function, intervals, 1);
                        yield WindowSpec.tumbling(intervals.get(0));
                    }
                    case "HOP" -> {
                        // Calcite passes HOP as (slide, size), which is the opposite of the order the SQL
                        // reads in. Getting this backwards produces windows of the wrong width that still
                        // fire plausibly, so it is asserted by test rather than trusted.
                        requireIntervals(function, intervals, 2);
                        yield WindowSpec.hopping(intervals.get(1), intervals.get(0));
                    }
                    case "SESSION" ->
                        throw new PravahaException(
                                SqlErrors.UNSUPPORTED_OPERATOR,
                                "SESSION windows exist in the runtime but are not wired to SQL yet: their state is a "
                                        + "per-key interval set rather than a slice grid, so they need the keyed state "
                                        + "store. Use TUMBLE or HOP.");
                    default ->
                        throw new PravahaException(
                                SqlErrors.UNSUPPORTED_OPERATOR, "unsupported windowing function " + function);
                };

        StreamSchema output = schemaOf(windowing, input.outputSchema().name() + "_windowed");
        return new WindowAssignOperator(input, output, spec, eventTimeOrdinal);
    }

    private static void requireIntervals(String function, List<Long> intervals, int expected) {
        if (intervals.size() < expected) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    function + " needs " + expected + " interval argument(s); got " + intervals.size());
        }
    }

    private static int descriptorOrdinal(RexCall descriptor, StreamSchema schema) {
        for (RexNode operand : descriptor.getOperands()) {
            if (operand instanceof RexInputRef ref) {
                return ref.getIndex();
            }
            String name = operand.toString().replace("'", "").trim();
            for (int i = 0; i < schema.fieldCount(); i++) {
                if (schema.field(i).name().equalsIgnoreCase(name)) {
                    return i;
                }
            }
        }
        return -1;
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

        // A GROUP BY sitting on a window assignment is bounded: each window's state is released when
        // the window closes, so the state is keys times *open* windows rather than keys times
        // history. That is the whole reason windowing exists in this design, and it is the one shape
        // of keyed aggregate that can be admitted.
        WindowAssignOperator window = windowBelow(input);
        if (window != null) {
            int[] boundaries = windowBoundaryOrdinals(input.outputSchema(), groupKeys);
            if (boundaries == null) {
                throw new PravahaException(
                        SqlErrors.UNBOUNDED_STATE,
                        "this GROUP BY is over a windowed stream but does not group by the window: add "
                                + "window_start and window_end to the GROUP BY. Without them the aggregate spans "
                                + "every window at once, which is the unbounded case wearing a window's clothes.");
            }
            return new WindowedAggregateOperator(
                    input,
                    output,
                    window.spec(),
                    groupKeys,
                    calls,
                    boundaries[0],
                    boundaries[1],
                    DEFAULT_MAX_SLICES,
                    DEFAULT_ALLOWED_LATENESS_NANOS);
        }

        AggregateOperator operator = new AggregateOperator(input, output, groupKeys, calls);

        // Design 9.6: an unbounded integrate over an unbounded key space never stops growing, and
        // refusing the query is the only intervention that reliably works. A global aggregate is
        // bounded by construction -- one row, whatever the input volume; a keyed one is not.
        if (!groupKeys.isEmpty()) {
            throw new PravahaException(
                    SqlErrors.UNBOUNDED_STATE,
                    "GROUP BY " + namesOf(groupKeys, input.outputSchema())
                            + " has no bound on its key space, so its state grows with the number of distinct "
                            + "keys and never shrinks. One row per key is fine at a thousand keys and fatal at "
                            + "a hundred million, and the failure arrives weeks after deployment.\n"
                            + "  Bound it with a window -- GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), "
                            + namesOf(groupKeys, input.outputSchema())
                            + " -- so state is released when each window closes.\n"
                            + "Refusing now rather than exhausting memory later.");
        }
        return operator;
    }

    /**
     * Renders group-key ordinals as the column names the query was written with.
     *
     * <p>The gate for this wave asks for a diagnostic that <em>names the key</em>, and the reason is
     * practical rather than cosmetic: "GROUP BY [3]" tells somebody reading it nothing, and a person
     * debugging a rejected query at speed will map that ordinal to the wrong column at least once.
     */
    private static String namesOf(List<Integer> ordinals, StreamSchema schema) {
        return ordinals.stream()
                .map(ordinal ->
                        ordinal < schema.fieldCount() ? schema.field(ordinal).name() : "column " + ordinal)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * The ceiling on live accumulators for a windowed aggregate.
     *
     * <p>A window bounds state over *time*; it does nothing about the key space within one window,
     * so a hundred million distinct keys in a single minute is still a hundred million accumulators.
     * The ceiling is what turns that into a refusal naming the key rather than an out-of-memory
     * kill. A per-query setting belongs with the query lifecycle; until then this is the default,
     * chosen so that a legitimate high-cardinality query fits and a runaway one does not.
     */
    private static final int DEFAULT_MAX_SLICES = 2_000_000;

    /**
     * Allowed lateness until a query can declare its own.
     *
     * <p>Zero, and deliberately so. A default allowance decides on the operator's behalf how much
     * correctness to trade for how much state, and does it silently; zero means every late record is
     * counted and visible, so the number can be looked at before anybody picks a value. The
     * {@code EMIT CHANGES WITH ('allowed.lateness' = ...)} clause of design 11.2 is where a real
     * value will come from.
     */
    private static final long DEFAULT_ALLOWED_LATENESS_NANOS = 0L;

    /** The window assignment feeding this aggregate, looking through projections. */
    private static WindowAssignOperator windowBelow(PhysicalOperator operator) {
        PhysicalOperator current = operator;
        while (true) {
            if (current instanceof WindowAssignOperator window) {
                return window;
            }
            if (current instanceof ProjectOperator project) {
                current = project.input();
                continue;
            }
            return null;
        }
    }

    /**
     * Finds the group keys that carry the window boundaries.
     *
     * <p>Matched by name, in the aggregate's <em>own input</em> schema rather than the window
     * assigner's. A projection sits between them and renumbers everything: the boundaries are the
     * assigner's last two columns and the aggregate's first two here, so resolving against the wrong
     * schema finds boundaries where there are none -- and, worse, could find them at the wrong
     * ordinals and group by whichever columns happened to be there, producing correct-looking
     * numbers for the wrong grouping.
     */
    private static int[] windowBoundaryOrdinals(StreamSchema schema, List<Integer> groupKeys) {
        int start = -1;
        int end = -1;
        for (int ordinal : groupKeys) {
            if (ordinal >= schema.fieldCount()) {
                continue;
            }
            String name = schema.field(ordinal).name();
            if (name.equalsIgnoreCase("window_start")) {
                start = ordinal;
            } else if (name.equalsIgnoreCase("window_end")) {
                end = ordinal;
            }
        }
        return start >= 0 && end >= 0 ? new int[] {start, end} : null;
    }

    private static AggregateOperator.AggregateCall.Kind kindOf(AggregateCall call) {
        String name = call.getAggregation().getName().toUpperCase(java.util.Locale.ROOT);
        return switch (name) {
            case "COUNT" ->
                call.isDistinct()
                        ? AggregateOperator.AggregateCall.Kind.COUNT_DISTINCT
                        : AggregateOperator.AggregateCall.Kind.COUNT;
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
