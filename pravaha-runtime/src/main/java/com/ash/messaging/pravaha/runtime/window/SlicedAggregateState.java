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
package com.ash.messaging.pravaha.runtime.window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * Windowed aggregates, kept per slice and combined when a window fires.
 *
 * <p>The state is one accumulator per {@code (key, slice)} rather than per {@code (key, window)}, so
 * a record updates one accumulator whatever the window overlap and a 60-second window hopping every
 * 10 seconds holds a sixth of the state it otherwise would (design section 15.3).
 *
 * <p><strong>Accumulation is weighted, and that is the point rather than a detail.</strong> A record
 * arriving with weight {@code -1} decrements the count and subtracts from the sum, so a retraction
 * is handled by the same arithmetic as an insert -- there is no separate retract path to get wrong,
 * which is the whole argument for Z-sets (design section 9.2). It is also what makes late data
 * tractable: a window that has already fired can be re-fired with a correction, because the
 * accumulator can go backwards.
 *
 * <p><strong>MIN and MAX are refused under retraction</strong>, for the same reason they are refused
 * in the unwindowed aggregate: knowing the current extreme does not tell you the previous one once
 * it is retracted. Doing it properly needs an ordered multiset per group, which is real state and
 * real work; claiming support and returning a stale extreme would be a wrong answer that looks
 * exactly like a right one.
 *
 * <p><strong>State is bounded or it is refused.</strong> A keyed aggregate over an unbounded key
 * space is how incremental engines die in production -- gradually, then all at once, weeks after
 * deployment. The limit is a constructor argument rather than a configuration nicety, and exceeding
 * it names the key that broke it, because "out of memory" is not a diagnosis.
 */
public final class SlicedAggregateState {

    /**
     * What a window produced for one key.
     *
     * @param keyValues the grouping columns' values, in group-key order. Carried rather than
     *     recomputed because the result row has to contain them: an aggregate keyed only by a hash
     *     can tell you that some group counted seven, and not which group. That was found by the
     *     first end-to-end query, which failed with "NOT NULL field never written" rather than with
     *     a wrong number -- a better outcome than the alternative.
     */
    public record WindowResult(
            long keyHigh,
            long keyLow,
            Object[] keyValues,
            long windowStartNanos,
            long windowEndNanos,
            long[] values,
            long count) {

        /**
         * A stable single-word identity for the group, for maps and ordering.
         *
         * <p>Mixed rather than XORed. XOR collapses to zero whenever the two halves are equal, which
         * never happens with two independently-seeded digests and happens constantly in a test that
         * passes the same value twice -- so a test using it as an identity found every group under
         * the key zero. A degenerate case that only appears in tests is still a bad identity
         * function.
         */
        public long key() {
            return keyHigh ^ (keyLow * 0x9E3779B97F4A7C15L);
        }
    }

    /** Which aggregate a column holds. */
    public enum Kind {
        COUNT,
        /**
         * {@code AVG(x)}: the sum and the count of non-null values, divided when the window fires.
         *
         * <p>It did not exist. {@code WindowedAggregate} mapped AVG onto {@link #SUM}, and nothing
         * divided anywhere -- so a windowed AVG returned the SUM, and only a group with more than
         * one row could reveal it. The keyed and global paths both divide, so the same query
         * answered differently over a window than over a view.
         */
        AVG,
        /**
         * {@code COUNT(DISTINCT x)}: one entry per distinct value per group per slice.
         *
         * <p>Counted rather than flagged, because a retraction has to be able to remove a value --
         * and a value seen three times and retracted once is still present. A set would say it had
         * gone.
         */
        COUNT_DISTINCT,
        SUM,
        MIN,
        MAX
    }

    /**
     * One accumulator's identity: which group, and which slice.
     *
     * <p>The group is a <strong>128-bit</strong> digest of the grouping columns, not a 64-bit one.
     * With 64 bits and a million live groups the chance that two of them collide is about
     * 3 x 10^-8 -- small enough to ignore in most systems and not in one whose entire claim is that
     * its answers are right, because the failure is two unrelated groups silently merged into a
     * number that looks perfectly reasonable. At 128 bits the same figure is around 10^-27, which is
     * below the rate at which the hardware gets the arithmetic wrong.
     */
    private record SliceKey(long keyHigh, long keyLow, long sliceStart) {}

