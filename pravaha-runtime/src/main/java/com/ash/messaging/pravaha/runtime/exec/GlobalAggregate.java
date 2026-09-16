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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;

/**
 * An aggregate over the whole stream, with no grouping key.
 *
 * <p>Bounded by construction -- one row of state, whatever the input volume -- which is why the
 * planner allows it while refusing a keyed {@code GROUP BY} until windowing can bound that one
 * (design section 9.6).
 *
 * <p>Accumulation is <strong>weighted</strong>, and that is the point rather than a detail. A row
 * arriving with weight {@code -1} decrements the count and subtracts from the sum, so a retraction
 * is handled by the same arithmetic as an insert. There is no separate retract path to get wrong,
 * which is the whole argument for Z-sets (design section 9.2).
 */
final class GlobalAggregate implements RowProcessor {

    private final AggregateOperator operator;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;

    private final long[] sums;
    private final long[] counts;

    /**
     * Per aggregate, the distinct values seen. Null unless that aggregate counts them.
     *
     * <p>A set, not a weighted map as the windowed form uses. This runs over a bounded read, which
     * has no retractions to invert: every row arrives once and the scan ends.
     */
    private final java.util.Set<Object>[] distincts;

    private final boolean[] seen;
    private long rowCount;
    private long lastTimestamp;
    private long lastSequence;

