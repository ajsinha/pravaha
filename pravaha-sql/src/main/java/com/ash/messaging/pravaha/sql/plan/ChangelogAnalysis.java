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
import java.util.function.Predicate;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
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
 * <p><strong>Where it is called.</strong> {@code QueryRegistry} calls {@link #checkAgainst} for a
 * registration that names a sink (ADR-043), before the sink is opened, telling it which streams are
 * read from a source that emits deletes (HLP-3). It is not called for {@code pravaha run}: that is
 * a bounded read where every operator emits once at the end of input, and this analysis has no
 * notion of boundedness, so it would refuse {@code examples/02-aggregate}, which is correct.
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
        return analyse(plan, stream -> false);
    }

    /**
     * Works out what a plan emits, given which of the streams it reads can delete.
     *
     * <p>HLP-3. The one-argument form assumes every source only appends, which stopped being true
     * with {@code postgres-cdc}: a delete arrives as a row at weight {@code -1}, and everything
     * above the scan passes it on -- a filter as a retracted row, a join as the retracted pairs its
     * insert produced (the bilinear rule). So a join over two streams, append-only over files and
     * revising over a change feed, was admitted to an append-only sink either way.
     *
     * @param retracts whether rows read from the named stream can carry a negative weight; the
     *     registry answers it from the source bound to the stream
     */
    public static Result analyse(PhysicalOperator plan, Predicate<String> retracts) {
        return switch (plan) {
            case ScanOperator scan
            when retracts.test(scan.streamName()) ->
                new Result(
                        EnumSet.of(EmitMode.RETRACT, EmitMode.UPSERT),
                        "stream '" + scan.streamName() + "' is read from a source that emits deletes, so a "
                                + "row it delivers can later be withdrawn, and whatever this query built from "
                                + "it -- a join's pair included -- is withdrawn with it");
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
                    Result below = analyse(input, retracts);
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
        checkAgainst(plan, stream -> false, sink, sinkName);
    }

    /** As {@link #checkAgainst(PhysicalOperator, SinkCapabilities, String)}, knowing which streams delete. */
    public static void checkAgainst(
            PhysicalOperator plan, Predicate<String> retracts, SinkCapabilities sink, String sinkName) {
        Result result = analyse(plan, retracts);
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
