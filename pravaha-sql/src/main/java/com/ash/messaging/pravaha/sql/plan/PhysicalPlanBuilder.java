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
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableFunctionScan;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
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

    /**
     * The default ceiling on rows held per join side.
     *
     * <p>A number, not a policy, and deliberately not a large one. A stream-to-stream join without a
     * time bound accumulates for as long as the query runs, so this exists to make that failure
     * arrive early and with a sentence explaining it, rather than as an out-of-memory kill days
     * later. Windowed joins are what will replace it; when they land this becomes the ceiling for
     * the unwindowed case only.
     */
    private static final long MAX_JOIN_ROWS_PER_SIDE = 1_000_000;

    /** Builds a plan from an optimised relational tree. */
    private BoundParameters parameters = BoundParameters.none();

    private boolean boundedInput;

    /**
     * Declares that this plan reads a finite input that ends, rather than a stream that does not.
     *
     * <p>The only thing it changes is the unbounded-state refusal on a keyed {@code GROUP BY}. Over
     * a stream that aggregate holds one accumulator per distinct key forever; over a bounded read
     * the scan stops and the state goes with it, so the same SQL is an ordinary question there and
     * a standing memory leak here.
     *
     * <p>The caller is asserting the input is finite. That belongs to the serving layer reading a
     * materialised view and to nothing that reads a source.
     */
    public PhysicalPlanBuilder overBoundedInput() {
        this.boundedInput = true;
        return this;
    }

    /**
     * Binds this statement's {@code ?} placeholders for the plan about to be built.
     *
     * <p>Binding at plan-build time rather than at parse time is the split that makes prepared
     * statements worth having here: parse, validate and optimise once, then walk the planned tree
     * per call. It also means a bound value never passes through a parser, so there is no escaping
     * to get right (ADR-032).
     */
    public PhysicalPlanBuilder bind(BoundParameters parameters) {
        this.parameters = parameters == null ? BoundParameters.none() : parameters;
        return this;
    }

    public PhysicalOperator build(RelNode rel) {
        return switch (rel) {
            case TableScan scan -> buildScan(scan);
            case Filter filter -> buildFilter(filter);
            case Project project -> buildProject(project);
            case Aggregate aggregate -> buildAggregate(aggregate);
            case TableFunctionScan windowing -> buildWindowAssign(windowing);
            case org.apache.calcite.rel.core.Join join -> buildJoin(join);
            case org.apache.calcite.rel.core.Correlate correlate -> buildLookupJoin(correlate);
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_OPERATOR,
                        "the planner produced a " + rel.getRelTypeName()
                                + ", which Pravaha cannot execute yet. Supported: scan, filter, project, "
                                + "compute, aggregate, windowing (both the TABLE(TUMBLE(...)) and GROUP BY "
                                + "TUMBLE(...) forms), inner equi-joins between streams, and lookup joins "
                                + "against a dimension table.");
        };
    }

    /**
     * An inner equi-join between two streams.
     *
     * <p>Only the inner, and only equality, and both restrictions are about state rather than
     * effort. An outer join has to emit a null-padded row for a left row that has not matched
     * <em>yet</em> and retract it if a match arrives later, which means holding the unmatched rows
     * for as long as a match remains possible -- with no watermark on the join, that is forever. A
     * non-equality condition has no key to index by, so every row is a candidate for every other:
     * a cross product with a filter, which cannot be executed incrementally at any useful rate.
     *
     * <p>Both are refusals with a reason rather than gaps, because a user who reads "not supported"
     * asks when it will be, and a user who reads why it cannot be bounded rewrites the query.
     */
    private PhysicalOperator buildJoin(org.apache.calcite.rel.core.Join join) {
        if (join.getJoinType() != org.apache.calcite.rel.core.JoinRelType.INNER) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "a " + join.getJoinType() + " join between streams is not supported yet. An outer join must "
                            + "hold every unmatched row for as long as a match could still arrive, which without "
                            + "a time bound on the join is forever. Use an inner join, or wait for windowed "
                            + "joins.");
        }

        PhysicalOperator left = build(join.getLeft());
        PhysicalOperator right = build(join.getRight());
        int leftWidth = left.outputSchema().fields().size();

        List<Integer> leftKeys = new ArrayList<>();
        List<Integer> rightKeys = new ArrayList<>();
        TimeBounds bounds = new TimeBounds();
        collectEquiKeys(join.getCondition(), leftWidth, leftKeys, rightKeys, join, bounds);

        StreamSchema output = schemaOf(
                join, left.outputSchema().name() + "_" + right.outputSchema().name());
        if (leftKeys.isEmpty()) {
            // A time bound narrows which pairs count; it does not give the join anything to index by.
            // Without an equality every row of one side is still a candidate for every row of the
            // other within the window, which is a cross product with a filter on it.
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "the join condition states a time bound but no equality, so there is no join key to "
                            + "index either side by. A window narrows which pairs count; it does not stop every "
                            + "row being a candidate for every other inside it, which is a cross product with a "
                            + "filter and has no incremental execution. Add the equality the two streams "
                            + "correlate on.");
        }
        if (!bounds.stated()) {
            return new JoinOperator(left, right, leftKeys, rightKeys, output, MAX_JOIN_ROWS_PER_SIDE);
        }
        return JoinOperator.withinRange(
                left, right, leftKeys, rightKeys, output, MAX_JOIN_ROWS_PER_SIDE, bounds.lower(), bounds.upper());
    }

    /**
     * Pulls {@code l.k = r.k} pairs out of the join condition.
     *
     * <p>Calcite numbers a join's condition over the concatenated inputs: field {@code i} is the
     * left's {@code i} while {@code i < leftWidth}, and the right's {@code i - leftWidth} after
     * that. Getting that arithmetic wrong produces a join that indexes the wrong column and returns
     * nothing, which looks exactly like no data matching.
     */
    private void collectEquiKeys(
            RexNode condition,
            int leftWidth,
            List<Integer> leftKeys,
            List<Integer> rightKeys,
            org.apache.calcite.rel.core.Join join,
            TimeBounds bounds) {
        if (condition.getKind() == SqlKind.AND) {
            ((RexCall) condition)
                    .getOperands()
                    .forEach(part -> collectEquiKeys(part, leftWidth, leftKeys, rightKeys, join, bounds));
            return;
        }
        if (collectTimeBound(condition, leftWidth, bounds)) {
            return;
        }
        if (condition.getKind() == SqlKind.EQUALS
                && ((RexCall) condition).getOperands().get(0) instanceof RexInputRef a
                && ((RexCall) condition).getOperands().get(1) instanceof RexInputRef b) {
            int first = a.getIndex();
            int second = b.getIndex();
            // Either order: `l.k = r.k` and `r.k = l.k` are the same join.
            if (first < leftWidth && second >= leftWidth) {
                leftKeys.add(first);
                rightKeys.add(second - leftWidth);
                return;
            }
            if (second < leftWidth && first >= leftWidth) {
                leftKeys.add(second);
                rightKeys.add(first - leftWidth);
                return;
            }
        }
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_OPERATOR,
                "the join condition '" + condition + "' is neither an equality between one column of each side "
                        + "nor a time bound between them. Pravaha indexes both sides by the join key; a condition "
                        + "with no such key makes every row a candidate for every other, which is a cross product "
                        + "with a filter and has no incremental execution. Write the equality, optionally with a "
                        + "bound such as l.event_time BETWEEN r.event_time - INTERVAL '5' MINUTE AND r.event_time, "
                        + "and move anything else into a WHERE clause.");
    }

    /**
     * Collects {@code l.t >= r.t - INTERVAL 'x' UNIT} and its relatives into the join's time bounds.
     *
     * <p>Without this a temporal predicate was refused outright, and the bound that actually governed
     * the join was an hour, chosen in the engine and invisible in the query. That is the wrong place
     * for it by the project's own rule: a bound that changes the answer belongs in the query's
     * meaning, and only a bound that protects the machine belongs in configuration. Which rows match
     * is unarguably the answer.
     *
     * <p>Calcite has already expanded {@code BETWEEN} into two comparisons and normalised the
     * literal to milliseconds by the time this sees it.
     *
     * @return true if the conjunct was a time bound and has been recorded
     */
    private boolean collectTimeBound(RexNode condition, int leftWidth, TimeBounds bounds) {
        SqlKind kind = condition.getKind();
        if (kind != SqlKind.GREATER_THAN
                && kind != SqlKind.GREATER_THAN_OR_EQUAL
                && kind != SqlKind.LESS_THAN
                && kind != SqlKind.LESS_THAN_OR_EQUAL) {
            return false;
        }
        RexCall comparison = (RexCall) condition;
        RexNode first = comparison.getOperands().get(0);
        RexNode second = comparison.getOperands().get(1);

        Offset near = asOffset(first, leftWidth);
        Offset far = asOffset(second, leftWidth);
        if (near == null || far == null || near.fromLeft() == far.fromLeft()) {
            // Both sides of the comparison must name a column, and they must be opposite inputs.
            // A bound against a constant is a filter, not a join condition, and belongs in WHERE.
            return false;
        }

        // Normalise to left - right, whichever order it was written in.
        long delta;
        boolean lower;
        if (near.fromLeft()) {
            // left + nearOffset OP right + farOffset  ->  left - right OP farOffset - nearOffset
            delta = far.nanos() - near.nanos();
            lower = kind == SqlKind.GREATER_THAN || kind == SqlKind.GREATER_THAN_OR_EQUAL;
        } else {
            // right + nearOffset OP left + farOffset  ->  left - right (reversed) ...
            delta = near.nanos() - far.nanos();
            lower = kind == SqlKind.LESS_THAN || kind == SqlKind.LESS_THAN_OR_EQUAL;
        }
        if (lower) {
            bounds.atLeast(delta);
        } else {
            bounds.atMost(delta);
        }
        return true;
    }

    /** A time column on one side, optionally shifted by an interval literal. */
    private record Offset(boolean fromLeft, long nanos) {}

    private Offset asOffset(RexNode node, int leftWidth) {
        if (node instanceof RexInputRef ref) {
            return isTimestamp(ref) ? new Offset(ref.getIndex() < leftWidth, 0) : null;
        }
        if (node instanceof RexCall call
                && (call.getKind() == SqlKind.PLUS || call.getKind() == SqlKind.MINUS)
                && call.getOperands().size() == 2
                && call.getOperands().get(0) instanceof RexInputRef ref
                && call.getOperands().get(1) instanceof org.apache.calcite.rex.RexLiteral literal) {
            if (!isTimestamp(ref)) {
                return null;
            }
            Long millis = intervalMillis(literal);
            if (millis == null) {
                return null;
            }
            long nanos = millis * 1_000_000L;
            return new Offset(ref.getIndex() < leftWidth, call.getKind() == SqlKind.MINUS ? -nanos : nanos);
        }
        return null;
    }

    /**
     * True if this column is a timestamp.
     *
     * <p>Checked, and the reason is a bug this caught. Without it, {@code o.user_id > u.user_id} --
     * two integer columns, an ordinary non-equi join -- was read as a time bound, accepted, and
     * planned as a join with no equality at all. It would have run, held both sides entirely, and
     * returned a cross product filtered by an inequality. The refusal it used to get was correct and
     * this restores it.
     */
    private boolean isTimestamp(RexInputRef ref) {
        org.apache.calcite.sql.type.SqlTypeName type = ref.getType().getSqlTypeName();
        return type == org.apache.calcite.sql.type.SqlTypeName.TIMESTAMP
                || type == org.apache.calcite.sql.type.SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE;
    }

    private Long intervalMillis(org.apache.calcite.rex.RexLiteral literal) {
        if (!(literal.getType().getSqlTypeName().getFamily()
                == org.apache.calcite.sql.type.SqlTypeFamily.INTERVAL_DAY_TIME)) {
            // Months and years have no fixed length, so they cannot become a number of nanoseconds
            // without knowing which month. A join window measured in months is not a thing anybody
            // needs, and guessing thirty days would be wrong twice a year.
            return null;
        }
        java.math.BigDecimal value = literal.getValueAs(java.math.BigDecimal.class);
        return value == null ? null : value.longValue();
    }

    /**
     * The bounds on {@code left.time - right.time} gathered from a join condition.
     *
     * <p>Both sides start unstated rather than at infinity, so "the query said nothing" and "the
     * query said something unbounded" stay distinguishable -- the first gets the default window, and
     * the second cannot be written.
     */
    private static final class TimeBounds {
        private Long lower;
        private Long upper;

        void atLeast(long nanos) {
            lower = lower == null ? nanos : Math.max(lower, nanos);
        }

        void atMost(long nanos) {
            upper = upper == null ? nanos : Math.min(upper, nanos);
        }

        boolean stated() {
            return lower != null || upper != null;
        }

        /** A one-sided bound is closed on the other side at zero: the unstated side is "no shift". */
        long lower() {
            return lower != null ? lower : Math.min(0, upper);
        }

        long upper() {
            return upper != null ? upper : Math.max(0, lower);
        }
    }

    /**
     * {@code JOIN dim FOR SYSTEM_TIME AS OF t.ts ON ...} -- enrichment from a dimension table.
     *
     * <p>Calcite expresses this as a correlated join: for each row of the left, evaluate the right
     * subtree with that row's values bound. The right subtree it produces is always the same shape
     * -- a filter comparing the correlation variable to a column, over a snapshot, over the scan of
     * the dimension table -- and anything else is a correlated subquery Pravaha does not run.
     *
     * <p>{@code LEFT} is accepted here and refused for stream-to-stream joins, which is not the
     * inconsistency it looks like. A lookup answers definitively at the moment it is asked, so an
     * unmatched record is emitted with nulls and never retracted. Between two streams the same
     * syntax means holding every unmatched row for as long as a match could still arrive.
     *
     * <p>The period the syntax names is <em>not</em> honoured: the lookup is as of now, so a replay
     * of last month's stream is enriched with today's dimension rows. Every engine with this
     * operator behaves the same way, and it matters most exactly when somebody is rebuilding
     * history, which is when they are least likely to be reading the manual.
     */
    private PhysicalOperator buildLookupJoin(org.apache.calcite.rel.core.Correlate correlate) {
        JoinRelType type = correlate.getJoinType();
        if (type != JoinRelType.INNER && type != JoinRelType.LEFT) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "a " + type + " join against a lookup table is not supported; use an inner or left join");
        }

        PhysicalOperator left = build(correlate.getLeft());
        RelNode right = correlate.getRight();

        RexNode condition = null;
        if (right instanceof Filter filter) {
            condition = filter.getCondition();
            right = filter.getInput();
        }
        if (!(right instanceof org.apache.calcite.rel.core.Snapshot snapshot)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "correlated subqueries are not supported; the only correlated form Pravaha runs is a join "
                            + "against a lookup table, written as 'JOIN dim FOR SYSTEM_TIME AS OF <time>'");
        }
        if (!(snapshot.getInput() instanceof TableScan scan)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "a lookup join must read a registered dimension table directly, not "
                            + snapshot.getInput().getRelTypeName());
        }

        PravahaTable table = scan.getTable().unwrap(PravahaTable.class);
        if (table == null || !table.isLookup()) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "stream '" + scan.getTable().getQualifiedName() + "' is registered as a stream, not as a "
                            + "lookup table. A stream is consumed and its rows are held in join state; a lookup "
                            + "table is asked one key at a time and holds nothing. Register it with "
                            + "registerLookup to join against it this way.");
        }

        List<Integer> streamKeys = new ArrayList<>();
        collectLookupKeys(condition, left.outputSchema().fields().size(), streamKeys, correlate);

        StreamSchema output = schemaOf(
                correlate,
                left.outputSchema().name() + "_" + table.streamSchema().name());
        return new LookupJoinOperator(
                left, table.streamSchema().name(), streamKeys, table.streamSchema(), output, type == JoinRelType.LEFT);
    }

    /**
     * Pulls {@code $cor0.k = dim.k} pairs out of the correlated condition.
     *
     * <p>The left side of each equality is a field access on the correlation variable -- a column of
     * the record being enriched -- and the right is a plain reference into the dimension table. The
     * dimension's ordinals are not carried through: the source declares its own key columns and is
     * asked in that order, because a store answers only on the keys it is indexed for.
     */
    private void collectLookupKeys(
            RexNode condition, int leftWidth, List<Integer> streamKeys, org.apache.calcite.rel.core.Correlate node) {
        if (condition == null) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "a lookup join needs an equality on the dimension table's key; without one every record "
                            + "would ask the store for its whole contents");
        }
        if (condition.getKind() == SqlKind.AND) {
            ((RexCall) condition).getOperands().forEach(part -> collectLookupKeys(part, leftWidth, streamKeys, node));
            return;
        }
        if (condition.getKind() == SqlKind.EQUALS) {
            List<RexNode> operands = ((RexCall) condition).getOperands();
            Integer fromCorrelation = correlatedOrdinal(operands.get(0));
            if (fromCorrelation == null) {
                fromCorrelation = correlatedOrdinal(operands.get(1));
            }
            if (fromCorrelation != null) {
                streamKeys.add(fromCorrelation);
                return;
            }
        }
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_OPERATOR,
                "'" + condition + "' is not an equality between a column of the stream and one of the lookup "
                        + "table. A lookup is a key-value read; a condition it cannot be a key for would mean "
                        + "scanning the dimension table once per record.");
    }

    /** The stream-side ordinal behind {@code $cor0.column}, or null if this is not one. */
    private static Integer correlatedOrdinal(RexNode node) {
        if (node instanceof org.apache.calcite.rex.RexFieldAccess access
                && access.getReferenceExpr() instanceof org.apache.calcite.rex.RexCorrelVariable) {
            return access.getField().getIndex();
        }
        return null;
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
        Predicate predicate = new PredicateCompiler(input.outputSchema(), parameters).compile(filter.getCondition());
        return new FilterOperator(input, predicate);
    }

    private PhysicalOperator buildProject(Project project) {
        PhysicalOperator input = build(project.getInput());
        StreamSchema output = schemaOf(project, input.outputSchema().name() + "_projected");

        // The grouped-window form -- GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND) -- arrives as
        // an ordinary projection containing a $TUMBLE call, because Calcite rewrites the grouping
        // into "project the window start, then group by it". That is the same window this engine
        // assigns explicitly, expressed differently, so it is turned back into an assignment rather
        // than evaluated as an expression.
        RexCall groupedWindow = groupedWindowCall(project);
        if (groupedWindow != null) {
            return buildGroupedWindow(project, input, groupedWindow);
        }

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

    /** The {@code $TUMBLE} or {@code $HOP} call in a projection, or null if there is none. */
    private static RexCall groupedWindowCall(Project project) {
        for (RexNode expression : project.getProjects()) {
            if (expression instanceof RexCall call
                    && call.getOperator()
                            .getName()
                            .toUpperCase(java.util.Locale.ROOT)
                            .startsWith("$")
                    && isWindowFunction(call.getOperator().getName())) {
                return call;
            }
        }
        return null;
    }

    private static boolean isWindowFunction(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT).replace("$", "");
        return upper.equals("TUMBLE") || upper.equals("HOP") || upper.equals("SESSION");
    }

    /**
     * Turns {@code GROUP BY TUMBLE(...)} back into a window assignment.
     *
     * <p>Calcite lowers the grouped form into a projection of the window's <em>start</em>, which an
     * aggregate above then groups by. The engine wants the assignment as an operator -- so the
     * projection is replaced by an assignment plus a projection that reads the assigner's own
     * boundary columns.
     *
     * <p>The window <em>end</em> is appended even though the SQL never asked for it. The aggregate
     * above needs both boundaries to know when a window may be fired and its state released, and
     * the end is a function of the start, so materialising it changes nothing about the answer and
     * saves reconstructing it from the window spec in three places. {@link #buildAggregate} adds it
     * to the grouping, which is a no-op semantically for the same reason.
     */
    private PhysicalOperator buildGroupedWindow(Project project, PhysicalOperator input, RexCall window) {
        String function = window.getOperator().getName().replace("$", "").toUpperCase(java.util.Locale.ROOT);
        int eventTimeOrdinal = -1;
        List<Long> intervals = new ArrayList<>();
        for (RexNode operand : window.getOperands()) {
            if (operand instanceof RexInputRef ref) {
                eventTimeOrdinal = ref.getIndex();
            } else if (operand instanceof RexLiteral literal && literal.getValue() != null) {
                // Milliseconds from Calcite, nanoseconds in the engine (ADR-012).
                intervals.add(((java.math.BigDecimal) literal.getValue4()).longValue() * 1_000_000L);
            }
        }
        if (eventTimeOrdinal < 0) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_OPERATOR,
                    "GROUP BY " + function + " names no time column; it needs one, as in "
                            + "TUMBLE(event_time, INTERVAL '10' SECOND)");
        }
        WindowSpec spec =
                switch (function) {
                    case "TUMBLE" -> {
                        requireIntervals(function, intervals, 1);
                        yield WindowSpec.tumbling(intervals.get(0));
                    }
                    case "HOP" -> {
                        requireIntervals(function, intervals, 2);
                        yield WindowSpec.hopping(intervals.get(1), intervals.get(0));
                    }
                    default ->
                        throw new PravahaException(
                                SqlErrors.UNSUPPORTED_OPERATOR,
                                "GROUP BY " + function + " is not supported; use TUMBLE or HOP");
                };

        // The assigner appends window_start and window_end to whatever it is given.
        StreamSchema assigned = appendBoundaries(input.outputSchema());
        WindowAssignOperator assign = new WindowAssignOperator(input, assigned, spec, eventTimeOrdinal);
        int startOrdinal = assigned.fields().size() - 2;
        int endOrdinal = assigned.fields().size() - 1;

        // Now the projection Calcite asked for, with the window call replaced by the assigner's
        // start column, and the end appended so the aggregate above can find it.
        List<Integer> ordinals = new ArrayList<>();
        StreamSchema.Builder schema = StreamSchema.builder(input.outputSchema().name() + "_windowed");
        List<String> names = project.getRowType().getFieldNames();
        for (int i = 0; i < project.getProjects().size(); i++) {
            RexNode expression = project.getProjects().get(i);
            if (expression == window) {
                ordinals.add(startOrdinal);
                // Named, not left as Calcite's $f0: the aggregate resolves the boundaries by name.
                schema.field("window_start", assigned.field(startOrdinal).type());
            } else if (expression instanceof RexInputRef ref) {
                ordinals.add(ref.getIndex());
                schema.field(names.get(i), assigned.field(ref.getIndex()).type());
            } else {
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_OPERATOR,
                        "'" + expression + "' sits beside a windowing function in the same projection, which "
                                + "Pravaha cannot rewrite yet. Move the expression outside the GROUP BY.");
            }
        }
        ordinals.add(endOrdinal);
        schema.field("window_end", assigned.field(endOrdinal).type());
        return new ProjectOperator(assign, schema.build(), ordinals);
    }

    /** A schema with the assigner's two boundary columns appended. */
    private static StreamSchema appendBoundaries(StreamSchema input) {
        StreamSchema.Builder builder = StreamSchema.builder(input.name() + "_assigned");
        input.fields().forEach(field -> builder.field(field.name(), field.type()));
        builder.field("window_start", com.ash.messaging.pravaha.api.data.Types.timestamp());
        builder.field("window_end", com.ash.messaging.pravaha.api.data.Types.timestamp());
        return builder.build();
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
        //
        // Over a bounded input the argument does not apply: a read of a maintained view scans a
        // finite set of rows and stops, so the accumulators are bounded by the scan and released
        // when it ends. KeyedAggregate executes those, capped at a group count that refuses rather
        // than grows -- because "bounded by the scan" is only true if the scan is.
        if (!groupKeys.isEmpty() && !boundedInput) {
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
    /**
     * Finds the window assignment an aggregate sits on, through anything that preserves it.
     *
     * <p>What may be walked through is decided by one question: does this operator still emit the
     * window boundary columns, for the same rows, at the same ordinals? A projection does -- it
     * renumbers, and the boundaries are resolved by name afterwards. A filter does; it removes rows,
     * not columns. A lookup join does: it appends the dimension's columns on the right and leaves
     * the record's own, including its boundaries, exactly where they were.
     *
     * <p>A <em>stream-to-stream</em> join deliberately may not be walked through, and that is not
     * caution. Its output pairs rows from two independently windowed sides, so there is no single
     * window whose closing releases the state -- the boundary columns are still there and no longer
     * mean what a windowed aggregate needs them to mean. Admitting it would produce an aggregate
     * that looks bounded and is not, which is the exact failure the bounded-state check exists to
     * prevent.
     */
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
            if (current instanceof FilterOperator filter) {
                current = filter.input();
                continue;
            }
            if (current instanceof ComputeOperator compute) {
                // A projection that computes something -- SUM(amount * 2), or any expression in the
                // select list -- becomes a ComputeOperator rather than a ProjectOperator. Omitting
                // it here made the window assigner invisible, so `SUM(amount * 2) ... GROUP BY
                // window_start, window_end` was refused as an unbounded aggregate: a correct-looking
                // refusal for an entirely ordinary query. Safe for the same reason walking a
                // projection is, since the boundaries are resolved by name against the aggregate's
                // own input schema.
                current = compute.input();
                continue;
            }
            if (current instanceof LookupJoinOperator lookup) {
                current = lookup.input();
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
            if (schema.field(ordinal).name().equalsIgnoreCase("window_start")) {
                start = ordinal;
            } else if (schema.field(ordinal).name().equalsIgnoreCase("window_end")) {
                end = ordinal;
            }
        }
        if (start < 0) {
            return null;
        }
        if (end < 0) {
            // The grouped-window form -- GROUP BY TUMBLE(...) -- groups by the start alone, because
            // that is all Calcite projects; the end is a function of it and grouping by both would
            // be redundant. The operator still needs to know where the end is, but only to read it:
            // it emits one output column per group key, so a boundary that is not grouped is simply
            // not emitted, and adding it to the grouping to make it findable would add a column the
            // query never asked for and shift every ordinal above it.
            for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
                if (schema.field(ordinal).name().equalsIgnoreCase("window_end")) {
                    end = ordinal;
                    break;
                }
            }
        }
        return end >= 0 ? new int[] {start, end} : null;
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
