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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * Refuses, at registration, a query whose answer depends on how many times a row arrived, over a
 * source that repeats rows (SCAN-1, {@code PRV-2042}).
 *
 * <p>A source that repeats rows -- the {@code cassandra} source re-reading its whole token range on
 * every pass, the {@code aerospike} {@code lut-scan} re-reading an updated record without retracting
 * the old one, a {@code jdbc} poll re-reading a row whose modification timestamp moved -- delivers
 * every copy at weight {@code +1}. Nothing downstream can tell a copy from a new row. So:
 *
 * <ul>
 *   <li><strong>An aggregate</strong>, windowed or not, counts each copy: {@code COUNT(*)} over a
 *       Cassandra table grows by the table's size every pass, and {@code SUM} with it. {@code MIN},
 *       {@code MAX} and {@code COUNT(DISTINCT)} are refused as well: they happen to survive a copy of
 *       an unchanged row, but an update arrives as the new row with no retraction of the old, so the
 *       old value is never withdrawn from them either, and one rule an operator can predict is worth
 *       more than a list of exceptions.
 *   <li><strong>A join</strong> multiplies: each copy pairs again with every row it matches, and a
 *       retraction from the other side withdraws one pair while the copies keep it alive.
 *   <li><strong>A sink that cannot upsert by key</strong> -- an append-only file, a changelog topic,
 *       {@code jdbc-sink} with {@code mode: append} -- writes each copy as another row or event.
 * </ul>
 *
 * <p><strong>What is admitted, and why.</strong> A projection or filter (with computed columns, and
 * a lookup join, which enriches one row at a time) served as a keyed view. The served view is a
 * Z-set keyed by the registration's key columns: a copy raises its key's weight from 1 to 2 and
 * overwrites the row with the same values, so a keyed read or a scan returns each row once, as the
 * store holds it. A source that repeats never retracts -- that is what makes it repeat rather than
 * change -- so a weight above 1 is never walked back down past a row that should still be there. A
 * subscriber does see each copy, as a {@code +1} of values it already has, which is an upsert of
 * the same row; a subscriber that sums weights instead of overwriting by key would count the copies,
 * and {@code docs/TROUBLESHOOTING.md} says so under this code. A sink that upserts by key is admitted
 * for the same reason as the view.
 *
 * <p>Checked for every registration, not only one that names a sink: a view is where SCAN-1 was
 * wrong, and a view with no sink is the common case.
 */
public final class RepeatedRowsAnalysis {

    /** A stream read from a source that repeats, and the plugin bound to it. */
    private record Repeating(String stream, String plugin) {}

    private RepeatedRowsAnalysis() {}

    /**
     * Refuses the plan when its answer depends on multiplicity over a source that repeats rows.
     *
     * @param repeating for a stream, the plugin bound to it when that source repeats rows, empty when
     *     it does not or when nothing is bound. Asked once per stream
     * @param sink what the named sink accepts, or null when the registration names none
     * @param sinkName the sink's name, for the message; ignored without a sink
     */
    public static void check(
            PhysicalOperator plan,
            Function<String, Optional<String>> repeating,
            SinkCapabilities sink,
            String sinkName) {
        Map<String, Optional<String>> asked = new LinkedHashMap<>();
        Function<String, Optional<String>> once = stream -> asked.computeIfAbsent(stream, repeating);
        Optional<Repeating> repeated = firstRepeating(plan, once);
        if (repeated.isEmpty()) {
            return;
        }
        String offending = offendingOperator(plan, once);
        if (offending != null) {
            throw refusal(repeated.get(), offending, asked);
        }
        if (sink != null && !sink.accepts(EmitMode.UPSERT)) {
            throw refusal(
                    repeated.get(),
                    "sink '" + sinkName + "' accepts " + sink.emitModes() + " and cannot upsert by key, so it "
                            + "would write every copy as another row",
                    asked);
        }
    }

    /**
     * The lowest operator whose answer depends on multiplicity and which reads, directly or below it,
     * a stream from a source that repeats; null when there is none.
     */
    private static String offendingOperator(PhysicalOperator plan, Function<String, Optional<String>> repeating) {
        for (PhysicalOperator input : plan.inputs()) {
            String below = offendingOperator(input, repeating);
            if (below != null) {
                return below;
            }
        }
        if (firstRepeating(plan, repeating).isEmpty()) {
            return null;
        }
        return switch (plan) {
            case AggregateOperator aggregate ->
                "the aggregate over " + aggregate.input().outputSchema().name() + " counts every copy of a row "
                        + "as another row, so its answer grows on every pass rather than following the store";
            case WindowedAggregateOperator windowed ->
                "the windowed aggregate over " + windowed.input().outputSchema().name() + " counts every copy "
                        + "of a row in the window it falls in";
            case JoinOperator join ->
                "the join pairs every copy of a row again with each row it matches, and a copy keeps a pair "
                        + "alive after the other side retracts it";
            case com.ash.messaging.pravaha.runtime.plan.TopNOperator topN ->
                "the top-N numbers every copy of a row as another row, so a copy takes a place in the first N "
                        + "that belongs to a different row";
            default -> null;
        };
    }

    /** The plugin behind the first stream under this operator whose source repeats, if any. */
    private static Optional<Repeating> firstRepeating(
            PhysicalOperator plan, Function<String, Optional<String>> repeating) {
        if (plan instanceof ScanOperator scan) {
            return repeating.apply(scan.streamName()).map(plugin -> new Repeating(scan.streamName(), plugin));
        }
        for (PhysicalOperator input : plan.inputs()) {
            Optional<Repeating> found = firstRepeating(input, repeating);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static PravahaException refusal(Repeating first, String why, Map<String, Optional<String>> asked) {
        String stream = first.stream();
        String plugin = first.plugin();
        StringBuilder repeating = new StringBuilder();
        asked.forEach((name, bound) -> bound.ifPresent(p -> repeating
                .append(repeating.isEmpty() ? "" : ", ")
                .append("'")
                .append(name)
                .append("' (")
                .append(p)
                .append(")")));
        return new PravahaException(
                SqlErrors.SOURCE_REPEATS_ROWS,
                "stream '" + stream + "' is read from a " + plugin + " source that repeats rows: it delivers a row "
                        + "it has already delivered -- a scan re-reading an unchanged row, or an update read as a "
                        + "new row with nothing retracting the old -- and every copy arrives at weight +1.\n"
                        + "  Why this query is refused: " + why + ". Streams that repeat: " + repeating + ".\n"
                        + "  Fix: make the source an exact changelog. For a cassandra or aerospike binding, set "
                        + "`deletes: detect` on the binding for '" + stream + "' (with `deletes.state.dir`): each "
                        + "pass is then compared with what was emitted, so an unchanged row is not sent again, an "
                        + "update is a retraction and an insertion, and a delete is a retraction. For a jdbc "
                        + "binding, poll a watermark column that only an insert sets, with `key.column`, and say "
                        + "so with `watermark.moves.on.update: false`; or read the table through postgres-cdc. "
                        + "Otherwise keep this query a projection or filter served as a keyed view, where a copy "
                        + "only overwrites its own key.");
    }
}
