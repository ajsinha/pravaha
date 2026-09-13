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
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.runtime.window.SlicedAggregateState;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;

/**
 * The interpreted windowed aggregate.
 *
 * <p>Folds each row into its slice and fires whole windows when the watermark passes their end
 * (design section 15.3). The state lives in {@link SlicedAggregateState}, which is where the
 * arithmetic and the bounds are; this class is the part that knows about rows.
 *
 * <p><strong>Grouping is on a single composite key.</strong> The group columns are hashed into one
 * {@code long}, which is what the slice state is keyed by. That is a deliberate simplification with
 * a real consequence: two different key combinations that hash the same would be merged, silently.
 * The hash is a 64-bit mix, so at a million keys the chance of any collision is around
 * 3 x 10^-8 -- small, but not zero, and the honest fix is to carry the key bytes rather than a hash.
 * That belongs with the keyed state store in Wave 4's second half, and until then this is recorded
 * here rather than left for somebody to discover.
 *
 * <p>Firing is driven by {@link #advanceWatermark}, and end of input fires everything still open.
 * A bounded source -- a file, a backfill -- would otherwise leave its last windows unemitted, which
 * looks exactly like the query being wrong about its final period.
 *
 * <p><strong>Late data has three outcomes, not two</strong> (design section 15.4). A record whose
 * window has not closed is simply on time. One whose window has closed but is still within the
 * allowed lateness re-opens that window: the previous result is retracted with weight {@code -1} and
 * the corrected one emitted, which downstream consolidates to exactly the difference. One that is
 * later than that is <em>too late</em> -- its state is gone and cannot be reconstructed -- so it is
 * routed to the late output and counted, never silently dropped and never allowed to produce a
 * result that contradicts one already sent.
 */
final class WindowedAggregate implements RowProcessor {

    private final WindowedAggregateOperator operator;
    private final SlicedWindows windows;
    private final SlicedAggregateState state;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final long[] scratch;
    private final boolean[] present;
    private final List<Integer> valueOrdinals;
    private final List<com.ash.messaging.pravaha.api.data.TypeName> groupTypes;
    /** Group keys other than the window boundaries: the actual data keys. */
    private final List<Integer> dataKeyOrdinals;

    /** What each window last emitted per key, so a correction can retract it exactly. */
    private final java.util.Map<Long, java.util.Map<Long, long[]>> emitted = new java.util.HashMap<>();
    /** Windows a late record has changed since they last fired. */
    private final java.util.Set<Long> dirty = new java.util.LinkedHashSet<>();

    private java.util.function.Consumer<RowView> lateOutput = row -> {};
    private long lateRecords;
    private long corrections;

    private long watermark = Long.MIN_VALUE;
    private long lastFiredWatermark = Long.MIN_VALUE;
    private long highestEventTime = Long.MIN_VALUE;
    private long earliestWindowStart = Long.MAX_VALUE;

    WindowedAggregate(WindowedAggregateOperator operator, RowArena arena, RowProcessor downstream) {
        this.operator = operator;
        this.windows = new SlicedWindows(operator.spec());
        this.arena = arena;
        this.downstream = downstream;
        this.layout = RowLayout.of(operator.outputSchema());
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);

