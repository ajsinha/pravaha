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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.state.VariableKeyStateMap;

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
 *
 * <h2>ADR-037 item B2: two storage strategies, chosen once, by shape</h2>
 *
 * <p>Every accumulator here is a handful of fixed-width numbers <strong>except</strong> {@code
 * COUNT(DISTINCT x)}, whose state is a per-group set that grows with cardinality rather than
 * staying constant. That difference, not the digest keying, is what decides whether an aggregate's
 * state can live off-heap: a fixed-width accumulator maps onto a {@link
 * com.ash.messaging.pravaha.state.RowStore} block the way a join's rows already do; a growing set
 * does not, any more than {@code JoinSide}'s bucket chains would fit in one.
 *
 * <p>So this class picks its representation once, at construction, from the aggregate {@link
 * Kind}s it was given -- never per accumulator, since every accumulator here shares one shape:
 *
 * <ul>
 *   <li><strong>No {@code COUNT DISTINCT}:</strong> accumulators live off-heap, in a {@link
 *       VariableKeyStateMap} keyed by the same 128-bit digest ({@code keyHigh}, {@code keyLow}) and
 *       {@code sliceStart} this class has always used -- no re-keying to raw group-key bytes, since
 *       the ~10^-27 collision risk of a 128-bit digest was already this design's accepted answer
 *       (see the class javadoc above) and JoinSide's raw-byte requirement does not apply here. This
 *       is also where an overflow tier attaches, exactly the way {@code RowStore} and {@code
 *       SymmetricHashJoin} already accept one.
 *   <li><strong>Any {@code COUNT DISTINCT}:</strong> accumulators stay exactly where they have
 *       always been, in an on-heap {@code HashMap}, with the per-value counting {@code
 *       COUNT_DISTINCT} needs. Untouched code, on purpose: {@code CountDistinctTest} exercises this
 *       path today and must keep exercising the same lines tomorrow. An aggregate on this path
 *       cannot spill -- there is nowhere to spill a growing on-heap set to -- so asking for an
 *       overflow tier here is refused by name, at construction, with {@link
 *       RuntimeErrors#COUNT_DISTINCT_CANNOT_SPILL}, rather than accepted and left to hit the
 *       ordinary ceiling later with no more room than it had before spilling was configured.
 * </ul>
 *
 * <p>{@code keyValues} -- the group's own column values, carried so a fired window's output row can
 * be built without re-deriving them from a digest -- are encoded off-heap with the exact same {@link
 * #writeTagged}/{@link #readTagged} pair the checkpoint format already uses, not a second encoding
 * invented for this. A group's {@code keyValues} are fixed at creation and never rewritten, which is
 * what makes storing them as one variable-length tail on the accumulator's own block enough: there is
 * no later resize to plan for.
 */
public final class SlicedAggregateState implements AutoCloseable {

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
        /**
         * Per distinct-column, how many times each value is currently present. Null unless needed.
         *
         * <p>Keyed by the value itself, not by a long. It was keyed by a long, and the caller
         * filled that long with {@code row.getLong(ordinal)} whatever the column's type -- so over
         * a STRING column it counted distinct <em>(offset, length)</em> pairs read out of the
         * string's slot. Four rows over three distinct users reported one, and two equal strings
         * written at different offsets counted as two.
         */
        Map<Object, Long>[] distinct;

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
    private final boolean anyDistinct;
    private final int maxSlices;
    private final Map<SliceKey, Accumulator> slices = new HashMap<>();
    private long peakSlices;

    /** Non-null only when {@link #anyDistinct} is false -- see the class javadoc. */
    private final OffHeapAccumulators offHeap;

    /**
     * Whether an overflow tier was configured. When it was, {@link #maxSlices} stops being a hard
     * refusal point and becomes the point past which new accumulators are carved from the overflow
     * tier instead -- the same relationship {@code RowStore}'s own {@code maxSlabs} has to its
     * overflow slabs. The real backstop then is however much the off-heap store itself can carve,
     * RAM and overflow slabs together; {@code maxSlices} without an overflow tier remains exactly
     * the hard ceiling it always was.
     */
    private final boolean hasOverflow;

    /**
     * @param maxSlices the ceiling on live {@code (key, slice)} accumulators. Exceeding it is a
     *     refusal, not an eviction: dropping state silently would make the answer wrong rather than
     *     the query fail, and a wrong answer nobody is told about is worse than a stopped query.
     */
    public SlicedAggregateState(SlicedWindows windows, Kind[] kinds, int maxSlices) {
        this(windows, kinds, maxSlices, null, 0);
    }

    /**
     * ADR-037 item B2: a windowed aggregate whose live-slice count exceeds {@code maxSlices} keeps
     * running, slower, by spilling to a file under {@code overflowAccess} instead of being refused
     * -- unless this aggregate includes {@code COUNT DISTINCT}, which cannot spill and is refused
     * here, by name, rather than silently denied the overflow tier it was configured to have.
     *
     * @param overflowAccess where slabs beyond the in-memory ceiling are carved from, or {@code
     *     null} for no overflow tier -- today's behaviour, unchanged
     * @param maxOverflowSlabs the ceiling on {@code overflowAccess} slabs, ignored when {@code
     *     overflowAccess} is {@code null}
     * @throws PravahaException {@link RuntimeErrors#COUNT_DISTINCT_CANNOT_SPILL} if {@code
     *     overflowAccess} is given and {@code kinds} contains {@link Kind#COUNT_DISTINCT}
     */
    public SlicedAggregateState(
            SlicedWindows windows, Kind[] kinds, int maxSlices, MemoryAccess overflowAccess, int maxOverflowSlabs) {
        if (maxSlices < 1) {
            throw new IllegalArgumentException("the slice limit must be at least 1, got " + maxSlices);
        }
        this.windows = windows;
        this.kinds = kinds.clone();
        this.needsDistinct = new boolean[kinds.length];
        boolean anyDistinct = false;
        for (int i = 0; i < kinds.length; i++) {
            needsDistinct[i] = kinds[i] == Kind.COUNT_DISTINCT;
            anyDistinct |= needsDistinct[i];
        }
        this.anyDistinct = anyDistinct;
        this.maxSlices = maxSlices;
        this.hasOverflow = overflowAccess != null;
        if (anyDistinct) {
            if (overflowAccess != null) {
                throw new PravahaException(
                        RuntimeErrors.COUNT_DISTINCT_CANNOT_SPILL,
                        "this windowed aggregate computes COUNT(DISTINCT ...), whose state is one entry per "
                                + "distinct value per group per slice -- a set that grows with cardinality, not a "
                                + "fixed-width number, and has nowhere to spill to. Spilling was configured for "
                                + "this deployment; this specific aggregate cannot use it. Remove the DISTINCT, "
                                + "narrow the window, or raise the slice ceiling instead.");
            }
            this.offHeap = null;
        } else {
            this.offHeap = new OffHeapAccumulators(
                    kinds.length,
                    MemoryAccess.best(),
                    OffHeapAccumulators.ramSlabsFor(maxSlices),
                    overflowAccess,
                    maxOverflowSlabs);
        }
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
        update(keyHigh, keyLow, keyValues, eventTimeNanos, values, present, null, weight);
    }

    /**
     * Folds one record in, with the distinct columns' values as themselves.
     *
     * @param distinctValues one per aggregate column, or null when nothing needs distinctness. Only
     *     the {@code COUNT_DISTINCT} entries are read. Separate from {@code values} because
     *     distinctness is about the value and the rest of the arithmetic is about its number: a
     *     string has no number, and reading its slot as one counts storage offsets.
     */
    public void update(
            long keyHigh,
            long keyLow,
            Object[] keyValues,
            long eventTimeNanos,
            long[] values,
            boolean[] present,
            Object[] distinctValues,
            long weight) {
        if (weight == 0) {
            // A consolidated row contributes nothing and must not be counted. Skipping it here also
            // stops it creating an accumulator, which would otherwise be state held for no data.
            return;
        }
        long sliceStart = windows.sliceStartFor(eventTimeNanos);
        if (anyDistinct) {
            updateOnHeap(keyHigh, keyLow, keyValues, sliceStart, values, present, distinctValues, weight);
        } else {
            updateOffHeap(keyHigh, keyLow, keyValues, sliceStart, values, present, weight);
        }
    }

    private void updateOnHeap(
            long keyHigh,
            long keyLow,
            Object[] keyValues,
            long sliceStart,
            long[] values,
            boolean[] present,
            Object[] distinctValues,
            long weight) {
        SliceKey sliceKey = new SliceKey(keyHigh, keyLow, sliceStart);
        Accumulator accumulator = slices.get(sliceKey);
        if (accumulator == null) {
            if (slices.size() >= maxSlices) {
                throw ceilingExceeded(keyHigh, keyLow, sliceStart, slices.size());
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
                    // NULL is not a value SQL counts. It used to be: a null flattens to 0 on its
                    // way in, and counting it made COUNT(DISTINCT status) over {ok, NULL, ok,
                    // flagged} report three where SQL says two.
                    if (present[i]) {
                        // Counted, not flagged. A value seen three times and retracted once is
                        // still present, and a set would have said it had gone.
                        Object value = distinctValues == null ? values[i] : distinctValues[i];
                        Map<Object, Long> seen = accumulator.distinct[i];
                        long remaining = seen.merge(value, weight, Long::sum);
                        if (remaining <= 0) {
                            seen.remove(value);
                        }
                    }
                    accumulator.values[i] = accumulator.distinct[i].size();
                }
                case SUM -> {
                    // Also guarded by presence. A null flattens to 0, which adds nothing, so the
                    // total was right either way -- but nonNull is what MIN and MAX now seed from,
                    // and a SUM that did not maintain it would leave them seeding off a count that
                    // no longer meant what they thought.
                    if (present[i]) {
                        accumulator.values[i] += values[i] * weight;
                        accumulator.nonNull[i] += weight;
                    }
                }
                case AVG -> {
                    if (present[i]) {
                        accumulator.values[i] += values[i] * weight;
                        accumulator.nonNull[i] += weight;
                    }
                }
                case MIN, MAX -> {
                    if (weight < 0) {
                        throw retractionRefused(kinds[i]);
                    }
                    // NULL is not a value SQL's MIN or MAX considers, and this folded it in: a
                    // null flattens to 0 on the way in, so MIN over {5, 5, 7, NULL} answered 0 --
                    // which is also what it answers over an all-positive column that was never
                    // seeded, so the value carried no information about the data at all. MAX was
                    // right only by luck, because zero is below every positive amount.
                    if (!present[i]) {
                        break;
                    }
                    // Seeded from the first non-null value, not the first row. accumulator.count is
                    // the row count, so a group whose first row was null seeded the extreme to that
                    // null's zero and then compared every real value against it.
                    boolean firstValue = accumulator.nonNull[i] == 0;
                    accumulator.nonNull[i] += weight;
                    if (firstValue) {
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
     * The off-heap twin of {@link #updateOnHeap}, for the same reason and to the same arithmetic --
     * {@code COUNT_DISTINCT} never reaches here (see the class javadoc), so the switch below has one
     * fewer case than the on-heap one and no {@code Map} to touch.
     */
    private void updateOffHeap(
            long keyHigh,
            long keyLow,
            Object[] keyValues,
            long sliceStart,
            long[] values,
            boolean[] present,
            long weight) {
        long handle = offHeap.find(keyHigh, keyLow, sliceStart);
        if (handle == ArenaHandle.NULL) {
            if (!hasOverflow && offHeap.size() >= maxSlices) {
                throw ceilingExceeded(keyHigh, keyLow, sliceStart, offHeap.size());
            }
            // With an overflow tier, maxSlices is where spilling starts rather than where the
            // aggregate refuses -- see hasOverflow's own javadoc. The off-heap store's own ceiling
            // (RAM slabs, then overflow slabs) is what still refuses, from inside RowStore, if a
            // key space really is unbounded.
            handle = offHeap.create(keyHigh, keyLow, sliceStart, keyValues);
            peakSlices = Math.max(peakSlices, offHeap.size());
        }

        offHeap.setCount(handle, offHeap.count(handle) + weight);
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case COUNT -> {
                    if (present[i]) {
                        offHeap.setValue(handle, i, offHeap.value(handle, i) + weight);
                    }
                }
                case SUM, AVG -> {
                    if (present[i]) {
                        offHeap.setValue(handle, i, offHeap.value(handle, i) + values[i] * weight);
                        offHeap.setNonNull(handle, i, offHeap.nonNull(handle, i) + weight);
                    }
                }
                case MIN, MAX -> {
                    if (weight < 0) {
                        throw retractionRefused(kinds[i]);
                    }
                    if (!present[i]) {
                        break;
                    }
                    boolean firstValue = offHeap.nonNull(handle, i) == 0;
                    offHeap.setNonNull(handle, i, offHeap.nonNull(handle, i) + weight);
                    if (firstValue) {
                        offHeap.setValue(handle, i, values[i]);
                    } else if (kinds[i] == Kind.MIN) {
                        offHeap.setValue(handle, i, Math.min(offHeap.value(handle, i), values[i]));
                    } else {
                        offHeap.setValue(handle, i, Math.max(offHeap.value(handle, i), values[i]));
                    }
                }
                case COUNT_DISTINCT ->
                    throw new IllegalStateException(
                            "unreachable: COUNT_DISTINCT always takes the on-heap path (see class javadoc)");
            }
        }
    }

    private PravahaException ceilingExceeded(long keyHigh, long keyLow, long sliceStart, int currentSize) {
        return new PravahaException(
                RuntimeErrors.UNSUPPORTED_AGGREGATE,
                "this windowed aggregate is holding " + currentSize + " (key, slice) accumulators, "
                        + "its configured ceiling, and key " + keyHigh + ":" + keyLow + " at slice " + sliceStart
                        + " needs another. Either the key space is unbounded -- which no window can fix -- "
                        + "or the window is too wide for the key count. Raise the limit deliberately, "
                        + "narrow the window, or add a key predicate.");
    }

    private PravahaException retractionRefused(Kind kind) {
        return new PravahaException(
                RuntimeErrors.UNSUPPORTED_AGGREGATE,
                kind + " cannot handle a retraction: restoring the previous extreme needs "
                        + "an ordered multiset per group, which arrives with the aggregate lift. "
                        + "Use SUM or COUNT, or drop the retraction.");
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
        if (anyDistinct) {
            for (long sliceStart : sliceStarts) {
                for (Map.Entry<SliceKey, Accumulator> entry : slices.entrySet()) {
                    if (entry.getKey().sliceStart() != sliceStart) {
                        continue;
                    }
                    // Keyed by the group alone -- slice zeroed -- because combining slices into a
                    // window is precisely the act of forgetting which slice a value came from.
                    SliceKey groupKey = new SliceKey(
                            entry.getKey().keyHigh(), entry.getKey().keyLow(), 0);
                    Accumulator target =
                            combined.computeIfAbsent(groupKey, key -> new Accumulator(kinds.length, needsDistinct));
                    merge(target, entry.getValue());
                }
            }
        } else {
            List<Long> handles = new ArrayList<>();
            offHeap.forEach(handles::add);
            for (long sliceStart : sliceStarts) {
                for (long handle : handles) {
                    if (offHeap.sliceStartOf(handle) != sliceStart) {
                        continue;
                    }
                    SliceKey groupKey = new SliceKey(offHeap.keyHighOf(handle), offHeap.keyLowOf(handle), 0);
                    Accumulator target =
                            combined.computeIfAbsent(groupKey, key -> new Accumulator(kinds.length, needsDistinct));
                    merge(target, offHeap.readAccumulator(handle, kinds.length, needsDistinct));
                }
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
        if (target.keyValues == null) {
            target.keyValues = source.keyValues;
        }
        target.count += source.count;
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case COUNT -> target.values[i] += source.values[i];
                case SUM -> {
                    target.values[i] += source.values[i];
                    target.nonNull[i] += source.nonNull[i];
                }
                case AVG -> {
                    target.values[i] += source.values[i];
                    target.nonNull[i] += source.nonNull[i];
                }
                case COUNT_DISTINCT -> {
                    // Distinct counts do not add across slices: a value in two slices is one distinct
                    // value in the window, not two. The per-value counts have to be merged and the
                    // size taken afterwards, which is why the maps travel rather than the numbers.
                    Map<Object, Long> merged = target.distinct[i];
                    source.distinct[i].forEach((value, seenCount) -> merged.merge(value, seenCount, Long::sum));
                    target.values[i] = merged.size();
                }
                // Merged on the non-null count, not on whether the target held any rows: a
                // slice of nothing but nulls has rows and no extreme, and treating it as seeded
                // would merge its zero in as though it were a value.
                case MIN -> {
                    if (source.nonNull[i] > 0) {
                        target.values[i] = target.nonNull[i] == 0
                                ? source.values[i]
                                : Math.min(target.values[i], source.values[i]);
                    }
                    // After the comparison, never before: the test above asks whether the target
                    // had an extreme yet, and adding first would answer it with the source's own
                    // rows.
                    target.nonNull[i] += source.nonNull[i];
                }
                case MAX -> {
                    if (source.nonNull[i] > 0) {
                        target.values[i] = target.nonNull[i] == 0
                                ? source.values[i]
                                : Math.max(target.values[i], source.values[i]);
                    }
                    target.nonNull[i] += source.nonNull[i];
                }
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
        if (anyDistinct) {
            int before = slices.size();
            slices.keySet()
                    .removeIf(sliceKey ->
                            windows.lastWindowEndFor(sliceKey.sliceStart()) + allowedLatenessNanos <= watermarkNanos);
            return before - slices.size();
        }
        int before = offHeap.size();
        List<Long> handles = new ArrayList<>();
        offHeap.forEach(handles::add);
        for (long handle : handles) {
            long sliceStart = offHeap.sliceStartOf(handle);
            if (windows.lastWindowEndFor(sliceStart) + allowedLatenessNanos <= watermarkNanos) {
                offHeap.remove(offHeap.keyHighOf(handle), offHeap.keyLowOf(handle), sliceStart);
            }
        }
        return before - offHeap.size();
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
     *
     * <p>The wire format does not know or care which of the two storage strategies produced it: an
     * off-heap-sourced checkpoint and an on-heap-sourced one are byte-for-byte indistinguishable,
     * which is what lets a restore rebuild whichever representation this construction chose without
     * the checkpoint format needing a third thing to agree on.
     */
    public void writeTo(java.io.DataOutput out) throws java.io.IOException {
        out.writeInt(FORMAT_VERSION);
        out.writeInt(kinds.length);
        for (Kind kind : kinds) {
            out.writeUTF(kind.name());
        }
        if (anyDistinct) {
            out.writeInt(slices.size());
            for (Map.Entry<SliceKey, Accumulator> entry : slices.entrySet()) {
                writeEntry(out, entry.getKey(), entry.getValue());
            }
        } else {
            List<Long> handles = new ArrayList<>();
            offHeap.forEach(handles::add);
            out.writeInt(handles.size());
            for (long handle : handles) {
                SliceKey key =
                        new SliceKey(offHeap.keyHighOf(handle), offHeap.keyLowOf(handle), offHeap.sliceStartOf(handle));
                writeEntry(out, key, offHeap.readAccumulator(handle, kinds.length, needsDistinct));
            }
        }
    }

    private void writeEntry(java.io.DataOutput out, SliceKey key, Accumulator accumulator) throws java.io.IOException {
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

        if (anyDistinct) {
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
        } else {
            offHeap.clear();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long keyHigh = in.readLong();
                long keyLow = in.readLong();
                long sliceStart = in.readLong();
                long readCount = in.readLong();
                long[] values = new long[kinds.length];
                for (int column = 0; column < kinds.length; column++) {
                    values[column] = in.readLong();
                }
                Object[] keyValues = readKeyValues(in);
                // Off-heap accumulators never have a distinct column (see the class javadoc), so
                // this reads and discards nothing -- but it still has to be called, symmetrically
                // with writeEntry always calling writeDistinct, or a checkpoint written with one
                // aggregate shape and read with another would misalign every field after this one.
                readDistinct(in, new Accumulator(kinds.length, needsDistinct));
                long handle = offHeap.create(keyHigh, keyLow, sliceStart, keyValues);
                offHeap.setCount(handle, readCount);
                for (int column = 0; column < kinds.length; column++) {
                    offHeap.setValue(handle, column, values[column]);
                }
            }
            peakSlices = Math.max(peakSlices, offHeap.size());
        }
    }

    /** Bumped whenever the layout above changes in a way an older reader would misread. */
    private static final int FORMAT_VERSION = 1;

    private static void writeKeyValues(java.io.DataOutput out, Object[] keyValues) throws java.io.IOException {
        out.writeInt(keyValues == null ? -1 : keyValues.length);
        if (keyValues == null) {
            return;
        }
        for (Object value : keyValues) {
            writeTagged(out, value);
        }
    }

    /**
     * One value, tagged with its shape.
     *
     * <p>Shared by the group keys and the distinct sets, which hold the same kinds of value for the
     * same reason. A distinct set used to be longs, so this did not apply to it; keying it by the
     * value rather than by the slot's bits made the two the same problem.
     */
    private static void writeTagged(java.io.DataOutput out, Object value) throws java.io.IOException {
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

    private static Object readTagged(java.io.DataInput in) throws java.io.IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case 0 -> null;
            case 1 -> in.readUTF();
            case 2 -> in.readDouble();
            case 3 -> in.readBoolean();
            case 4 -> in.readLong();
            default -> throw new java.io.IOException("unknown key-value tag " + tag + " in the checkpoint");
        };
    }

    private static Object[] readKeyValues(java.io.DataInput in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        Object[] values = new Object[length];
        for (int i = 0; i < length; i++) {
            values[i] = readTagged(in);
        }
        return values;
    }

    private void writeDistinct(java.io.DataOutput out, Accumulator accumulator) throws java.io.IOException {
        for (int i = 0; i < kinds.length; i++) {
            if (!needsDistinct[i]) {
                continue;
            }
            Map<Object, Long> seen = accumulator.distinct[i];
            out.writeInt(seen.size());
            for (Map.Entry<Object, Long> entry : seen.entrySet()) {
                writeTagged(out, entry.getKey());
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
            Map<Object, Long> seen = accumulator.distinct[i];
            for (int e = 0; e < entries; e++) {
                seen.put(readTagged(in), in.readLong());
            }
        }
    }

    /** Live accumulators. The number bounded-state enforcement watches. */
    public int liveSlices() {
        return anyDistinct ? slices.size() : offHeap.size();
    }

    public long peakSlices() {
        return peakSlices;
    }

    public int maxSlices() {
        return maxSlices;
    }

    /** Whether this aggregate's off-heap accumulators have spilled to the overflow tier. False for
     * an aggregate using the on-heap ({@code COUNT DISTINCT}) path, which never spills. */
    public boolean hasSpilled() {
        return !anyDistinct && offHeap.hasSpilled();
    }

    /** Bytes this aggregate's off-heap accumulators have taken from the operating system -- index
     * and data together. Zero for the on-heap path, which this does not measure. */
    public long offHeapBytesAllocated() {
        return anyDistinct ? 0 : offHeap.bytesAllocated();
    }

    /**
     * Releases the off-heap resources this aggregate holds. A no-op for the on-heap ({@code COUNT
     * DISTINCT}) path, which has none.
     */
    @Override
    public void close() {
        if (offHeap != null) {
            offHeap.close();
        }
    }

    /**
     * Off-heap storage for every accumulator this class holds, when none of them needs {@code
     * COUNT_DISTINCT}'s per-value counting.
     *
     * <p>Indexed by {@link VariableKeyStateMap}, keyed by the same 24 fixed bytes -- {@code
     * keyHigh}, {@code keyLow}, {@code sliceStart} -- this class has always identified a slice by.
     * The value is one {@link com.ash.messaging.pravaha.state.RowStore} block per accumulator: a
     * fixed header ({@code count}, then {@code values[]}, then {@code nonNull[]}, all eight-byte
     * longs) followed by {@code keyValues} encoded exactly as {@link #writeKeyValues} writes it to a
     * checkpoint -- reused rather than re-invented, and safe to reuse because a group's {@code
     * keyValues} are fixed at creation and never rewritten, so the block never needs to grow.
     */
    private static final class OffHeapAccumulators implements AutoCloseable {

        private static final int INDEX_INITIAL_CAPACITY = 64;
        private static final int STORE_SLAB_BYTES = 1 << 16;

        /**
         * A deliberately generous per-accumulator estimate -- fixed header plus a modest {@code
         * keyValues} encoding -- used only to size the RAM tier from {@code maxSlices}, never to
         * bound anything: a bigger accumulator than this simply fits fewer per slab, and a query
         * genuinely holding more accumulators than {@code maxSlices} was ever sized for is exactly
         * what the overflow tier, once it is reached, exists to keep running through.
         */
        private static final int ESTIMATED_BYTES_PER_ENTRY = 256;

        private final int columns;
        private final int fixedHeaderBytes;
        private final MemoryAccess access;
        private final MemoryAccess overflowAccess;
        private final int maxOverflowSlabs;
        private final int ramMaxSlabs;
        private final MemoryRegion keyScratch;

        private VariableKeyStateMap map;

        /**
         * @param ramMaxSlabs the RAM tier's own slab ceiling, in {@link #STORE_SLAB_BYTES}-sized
         *     slabs -- derived from {@code maxSlices} by {@link SlicedAggregateState}, not a
         *     separately configured number, so that a small {@code maxSlices} does not carry a
         *     RAM budget sized for a large one
         */
        OffHeapAccumulators(
                int columns, MemoryAccess access, int ramMaxSlabs, MemoryAccess overflowAccess, int maxOverflowSlabs) {
            this.columns = columns;
            this.fixedHeaderBytes = Long.BYTES + 2 * Long.BYTES * columns;
            this.access = access;
            this.overflowAccess = overflowAccess;
            this.maxOverflowSlabs = maxOverflowSlabs;
            this.ramMaxSlabs = ramMaxSlabs;
            this.keyScratch = access.allocate(3 * Long.BYTES);
            this.map = newMap();
        }

        /** How many RAM slabs comfortably hold {@code maxSlices} accumulators at the estimate
         * above -- at least one, however small {@code maxSlices} is. */
        static int ramSlabsFor(int maxSlices) {
            long estimatedBytes = (long) maxSlices * ESTIMATED_BYTES_PER_ENTRY;
            return (int) Math.max(1, (estimatedBytes + STORE_SLAB_BYTES - 1) / STORE_SLAB_BYTES);
        }

        private VariableKeyStateMap newMap() {
            return new VariableKeyStateMap(
                    access, INDEX_INITIAL_CAPACITY, STORE_SLAB_BYTES, ramMaxSlabs, overflowAccess, maxOverflowSlabs);
        }

        private void writeKey(long keyHigh, long keyLow, long sliceStart) {
            keyScratch.putLong(0, keyHigh);
            keyScratch.putLong(Long.BYTES, keyLow);
            keyScratch.putLong(2 * Long.BYTES, sliceStart);
        }

        long find(long keyHigh, long keyLow, long sliceStart) {
            writeKey(keyHigh, keyLow, sliceStart);
            return map.find(keyScratch, 0, 3 * Long.BYTES);
        }

        /** Creates a new, zeroed accumulator (the store zeroes a fresh block's value bytes) with
         * {@code keyValues} written into its variable tail. */
        long create(long keyHigh, long keyLow, long sliceStart, Object[] keyValues) {
            writeKey(keyHigh, keyLow, sliceStart);
            byte[] encodedKeyValues = encodeKeyValues(keyValues);
            long handle = map.getOrCreate(keyScratch, 0, 3 * Long.BYTES, fixedHeaderBytes + encodedKeyValues.length);
            map.valueRegionOf(handle)
                    .putBytes(
                            map.valueOffsetOf(handle) + fixedHeaderBytes, encodedKeyValues, 0, encodedKeyValues.length);
            return handle;
        }

        private static byte[] encodeKeyValues(Object[] keyValues) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    writeKeyValues(out, keyValues);
                }
                return bytes.toByteArray();
            } catch (java.io.IOException e) {
                // ByteArrayOutputStream never throws IOException; this exists so the checked
                // exception on the shared writeKeyValues signature does not have to leak here.
                throw new IllegalStateException(e);
            }
        }

        Object[] keyValuesOf(long handle) {
            MemoryRegion region = map.valueRegionOf(handle);
            int base = map.valueOffsetOf(handle);
            int length = map.valueLengthOf(handle) - fixedHeaderBytes;
            byte[] encoded = new byte[length];
            region.getBytes(base + fixedHeaderBytes, encoded, 0, length);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
                return readKeyValues(in);
            } catch (java.io.IOException e) {
                // Reading from a ByteArrayInputStream cannot fail on I/O; a failure here means the
                // bytes this class itself wrote are not what this class itself expects, which is a
                // bug in this class rather than a condition a caller could have caused.
                throw new IllegalStateException("cannot decode this accumulator's own key values", e);
            }
        }

        long count(long handle) {
            return map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle));
        }

        void setCount(long handle, long value) {
            map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), value);
        }

        long value(long handle, int column) {
            return map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * column);
        }

        void setValue(long handle, int column, long value) {
            map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * column, value);
        }

        long nonNull(long handle, int column) {
            return map.valueRegionOf(handle)
                    .getLong(map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * columns + Long.BYTES * column);
        }

        void setNonNull(long handle, int column, long value) {
            map.valueRegionOf(handle)
                    .putLong(
                            map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * columns + Long.BYTES * column, value);
        }

        long keyHighOf(long handle) {
            return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle));
        }

        long keyLowOf(long handle) {
            return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle) + Long.BYTES);
        }

        long sliceStartOf(long handle) {
            return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle) + 2 * Long.BYTES);
        }

        /** A temporary, on-heap copy of one accumulator, for {@link #merge} and the checkpoint
         * writer to work with exactly as they already do for the on-heap path. */
        Accumulator readAccumulator(long handle, int columnCount, boolean[] needsDistinct) {
            Accumulator accumulator = new Accumulator(columnCount, needsDistinct);
            accumulator.count = count(handle);
            for (int i = 0; i < columnCount; i++) {
                accumulator.values[i] = value(handle, i);
                accumulator.nonNull[i] = nonNull(handle, i);
            }
            accumulator.keyValues = keyValuesOf(handle);
            return accumulator;
        }

        void remove(long keyHigh, long keyLow, long sliceStart) {
            writeKey(keyHigh, keyLow, sliceStart);
            map.remove(keyScratch, 0, 3 * Long.BYTES);
        }

        void forEach(java.util.function.LongConsumer visitor) {
            map.forEach(visitor::accept);
        }

        int size() {
            return map.size();
        }

        boolean hasSpilled() {
            return map.hasSpilled();
        }

        long bytesAllocated() {
            return map.bytesAllocated();
        }

        /** Discards every accumulator, for a restore that replaces rather than merges. */
        void clear() {
            map.close();
            map = newMap();
        }

        @Override
        public void close() {
            map.close();
            keyScratch.close();
        }
    }
}
