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

    /** What a window produced for one key. */
    public record WindowResult(long key, long windowStartNanos, long windowEndNanos, long[] values, long count) {}

    /** Which aggregate a column holds. */
    public enum Kind {
        COUNT,
        SUM,
        MIN,
        MAX
    }

    private record SliceKey(long key, long sliceStart) {}

    private static final class Accumulator {
        final long[] values;
        long count;

        Accumulator(int columns) {
            this.values = new long[columns];
        }
    }

    private final SlicedWindows windows;
    private final Kind[] kinds;
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
        this.maxSlices = maxSlices;
    }

    /**
     * Folds one record into its slice.
     *
     * @param values one per aggregate column; ignored for {@code COUNT}
     * @param weight the Z-set weight: {@code +1} for an insert, {@code -1} for a retraction
     */
    public void update(long key, long eventTimeNanos, long[] values, long weight) {
        if (weight == 0) {
            // A consolidated row contributes nothing and must not be counted. Skipping it here also
            // stops it creating an accumulator, which would otherwise be state held for no data.
            return;
        }
        long sliceStart = windows.sliceStartFor(eventTimeNanos);
        SliceKey sliceKey = new SliceKey(key, sliceStart);
        Accumulator accumulator = slices.get(sliceKey);
        if (accumulator == null) {
            if (slices.size() >= maxSlices) {
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "this windowed aggregate is holding " + slices.size() + " (key, slice) accumulators, "
                                + "its configured ceiling, and key " + key + " at event time " + eventTimeNanos
                                + " needs another. Either the key space is unbounded -- which no window can fix -- "
                                + "or the window is too wide for the key count. Raise the limit deliberately, "
                                + "narrow the window, or add a key predicate.");
            }
            accumulator = new Accumulator(kinds.length);
            slices.put(sliceKey, accumulator);
            peakSlices = Math.max(peakSlices, slices.size());
        }

        accumulator.count += weight;
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case COUNT -> accumulator.values[i] += weight;
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
        Map<Long, Accumulator> combined = new HashMap<>();
        for (long sliceStart : sliceStarts) {
            for (Map.Entry<SliceKey, Accumulator> entry : slices.entrySet()) {
                if (entry.getKey().sliceStart() != sliceStart) {
                    continue;
                }
                Accumulator target =
                        combined.computeIfAbsent(entry.getKey().key(), key -> new Accumulator(kinds.length));
                merge(target, entry.getValue());
            }
        }

        long windowStart = windowEndNanos - windows.spec().sizeNanos();
        List<WindowResult> results = new ArrayList<>(combined.size());
        combined.forEach((key, accumulator) -> {
            if (accumulator.count != 0) {
                // A key whose weights cancel to zero within the window has no rows in it. Emitting a
                // result for it would report an empty group as a present one.
                results.add(new WindowResult(
                        key, windowStart, windowEndNanos, accumulator.values.clone(), accumulator.count));
            }
        });
        results.sort((a, b) -> Long.compare(a.key(), b.key()));
        return results;
    }

    private void merge(Accumulator target, Accumulator source) {
        boolean targetWasEmpty = target.count == 0;
        target.count += source.count;
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case COUNT, SUM -> target.values[i] += source.values[i];
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