    private static final class Accumulator {
        final long[] values;
        /**
         * Per column, how many non-null values have been accumulated.
         *
         * <p>Needed twice over. {@code COUNT(col)} counts non-null values and was counting rows,
         * and {@code AVG} must divide by the non-null count rather than the row count -- and the
         * caller flattens a null to 0 before this class ever sees it, so the value cannot say.
         */
        final long[] nonNull;
        /** Per distinct-column, how many times each value is currently present. Null unless needed. */
        Map<Long, Long>[] distinct;

        Object[] keyValues;
        long count;

        @SuppressWarnings("unchecked")
        Accumulator(int columns, boolean[] needsDistinct) {
            this.values = new long[columns];
            this.nonNull = new long[columns];
            for (int i = 0; i < columns; i++) {
                if (needsDistinct[i]) {
                    if (distinct == null) {
                        distinct = new Map[columns];
                    }
                    distinct[i] = new HashMap<>();
                }
            }
        }
    }

    private final SlicedWindows windows;
    private final Kind[] kinds;
    private final boolean[] needsDistinct;
    private final int maxSlices;
    private final Map<SliceKey, Accumulator> slices = new HashMap<>();
    private long peakSlices;

    /**
     * @param maxSlices the ceiling on live {@code (key, slice)} accumulators. Exceeding it is a
     *     refusal, not an eviction: dropping state silently would make the answer wrong rather than
     *     the query fail, and a wrong answer nobody is told about is worse than a stopped query.
     */
    public SlicedAggregateState(SlicedWindows windows, Kind[] kinds, int maxSlices) {
        if (maxSlices < 1) {
            throw new IllegalArgumentException("the slice limit must be at least 1, got " + maxSlices);
        }
        this.windows = windows;
        this.kinds = kinds.clone();
        this.needsDistinct = new boolean[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            needsDistinct[i] = kinds[i] == Kind.COUNT_DISTINCT;
        }
        this.maxSlices = maxSlices;
    }

    /**
     * Folds one record into its slice.
     *
     * @param values one per aggregate column; ignored for {@code COUNT}
     * @param weight the Z-set weight: {@code +1} for an insert, {@code -1} for a retraction
     */
    /**
     * Updates with every value treated as present.
     *
     * <p>For a caller that has no nulls to report. The distinction matters only to {@code
     * COUNT(col)} and {@code AVG}, both of which must ignore nulls and cannot tell from the value --
     * a null is flattened to 0 before it arrives here.
     */
    public void update(long keyHigh, long keyLow, Object[] keyValues, long eventTimeNanos, long[] values, long weight) {
        boolean[] present = new boolean[values.length];
        java.util.Arrays.fill(present, true);
        update(keyHigh, keyLow, keyValues, eventTimeNanos, values, present, weight);
    }

    public void update(
            long keyHigh,
            long keyLow,
            Object[] keyValues,
            long eventTimeNanos,
            long[] values,
            boolean[] present,
            long weight) {
        if (weight == 0) {
            // A consolidated row contributes nothing and must not be counted. Skipping it here also
            // stops it creating an accumulator, which would otherwise be state held for no data.
            return;
        }
        long sliceStart = windows.sliceStartFor(eventTimeNanos);
        SliceKey sliceKey = new SliceKey(keyHigh, keyLow, sliceStart);
        Accumulator accumulator = slices.get(sliceKey);
        if (accumulator == null) {
            if (slices.size() >= maxSlices) {
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "this windowed aggregate is holding " + slices.size() + " (key, slice) accumulators, "
                                + "its configured ceiling, and key " + keyHigh + ":" + keyLow + " at event time "
                                + eventTimeNanos
                                + " needs another. Either the key space is unbounded -- which no window can fix -- "
                                + "or the window is too wide for the key count. Raise the limit deliberately, "
                                + "narrow the window, or add a key predicate.");
            }
            accumulator = new Accumulator(kinds.length, needsDistinct);
            accumulator.keyValues = keyValues;
            slices.put(sliceKey, accumulator);
            peakSlices = Math.max(peakSlices, slices.size());
        }