    GlobalAggregate(AggregateOperator operator, RowArena arena, RowProcessor downstream) {
        if (!operator.groupKeyOrdinals().isEmpty()) {
            // This class aggregates everything into one group, by design. Handed a keyed operator it
            // would ignore the keys and return a single row where the query asked for one per key --
            // a wrong answer that looks entirely plausible, which is the worst failure available.
            //
            // The SQL planner refuses a keyed unwindowed GROUP BY (PRV-2050) so this cannot normally
            // be reached. The check is here because the day somebody relaxes that refusal -- to
            // support GROUP BY over a bounded view read, which is a reasonable thing to want -- the
            // missing piece is a keyed aggregate operator, and the failure should say so rather than
            // quietly halving somebody's dashboard.
            throw new IllegalArgumentException("GlobalAggregate cannot execute a keyed GROUP BY on "
                    + operator.groupKeyOrdinals() + "; a keyed unwindowed aggregate operator does not "
                    + "exist yet, and running this one would ignore the keys and return a single row");
        }
        this.operator = operator;
        this.arena = arena;
        this.downstream = downstream;
        this.layout = RowLayout.of(operator.outputSchema());
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);
        int n = operator.aggregates().size();
        this.sums = new long[n];
        this.counts = new long[n];
        @SuppressWarnings("unchecked")
        java.util.Set<Object>[] sets = new java.util.Set[n];
        this.distincts = sets;
        this.seen = new boolean[n];
    }

    @Override
    public void process(RowView row) {
        long weight = row.weight();
        if (weight == 0) {
            // A consolidated row: it contributes nothing and must not be counted.
            return;
        }
        rowCount += weight;
        lastTimestamp = row.eventTimestampNanos();
        lastSequence = row.sequence();

        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (int i = 0; i < calls.size(); i++) {
            AggregateOperator.AggregateCall call = calls.get(i);
            switch (call.kind()) {
                case COUNT -> {
                    // COUNT(*) counts rows; COUNT(col) counts rows where col is not null. This
                    // branch counted rows either way, so COUNT(n) silently reported COUNT(*) --
                    // five where four values existed, and no way to tell from the answer. The SUM
                    // and MIN/MAX branches beside it had the check all along.
                    if (call.argumentOrdinal() < 0 || !row.isNull(call.argumentOrdinal())) {
                        counts[i] += weight;
                    }
                }
                case COUNT_DISTINCT -> {
                    // Bounded by the scan, exactly as KeyedAggregate's is. The refusal this used to
                    // throw belongs at planning time, where it can tell a continuous registration
                    // from a finite read; thrown here it also refused the bounded read, so a
                    // construct CONTINUOUS_QUERIES.md marks supported could not be run on the only surface
                    // that was supposed to support it.
                    if (call.argumentOrdinal() >= 0 && !row.isNull(call.argumentOrdinal())) {
                        if (distincts[i] == null) {
                            distincts[i] = new java.util.HashSet<>();
                        }
                        distincts[i].add(read(row, call.argumentOrdinal()));
                    }
                }
                case SUM, AVG -> {
                    if (call.argumentOrdinal() >= 0 && !row.isNull(call.argumentOrdinal())) {
                        sums[i] += row.getLong(call.argumentOrdinal()) * weight;
                        counts[i] += weight;
                    }
                }
                case MIN, MAX -> {
                    if (weight < 0) {
                        // MIN and MAX are not invertible: knowing the current extreme does not tell
                        // you the previous one once it is retracted. Doing this correctly needs an
                        // ordered multiset per group, which lands with the aggregate lift in Wave 4.
                        throw new PravahaException(
                                RuntimeErrors.UNSUPPORTED_AGGREGATE,
                                call.kind() + " cannot yet handle a retraction: restoring the previous "
                                        + "extreme needs an ordered multiset per group, which arrives with "
                                        + "the aggregate lift. Use SUM or COUNT for now.");
                    }
                    if (call.argumentOrdinal() >= 0 && !row.isNull(call.argumentOrdinal())) {
                        long value = row.getLong(call.argumentOrdinal());
                        if (!seen[i]) {
                            sums[i] = value;
                            seen[i] = true;
                        } else if (call.kind() == AggregateOperator.AggregateCall.Kind.MIN) {
                            sums[i] = Math.min(sums[i], value);
                        } else {
                            sums[i] = Math.max(sums[i], value);
                        }
                    }
                }
            }
        }
    }

    /** Emits the accumulated result. Called when the input ends. */
    /**
     * Emits the running total, retracting the one emitted before it.
     *
     * <p>This is what makes an unwindowed aggregate a <em>continuous</em> one. {@code emit} was
     * wired only into the pipeline's finishers, which run when the input ends -- and a stream does
     * not end, so a registered {@code SELECT COUNT(*) FROM txn} reported RUNNING and produced
     * nothing, for ever. The one time a number appeared it was because the lane had crashed.
     *
     * <p>Re-emitting is a retraction of the previous answer and an insert of the new one, which is
     * how every other change in this engine is expressed. Without the retraction each emission would
     * be a separate row and the view would accumulate one per tick.
     */
    void emitIncremental() {
        if (rowCount == 0 && !emittedBefore) {
            // Nothing has arrived. An aggregate over no rows is a question with no answer yet, not
            // an answer of zero -- and emitting one would put a row in the view that no data
            // supports.
            return;
        }
        if (emittedBefore) {
            writeResult(previous, -1L);
        }
        long[] current = currentValues();
        writeResult(current, 1L);
        previous = current;
        emittedBefore = true;
    }

    private long[] previous;
    private boolean emittedBefore;

    private long[] currentValues() {
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        long[] values = new long[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            values[i] = valueOf(i, calls.get(i));
        }
        return values;
    }

    private void writeResult(long[] values, long weight) {
        long handle = arena.allocate(layout.rowSize(256));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(RuntimeErrors.ARENA_EXHAUSTED, "no room to emit the aggregate result");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int i = 0; i < values.length; i++) {
            writer.setLong(i, values[i]);
        }
        writer.weight(weight)
                .eventTimestampNanos(lastTimestamp)
                .sequence(lastSequence)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private long valueOf(int i, AggregateOperator.AggregateCall call) {
        return switch (call.kind()) {
            case COUNT -> counts[i];
            case SUM, MIN, MAX -> sums[i];
            // Integer division, matching SQL's AVG over an integer column.
            case AVG -> counts[i] == 0 ? 0 : sums[i] / counts[i];
            case COUNT_DISTINCT -> distincts[i] == null ? 0 : distincts[i].size();
        };
    }

    /** One column as itself, so a distinct set holds values rather than slot bits. */
    private Object read(RowView row, int ordinal) {
        return switch (operator.input().outputSchema().field(ordinal).type().typeName()) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            case STRING -> row.getString(ordinal);
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "COUNT(DISTINCT ...) over a "
                                + operator.input()
                                        .outputSchema()
                                        .field(ordinal)
                                        .type()
                                        .typeName()
                                + " column is not supported");
        };
    }

    void emit() {
        long handle = arena.allocate(layout.rowSize(256));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(RuntimeErrors.ARENA_EXHAUSTED, "no room to emit the aggregate result");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));

        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (int i = 0; i < calls.size(); i++) {
            long value =
                    switch (calls.get(i).kind()) {
                        case COUNT -> counts[i];
                        case SUM, MIN, MAX -> sums[i];
                        // Integer division, matching SQL's AVG over an integer column.
                        case AVG -> counts[i] == 0 ? 0 : sums[i] / counts[i];
                        case COUNT_DISTINCT -> distincts[i] == null ? 0 : distincts[i].size();
                    };
            writer.setLong(i, value);
        }
        writer.weight(1L)
                .eventTimestampNanos(lastTimestamp)
                .sequence(lastSequence)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    /** Net rows seen, weights included. Negative is possible and legitimate. */
    long rowCount() {
        return rowCount;
    }
}
