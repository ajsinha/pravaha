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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Windowed aggregates over slices.
 *
 * <p>Two properties carry the design. Slicing must produce exactly the same answers as computing
 * each window independently -- otherwise the optimisation is a bug -- and retraction must work by
 * the same arithmetic as insertion, which is what makes late data a correction rather than a special
 * case.
 */
class SlicedAggregateStateTest {

    private static final long SECOND = 1_000_000_000L;

    private static SlicedAggregateState state(WindowSpec spec, int maxSlices, SlicedAggregateState.Kind... kinds) {
        return new SlicedAggregateState(new SlicedWindows(spec), kinds, maxSlices);
    }

    @Test
    void aTumblingSumAndCountOverOneWindow() {
        SlicedAggregateState state = state(
                WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM);

        state.update(1L, SECOND, new long[] {0, 100}, 1);
        state.update(1L, 2 * SECOND, new long[] {0, 200}, 1);
        state.update(2L, 3 * SECOND, new long[] {0, 50}, 1);

        List<SlicedAggregateState.WindowResult> results = state.fire(10 * SECOND);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).key()).isEqualTo(1L);
        assertThat(results.get(0).values()).containsExactly(2, 300);
        assertThat(results.get(0).windowStartNanos()).isZero();
        assertThat(results.get(1).values()).containsExactly(1, 50);
    }

    @Test
    void aRecordUpdatesOneAccumulatorHoweverManyWindowsItIsIn() {
        // The optimisation, asserted directly. A 60 s window hopping 10 s puts every record in six
        // windows; the state must still be one accumulator per record's slice.
        SlicedAggregateState state =
                state(WindowSpec.hopping(60 * SECOND, 10 * SECOND), 100, SlicedAggregateState.Kind.COUNT);

        state.update(1L, 5 * SECOND, new long[] {0}, 1);

        assertThat(state.liveSlices())
                .as("one record, one accumulator, not one per window it belongs to")
                .isEqualTo(1);
    }

    @Test
    void slicingAgreesWithComputingEachWindowIndependently() {
        // If the optimisation and the definition ever disagree, the optimisation is a bug -- and the
        // symptom is a number that is close enough to look right.
        WindowSpec spec = WindowSpec.hopping(60 * SECOND, 10 * SECOND);
        SlicedWindows windows = new SlicedWindows(spec);
        SlicedAggregateState state =
                state(spec, 10_000, SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM);

        long[][] records = new long[200][];
        for (int i = 0; i < 200; i++) {
            long eventTime = (long) i * SECOND / 2;
            long key = i % 3;
            long value = i;
            records[i] = new long[] {eventTime, key, value};
            state.update(key, eventTime, new long[] {0, value}, 1);
        }

        for (long windowEnd = 60 * SECOND; windowEnd <= 160 * SECOND; windowEnd += 10 * SECOND) {
            long windowStart = windowEnd - spec.sizeNanos();
            for (long key = 0; key < 3; key++) {
                long expectedCount = 0;
                long expectedSum = 0;
                for (long[] record : records) {
                    if (record[1] == key && record[0] >= windowStart && record[0] < windowEnd) {
                        expectedCount++;
                        expectedSum += record[2];
                    }
                }

                long finalKey = key;
                List<SlicedAggregateState.WindowResult> fired = state.fire(windowEnd);
                var actual = fired.stream()
                        .filter(result -> result.key() == finalKey)
                        .findFirst();

                long finalExpectedCount = expectedCount;
                long finalExpectedSum = expectedSum;
                if (expectedCount == 0) {
                    assertThat(actual)
                            .as("window ending %d, key %d has no records", windowEnd, key)
                            .isEmpty();
                } else {
                    assertThat(actual)
                            .as("window ending %d, key %d", windowEnd, key)
                            .hasValueSatisfying(result -> {
                                assertThat(result.values()[0]).isEqualTo(finalExpectedCount);
                                assertThat(result.values()[1]).isEqualTo(finalExpectedSum);
                            });
                }
            }
        }
        assertThat(windows.slicesOfWindowEnding(60 * SECOND)).hasSize(6);
    }

    @Test
    void aRetractionIsTheSameArithmeticAsAnInsert() {
        // No separate retract path to get wrong, which is the whole argument for Z-sets. It is also
        // what makes a late correction possible: the accumulator can go backwards.
        SlicedAggregateState state = state(
                WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM);

        state.update(1L, SECOND, new long[] {0, 100}, 1);
        state.update(1L, 2 * SECOND, new long[] {0, 200}, 1);
        assertThat(state.fire(10 * SECOND).get(0).values()).containsExactly(2, 300);

        state.update(1L, 2 * SECOND, new long[] {0, 200}, -1);
        assertThat(state.fire(10 * SECOND).get(0).values())
                .as("the retraction undoes exactly what the insert did")
                .containsExactly(1, 100);
    }

    @Test
    void aKeyWhoseWeightsCancelProducesNoResult() {
        // An empty group must not be reported as a present one with zeroes: a consumer cannot tell
        // "no rows" from "rows summing to nothing", and for a COUNT the difference is the answer.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.COUNT);

        state.update(1L, SECOND, new long[] {0}, 1);
        state.update(1L, SECOND, new long[] {0}, -1);

        assertThat(state.fire(10 * SECOND)).isEmpty();
    }

    @Test
    void minAndMaxCombineAcrossSlices() {
        SlicedAggregateState state = state(
                WindowSpec.hopping(30 * SECOND, 10 * SECOND),
                100,
                SlicedAggregateState.Kind.MIN,
                SlicedAggregateState.Kind.MAX);

        state.update(1L, SECOND, new long[] {50, 50}, 1);
        state.update(1L, 12 * SECOND, new long[] {10, 10}, 1);
        state.update(1L, 22 * SECOND, new long[] {90, 90}, 1);

        assertThat(state.fire(30 * SECOND).get(0).values()).containsExactly(10, 90);
    }

    @Test
    void retractingFromAMinOrMaxIsRefusedRatherThanApproximated() {
        // Knowing the current extreme does not tell you the previous one. Returning a stale extreme
        // would be a wrong answer that looks exactly like a right one.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.MAX);
        state.update(1L, SECOND, new long[] {50}, 1);

        assertThatThrownBy(() -> state.update(1L, SECOND, new long[] {50}, -1))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("ordered multiset");
    }

    @Test
    void anUnboundedKeySpaceIsRefusedWithTheKeyThatBrokeIt() {
        // How incremental engines die in production: gradually, then all at once, weeks after
        // deployment. "Out of memory" is not a diagnosis, so the refusal names the key and says what
        // to do about it.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 5, SlicedAggregateState.Kind.COUNT);

        for (long key = 0; key < 5; key++) {
            state.update(key, SECOND, new long[] {0}, 1);
        }

        assertThatThrownBy(() -> state.update(99L, SECOND, new long[] {0}, 1))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("key 99")
                .hasMessageContaining("key space is unbounded");
    }

    @Test
    void aStateCeilingRefusesRatherThanEvicting() {
        // Eviction would make the answer wrong instead of making the query fail, and a wrong answer
        // nobody is told about is worse than a stopped query.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 2, SlicedAggregateState.Kind.COUNT);
        state.update(1L, SECOND, new long[] {0}, 1);
        state.update(2L, SECOND, new long[] {0}, 1);

        assertThatThrownBy(() -> state.update(3L, SECOND, new long[] {0}, 1)).isInstanceOf(PravahaException.class);
        assertThat(state.liveSlices()).as("nothing was evicted to make room").isEqualTo(2);
        assertThat(state.fire(10 * SECOND)).hasSize(2);
    }

    @Test
    void slicesAreReleasedOnceNoWindowCanNeedThemAgain() {
        // The other half of bounding state: the ceiling bounds it at an instant, this bounds it over
        // time. Until it is called, every slice ever created is still held.
        SlicedAggregateState state =
                state(WindowSpec.hopping(60 * SECOND, 10 * SECOND), 1_000, SlicedAggregateState.Kind.COUNT);

        for (long eventTime = 0; eventTime < 120 * SECOND; eventTime += 5 * SECOND) {
            state.update(1L, eventTime, new long[] {0}, 1);
        }
        int before = state.liveSlices();
        assertThat(before).isEqualTo(12);

        int released = state.discardSlicesEndingBefore(100 * SECOND, 0);

        assertThat(released).isPositive();
        assertThat(state.liveSlices()).isEqualTo(before - released);
        // What remains must still be enough to fire the windows that have not completed.
        assertThat(state.fire(120 * SECOND)).isNotEmpty();
    }

    @Test
    void allowedLatenessKeepsSlicesAliveForCorrections() {
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 1_000, SlicedAggregateState.Kind.COUNT);
        state.update(1L, SECOND, new long[] {0}, 1);

        assertThat(state.discardSlicesEndingBefore(15 * SECOND, 30 * SECOND))
                .as("still within the lateness allowance, so the window can be corrected")
                .isZero();
        assertThat(state.discardSlicesEndingBefore(45 * SECOND, 30 * SECOND)).isEqualTo(1);
    }
}
