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
        /** Per distinct-column, how many times each value is currently present. Null unless needed. */
        Map<Long, Long>[] distinct;

        Object[] keyValues;
        long count;

        @SuppressWarnings("unchecked")
        Accumulator(int columns, boolean[] needsDistinct) {
            this.values = new long[columns];
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
    public void update(long keyHigh, long keyLow, Object[] keyValues, long eventTimeNanos, long[] values, long weight) {
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
                case COUNT -> accumulator.values[i] += weight;
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
                results.add(new WindowResult(
                        groupKey.keyHigh(),
                        groupKey.keyLow(),
                        accumulator.keyValues,
                        windowStart,
                        windowEndNanos,
                        accumulator.values.clone(),
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