        SlicedAggregateState.Kind[] kinds =
                new SlicedAggregateState.Kind[operator.aggregates().size()];
        this.valueOrdinals = operator.aggregates().stream()
                .map(AggregateOperator.AggregateCall::argumentOrdinal)
                .toList();
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = switch (operator.aggregates().get(i).kind()) {
                case COUNT -> SlicedAggregateState.Kind.COUNT;
                case COUNT_DISTINCT -> SlicedAggregateState.Kind.COUNT_DISTINCT;
                case SUM -> SlicedAggregateState.Kind.SUM;
                case AVG -> SlicedAggregateState.Kind.AVG;
                case MIN -> SlicedAggregateState.Kind.MIN;
                case MAX -> SlicedAggregateState.Kind.MAX;
            };
        }
        this.state = new SlicedAggregateState(windows, kinds, operator.maxSlices());
        this.scratch = new long[kinds.length];
        this.present = new boolean[kinds.length];
        // The window boundaries are group keys in SQL and must NOT be part of the accumulator's key
        // here. The slice dimension already separates windows; including the boundaries as well
        // gives each slice of a window its own accumulator and they never combine -- which is
        // exactly what happened, and produced two partial sums where one total belonged, both of
        // them arithmetically correct and neither of them the answer.
        this.dataKeyOrdinals = operator.groupKeys().stream()
                .filter(ordinal -> ordinal != operator.windowStartOrdinal() && ordinal != operator.windowEndOrdinal())
                .toList();
        // Resolved once, at construction: the key hash must not look a column's type up per row.
        this.groupTypes = dataKeyOrdinals.stream()
                .map(ordinal ->
                        operator.input().outputSchema().field(ordinal).type().typeName())
                .toList();
    }

    /**
     * Where records too late to correct anything are sent.
     *
     * <p>A named side output rather than a drop. "The number was wrong because 0.2 % of records
     * arrived after their window had been released" is a diagnosis; a missing record is not.
     */
    void lateOutput(java.util.function.Consumer<RowView> sink) {
        this.lateOutput = sink;
    }

    @Override
    public void process(RowView row) {
        long windowStart = row.getLong(operator.windowStartOrdinal());
        long lastWindowEnd = windows.lastWindowEndFor(windowStart);
        if (watermark != Long.MIN_VALUE && lastWindowEnd + operator.allowedLatenessNanos() <= watermark) {
            // Its state has been released and cannot be rebuilt. Accepting it would produce a result
            // that contradicts one already sent, from state that no longer exists.
            lateRecords++;
            lateOutput.accept(row);
            return;
        }
        long keyHigh = compositeKey(row, 0x9E3779B97F4A7C15L);
        // A second, independently-seeded digest of the same columns. Two 64-bit hashes of the same
        // input are not two independent 64-bit hashes -- but seeded differently and finalised
        // separately they are close enough that the joint collision probability is the product,
        // which is what takes a one-in-thirty-million risk at a million groups down to nothing worth
        // reasoning about.
        long keyLow = compositeKey(row, 0xC2B2AE3D27D4EB4FL);
        for (int i = 0; i < scratch.length; i++) {
            int ordinal = valueOrdinals.get(i);
            // A null flattens to 0 for the arithmetic, and `present` remembers that it was a null.
            // Without that memory COUNT(col) counts rows and AVG divides by the wrong number, and
            // neither can tell a genuine zero from an absent value.
            boolean known = ordinal < 0 || !row.isNull(ordinal);
            present[i] = known;
            scratch[i] = known && ordinal >= 0 ? row.getLong(ordinal) : 0;
        }
        // The key's values travel with the accumulator: the result row has to contain them, and a
        // hash can say that a group counted seven without saying which group.
        Object[] keyValues = new Object[dataKeyOrdinals.size()];
        for (int i = 0; i < keyValues.length; i++) {
            int ordinal = dataKeyOrdinals.get(i);
            keyValues[i] = row.isNull(ordinal) ? null : readKey(row, ordinal, groupTypes.get(i));
        }
        // The window start is the event time as far as slicing is concerned: the assigner has
        // already placed the row, and using it here keeps the two from disagreeing about a boundary.
        state.update(keyHigh, keyLow, keyValues, windowStart, scratch, present, row.weight());
        highestEventTime = Math.max(highestEventTime, row.eventTimestampNanos());
        earliestWindowStart = Math.min(earliestWindowStart, windowStart);

        // Late but still correctable: mark every already-fired window this record belongs to, so the
        // next advance re-emits them with the correction rather than leaving the old answer standing.
        if (watermark != Long.MIN_VALUE) {
            for (long windowEnd : windows.windowEndsContaining(windowStart)) {
                if (emitted.containsKey(windowEnd)) {
                    dirty.add(windowEnd);
                }
            }
        }
    }

    /**
     * Fires every window that has completed since the last call.
     *
     * <p>Windows fire in order and each fires once, because a consumer applying two results for one
     * window in arrival order keeps whichever arrived last.
     */
    void advanceWatermark(long watermarkNanos) {
        if (watermarkNanos <= watermark) {
            return;
        }
        watermark = watermarkNanos;
        long from = lastFiredWatermark == Long.MIN_VALUE ? firstWindowStart() : lastFiredWatermark;
        // Corrections first: a consumer applying results in arrival order should see the fix for an
        // old window before the results of newer ones.
        for (long windowEnd : List.copyOf(dirty)) {
            corrections++;
            emitWindow(windowEnd);
        }
        dirty.clear();

        for (long windowEnd : windows.windowsCompletedBetween(from, watermark)) {
            emitWindow(windowEnd);
        }
        lastFiredWatermark = watermark;
        // Release what no window can need again. Allowed lateness is not wired to the query yet, so
        // this releases at the watermark; when lateness arrives it is one argument.
        int released = state.discardSlicesEndingBefore(watermark, operator.allowedLatenessNanos());
        if (released > 0) {
            // Forget what those windows emitted too: keeping it would be state that outlives the
            // state it describes, which is the definition of a leak.
            emitted.keySet().removeIf(windowEnd -> windowEnd + operator.allowedLatenessNanos() <= watermark);
        }
    }

    /**
     * Where firing starts on the very first advance.
     *
     * <p>The <em>earliest</em> window seen, not the latest, and not the epoch. Starting from the
     * latest silently drops the opening windows of the stream: the first end-to-end query came back
     * with three groups where five were expected, and every number in those three was correct.
     * Starting from the epoch would instead walk every window since 1970 to reach the first real one.
     *
     * <p>One slide is subtracted because the scan takes window ends strictly after the point it
     * starts from, and the earliest window's own end must count.
     */
    private long firstWindowStart() {
        return earliestWindowStart == Long.MAX_VALUE
                ? 0
                : earliestWindowStart - operator.spec().slideNanos();
    }

    /** Called when the input ends: a bounded source must not leave its last windows unemitted. */
    @Override
    public void finish() {
        if (highestEventTime != Long.MIN_VALUE) {
            // Past the end of the last window that can contain the highest event time seen.
            advanceWatermark(highestEventTime
                    + operator.spec().sizeNanos()
                    + operator.spec().slideNanos());
        }
    }

    private void emitWindow(long windowEnd) {
        java.util.Map<Long, long[]> previous = emitted.get(windowEnd);
        java.util.Map<Long, long[]> current = new java.util.HashMap<>();

        for (SlicedAggregateState.WindowResult result : state.fire(windowEnd)) {
            if (previous != null) {
                long[] before = previous.get(result.key());
                if (before != null) {
                    if (java.util.Arrays.equals(before, result.values())) {
                        // Unchanged by the correction. Emitting a retraction and an identical
                        // insertion would be two rows that consolidate to nothing, which is
                        // arithmetically harmless and pure noise on the wire.
                        current.put(result.key(), before);
                        continue;
                    }
                    emitRow(result, before, -1L);
                }
            }
            current.put(result.key(), result.values());
            emitRow(result, result.values(), 1L);
        }
        emitted.put(windowEnd, current);
    }

    /** Writes one result row with the given values and Z-set weight. */
    private void emitRow(SlicedAggregateState.WindowResult result, long[] values, long weight) {
        {
            long handle = arena.allocate(layout.rowSize(256));
            if (handle == ArenaHandle.NULL) {
                throw new PravahaException(
                        RuntimeErrors.ARENA_EXHAUSTED, "no room to emit a window result for key " + result.key());
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            // Group keys in the order the plan put them. The window boundaries come from the window
            // that fired, not from the row: the assigner wrote *slice* boundaries, which are what
            // the slicing needs and are narrower than the window for anything hopping.
            int column = 0;
            int dataKey = 0;
            for (int ordinal : operator.groupKeys()) {
                if (ordinal == operator.windowStartOrdinal()) {
                    writer.setLong(column++, result.windowStartNanos());
                } else if (ordinal == operator.windowEndOrdinal()) {
                    writer.setLong(column++, result.windowEndNanos());
                } else {
                    writeKey(column++, result.keyValues()[dataKey++]);
                }
            }
            for (int i = 0; i < values.length; i++) {
                writer.setLong(column++, values[i]);
            }
            writer.weight(weight)
                    .eventTimestampNanos(result.windowEndNanos())
                    .sequence(result.windowEndNanos())
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
    }

    /**
     * Writes this operator's state: the accumulators, the watermark cursors, and what each window
     * last emitted.
     *
     * <p>The last of those is easy to leave out and wrong to. Without it a restored operator does
     * not know what it has already told anybody, so the first correction after a restore emits a new
     * answer with no retraction of the old one -- and a retract-mode consumer ends up holding both.
     * The state that describes what was emitted is part of the state.
     */
    void writeTo(java.io.DataOutput out) throws java.io.IOException {
        out.writeLong(watermark);
        out.writeLong(lastFiredWatermark);
        out.writeLong(highestEventTime);
        out.writeLong(earliestWindowStart);
        out.writeLong(lateRecords);
        out.writeLong(corrections);

        out.writeInt(emitted.size());
        for (java.util.Map.Entry<Long, java.util.Map<Long, long[]>> window : emitted.entrySet()) {
            out.writeLong(window.getKey());
            out.writeInt(window.getValue().size());
            for (java.util.Map.Entry<Long, long[]> perKey : window.getValue().entrySet()) {
                out.writeLong(perKey.getKey());
                out.writeInt(perKey.getValue().length);
                for (long value : perKey.getValue()) {
                    out.writeLong(value);
                }
            }
        }
        state.writeTo(out);
    }

    /** Reads state back, replacing whatever is held. */
    void readFrom(java.io.DataInput in) throws java.io.IOException {
        watermark = in.readLong();
        lastFiredWatermark = in.readLong();
        highestEventTime = in.readLong();
        earliestWindowStart = in.readLong();
        lateRecords = in.readLong();
        corrections = in.readLong();

        emitted.clear();
        dirty.clear();
        int windows = in.readInt();
        for (int w = 0; w < windows; w++) {
            long windowEnd = in.readLong();
            int keys = in.readInt();
            java.util.Map<Long, long[]> perWindow = new java.util.HashMap<>();
            for (int k = 0; k < keys; k++) {
                long key = in.readLong();
                long[] values = new long[in.readInt()];
                for (int v = 0; v < values.length; v++) {
                    values[v] = in.readLong();
                }
                perWindow.put(key, values);
            }
            emitted.put(windowEnd, perWindow);
        }
        state.readFrom(in);
    }

    /** Records too late to correct anything. The number that says whether the lateness is set right. */
    long lateRecords() {
        return lateRecords;
    }

    /** Windows re-emitted because a late record changed them. */
    long corrections() {
        return corrections;
    }

    /**
     * Hashes the grouping columns into one long.
     *
     * <p>Window boundaries are part of the key, so two windows for the same user are different
     * groups without the state needing to know what a window is.
     */
    private long compositeKey(RowView row, long seed) {
        long hash = seed;
        for (int i = 0; i < dataKeyOrdinals.size(); i++) {
            int ordinal = dataKeyOrdinals.get(i);
            long value;
            if (row.isNull(ordinal)) {
                // A distinct constant rather than zero: NULL and 0 are different groups, and SQL is
                // emphatic that they are.
                value = 0xD1B54A32D192ED03L;
            } else {
                value = switch (groupTypes.get(i)) {
                    case STRING -> row.getString(ordinal).hashCode();
                    case BOOLEAN -> row.getBoolean(ordinal) ? 1 : 0;
                    case INT8 -> row.getByte(ordinal);
                    case INT16 -> row.getShort(ordinal);
                    case INT32, DATE -> row.getInt(ordinal);
                    default -> row.getLong(ordinal);
                };
            }
            hash = mix(hash ^ value);
        }
        return hash;
    }

    /** Reads one group column as an object, so it can be written back out verbatim. */
    private static Object readKey(RowView row, int ordinal, com.ash.messaging.pravaha.api.data.TypeName type) {
        return switch (type) {
            case STRING -> row.getString(ordinal);
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            default -> row.getLong(ordinal);
        };
    }

    /** Writes one group column back into the result row, in the output schema's type. */
    private void writeKey(int column, Object value) {
        if (value == null) {
            writer.setNull(column);
            return;
        }
        switch (operator.outputSchema().field(column).type().typeName()) {
            case STRING -> writer.setString(column, (String) value);
            case BOOLEAN -> writer.setBoolean(column, (Boolean) value);
            case INT8 -> writer.setByte(column, ((Number) value).byteValue());
            case INT16 -> writer.setShort(column, ((Number) value).shortValue());
            case INT32, DATE -> writer.setInt(column, ((Number) value).intValue());
            case FLOAT32 -> writer.setFloat(column, ((Number) value).floatValue());
            case FLOAT64 -> writer.setDouble(column, ((Number) value).doubleValue());
            default -> writer.setLong(column, ((Number) value).longValue());
        }
    }

    private static long mix(long z) {
        long h = z;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
