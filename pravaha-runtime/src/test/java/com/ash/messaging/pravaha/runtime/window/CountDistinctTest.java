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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code COUNT(DISTINCT x)} inside a window.
 *
 * <p>Distinct is the aggregate that misleads. It looks like a counter and behaves like a set, so its
 * state grows with cardinality rather than staying constant -- which is why it is only legal inside
 * a window, and why it is a separate kind rather than a flag on {@code COUNT}.
 *
 * <p>Two properties are easy to get wrong and produce plausible numbers either way. A distinct count
 * does not add across slices: a value appearing in two slices of the same window is one distinct
 * value, not two. And a retraction has to remove a value only when the last occurrence of it goes --
 * a value seen three times and retracted once is still present.
 */
class CountDistinctTest {

    private static final long SECOND = 1_000_000_000L;

    private static SlicedAggregateState state(WindowSpec spec) {
        return new SlicedAggregateState(
                new SlicedWindows(spec),
                new SlicedAggregateState.Kind[] {
                    SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.COUNT_DISTINCT
                },
                1_000);
    }

    private static void feed(SlicedAggregateState state, long key, long eventTime, long value, long weight) {
        state.update(key, key * 31, new Object[] {key}, eventTime, new long[] {0, value}, weight);
    }

    @Test
    void countsEachValueOnceHoweverOftenItAppears() {
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND));

        feed(state, 1, SECOND, 100, 1);
        feed(state, 1, 2 * SECOND, 100, 1);
        feed(state, 1, 3 * SECOND, 200, 1);

        List<SlicedAggregateState.WindowResult> results = state.fire(10 * SECOND);

        assertThat(results.get(0).values()[0]).as("three rows").isEqualTo(3);
        assertThat(results.get(0).values()[1]).as("two distinct values").isEqualTo(2);
    }

    @Test
    void aValueInTwoSlicesOfOneWindowIsStillOneDistinctValue() {
        // The property that makes distinct different from every other aggregate here: the others
        // add across slices and this one cannot, because addition would count the same value twice.
        SlicedAggregateState state = state(WindowSpec.hopping(30 * SECOND, 10 * SECOND));

        feed(state, 1, 5 * SECOND, 100, 1); // slice [0,10)
        feed(state, 1, 15 * SECOND, 100, 1); // slice [10,20)
        feed(state, 1, 25 * SECOND, 200, 1); // slice [20,30)

        List<SlicedAggregateState.WindowResult> results = state.fire(30 * SECOND);

        assertThat(results.get(0).values()[0])
                .as("three rows across three slices")
                .isEqualTo(3);
        assertThat(results.get(0).values()[1])
                .as("value 100 appears in two slices and is one distinct value")
                .isEqualTo(2);
    }

    @Test
    void aRetractionRemovesAValueOnlyWhenItsLastOccurrenceGoes() {
        // Counted rather than flagged. A set would say the value had gone after the first
        // retraction, and the count would be wrong from then on with nothing to indicate it.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND));

        feed(state, 1, SECOND, 100, 1);
        feed(state, 1, 2 * SECOND, 100, 1);
        feed(state, 1, 3 * SECOND, 200, 1);
        assertThat(state.fire(10 * SECOND).get(0).values()[1]).isEqualTo(2);

        feed(state, 1, SECOND, 100, -1);
        assertThat(state.fire(10 * SECOND).get(0).values()[1])
                .as("one of two occurrences of 100 retracted: it is still present")
                .isEqualTo(2);

        feed(state, 1, 2 * SECOND, 100, -1);
        assertThat(state.fire(10 * SECOND).get(0).values()[1])
                .as("the last occurrence retracted: now it is gone")
                .isEqualTo(1);
    }

    @Test
    void distinctIsPerGroupNotPerWindow() {
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND));

        feed(state, 1, SECOND, 100, 1);
        feed(state, 2, SECOND, 100, 1);

        List<SlicedAggregateState.WindowResult> results = state.fire(10 * SECOND);

        assertThat(results).hasSize(2);
        assertThat(results)
                .allSatisfy(result -> assertThat(result.values()[1])
                        .as("the same value in two groups is one distinct value in each")
                        .isEqualTo(1));
    }
}
