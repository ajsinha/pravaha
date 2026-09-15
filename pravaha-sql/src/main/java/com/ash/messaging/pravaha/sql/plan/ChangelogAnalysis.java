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

import java.util.EnumSet;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * What a plan actually emits, and whether the sink can take it.
 *
 * <p>Design section 15.5's gap G8. A query is not simply "a stream of rows": some plans only ever
 * add rows, and some revise answers they have already given. A {@code SELECT ... WHERE} over an
 * append-only source can only append. A global aggregate revises its answer on every record. A
 * windowed aggregate with allowed lateness revises a window when a late record corrects it.
 *
 * <p><strong>The mismatch has to be caught at registration.</strong> Discovered at runtime it looks
 * like this: the query runs, results are written, and a retraction arrives at a sink that has no
 * concept of one -- so it is written as another row. The sink now holds both the old answer and the
 * new one, the totals downstream are double, and nothing has failed. Minutes of checking at
 * registration against days of reconciliation afterwards.
 *
 * <p>The message names the operator responsible, because "this query produces updates" is not
 * actionable when the query is forty lines long and only one of its clauses is the reason.
 *
 * <p><strong>Nothing calls {@link #checkAgainst}, and that is the answer rather than an omission.</strong>
 * Wave 8 went looking for the registration it is supposed to happen at and there is none, because
 * there is nothing to register against: no {@code INSERT INTO}, no {@code pravaha.sinks} block, no
 * {@code ServiceLoader} declaration for {@code StreamSinkPlugin}, and no code that resolves a sink
 * by name. Every continuous query in this product writes to a {@code ViewSink}, which reads the
 * Z-set weight and applies a retraction as a removal -- so the mismatch this class exists to catch
 * cannot occur on the only path that revises. The one sink binding anywhere is {@code QueryRunner}'s
 * hard-coded append-only filesystem sink on {@code pravaha run}, and that is a bounded read where
 * every operator emits once at the end of input and no retraction is produced; checking it there
 * would refuse {@code examples/02-aggregate}, which is documented, runs today, and is correct.
 *
 * <p>So this is kept, unwired, and the reason is pinned by a test rather than a comment:
 * {@code ErrcSqlTest} asserts that no sink service declaration exists and that {@code QueryRunner}
 * is still the only file that binds one. The first binding that can carry a revising query fails
 * that test, and this is what it should call (W8-13). The analysis itself is the part worth keeping
 * -- which plan shapes revise is a fact about the algebra, not about the wiring -- but note it has
 * no notion of boundedness, which is exactly why it is wrong for {@code pravaha run}.
 */
public final class ChangelogAnalysis {

    /**
     * The modes a plan's output can be delivered in, and why it needs them.
     *
     * <p>The set is what the plan is <em>compatible with</em>, not what it emits. A plan that only
     * ever appends is compatible with all three modes, because an append-only stream is a legal
     * upsert stream and a legal retract stream; a plan that revises is compatible with upsert and
     * retract only. Reading it the other way round -- as "what it emits" -- makes an append-only
     * plan look like it needs three modes and a revising one look narrower, which is backwards.
     */
    public record Result(Set<EmitMode> produces, String reason) {

        /** True when the plan can revise an answer it has already given. */
        public boolean producesUpdates() {
            return !produces.contains(EmitMode.APPEND);
        }
    }

    private ChangelogAnalysis() {}

    /**
     * Works out what a plan emits.
     *
     * <p>Bottom-up and pessimistic: an operator that can revise makes everything above it able to
     * revise, because a corrected input produces a corrected output. Being wrong in the optimistic
     * direction means admitting a query that corrupts a sink, so where the analysis is unsure it
     * says the stronger thing.
     */
    public static Result analyse(PhysicalOperator plan) {
        return switch (plan) {
            case WindowedAggregateOperator windowed -> {
                if (windowed.allowedLatenessNanos() > 0) {
                    yield new Result(
                            EnumSet.of(EmitMode.RETRACT, EmitMode.UPSERT),
                            "the windowed aggregate allows " + windowed.allowedLatenessNanos() / 1_000_000
                                    + " ms of lateness, so a late record re-emits a window it has already "
                                    + "reported -- as a retraction of the old answer and the corrected one");
                }
                // Without lateness a window fires exactly once and never revises. That is what makes
                // a tumbling window with no lateness safe for an append-only sink, and it is worth
                // stating because it is the only aggregate shape that is.
                yield new Result(EnumSet.of(EmitMode.APPEND, EmitMode.UPSERT, EmitMode.RETRACT), "");
            }
            case AggregateOperator aggregate ->
                new Result(
                        EnumSet.of(EmitMode.RETRACT, EmitMode.UPSERT),
                        "the aggregate over " + aggregate.input().outputSchema().name()
                                + " revises its answer on every record: each new row makes the previous result "
                                + "wrong, so the previous result has to be withdrawn");
            default -> {
                Result strongest = new Result(EnumSet.of(EmitMode.APPEND, EmitMode.UPSERT, EmitMode.RETRACT), "");
                for (PhysicalOperator input : plan.inputs()) {
                    Result below = analyse(input);
                    if (below.producesUpdates()) {
                        strongest = below;
                    }
                }
                yield strongest;
            }
        };
    }

    /**
     * Checks a plan against a sink, and refuses the pair rather than the query.
     *
     * <p>Refusing the <em>pair</em> matters: the query may be perfectly good against a different
     * sink, and the fix is usually to change the sink or its key rather than the SQL. A message that
     * blames the query sends somebody to rewrite something that was never wrong.
     */
    public static void checkAgainst(PhysicalOperator plan, SinkCapabilities sink, String sinkName) {
        Result result = analyse(plan);
        for (EmitMode mode : result.produces()) {
            if (sink.accepts(mode)) {
                return;
            }
        }

        throw new PravahaException(
                SqlErrors.EMIT_MODE_MISMATCH,
                "sink '" + sinkName + "' accepts " + sink.emitModes() + ", but this query needs one of "
                        + result.produces() + ".\n  Why: " + result.reason() + ".\n"
                        + "  Fix: point it at a sink that supports keyed upsert (Aerospike, Redis, JDBC, a "
                        + "compacted Kafka topic), or make the query append-only -- a tumbling window with no "
                        + "allowed lateness fires each window once and never revises it.");
    }
}
