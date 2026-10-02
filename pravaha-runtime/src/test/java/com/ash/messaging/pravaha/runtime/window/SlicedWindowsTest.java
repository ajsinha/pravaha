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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Window slicing.
 *
 * <p>The arithmetic here is pure, so it can be checked exhaustively rather than sampled -- which
 * matters because the failure mode is not a crash. An off-by-one at a boundary puts records in the
 * wrong window and produces an answer that is plausible, stable and wrong, and no test that only
 * samples the middle of a window will ever see it.
 *
 * <p>{@link #everyRecordLandsInExactlyOneSliceAndThatSliceIsInEveryWindowItBelongsTo} is the
 * consistency check between the two halves: the hot path files a record into one slice, the firing
 * path combines slices into a window, and if those two disagree the query silently loses or
 * duplicates records.
 */
class SlicedWindowsTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aTumblingWindowIsOneSlice() {
        // No combining at all, which is worth asserting: the slicing machinery must not make the
        // simple case more expensive than it was.
        WindowSpec spec = WindowSpec.tumbling(60 * SECOND);
        assertThat(spec.sliceSizeNanos()).isEqualTo(60 * SECOND);
        assertThat(spec.slicesPerWindow()).isEqualTo(1);

        SlicedWindows windows = new SlicedWindows(spec);
        assertThat(windows.sliceStartFor(90 * SECOND)).isEqualTo(60 * SECOND);
        assertThat(windows.slicesOfWindowEnding(120 * SECOND)).containsExactly(60 * SECOND);
    }

    @Test
    void aHoppingWindowIsSlicedByTheGreatestCommonDivisor() {
        // Design 15.3's example: 60 s hopping 10 s. Six slices per window, one update per record
        // instead of six.
        WindowSpec spec = WindowSpec.hopping(60 * SECOND, 10 * SECOND);
        assertThat(spec.sliceSizeNanos()).isEqualTo(10 * SECOND);
        assertThat(spec.slicesPerWindow()).isEqualTo(6);

        SlicedWindows windows = new SlicedWindows(spec);
        assertThat(windows.slicesOfWindowEnding(60 * SECOND))
                .containsExactly(0L, 10 * SECOND, 20 * SECOND, 30 * SECOND, 40 * SECOND, 50 * SECOND);
    }

    @Test
    void aSlideThatDoesNotDivideTheSizeStillSlicesCorrectly() {
        // 60 s hopping 7 s: the gcd is 1 s, so 60 slices per window. Wasteful, and correct, and the
        // arithmetic must not quietly round to something convenient.
        WindowSpec spec = WindowSpec.hopping(60 * SECOND, 7 * SECOND);
        assertThat(spec.sliceSizeNanos()).isEqualTo(SECOND);
        assertThat(spec.slicesPerWindow()).isEqualTo(60);
    }

    @Test
    void aRecordOnABoundaryBelongsToTheWindowThatStartsThere() {
        // Half-open, [start, end). Any other convention double-counts, and event times land exactly
        // on boundaries constantly: clocks tick in round numbers and batch jobs stamp whole seconds.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));

        assertThat(windows.sliceStartFor(10 * SECOND)).isEqualTo(10 * SECOND);
        assertThat(windows.sliceStartFor(10 * SECOND - 1)).isZero();
        assertThat(windows.windowEndsContaining(10 * SECOND)).containsExactly(20 * SECOND);
        assertThat(windows.windowEndsContaining(10 * SECOND - 1)).containsExactly(10 * SECOND);
    }

    @Test
    void negativeEventTimesLandInTheSliceBelowNotAbove() {
        // Epoch nanoseconds before 1970 are legal and backfills reach them. Truncating division
        // rounds towards zero, which would put -1 ns in the slice starting at 0 -- a record in the
        // future of its own timestamp.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));

        assertThat(windows.sliceStartFor(-1)).isEqualTo(-10 * SECOND);
        assertThat(windows.sliceStartFor(-10 * SECOND)).isEqualTo(-10 * SECOND);
        assertThat(windows.sliceStartFor(-10 * SECOND - 1)).isEqualTo(-20 * SECOND);
    }

    @Test
    void everyRecordLandsInExactlyOneSliceAndThatSliceIsInEveryWindowItBelongsTo() {
        // The consistency check between the two halves of the design. The hot path files a record
        // into one slice; the firing path combines slices into a window. If they disagree, records
        // are silently lost or double-counted -- and the query still produces numbers.
        //
        // Exhaustive over a full window's worth of nanosecond-resolution offsets at second
        // granularity, for several shapes.
        List<WindowSpec> shapes = List.of(
                WindowSpec.tumbling(10 * SECOND),
                WindowSpec.hopping(60 * SECOND, 10 * SECOND),
                WindowSpec.hopping(30 * SECOND, 30 * SECOND),
                WindowSpec.hopping(60 * SECOND, 7 * SECOND));

        for (WindowSpec spec : shapes) {
            SlicedWindows windows = new SlicedWindows(spec);
            for (long eventTime = 0; eventTime <= 120 * SECOND; eventTime += SECOND / 4) {
                long slice = windows.sliceStartFor(eventTime);
                assertThat(eventTime)
                        .as("%s: the record must lie inside its own slice", spec)
                        .isBetween(slice, slice + windows.sliceSizeNanos() - 1);

                for (long windowEnd : windows.windowEndsContaining(eventTime)) {
                    assertThat(windows.slicesOfWindowEnding(windowEnd))
                            .as(
                                    "%s: record at %d is in window ending %d, so its slice %d must be one "
                                            + "of that window's slices",
                                    spec, eventTime, windowEnd, slice)
                            .contains(slice);
                }
            }
        }
    }

    @Test
    void everyRecordBelongsToTheRightNumberOfWindows() {
        // A 60 s window hopping 10 s covers every instant six times. Fewer means a gap; more means
        // double counting. Both produce an answer.
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(60 * SECOND, 10 * SECOND));

        for (long eventTime = 60 * SECOND; eventTime < 120 * SECOND; eventTime += SECOND) {
            assertThat(windows.windowEndsContaining(eventTime))
                    .as("at %d", eventTime)
                    .hasSize(6);
        }
    }

    @Test
    void aWindowFiresOnceAndOnlyWhenItsWatermarkArrives() {
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(60 * SECOND, 10 * SECOND));

        assertThat(windows.windowsCompletedBetween(0, 55 * SECOND))
                .containsExactly(10 * SECOND, 20 * SECOND, 30 * SECOND, 40 * SECOND, 50 * SECOND);
        assertThat(windows.windowsCompletedBetween(55 * SECOND, 65 * SECOND))
                .as("only the ones that completed since the last firing")
                .containsExactly(60 * SECOND);

        // Firing over a full run must produce each window exactly once, whatever the watermark
        // steps happen to be.
        Set<Long> fired = new HashSet<>();
        long previous = 0;
        for (long watermark = 3 * SECOND; watermark <= 300 * SECOND; watermark += 7 * SECOND) {
            for (long end : windows.windowsCompletedBetween(previous, watermark)) {
                assertThat(fired.add(end))
                        .as("window ending %d fired twice", end)
                        .isTrue();
            }
            previous = watermark;
        }
        assertThat(fired).hasSize(29);
    }

    @Test
    void aSliceKnowsWhenItCanBeDiscarded() {
        // What bounds state. Without it a slice is kept in case some future window needs it; with
        // it, a slice lives exactly one window's width plus the allowed lateness.
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(60 * SECOND, 10 * SECOND));

        // The slice [0, 10) is in the windows ending at 10, 20, ..., 60. After 60 fires it is dead.
        assertThat(windows.lastWindowEndFor(0)).isEqualTo(60 * SECOND);
        assertThat(windows.lastWindowEndFor(50 * SECOND)).isEqualTo(110 * SECOND);

        for (long slice = 0; slice <= 200 * SECOND; slice += 10 * SECOND) {
            long last = windows.lastWindowEndFor(slice);
            assertThat(windows.slicesOfWindowEnding(last))
                    .as("the slice must actually be part of the last window that needs it")
                    .contains(slice);
            assertThat(windows.slicesOfWindowEnding(last + 10 * SECOND))
                    .as("and must not be part of the one after it")
                    .doesNotContain(slice);
        }
    }

    @Test
    void aHopWhoseSizeIsNotAMultipleOfTheSlideStartsItsWindowsOnTheSlide() {
        // HOPALIGN-1. SQL's HOP starts windows at multiples of the slide: 25 s sliding 10 s puts
        // t = 12 s in [-10, 15), [0, 25) and [10, 35). Ends used to be aligned instead: [-5, 20) and
        // [5, 30), and lastWindowEndFor -- start-aligned all along -- disagreed with the firing.
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(25 * SECOND, 10 * SECOND));
        assertThat(windows.windowEndsContaining(12 * SECOND)).containsExactly(15 * SECOND, 25 * SECOND, 35 * SECOND);
        assertThat(windows.windowsCompletedBetween(0, 60 * SECOND))
                .containsExactly(5 * SECOND, 15 * SECOND, 25 * SECOND, 35 * SECOND, 45 * SECOND, 55 * SECOND);
        // Every slice is in exactly the windows that contain it, the firing and the discard agree,
        // and each window's slices start on its own start.
        for (long slice = -60 * SECOND; slice <= 60 * SECOND; slice += windows.sliceSizeNanos()) {
            List<Long> ends = windows.windowEndsContaining(slice);
            assertThat(ends).as("slice %d", slice).hasSizeBetween(2, 3);
            assertThat(windows.lastWindowEndFor(slice)).as("slice %d", slice).isEqualTo(ends.get(ends.size() - 1));
            for (long end : ends) {
                assertThat(Math.floorMod(end - 25 * SECOND, 10 * SECOND)).isZero();
                assertThat(windows.slicesOfWindowEnding(end)).contains(slice);
            }
        }
        // A size that is a multiple of the slide, and a tumble, are unchanged.
        assertThat(new SlicedWindows(WindowSpec.hopping(60 * SECOND, 10 * SECOND)).windowEndsContaining(12 * SECOND))
                .containsExactly(20 * SECOND, 30 * SECOND, 40 * SECOND, 50 * SECOND, 60 * SECOND, 70 * SECOND);
        assertThat(new SlicedWindows(WindowSpec.hopping(30 * SECOND, 30 * SECOND)).windowEndsContaining(12 * SECOND))
                .containsExactly(30 * SECOND);
    }

    @Test
    void aWindowingSchemeThatWouldLoseRecordsIsRefused() {
        // A slide wider than the size leaves gaps, so records between windows belong to none. Almost
        // always a typo; when it is not, it is a filter followed by a tumble, which says what it
        // means.
        assertThatThrownBy(() -> WindowSpec.hopping(10 * SECOND, 30 * SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaves gaps");
        assertThatThrownBy(() -> WindowSpec.tumbling(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionWindowsRefuseToBeSliced() {
        // Their boundaries come from the data, not the clock, so no fixed interval avoids straddling
        // one. Saying so beats returning a number that looks usable.
        assertThatThrownBy(() -> WindowSpec.session(30 * SECOND).sliceSizeNanos())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("decided by the data");
        assertThatThrownBy(() -> new SlicedWindows(WindowSpec.session(30 * SECOND)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneStaleEventTimeIsRefusedRatherThanWalkedThrough() {
        // TIME-1. The loop's length is (watermark - previousWatermark) / slide, and both ends of
        // that subtraction come from the data. A single row timestamped at the epoch in a stream of
        // present-day rows makes the first window start there; at a one-second slide that is on the
        // order of a billion iterations, during which the lane does nothing else and looks hung.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(SECOND));
        long now = java.time.Instant.parse("2026-09-15T00:00:00Z").getEpochSecond() * SECOND;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> windows.windowsCompletedBetween(0L, now))
                .as("a lane that stops with a message naming the span is worth more than one that "
                        + "spins for an hour looking like a hang")
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("PRV-3022")
                .hasMessageContaining("event time");
    }

    @Test
    void theRefusalNamesTheSpanAndPointsAtTheEarliestRow() {
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(SECOND));
        long now = java.time.Instant.parse("2026-09-15T00:00:00Z").getEpochSecond() * SECOND;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> windows.windowsCompletedBetween(0L, now))
                .as("an operator needs to be sent to the data, not to the window configuration")
                .hasMessageContaining("1970")
                .hasMessageContaining("2026")
                .hasMessageContaining("earliest row");
    }

    @Test
    void aLongButLegitimateCatchUpIsStillFired() {
        // The property the bound must not cost. A day of one-second windows is 86,400 -- well within
        // the limit -- and a node that was down for a day must still emit every window it missed.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(SECOND));
        long aDay = 24L * 60 * 60 * SECOND;

        assertThat(windows.windowsCompletedBetween(0L, aDay))
                .as("catching up after an outage is not the same shape as a bad timestamp: one is "
                        + "large by a factor, the other by orders of magnitude")
                .hasSize(86_400);
    }

    @Test
    void anOrdinaryAdvanceIsUnchanged() {
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(60 * SECOND));
        assertThat(windows.windowsCompletedBetween(0L, 180 * SECOND))
                .containsExactly(60 * SECOND, 120 * SECOND, 180 * SECOND);
    }
}
