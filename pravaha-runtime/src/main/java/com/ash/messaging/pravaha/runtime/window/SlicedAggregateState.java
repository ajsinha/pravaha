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
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.runtime.AggregateTotals;
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
 * <h2>Where the state lives: off-heap, and spillable, for every aggregate</h2>
 *
 * <p>Accumulators live in {@link OffHeapAccumulators}: a {@link VariableKeyStateMap} keyed by the
 * 128-bit digest ({@code keyHigh}, {@code keyLow}), {@code sliceStart} and the group's own key
 * columns, one {@link com.ash.messaging.pravaha.state.RowStore} block per accumulator. The columns are
 * in the key because a digest is not an identity (W8-14): keyed by the digest alone, two groups whose
 * digests collided were one accumulator, and the answer was their merged sum with nothing to show for
 * it. The map compares the whole key against the arena byte for byte, without allocating. That is also where an
 * overflow tier attaches, exactly the way {@code RowStore} and {@code SymmetricHashJoin} accept one.
 *
 * <p>{@code COUNT(DISTINCT x)} used to be the exception. Its state is a set that grows with
 * cardinality rather than a handful of numbers, it stayed in an on-heap {@code HashMap} per
 * accumulator, and an aggregate containing it was refused by name when the overflow tier was
 * configured (it could not spill). ADR-044 closed that: the sets are flattened into {@link
 * DistinctValueCounts}, one off-heap entry per {@code (group, slice, column, value)} with a count, in
 * a second {@code RowStore} that takes the same overflow tier. A window's distinct count is then
 * computed when it fires by counting each value once across the window's slices -- a value is counted
 * in the earliest slice of the window that holds it, which is a lookup per value rather than a set
 * built on the heap.
 *
 * <p>{@code keyValues} -- the group's own column values -- are therefore stored once, as part of the
 * key, and a fired window's output row reads them back from there.
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
     * @param nulls per column, whether the answer is SQL NULL: a {@code SUM}, {@code AVG}, {@code MIN}
     *     or {@code MAX} of a group none of whose values was non-null (ALLNULLAGG-1). Its {@code
     *     values} entry is then 0 and means nothing. It used to be published as that 0, which is
     *     indistinguishable from a real total of zero. Never null; all false when nothing is NULL.
     */
    public record WindowResult(
            long keyHigh,
            long keyLow,
            Object[] keyValues,
            long windowStartNanos,
            long windowEndNanos,
            long[] values,
            long count,
            boolean[] nulls) {

        /** A result none of whose answers is NULL. */
        public WindowResult(
                long keyHigh,
                long keyLow,
                Object[] keyValues,
                long windowStartNanos,
                long windowEndNanos,
                long[] values,
                long count) {
            this(
                    keyHigh,
                    keyLow,
                    keyValues,
                    windowStartNanos,
                    windowEndNanos,
                    values,
                    count,
                    new boolean[values.length]);
        }

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

    private final SlicedWindows windows;
    private final Kind[] kinds;
    private final int maxSlices;
    private long peakSlices;

    private final OffHeapAccumulators offHeap;

    /** Every distinct column's values and their counts; null when no column is {@code COUNT_DISTINCT}. */
    private final DistinctValueCounts distinct;

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
     * running, slower, by spilling to a file under {@code overflowAccess} instead of being refused --
     * {@code COUNT DISTINCT} included since ADR-044, whose per-value counts spill through the same
     * tier as the accumulators.
     *
     * @param overflowAccess where slabs beyond the in-memory ceiling are carved from, or {@code
     *     null} for no overflow tier -- today's behaviour, unchanged
     * @param maxOverflowSlabs the ceiling on {@code overflowAccess} slabs, ignored when {@code
     *     overflowAccess} is {@code null}
     */
    public SlicedAggregateState(
            SlicedWindows windows, Kind[] kinds, int maxSlices, MemoryAccess overflowAccess, int maxOverflowSlabs) {
        if (maxSlices < 1) {
            throw new IllegalArgumentException("the slice limit must be at least 1, got " + maxSlices);
        }
        this.windows = windows;
        this.kinds = kinds.clone();
        boolean anyDistinct = false;
        for (Kind kind : kinds) {
            anyDistinct |= kind == Kind.COUNT_DISTINCT;
        }
        this.maxSlices = maxSlices;
        this.hasOverflow = overflowAccess != null;
        int ramSlabs = OffHeapAccumulators.ramSlabsFor(maxSlices);
        this.offHeap =
                new OffHeapAccumulators(kinds.length, MemoryAccess.best(), ramSlabs, overflowAccess, maxOverflowSlabs);
        // The distinct values get a RAM budget of their own, the same size as the accumulators'. It
        // is a budget and not a bound on the answer: past it they spill with everything else, and
        // without a tier they are refused with PRV-4001 by the store rather than growing the heap.
        this.distinct = anyDistinct
                ? new DistinctValueCounts(MemoryAccess.best(), ramSlabs, overflowAccess, maxOverflowSlabs)
                : null;
    }

    /**
     * Updates with every value treated as present.
     *
     * <p>For a caller that has no nulls to report. The distinction matters only to {@code
     * COUNT(col)} and {@code AVG}, both of which must ignore nulls and cannot tell from the value --
     * a null is flattened to 0 before it arrives here.
     *
     * @param values one per aggregate column; ignored for {@code COUNT}
     * @param weight the Z-set weight: {@code +1} for an insert, {@code -1} for a retraction
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
        long handle = offHeap.find(keyHigh, keyLow, sliceStart, keyValues);
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
                    // COUNT(*) has no argument and counts rows; COUNT(col) counts non-null values.
                    // This counted rows either way, so a windowed COUNT(col) contradicted the SUM
                    // beside it in its own output row.
                    if (present[i]) {
                        offHeap.setValue(handle, i, checkedAdd(i, offHeap.value(handle, i), 1, weight));
                    }
                }
                case COUNT_DISTINCT -> {
                    // NULL is not a value SQL counts. It used to be: a null flattens to 0 on its
                    // way in, and counting it made COUNT(DISTINCT status) over {ok, NULL, ok,
                    // flagged} report three where SQL says two.
                    if (present[i]) {
                        // Counted, not flagged. A value seen three times and retracted once is
                        // still present, and a set would have said it had gone.
                        Object value = distinctValues == null ? (Object) values[i] : distinctValues[i];
                        int change = distinct.add(keyHigh, keyLow, sliceStart, i, keyValues, value, weight);
                        if (change != 0) {
                            // The slice's own distinct count, kept exact without recounting.
                            offHeap.setValue(handle, i, offHeap.value(handle, i) + change);
                        }
                    }
                }
                case SUM, AVG -> {
                    // Guarded by presence. A null flattens to 0, which adds nothing, so a SUM was
                    // right either way -- but nonNull is what AVG divides by and what MIN and MAX
                    // seed from, and it has to mean non-null values for all of them.
                    if (present[i]) {
                        // Netted over the batch in 128 bits (TRANSOVF-1): the low word is stored,
                        // and what it carries past 64 bits waits in `pending` for settle(). The
                        // checked add is the common case and the cheap one.
                        long total = offHeap.value(handle, i);
                        try {
                            offHeap.setValue(handle, i, AggregateTotals.addWeighted(total, values[i], weight));
                        } catch (ArithmeticException outside) {
                            offHeap.setValue(handle, i, total + values[i] * weight);
                            carry(handle, i, AggregateTotals.wideExcessOf(total, values[i], weight));
                        }
                        offHeap.setNonNull(handle, i, checkedAdd(i, offHeap.nonNull(handle, i), 1, weight));
                    }
                }
                case MIN, MAX -> {
                    if (weight < 0) {
                        throw retractionRefused(kinds[i]);
                    }
                    // NULL is not a value SQL's MIN or MAX considers, and this folded it in: a
                    // null flattens to 0 on the way in, so MIN over {5, 5, 7, NULL} answered 0.
                    if (!present[i]) {
                        break;
                    }
                    // Seeded from the first non-null value, not the first row: a group whose first
                    // row was null seeded the extreme to that null's zero and then compared every
                    // real value against it.
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
            }
        }
    }

    /**
     * {@code total + value * weight}, refused by name past the 64-bit range (SUMWRAP-1): a total that
     * wrapped would be served as the window's answer with nothing to say it was wrong.
     */
    private long checkedAdd(int column, long total, long value, long weight) {
        try {
            return AggregateTotals.addWeighted(total, value, weight);
        } catch (ArithmeticException overflow) {
            throw AggregateTotals.overflow(nameOf(column), overflow);
        }
    }

    private String nameOf(int column) {
        return names != null && column < names.length ? names[column] : kinds[column] + " (aggregate " + column + ")";
    }

    /**
     * Per accumulator handle, what each {@code SUM} carries past its stored 64-bit total in the
     * current batch, in units of {@code 2^64} (TRANSOVF-1, {@link AggregateTotals}). Empty unless a
     * total left the range this batch, so the common case costs one {@code isEmpty}; keyed by handle,
     * which is stable within a batch -- compaction moves blocks only between batches, after {@link
     * #settle}.
     */
    private final java.util.Map<Long, long[]> pending = new java.util.HashMap<>();

    private void carry(long handle, int column, long carried) {
        long[] excess = pending.computeIfAbsent(handle, h -> new long[kinds.length]);
        try {
            excess[column] = Math.addExact(excess[column], carried);
        } catch (ArithmeticException overflow) {
            throw AggregateTotals.overflow(nameOf(column), overflow);
        }
    }

    /**
     * Refuses a batch after which some slice's total does not fit in 64 bits, naming the aggregate
     * (PRV-3025). Called at the end of every batch and before anything reads, moves or writes the
     * accumulators, so a total that left the range inside a batch and came back is accepted and a
     * wrapped one is never read.
     */
    public void settle() {
        if (pending.isEmpty()) {
            return;
        }
        for (long[] excess : pending.values()) {
            for (int i = 0; i < excess.length; i++) {
                if (excess[i] != 0) {
                    throw AggregateTotals.overflow(nameOf(i), new ArithmeticException("long overflow"));
                }
            }
        }
        pending.clear();
    }

    /** How a refusal names each aggregate column, {@code SUM(amount)}; null names them by position. */
    private String[] names;

    /** Names the aggregate columns for a refusal, in {@code kinds} order. */
    public SlicedAggregateState describedAs(String[] aggregateNames) {
        this.names = aggregateNames == null ? null : aggregateNames.clone();
        return this;
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
     * Combines the slices of a window and hands each key that has data in it to {@code out}, one at
     * a time, as it is combined.
     *
     * <p>Does not discard anything. A window that has fired may fire again when a late record
     * arrives, which is what allowed lateness means (design section 15.4) -- so releasing state is a
     * separate decision, made by {@link #discardSlicesEndingBefore}.
     *
     * <p><strong>Nothing the size of the window is built on the heap</strong> (SPILL-3). This used to
     * gather every accumulator's handle into a list, then a map entry and a result per group, and
     * return the lot -- so the heap a watermark advance needed was the size of the whole state, and
     * 1.6 million accumulators threw {@code OutOfMemoryError} against a 160 MiB heap whether the state
     * had spilled or not. Now the accumulators are walked in place: an accumulator whose group has one
     * in an earlier slice of the window is passed over, and the first one found looks its group up in
     * each later slice, off-heap, and is emitted straight away. {@code COUNT(DISTINCT)} answers are
     * counted first into an off-heap map of their own ({@link WindowDistinctCounts}), released when the
     * window has fired. What the heap holds at once is one result, which {@code out} is free to keep or
     * drop.
     *
     * <p>{@code out} must not change this state: it runs while the accumulators are being walked. The
     * order is the store's, stable within a run and not sorted; {@link #fire(long)} sorts, for a
     * caller that wants a list.
     *
     * @return how many results were handed to {@code out}
     */
    public long fire(long windowEndNanos, java.util.function.Consumer<WindowResult> out) {
        settle();
        List<Long> slices = windows.slicesOfWindowEnding(windowEndNanos);
        long[] sliceStarts = new long[slices.size()];
        for (int i = 0; i < sliceStarts.length; i++) {
            sliceStarts[i] = slices.get(i);
        }
        java.util.Arrays.sort(sliceStarts);
        long windowStart = windowEndNanos - windows.spec().sizeNanos();
        WindowDistinctCounts distinctCounts = null;
        try {
            if (distinct != null) {
                distinctCounts = new WindowDistinctCounts(
                        kinds.length,
                        distinct.access(),
                        distinct.ramMaxSlabs(),
                        distinct.overflowAccess(),
                        distinct.maxOverflowSlabs());
                distinct.countWindow(sliceStarts, distinctCounts);
            }
            WindowDistinctCounts counts = distinctCounts;
            SliceAccumulator combined = new SliceAccumulator(kinds.length);
            // A window's SUM is its slices' totals netted in 128 bits, like a batch: slices of
            // MAX, 1 and -1 make a window of MAX, and only a window total that does not fit is refused.
            long[] combinedExcess = new long[kinds.length];
            long[] emitted = {0};
            // SPILL-4: in store order, not the index's hash order. Deciding whether an
            // accumulator is in this window reads it; in hash order that read landed at a random
            // address, which past the page cache is a fault reading far more than the entry.
            offHeap.forEachInStoreOrder(handle -> {
                int position = indexOf(sliceStarts, offHeap.sliceStartOf(handle));
                if (position < 0) {
                    return;
                }
                // The group is emitted from its earliest slice in this window, once.
                for (int earlier = 0; earlier < position; earlier++) {
                    if (offHeap.findInSlice(handle, sliceStarts[earlier]) != ArenaHandle.NULL) {
                        return;
                    }
                }
                java.util.Arrays.fill(combined.values, 0);
                java.util.Arrays.fill(combined.nonNull, 0);
                java.util.Arrays.fill(combinedExcess, 0);
                combined.count = 0;
                merge(combined, combinedExcess, handle);
                for (int later = position + 1; later < sliceStarts.length; later++) {
                    long other = offHeap.findInSlice(handle, sliceStarts[later]);
                    if (other != ArenaHandle.NULL) {
                        merge(combined, combinedExcess, other);
                    }
                }
                for (int i = 0; i < kinds.length; i++) {
                    if (combinedExcess[i] != 0) {
                        throw AggregateTotals.overflow(nameOf(i), new ArithmeticException("long overflow"));
                    }
                }
                if (combined.count == 0) {
                    // A key whose weights cancel to zero within the window has no rows in it.
                    // Emitting a result for it would report an empty group as a present one.
                    return;
                }
                long[] values = combined.values.clone();
                boolean[] nulls = new boolean[kinds.length];
                for (int i = 0; i < kinds.length; i++) {
                    // SQL: SUM, AVG, MIN and MAX of a group with no non-null value are NULL, not 0
                    // (ALLNULLAGG-1). The non-null count is already kept per accumulator, carried
                    // through every slice merge and every checkpoint, so it only had to be read.
                    if (isNullWhenEmpty(kinds[i]) && combined.nonNull[i] == 0) {
                        nulls[i] = true;
                        values[i] = 0;
                        continue;
                    }
                    if (kinds[i] == Kind.AVG) {
                        // Integer division, matching what the keyed and global paths do over an
                        // integer column. Dividing here rather than at the writer keeps every
                        // reader of a WindowResult seeing the answer rather than an intermediate.
                        values[i] = combined.nonNull[i] == 0 ? 0 : combined.values[i] / combined.nonNull[i];
                    } else if (kinds[i] == Kind.COUNT_DISTINCT) {
                        values[i] = counts.countOf(
                                offHeap.keyRegionOf(handle),
                                offHeap.keyOffsetOf(handle),
                                OffHeapAccumulators.GROUP_OFFSET,
                                offHeap.groupLengthOf(handle),
                                i);
                    }
                }
                out.accept(new WindowResult(
                        offHeap.keyHighOf(handle),
                        offHeap.keyLowOf(handle),
                        offHeap.keyValuesOf(handle),
                        windowStart,
                        windowEndNanos,
                        values,
                        combined.count,
                        nulls));
                emitted[0]++;
            });
            return emitted[0];
        } finally {
            if (distinctCounts != null) {
                distinctCounts.close();
            }
        }
    }

    /**
     * {@link #fire(long, java.util.function.Consumer)}, collected into a list sorted by digest.
     *
     * <p>The list is the whole window on the heap, which is exactly what the streaming form exists to
     * avoid; this is for a caller that knows its window is small -- a test, a tool -- and never for
     * the operator, which streams.
     */
    public List<WindowResult> fire(long windowEndNanos) {
        List<WindowResult> results = new ArrayList<>();
        fire(windowEndNanos, results::add);
        results.sort((a, b) -> a.keyHigh() != b.keyHigh()
                ? Long.compare(a.keyHigh(), b.keyHigh())
                : Long.compare(a.keyLow(), b.keyLow()));
        return results;
    }

    /** Whether an aggregate of this kind over no non-null value is NULL: every kind but the counts. */
    static boolean isNullWhenEmpty(Kind kind) {
        return kind == Kind.SUM || kind == Kind.AVG || kind == Kind.MIN || kind == Kind.MAX;
    }

    /** Where {@code sliceStart} is in a window's ascending slice starts, or {@code -1}. A window has a handful. */
    static int indexOf(long[] sliceStarts, long sliceStart) {
        for (int i = 0; i < sliceStarts.length; i++) {
            if (sliceStarts[i] == sliceStart) {
                return i;
            }
        }
        return -1;
    }

    /** Folds one slice's accumulator, read in place off-heap, into a window's. */
    private void merge(SliceAccumulator target, long[] excess, long handle) {
        target.count += offHeap.count(handle);
        for (int i = 0; i < kinds.length; i++) {
            long value = offHeap.value(handle, i);
            long nonNull = offHeap.nonNull(handle, i);
            switch (kinds[i]) {
                case COUNT -> target.values[i] = checkedAdd(i, target.values[i], value, 1);
                case SUM, AVG -> {
                    // A window's total is its slices' totals added: each can fit and their sum
                    // not, so the sum is netted in 128 bits and fire() refuses what does not fit.
                    AggregateTotals.addWeighted(target.values, excess, i, value, 1);
                    target.nonNull[i] = checkedAdd(i, target.nonNull[i], nonNull, 1);
                }
                case COUNT_DISTINCT -> {
                    // Not added: distinct counts do not add across slices, and WindowDistinctCounts
                    // counts the values themselves.
                }
                // Merged on the non-null count, not on whether the target held any rows: a
                // slice of nothing but nulls has rows and no extreme, and treating it as seeded
                // would merge its zero in as though it were a value.
                case MIN -> {
                    if (nonNull > 0) {
                        target.values[i] = target.nonNull[i] == 0 ? value : Math.min(target.values[i], value);
                    }
                    // After the comparison, never before: the test above asks whether the target
                    // had an extreme yet, and adding first would answer it with the source's own
                    // rows.
                    target.nonNull[i] += nonNull;
                }
                case MAX -> {
                    if (nonNull > 0) {
                        target.values[i] = target.nonNull[i] == 0 ? value : Math.max(target.values[i], value);
                    }
                    target.nonNull[i] += nonNull;
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
        settle();
        java.util.function.LongPredicate dead =
                sliceStart -> windows.lastWindowEndFor(sliceStart) + allowedLatenessNanos <= watermarkNanos;
        int before = offHeap.size();
        // Only the dead handles are gathered, and as primitives: a list of every live handle, boxed,
        // was a heap the size of the whole state at every watermark advance (SPILL-3's sibling).
        long[][] deadHandles = {new long[64]};
        int[] deadCount = {0};
        // In store order (SPILL-4): every watermark advance reads every accumulator's slice here,
        // and in the index's hash order each of those reads was a random one. Removed after the
        // walk, never during it.
        offHeap.forEachInStoreOrder(handle -> {
            if (dead.test(offHeap.sliceStartOf(handle))) {
                if (deadCount[0] == deadHandles[0].length) {
                    deadHandles[0] = java.util.Arrays.copyOf(deadHandles[0], deadCount[0] * 2);
                }
                deadHandles[0][deadCount[0]++] = handle;
            }
        });
        for (int i = 0; i < deadCount[0]; i++) {
            offHeap.remove(deadHandles[0][i]);
        }
        if (distinct != null) {
            distinct.removeSlices(dead);
        }
        return before - offHeap.size();
    }

    /**
     * Writes every accumulator, then every distinct value, to a stream.
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
        // Between batches nothing is pending; settling makes that a fact, and the format unchanged.
        settle();
        out.writeInt(FORMAT_VERSION);
        out.writeInt(kinds.length);
        for (Kind kind : kinds) {
            out.writeUTF(kind.name());
        }
        List<Long> handles = new ArrayList<>();
        offHeap.forEach(handles::add);
        out.writeInt(handles.size());
        for (long handle : handles) {
            out.writeLong(offHeap.keyHighOf(handle));
            out.writeLong(offHeap.keyLowOf(handle));
            out.writeLong(offHeap.sliceStartOf(handle));
            SliceAccumulator accumulator = offHeap.read(handle);
            out.writeLong(accumulator.count);
            for (int column = 0; column < kinds.length; column++) {
                out.writeLong(accumulator.values[column]);
                out.writeLong(accumulator.nonNull[column]);
            }
            TaggedValues.writeKeyValues(out, accumulator.keyValues);
        }
        if (distinct == null) {
            out.writeInt(0);
        } else {
            distinct.writeTo(out);
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
        pending.clear();
        int version = in.readInt();
        if (version == 1) {
            throw new java.io.IOException("this windowed aggregate's checkpoint is format version 1, written before "
                    + "COUNT(DISTINCT) state moved off-heap (ADR-044); this engine writes version " + FORMAT_VERSION
                    + " and does not read version 1, which kept each distinct set inside its accumulator and "
                    + "did not carry the non-null counts AVG divides by. Refusing to guess at the difference.");
        }
        if (version == 2) {
            throw new java.io.IOException("this windowed aggregate's checkpoint is format version 2, written while "
                    + "its COUNT(DISTINCT) values named their group by digest alone (W8-14); this engine writes "
                    + "version " + FORMAT_VERSION + ", which names each value's group by its key columns, and "
                    + "does not read version 2, whose values cannot be told apart between two groups sharing a "
                    + "digest. Refusing to guess at the difference.");
        }
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

        offHeap.clear();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            long keyHigh = in.readLong();
            long keyLow = in.readLong();
            long sliceStart = in.readLong();
            long readCount = in.readLong();
            long[] values = new long[kinds.length];
            long[] nonNull = new long[kinds.length];
            for (int column = 0; column < kinds.length; column++) {
                values[column] = in.readLong();
                nonNull[column] = in.readLong();
            }
            Object[] keyValues = TaggedValues.readKeyValues(in);
            long handle = offHeap.create(keyHigh, keyLow, sliceStart, keyValues);
            offHeap.setCount(handle, readCount);
            for (int column = 0; column < kinds.length; column++) {
                offHeap.setValue(handle, column, values[column]);
                offHeap.setNonNull(handle, column, nonNull[column]);
            }
        }
        peakSlices = Math.max(peakSlices, offHeap.size());
        if (distinct == null) {
            int entries = in.readInt();
            if (entries != 0) {
                throw new java.io.IOException("checkpoint holds " + entries + " distinct values for an aggregate "
                        + "with no COUNT(DISTINCT) column: it was written by a different query");
            }
        } else {
            distinct.readFrom(in, kinds.length);
        }
    }

    /**
     * Bumped whenever the layout above changes in a way an older reader would misread.
     *
     * <p>Version 2 (ADR-044): each accumulator carries its non-null counts beside its values -- AVG
     * divides by them, and a version 1 restore left them at zero, so a restored window's AVG came out
     * 0 -- and the distinct values follow every accumulator as one section of their own, instead of
     * one set inside each accumulator. Version 1 is refused by name.
     *
     * <p>Version 3 (W8-14): each distinct value carries its group's key columns after its column
     * number, because the digest before it is not an identity. Version 2 is refused by name.
     */
    private static final int FORMAT_VERSION = 3;

    /** Live accumulators. The number bounded-state enforcement watches. */
    public int liveSlices() {
        return offHeap.size();
    }

    /** Live {@code (group, slice, column, value)} entries behind this aggregate's {@code COUNT(DISTINCT)}
     * columns; zero when it has none. */
    public int distinctValuesHeld() {
        return distinct == null ? 0 : distinct.size();
    }

    public long peakSlices() {
        return peakSlices;
    }

    public int maxSlices() {
        return maxSlices;
    }

    /** Whether this aggregate's off-heap state -- accumulators or distinct values -- has spilled to
     * the overflow tier. */
    public boolean hasSpilled() {
        return offHeap.map().hasSpilled() || (distinct != null && distinct.map().hasSpilled());
    }

    /** Bytes this aggregate's off-heap state has taken from the operating system -- index and data,
     * accumulators and distinct values together. */
    public long offHeapBytesAllocated() {
        return offHeap.map().bytesAllocated()
                + (distinct == null ? 0 : distinct.map().bytesAllocated());
    }

    /**
     * Compacts the accumulators' and the distinct values' stores if either's overflow tier is at
     * least {@code threshold} fragmented (ADR-044) -- which a windowed aggregate reaches every time it
     * discards the slices a watermark has passed. Between batches, on the lane thread: nothing here
     * keeps a handle across calls.
     *
     * @return how many overflow slabs were released
     */
    public int compactIfFragmented(double threshold) {
        // Compaction moves blocks to new handles, and `pending` is keyed by handle.
        settle();
        int released = offHeap.map().compactIfFragmented(threshold);
        if (distinct != null) {
            released += distinct.map().compactIfFragmented(threshold);
        }
        return released;
    }

    /** The overflow tier's numbers for this aggregate's stores. */
    public com.ash.messaging.pravaha.state.SpillStatistics spillStatistics() {
        com.ash.messaging.pravaha.state.SpillStatistics statistics =
                offHeap.map().spillStatistics();
        return distinct == null ? statistics : statistics.plus(distinct.map().spillStatistics());
    }

    /** Releases the off-heap resources this aggregate holds. */
    @Override
    public void close() {
        offHeap.close();
        if (distinct != null) {
            distinct.close();
        }
    }
}