        accumulator.count += weight;
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case COUNT -> {
                    // COUNT(*) has no argument and counts rows; COUNT(col) counts non-null values.
                    // This counted rows either way, so a windowed COUNT(col) contradicted the SUM
                    // beside it in its own output row.
                    if (present[i]) {
                        accumulator.values[i] += weight;
                    }
                }
                case COUNT_DISTINCT -> {
                    // Counted, not flagged. A value seen three times and retracted once is still
                    // present, and a set would have said it had gone.
                    Map<Long, Long> seen = accumulator.distinct[i];
                    long remaining = seen.merge(values[i], weight, Long::sum);
                    if (remaining <= 0) {
                        seen.remove(values[i]);
                    }
                    accumulator.values[i] = seen.size();
                }
                case SUM -> accumulator.values[i] += values[i] * weight;
                case AVG -> {
                    if (present[i]) {
                        accumulator.values[i] += values[i] * weight;
                        accumulator.nonNull[i] += weight;
                    }
                }
                case MIN, MAX -> {
                    if (weight < 0) {
                        throw new PravahaException(
                                RuntimeErrors.UNSUPPORTED_AGGREGATE,
                                kinds[i] + " cannot handle a retraction: restoring the previous extreme needs "
                                        + "an ordered multiset per group, which arrives with the aggregate lift. "
                                        + "Use SUM or COUNT, or drop the retraction.");
                    }
                    if (accumulator.count == weight) {
                        accumulator.values[i] = values[i];
                    } else if (kinds[i] == Kind.MIN) {
                        accumulator.values[i] = Math.min(accumulator.values[i], values[i]);
                    } else {
                        accumulator.values[i] = Math.max(accumulator.values[i], values[i]);
                    }
                }
            }
        }
    }

    /**
     * Combines the slices of a window and returns one result per key that has data in it.
     *
     * <p>Does not discard anything. A window that has fired may fire again when a late record
     * arrives, which is what allowed lateness means (design section 15.4) -- so releasing state is a
     * separate decision, made by {@link #discardSlicesEndingBefore}.
     */
    public List<WindowResult> fire(long windowEndNanos) {
        List<Long> sliceStarts = windows.slicesOfWindowEnding(windowEndNanos);
        Map<SliceKey, Accumulator> combined = new HashMap<>();
        for (long sliceStart : sliceStarts) {
            for (Map.Entry<SliceKey, Accumulator> entry : slices.entrySet()) {
                if (entry.getKey().sliceStart() != sliceStart) {
                    continue;
                }
                // Keyed by the group alone -- slice zeroed -- because combining slices into a window
                // is precisely the act of forgetting which slice a value came from.
                SliceKey groupKey =
                        new SliceKey(entry.getKey().keyHigh(), entry.getKey().keyLow(), 0);
                Accumulator target =
                        combined.computeIfAbsent(groupKey, key -> new Accumulator(kinds.length, needsDistinct));
                merge(target, entry.getValue());
            }
        }

        long windowStart = windowEndNanos - windows.spec().sizeNanos();
        List<WindowResult> results = new ArrayList<>(combined.size());
        combined.forEach((groupKey, accumulator) -> {
            if (accumulator.count != 0) {
                // A key whose weights cancel to zero within the window has no rows in it. Emitting a
                // result for it would report an empty group as a present one.
                long[] emitted = accumulator.values.clone();
                for (int i = 0; i < kinds.length; i++) {
                    if (kinds[i] == Kind.AVG) {
                        // Integer division, matching what the keyed and global paths do over an
                        // integer column. Dividing here rather than at the writer keeps every
                        // reader of a WindowResult seeing the answer rather than an intermediate.
                        emitted[i] = accumulator.nonNull[i] == 0 ? 0 : accumulator.values[i] / accumulator.nonNull[i];
                    }
                }
                results.add(new WindowResult(
                        groupKey.keyHigh(),
                        groupKey.keyLow(),
                        accumulator.keyValues,
                        windowStart,
                        windowEndNanos,
                        emitted,
                        accumulator.count));
            }
        });
        results.sort((a, b) -> a.keyHigh() != b.keyHigh()
                ? Long.compare(a.keyHigh(), b.keyHigh())
                : Long.compare(a.keyLow(), b.keyLow()));
        return results;
    }

    private void merge(Accumulator target, Accumulator source) {
        boolean targetWasEmpty = target.count == 0;
        if (target.keyValues == null) {
            target.keyValues = source.keyValues;
        }
        target.count += source.count;
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case COUNT, SUM -> target.values[i] += source.values[i];
                case AVG -> {
                    target.values[i] += source.values[i];
                    target.nonNull[i] += source.nonNull[i];
                }
                case COUNT_DISTINCT -> {
                    // Distinct counts do not add across slices: a value in two slices is one distinct
                    // value in the window, not two. The per-value counts have to be merged and the
                    // size taken afterwards, which is why the maps travel rather than the numbers.
                    Map<Long, Long> merged = target.distinct[i];
                    source.distinct[i].forEach((value, seenCount) -> merged.merge(value, seenCount, Long::sum));
                    target.values[i] = merged.size();
                }
                case MIN ->
                    target.values[i] = targetWasEmpty ? source.values[i] : Math.min(target.values[i], source.values[i]);
                case MAX ->
                    target.values[i] = targetWasEmpty ? source.values[i] : Math.max(target.values[i], source.values[i]);
            }
        }
    }

    /**
     * Releases slices that no window can need again.
     *
     * <p>This is what bounds state over time, as distinct from the ceiling that bounds it at any
     * instant. A slice is dead once the last window containing it has fired and the allowed lateness
     * has passed -- and until this is called, every slice ever created is still held.
     *
     * @return how many accumulators were released
     */
    public int discardSlicesEndingBefore(long watermarkNanos, long allowedLatenessNanos) {
        int before = slices.size();
        slices.keySet()
                .removeIf(sliceKey ->
                        windows.lastWindowEndFor(sliceKey.sliceStart()) + allowedLatenessNanos <= watermarkNanos);
        return before - slices.size();
    }

    /**
     * Writes every accumulator to a stream.
     *
     * <p>The format is deliberately explicit rather than derived from the object graph: Java
     * serialization is banned as a transport here (ADR-003's reasoning applies just as much to a
     * checkpoint as to a wire), and a checkpoint that cannot be read by a later version of the
     * engine is a checkpoint that turns an upgrade into a data loss event. Each value is written
     * with a tag, so the reader can fail on something it does not understand rather than silently
     * misinterpreting bytes.
     *
     * <p>Called on the lane thread, between batches. Anywhere else it would be reading state that
     * is being mutated -- a photograph of a car crash rather than a snapshot.
     */
    public void writeTo(java.io.DataOutput out) throws java.io.IOException {
        out.writeInt(FORMAT_VERSION);
        out.writeInt(kinds.length);
        for (Kind kind : kinds) {
            out.writeUTF(kind.name());
        }
        out.writeInt(slices.size());
        for (Map.Entry<SliceKey, Accumulator> entry : slices.entrySet()) {
            SliceKey key = entry.getKey();
            Accumulator accumulator = entry.getValue();
            out.writeLong(key.keyHigh());
            out.writeLong(key.keyLow());
            out.writeLong(key.sliceStart());
            out.writeLong(accumulator.count);
            for (long value : accumulator.values) {
                out.writeLong(value);
            }
            writeKeyValues(out, accumulator.keyValues);
            writeDistinct(out, accumulator);
        }
    }

    /**
     * Reads accumulators back, replacing whatever is held.
     *
     * <p>Replacing rather than merging: a restore happens after a failure, and merging the restored
     * state into whatever the process managed to accumulate since would double-count exactly the
     * records the checkpoint exists to make exactly-once.
     */
    public void readFrom(java.io.DataInput in) throws java.io.IOException {
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new java.io.IOException("checkpoint is format version " + version + ", this engine writes "
                    + FORMAT_VERSION + ". Refusing to guess at the difference.");
        }
        int columns = in.readInt();
        if (columns != kinds.length) {
            throw new java.io.IOException("checkpoint holds " + columns + " aggregate columns and this operator has "
                    + kinds.length + ": the query changed since the checkpoint was taken");
        }
        for (int i = 0; i < columns; i++) {
            String kind = in.readUTF();
            if (!kind.equals(kinds[i].name())) {
                throw new java.io.IOException("checkpoint column " + i + " is a " + kind + " and this operator's is a "
                        + kinds[i] + ": the query changed since the checkpoint was taken");
            }
        }

        slices.clear();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            SliceKey key = new SliceKey(in.readLong(), in.readLong(), in.readLong());
            Accumulator accumulator = new Accumulator(kinds.length, needsDistinct);
            accumulator.count = in.readLong();
            for (int column = 0; column < kinds.length; column++) {
                accumulator.values[column] = in.readLong();
            }
            accumulator.keyValues = readKeyValues(in);
            readDistinct(in, accumulator);
            slices.put(key, accumulator);
        }
        peakSlices = Math.max(peakSlices, slices.size());
    }

    /** Bumped whenever the layout above changes in a way an older reader would misread. */
    private static final int FORMAT_VERSION = 1;

    private static void writeKeyValues(java.io.DataOutput out, Object[] keyValues) throws java.io.IOException {
        out.writeInt(keyValues == null ? -1 : keyValues.length);
        if (keyValues == null) {
            return;
        }
        for (Object value : keyValues) {
            if (value == null) {
                out.writeByte(0);
            } else if (value instanceof String string) {
                out.writeByte(1);
                out.writeUTF(string);
            } else if (value instanceof Double || value instanceof Float) {
                out.writeByte(2);
                out.writeDouble(((Number) value).doubleValue());
            } else if (value instanceof Boolean flag) {
                out.writeByte(3);
                out.writeBoolean(flag);
            } else {
                out.writeByte(4);
                out.writeLong(((Number) value).longValue());
            }
        }
    }

    private static Object[] readKeyValues(java.io.DataInput in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        Object[] values = new Object[length];
        for (int i = 0; i < length; i++) {
            byte tag = in.readByte();
            values[i] = switch (tag) {
                case 0 -> null;
                case 1 -> in.readUTF();
                case 2 -> in.readDouble();
                case 3 -> in.readBoolean();
                case 4 -> in.readLong();
                default -> throw new java.io.IOException("unknown key-value tag " + tag + " in the checkpoint");
            };
        }
        return values;
    }

    private void writeDistinct(java.io.DataOutput out, Accumulator accumulator) throws java.io.IOException {
        for (int i = 0; i < kinds.length; i++) {
            if (!needsDistinct[i]) {
                continue;
            }
            Map<Long, Long> seen = accumulator.distinct[i];
            out.writeInt(seen.size());
            for (Map.Entry<Long, Long> entry : seen.entrySet()) {
                out.writeLong(entry.getKey());
                out.writeLong(entry.getValue());
            }
        }
    }

    private void readDistinct(java.io.DataInput in, Accumulator accumulator) throws java.io.IOException {
        for (int i = 0; i < kinds.length; i++) {
            if (!needsDistinct[i]) {
                continue;
            }
            int entries = in.readInt();
            Map<Long, Long> seen = accumulator.distinct[i];
            for (int e = 0; e < entries; e++) {
                seen.put(in.readLong(), in.readLong());
            }
        }
    }

    /** Live accumulators. The number bounded-state enforcement watches. */
    public int liveSlices() {
        return slices.size();
    }

    public long peakSlices() {
        return peakSlices;
    }

    public int maxSlices() {
        return maxSlices;
    }
}
