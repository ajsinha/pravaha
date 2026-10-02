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
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * The slicing arithmetic: which slice a record falls in, and which slices a window is made of.
 *
 * <p>All of it is integer arithmetic on epoch nanoseconds with no state at all, which is
 * deliberate. This is called once per record on the hot path, and it is also the part of windowing
 * that is easiest to get subtly wrong -- an off-by-one at a boundary puts a record in the wrong
 * window and produces an answer that is plausible, stable, and incorrect. Keeping it pure means it
 * can be tested exhaustively rather than sampled.
 *
 * <p><strong>Windows are half-open, {@code [start, end)}.</strong> A record whose event time is
 * exactly a boundary belongs to the window that <em>starts</em> there, never the one that ends. Any
 * other convention double-counts boundary records, and event times land exactly on boundaries far
 * more often than intuition suggests -- clocks tick in round numbers and batch jobs stamp whole
 * seconds.
 *
 * <p><strong>Windows start on multiples of the slide</strong>, as SQL's {@code HOP} does (Calcite's
 * {@code HopEnumerator}, Flink): {@code [k * slide, k * slide + size)}. Ends are therefore aligned to
 * {@code size mod slide}, which is zero for a tumble and for a hop whose size is a multiple of its
 * slide -- every window those have is unchanged. A hop of size 25 s sliding every 10 s used to align
 * its ends instead, so its windows were {@code [-5, 20)} and {@code [5, 30)} where SQL's are
 * {@code [-10, 15)}, {@code [0, 25)} and {@code [10, 35)} -- and {@link #lastWindowEndFor}, which
 * already assumed aligned starts, disagreed with the firing about a slice's last window (HOPALIGN-1).
 */
public final class SlicedWindows {

    private final WindowSpec spec;
    private final long sliceSize;

    /** Where window ends fall within a slide: {@code size mod slide}, since starts are aligned. */
    private final long endOffset;

    public SlicedWindows(WindowSpec spec) {
        if (spec.kind() == WindowSpec.Kind.SESSION) {
            throw new IllegalArgumentException("session windows are not sliced; use SessionWindows");
        }
        this.spec = spec;
        this.sliceSize = spec.sliceSizeNanos();
        this.endOffset = Math.floorMod(spec.sizeNanos(), spec.slideNanos());
    }

    /** The first window end strictly after {@code timeNanos}: a start on a multiple of the slide, plus size. */
    private long firstEndAfter(long timeNanos) {
        return Math.floorDiv(timeNanos - endOffset, spec.slideNanos()) * spec.slideNanos()
                + spec.slideNanos()
                + endOffset;
    }

    /**
     * The single slice a record belongs to.
     *
     * <p>One slice, whatever the window overlap: that is the point of slicing. Floor division rather
     * than truncation, so negative event times -- which happen, because epoch nanoseconds before
     * 1970 are legal and backfills reach them -- land in the slice below rather than the one above.
     */
    public long sliceStartFor(long eventTimeNanos) {
        return Math.floorDiv(eventTimeNanos, sliceSize) * sliceSize;
    }

    public long sliceSizeNanos() {
        return sliceSize;
    }

    /** The slices that make up the window ending at {@code windowEnd}, in time order. */
    public List<Long> slicesOfWindowEnding(long windowEndNanos) {
        List<Long> slices = new ArrayList<>(spec.slicesPerWindow());
        long start = windowEndNanos - spec.sizeNanos();
        for (long slice = start; slice < windowEndNanos; slice += sliceSize) {
            slices.add(slice);
        }
        return slices;
    }

    /**
     * The windows that a record at {@code eventTime} contributes to.
     *
     * <p>Not used on the hot path -- the record goes into one slice and the combining happens at
     * firing time -- but it is what the slicing has to agree with, so it exists for the tests to
     * check the two against each other. If a record's slice is not part of every window this
     * returns, the slicing is wrong.
     */
    public List<Long> windowEndsContaining(long eventTimeNanos) {
        List<Long> ends = new ArrayList<>();
        // The smallest window end strictly after the record. Strictly after is the half-open rule:
        // a window ending exactly at the record's time does not contain it.
        long firstEnd = firstEndAfter(eventTimeNanos);
        for (long end = firstEnd; end - spec.sizeNanos() <= eventTimeNanos; end += spec.slideNanos()) {
            ends.add(end);
        }
        // No membership test inside the loop. An earlier version had one -- `eventTime >= end - size
        // && eventTime < end` -- and both halves were already guaranteed by where the loop starts
        // and where it stops. Seeding a broken boundary rule into that condition changed nothing and
        // the test passed, which is what unreachable guards do: they look like safety and absorb
        // exactly the mistakes they appear to catch.
        return ends;
    }

    /**
     * Window ends that have completed at this watermark and not before.
     *
     * @param previousWatermarkNanos the watermark at the last firing
     * @param watermarkNanos the watermark now
     */
    /**
     * The most windows one watermark advance may fire.
     *
     * <p>Generous on purpose: a day of one-second windows is 86,400 and a year of hourly ones is
     * 8,760, so a legitimate catch-up after a long outage stays well inside this. What it refuses is
     * the shape produced by a bad timestamp, which is larger by orders of magnitude rather than by a
     * factor.
     */
    private static final long MAX_WINDOWS_PER_ADVANCE = 10_000_000L;

    public List<Long> windowsCompletedBetween(long previousWatermarkNanos, long watermarkNanos) {
        List<Long> ends = new ArrayList<>();
        // The first window end strictly after the previous watermark. Strictly after is what stops a
        // window firing twice: one whose end equals the previous watermark fired then.
        //
        // No `end > previous` test inside the loop. It can never be false -- firstEnd exceeds the
        // previous watermark by construction -- and an unreachable guard is worse than none: it
        // looks like the protection while silently absorbing the mistake it appears to catch. The
        // same shape was found and removed from windowEndsContaining minutes earlier, which is why
        // it is called out here rather than quietly deleted.
        long firstEnd = firstEndAfter(previousWatermarkNanos);
        // TIME-1. Bounded, because this loop's length is (watermark - previousWatermark) / slide and
        // both ends of that subtraction come from the data. One row timestamped 1970 in a stream of
        // present-day rows makes the first window start in 1970, and a one-second slide then means
        // something on the order of a billion iterations -- during which the lane does nothing else
        // and looks, from outside, exactly like a hang.
        //
        // Refused rather than skipped. Skipping forward would be silent, and the windows being
        // skipped cannot be shown to be empty from here: this class knows the shape of the windows
        // and not which of them hold slices. A lane that stops with a message naming the span and
        // the likely cause is worth more than one that quietly emits a different set of windows
        // than the query asked for -- and the actual defect is a timestamp nobody meant to send,
        // which no amount of skipping fixes.
        long span = watermarkNanos - firstEnd;
        if (span > 0 && span / spec.slideNanos() > MAX_WINDOWS_PER_ADVANCE) {
            throw new PravahaException(
                    RuntimeErrors.WINDOW_SPAN_IMPLAUSIBLE,
                    "one watermark advance would fire " + (span / spec.slideNanos() + 1) + " windows, between "
                            + java.time.Instant.ofEpochSecond(0L, firstEnd) + " and "
                            + java.time.Instant.ofEpochSecond(0L, watermarkNanos) + ", at a slide of "
                            + java.time.Duration.ofNanos(spec.slideNanos()) + ". That span almost always means a "
                            + "single row carried an event time far outside the rest of the stream -- an unset "
                            + "field read as the epoch, a value in the wrong unit, or a parse that silently "
                            + "produced zero. Check the event-time column of the earliest row this query saw. "
                            + "If the span is genuinely intended, the window is too fine for it.");
        }
        for (long end = firstEnd; end <= watermarkNanos; end += spec.slideNanos()) {
            ends.add(end);
        }
        return ends;
    }

    /**
     * The last window end a slice contributes to, which is when the slice may be discarded.
     *
     * <p>This is what bounds state (design section 9.6). Without it a slice is kept forever on the
     * chance that some future window needs it; with it, a slice's lifetime is exactly the width of
     * one window plus whatever lateness the query allows.
     */
    public long lastWindowEndFor(long sliceStartNanos) {
        long lastEnd = Math.floorDiv(sliceStartNanos, spec.slideNanos()) * spec.slideNanos() + spec.sizeNanos();
        while (lastEnd - spec.sizeNanos() > sliceStartNanos) {
            lastEnd -= spec.slideNanos();
        }
        while (lastEnd + spec.slideNanos() - spec.sizeNanos() <= sliceStartNanos) {
            lastEnd += spec.slideNanos();
        }
        return lastEnd;
    }

    public WindowSpec spec() {
        return spec;
    }
}
