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
import com.ash.messaging.pravaha.runtime.AggregateTotals;
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
     * What each {@code SUM} carries past its 64-bit total inside a batch, in units of {@code 2^64}
     * (TRANSOVF-1, {@link AggregateTotals}). Zero once the batch is settled.
     */
    private final long[] excess;

    /** Whether any {@link #excess} may be nonzero, so settling a batch that never left the range is free. */
    private boolean unsettled;

    /**
     * Per aggregate, the distinct values seen. Null unless that aggregate counts them.
     *
     * <p>A set, not a weighted map as the windowed form uses. This runs over a bounded read, which
     * has no retractions to invert: every row arrives once and the scan ends.
     */
    private final java.util.Set<Object>[] distincts;

    private final boolean[] seen;

    /** Each call's argument type and each output column's type, for {@link AggregateSlots} (HLP-1). */
    private final com.ash.messaging.pravaha.api.data.TypeName[] argumentTypes;

    private final com.ash.messaging.pravaha.api.data.TypeName[] outputTypes;

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
        this.excess = new long[n];
        @SuppressWarnings("unchecked")
        java.util.Set<Object>[] sets = new java.util.Set[n];
        this.distincts = sets;
        this.seen = new boolean[n];
        this.argumentTypes = AggregateSlots.typesOf(
                operator.input().outputSchema(),
                operator.aggregates().stream()
                        .map(AggregateOperator.AggregateCall::argumentOrdinal)
                        .toList());
        this.outputTypes = AggregateSlots.typesOf(
                operator.outputSchema(),
                java.util.stream.IntStream.range(0, n).boxed().toList());
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
            try {
                accumulate(row, weight, i, call);
            } catch (ArithmeticException overflow) {
                throw AggregateTotals.overflow(
                        AggregateSlots.describe(call, operator.input().outputSchema()), overflow);
            }
        }
    }

    /** Folds one row into call {@code i}'s accumulators; every sum and count is checked (SUMWRAP-1). */
    private void accumulate(RowView row, long weight, int i, AggregateOperator.AggregateCall call) {
        switch (call.kind()) {
            case COUNT -> {
                // COUNT(*) counts rows; COUNT(col) counts rows where col is not null. This
                // branch counted rows either way, so COUNT(n) silently reported COUNT(*) --
                // five where four values existed, and no way to tell from the answer. The SUM
                // and MIN/MAX branches beside it had the check all along.
                if (call.argumentOrdinal() < 0 || !row.isNull(call.argumentOrdinal())) {
                    counts[i] = AggregateTotals.add(counts[i], weight);
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
                    // Netted over the batch in 128 bits; settle() refuses what does not fit.
                    AggregateTotals.addWeighted(
                            sums,
                            excess,
                            i,
                            AggregateSlots.read(
                                    row,
                                    call.argumentOrdinal(),
                                    argumentTypes[i],
                                    operator.input().outputSchema()),
                            weight);
                    unsettled |= excess[i] != 0;
                    counts[i] = AggregateTotals.add(counts[i], weight);
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
                    long value = AggregateSlots.read(
                            row,
                            call.argumentOrdinal(),
                            argumentTypes[i],
                            operator.input().outputSchema());
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

    /**
     * ADR-039 item 6: folds a pre-combined partial into the same accumulators {@link #process}
     * would have built from the rows it summarises -- see {@code ReadRequest.PartialAggregate}.
     *
     * <p>The arithmetic is {@link #process}'s own, scaled: a row contributes {@code weight} to
     * {@code counts[i]}, or {@code value * weight} to {@code sums[i]}; a partial contributes {@code
     * weight} times its own already-combined count or sum instead of one row's worth. That scaling
     * is what makes retraction ({@code weight < 0}) correct here for exactly the same reason it is
     * correct per row: {@code counts[i] += weight * partialCount} undoes {@code counts[i] += weight
     * * partialCount} from an earlier call precisely, which is the property restricting this to
     * {@code COUNT} and {@code SUM} exists to preserve -- both compose under addition and invert
     * under subtraction. {@code MIN}/{@code MAX} do neither, which is why {@code SourcePushdown}
     * never offers a source one to push.
     *
     * <p>{@link #rowCount} is incremented by {@code weight} alone, not by {@code weight} times a
     * row count -- a partial for a query with no {@code COUNT} call (say, {@code SELECT SUM(amount)
     * FROM t}) carries no row count to scale by. That field exists only to answer "has anything
     * arrived yet" for {@link #emitIncremental}; it is not read by any aggregate's own arithmetic,
     * so undercounting it here changes nothing this class computes, only what {@link #rowCount()}
     * would report to a caller that reads it as a literal row count while partials are in use.
     *
     * @param partial one column per {@link #operator}'s aggregate call, in {@code
     *     operator.aggregates()} order -- the same layout {@link #emit} itself writes, since a
     *     partial is exactly a miniature aggregate result
     * @param weight the Z-set weight the summarised rows arrived (or are being retracted) with
     * @throws IllegalStateException if {@code operator} has a call this method cannot fold a
     *     partial into -- unreachable in practice, since {@code SourcePushdown} only ever offers a
     *     source a partial for an aggregate whose every call is {@code COUNT} or {@code SUM}
     */
    void processPartial(RowView partial, long weight) {
        if (weight == 0) {
            return;
        }
        rowCount += weight;
        lastTimestamp = partial.eventTimestampNanos();
        lastSequence = partial.sequence();

        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (int i = 0; i < calls.size(); i++) {
            AggregateOperator.AggregateCall call = calls.get(i);
            long partialValue = partial.getLong(i);
            try {
                switch (call.kind()) {
                    case COUNT -> counts[i] = AggregateTotals.addWeighted(counts[i], partialValue, weight);
                    case SUM -> {
                        AggregateTotals.addWeighted(sums, excess, i, partialValue, weight);
                        unsettled |= excess[i] != 0;
                        // A partial with a total makes the SUM present, so it is not NULL
                        // (ALLNULLAGG-1); a NULL partial -- a source's SUM of only nulls -- does not.
                        if (!partial.isNull(i)) {
                            counts[i] = AggregateTotals.add(counts[i], weight);
                        }
                    }
                    default ->
                        throw new IllegalStateException("processPartial received a " + call.kind()
                                + " call; SourcePushdown never offers partial-aggregate pushdown for anything "
                                + "but COUNT and SUM, so this aggregate should never have been given a partial "
                                + "for it");
                }
            } catch (ArithmeticException overflow) {
                throw AggregateTotals.overflow(
                        AggregateSlots.describe(call, operator.input().outputSchema()), overflow);
            }
        }
    }

    /**
     * Refuses a batch whose net total does not fit in 64 bits (TRANSOVF-1): called at the end of
     * every batch, and before anything reads a total, so a total that left the range inside the
     * batch and came back is accepted, and one that did not is PRV-3025 naming the aggregate.
     */
    void settle() {
        if (!unsettled) {
            return;
        }
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (int i = 0; i < excess.length; i++) {
            try {
                AggregateTotals.settled(sums[i], excess[i]);
            } catch (ArithmeticException overflow) {
                throw AggregateTotals.overflow(
                        AggregateSlots.describe(calls.get(i), operator.input().outputSchema()), overflow);
            }
        }
        unsettled = false;
    }

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
        settle();
        if (rowCount == 0 && !emittedBefore) {
            // Nothing has arrived. An aggregate over no rows is a question with no answer yet, not
            // an answer of zero -- and emitting one would put a row in the view that no data
            // supports.
            return;
        }
        if (emittedBefore) {
            writeResult(previous, -1L);
        }
        AggregateSlots.Answer current = currentValues();
        writeResult(current, 1L);
        previous = current;
        emittedBefore = true;
    }

    private AggregateSlots.Answer previous;
    private boolean emittedBefore;

    /**
     * Whether a lane drives this aggregate, rather than a bounded read.
     *
     * <p>It is the difference between the two things "the input ended" can mean, and the two are
     * not the same answer. A bounded read publishes at the end because that is the only moment it
     * has anything to publish, and an aggregate over no rows there answers zero -- the rows really
     * were all of them. A registered query has been publishing on every tick, so the view already
     * holds its last answer, and the end of the input is one more change to that answer or no
     * change at all.
     *
     * <p>Set by {@link InterpretedPipeline#drivenContinuously()} when the pipeline is put on a
     * lane, which is the one place where the distinction is a fact rather than a guess.
     */
    private boolean continuous;

    void drivenContinuously() {
        this.continuous = true;
    }

    private AggregateSlots.Answer currentValues() {
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        long[] values = new long[calls.size()];
        boolean[] nulls = new boolean[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            nulls[i] = isNull(i, calls.get(i));
            values[i] = nulls[i] ? 0 : valueOf(i, calls.get(i));
        }
        return new AggregateSlots.Answer(values, nulls);
    }

    /** Whether call {@code i}'s answer is NULL: a SUM, AVG, MIN or MAX of no non-null value (ALLNULLAGG-1). */
    private boolean isNull(int i, AggregateOperator.AggregateCall call) {
        return AggregateSlots.isNull(call.kind(), counts[i], seen[i]);
    }

    private void writeResult(AggregateSlots.Answer answer, long weight) {
        long[] values = answer.values();
        long handle = arena.allocate(layout.rowSize(256));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(RuntimeErrors.ARENA_EXHAUSTED, "no room to emit the aggregate result");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int i = 0; i < values.length; i++) {
            AggregateSlots.write(writer, i, values[i], answer.nulls()[i], outputTypes[i]);
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
            // NANGROUP-1: one distinct value for either zero, one for every NaN.
            case FLOAT32 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.canonical(row.getFloat(ordinal));
            case FLOAT64 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.canonical(row.getDouble(ordinal));
            case STRING -> row.getString(ordinal);
            // The whole unscaled value (DECKEYGROUP-1), as a windowed COUNT(DISTINCT) reads it.
            case DECIMAL ->
                new com.ash.messaging.pravaha.runtime.window.DecimalBits(
                        row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal));
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

    /**
     * Publishes the answer because the input has ended.
     *
     * <p>On a lane that is the change since the answer this aggregate last published, and nothing
     * at all when there has been no change. Inserting the published answer a second time was
     * CKPT-3: closing a query doubled its row's weight in the view a moment before the view was
     * discarded, and handed every subscriber a {@code +1} for an answer they already had, with no
     * retraction to pair it with. An upsert sink absorbed the repeat and an append-only sink is
     * refused a revising query, so what it cost was the subscribers and the view's own arithmetic
     * -- but a duplicate that happens to be survivable is still a duplicate.
     *
     * <p>For a bounded read nothing has been published before, so this writes the whole answer
     * once, including the answer an aggregate over no rows has -- {@code COUNT} 0, {@code SUM},
     * {@code AVG}, {@code MIN} and {@code MAX} NULL: there, the absence of rows is the answer rather
     * than a question not yet answered.
     */
    void emit() {
        settle();
        if (continuous) {
            if (emittedBefore && currentValues().equals(previous)) {
                return;
            }
            emitIncremental();
            return;
        }
        long handle = arena.allocate(layout.rowSize(256));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(RuntimeErrors.ARENA_EXHAUSTED, "no room to emit the aggregate result");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));

        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        for (int i = 0; i < calls.size(); i++) {
            if (AggregateSlots.exactAverage(calls.get(i).kind(), outputTypes[i])) {
                AggregateSlots.writeAverage(writer, i, sums[i], counts[i], operator.outputSchema());
                continue;
            }
            // SQL: SUM, AVG, MIN and MAX over no non-null value -- no rows at all, or only nulls
            // -- are NULL; COUNT is 0 (ALLNULLAGG-1).
            AggregateSlots.write(writer, i, valueOf(i, calls.get(i)), isNull(i, calls.get(i)), outputTypes[i]);
        }
        writer.weight(1L)
                .eventTimestampNanos(lastTimestamp)
                .sequence(lastSequence)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    /**
     * Writes everything this aggregate needs to resume where it stood: the accumulators, and the
     * answer it last published.
     *
     * <p>Both halves matter, and the second is the one that is easy to miss. The accumulators are
     * what the next answer is computed from; the last published answer is what the next emission
     * retracts. A checkpoint carries the served view as it stood at the cut, and that view holds
     * exactly this answer -- so an aggregate restored without it publishes its next answer beside
     * the restored one instead of in place of it. Neither was written, once (CKPT-2): a restarted
     * {@code SELECT COUNT(*), SUM(amount)} came back empty beside a view holding {@code [2, 350]},
     * and the next row put {@code [1, 75]} next to it.
     */
    void writeTo(java.io.DataOutput out) throws java.io.IOException {
        // A checkpoint is taken between batches, so there is nothing to carry; settling here makes
        // that a fact rather than an assumption about the caller.
        settle();
        int n = sums.length;
        // A published answer with a NULL in it (ALLNULLAGG-1) is marked by the count written
        // bitwise inverted, and its null flags follow its values. Without one the bytes are what they
        // were, and a checkpoint from before the flags reads as an answer with no NULL.
        boolean withNulls = emittedBefore && previous.anyNull();
        out.writeInt(withNulls ? ~n : n);
        out.writeLong(rowCount);
        out.writeLong(lastTimestamp);
        out.writeLong(lastSequence);
        for (int i = 0; i < n; i++) {
            out.writeLong(sums[i]);
            out.writeLong(counts[i]);
            out.writeBoolean(seen[i]);
            writeDistinct(out, distincts[i]);
        }
        out.writeBoolean(emittedBefore);
        if (emittedBefore) {
            previous.writeValues(out);
            if (withNulls) {
                previous.writeNulls(out);
            }
        }
    }

    /**
     * Restores what {@link #writeTo} wrote, replacing whatever this aggregate held.
     *
     * @throws java.io.IOException if the snapshot was written for a different number of aggregate
     *     calls -- a different query, whose accumulators would be read into the wrong columns
     */
    void readFrom(java.io.DataInput in) throws java.io.IOException {
        int n = in.readInt();
        boolean withNulls = n < 0;
        if (withNulls) {
            n = ~n;
        }
        if (n != sums.length) {
            throw new java.io.IOException("the checkpointed aggregate computes " + n + " values and this one computes "
                    + sums.length + ": the query changed since the checkpoint was taken");
        }
        rowCount = in.readLong();
        unsettled = false;
        lastTimestamp = in.readLong();
        lastSequence = in.readLong();
        for (int i = 0; i < n; i++) {
            sums[i] = in.readLong();
            excess[i] = 0;
            counts[i] = in.readLong();
            seen[i] = in.readBoolean();
            distincts[i] = readDistinct(in);
        }
        emittedBefore = in.readBoolean();
        previous = null;
        if (emittedBefore) {
            previous = AggregateSlots.Answer.read(in, n, withNulls);
        }
    }

    // A continuous registration refuses COUNT(DISTINCT) over an unwindowed stream (PRV-2050), so
    // these sets are absent from every checkpoint written today. They are written anyway: leaving a
    // field of this class out of its snapshot is exactly the defect the two methods above close,
    // and the day that refusal is relaxed the set must not be the next thing forgotten.
    private static final int NO_SET = -1;

    static void writeDistinct(java.io.DataOutput out, java.util.Set<Object> values) throws java.io.IOException {
        if (values == null) {
            out.writeInt(NO_SET);
            return;
        }
        out.writeInt(values.size());
        for (Object value : values) {
            switch (value) {
                case Boolean v -> {
                    out.writeByte(0);
                    out.writeBoolean(v);
                }
                case Byte v -> {
                    out.writeByte(1);
                    out.writeByte(v);
                }
                case Short v -> {
                    out.writeByte(2);
                    out.writeShort(v);
                }
                case Integer v -> {
                    out.writeByte(3);
                    out.writeInt(v);
                }
                case Long v -> {
                    out.writeByte(4);
                    out.writeLong(v);
                }
                case Float v -> {
                    out.writeByte(5);
                    out.writeFloat(v);
                }
                case Double v -> {
                    out.writeByte(6);
                    out.writeDouble(v);
                }
                case String v -> {
                    out.writeByte(7);
                    out.writeUTF(v);
                }
                case com.ash.messaging.pravaha.runtime.window.DecimalBits v -> {
                    // DECKEYGROUP-1: both halves; a new tag, so a checkpoint without one is unchanged.
                    out.writeByte(8);
                    out.writeLong(v.high());
                    out.writeLong(v.low());
                }
                default ->
                    throw new java.io.IOException("cannot checkpoint a distinct value of type "
                            + value.getClass().getName());
            }
        }
    }

    static java.util.Set<Object> readDistinct(java.io.DataInput in) throws java.io.IOException {
        int size = in.readInt();
        if (size == NO_SET) {
            return null;
        }
        if (size < 0) {
            throw new java.io.IOException("a distinct set of " + size + " values");
        }
        java.util.Set<Object> values = new java.util.HashSet<>();
        for (int i = 0; i < size; i++) {
            byte tag = in.readByte();
            values.add(
                    switch (tag) {
                        case 0 -> in.readBoolean();
                        case 1 -> in.readByte();
                        case 2 -> in.readShort();
                        case 3 -> in.readInt();
                        case 4 -> in.readLong();
                        // NANGROUP-1: canonical since; a value that is not is refused.
                        case 5 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.restored(in.readFloat());
                        case 6 -> com.ash.messaging.pravaha.runtime.window.GroupDoubles.restored(in.readDouble());
                        case 7 -> in.readUTF();
                        case 8 ->
                            new com.ash.messaging.pravaha.runtime.window.DecimalBits(in.readLong(), in.readLong());
                        default -> throw new java.io.IOException("unknown distinct value tag " + tag);
                    });
        }
        return values;
    }

    /**
     * The accumulators this aggregate holds, as text (ADR-048). One entry, because an unkeyed
     * aggregate is one row of state.
     *
     * <p>Read-only: {@link #emit} and {@link #emitIncremental} push rows downstream, and an
     * inspection that did that would change the answer it was asked about.
     */
    void describe(java.util.function.BiConsumer<String, java.util.Map<String, String>> into) {
        List<AggregateOperator.AggregateCall> calls = operator.aggregates();
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("rows", Long.toString(rowCount));
        for (int i = 0; i < calls.size(); i++) {
            values.put(
                    calls.get(i).outputName(),
                    isNull(i, calls.get(i))
                            ? "null"
                            : AggregateSlots.text(valueOf(i, calls.get(i)), operator.outputSchema(), i));
        }
        into.accept("", values);
    }

    /** Net rows seen, weights included. Negative is possible and legitimate. */
    long rowCount() {
        return rowCount;
    }
}
