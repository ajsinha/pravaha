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
import com.ash.messaging.pravaha.runtime.AggregateTotals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SUMWRAP-1 at the windowed accumulator: a slice's total, and a window's total over its slices,
 * are refused by name past the 64-bit range rather than wrapped; and WINDECKEY-1's key encoding
 * keeps a decimal whole.
 */
class SlicedAggregateOverflowTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aSliceTotalPastTheRangeIsRefusedNotWrapped() {
        SlicedAggregateState state = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                        new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.SUM},
                        100)
                .describedAs(new String[] {"SUM(amount)"});
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE}, 1);
        state.update(1L, 31L, new Object[] {1L}, 2 * SECOND, new long[] {1}, 1);

        // Refused when the batch settles (TRANSOVF-1), and never readable before it does.
        assertThatThrownBy(state::settle)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(amount)");
    }

    @Test
    void anUnsettledTotalCannotBeFired() {
        SlicedAggregateState state = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                        new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.SUM},
                        100)
                .describedAs(new String[] {"SUM(amount)"});
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE}, 1);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE}, 1);

        assertThatThrownBy(() -> state.fire(10 * SECOND))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(amount)");
    }

    @Test
    void aTotalThatLeavesTheRangeInsideABatchAndComesBackIsAccepted() {
        SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.SUM, SlicedAggregateState.Kind.AVG},
                100);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {5, 5}, 1);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, 1);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, 1);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, -1);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, -1);
        state.settle();

        List<SlicedAggregateState.WindowResult> results = state.fire(10 * SECOND);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).values()).containsExactly(5L, 5L);
    }

    @Test
    void aRetractionSubtractsAndIsCheckedToo() {
        SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.SUM},
                100);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE}, 1);
        state.settle();

        // Withdrawing a -1 adds one: past the top of the range.
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {-1}, -1);
        assertThatThrownBy(state::settle)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM (aggregate 0)");
    }

    @Test
    void aWindowOfSlicesThatNetInsideTheRangeIsAccepted() {
        // Slices of MAX, 1 and -1: the running combination passes 2^63, the window's total does not.
        SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.hopping(30 * SECOND, 10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.SUM},
                100);
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {Long.MAX_VALUE}, 1);
        state.update(1L, 31L, new Object[] {1L}, 11 * SECOND, new long[] {1}, 1);
        state.update(1L, 31L, new Object[] {1L}, 21 * SECOND, new long[] {-1}, 1);
        state.settle();

        assertThat(state.fire(30 * SECOND).get(0).values()).containsExactly(Long.MAX_VALUE);
    }

    @Test
    void theWideArithmeticIsExact() {
        long[] totals = {0};
        long[] excess = {0};
        java.math.BigInteger expected = java.math.BigInteger.ZERO;
        java.util.Random random = new java.util.Random(7);
        long[] edges = {Long.MAX_VALUE, Long.MIN_VALUE, -1, 1, 0, Long.MAX_VALUE / 3, Long.MIN_VALUE / 7};
        for (int step = 0; step < 20_000; step++) {
            long value = random.nextBoolean() ? edges[random.nextInt(edges.length)] : random.nextLong();
            // Weights up to 2^31, so fifty steps stay far inside 128 bits.
            long weight = random.nextInt(4) == 0
                    ? random.nextLong() >> (32 + random.nextInt(32))
                    : (random.nextBoolean() ? 1 : -1);
            AggregateTotals.addWeighted(totals, excess, 0, value, weight);
            expected = expected.add(java.math.BigInteger.valueOf(value).multiply(java.math.BigInteger.valueOf(weight)));
            java.math.BigInteger actual =
                    java.math.BigInteger.valueOf(excess[0]).shiftLeft(64).add(java.math.BigInteger.valueOf(totals[0]));
            assertThat(actual).as("step %d", step).isEqualTo(expected);
            assertThat(excess[0] == 0).isEqualTo(expected.bitLength() < 64);
            if (random.nextInt(50) == 0) {
                totals[0] = 0;
                excess[0] = 0;
                expected = java.math.BigInteger.ZERO;
            }
        }
    }

    @Test
    void aWindowTotalPastTheRangeIsRefusedWhenItsSlicesAreCombined() {
        // Each ten-second slice fits; the twenty-second window holding both does not.
        SlicedAggregateState state = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.hopping(20 * SECOND, 10 * SECOND)),
                        new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.SUM},
                        100)
                .describedAs(new String[] {"SUM(amount)"});
        long half = Long.MAX_VALUE / 2 + 1;
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {half}, 1);
        state.update(1L, 31L, new Object[] {1L}, 11 * SECOND, new long[] {half}, 1);

        assertThat(state.fire(10 * SECOND)).hasSize(1);
        assertThatThrownBy(() -> state.fire(20 * SECOND))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(amount)");
    }

    @Test
    void theCheckedArithmeticIsExactInsideTheRange() {
        assertThat(AggregateTotals.addWeighted(Long.MAX_VALUE - 5, 5, 1)).isEqualTo(Long.MAX_VALUE);
        assertThat(AggregateTotals.addWeighted(Long.MIN_VALUE + 5, 5, -1)).isEqualTo(Long.MIN_VALUE);
        assertThat(AggregateTotals.add(-3, 3)).isZero();
        assertThatThrownBy(() -> AggregateTotals.addWeighted(0, Long.MIN_VALUE, -1))
                .isInstanceOf(ArithmeticException.class);
        assertThat(AggregateTotals.overflow("COUNT(*)", new ArithmeticException("long overflow")))
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("COUNT(*)")
                .hasMessageContaining("wrapped");
    }

    @Test
    void aDecimalKeyKeepsBothHalvesThroughEveryEncoding() {
        // 1.50 and 2.75 at scale 2 have the same high half -- zero -- and different low halves.
        DecimalBits a = new DecimalBits(0L, 150L);
        DecimalBits b = new DecimalBits(0L, 275L);
        DecimalBits wide = new DecimalBits(-2L, 0x0123456789ABCDEFL);

        assertThat(TaggedValues.fromIdentityBytes(TaggedValues.identityBytes(a)))
                .isEqualTo(a);
        assertThat(TaggedValues.identityBytes(a)).isNotEqualTo(TaggedValues.identityBytes(b));
        assertThat(TaggedValues.fromIdentityBytes(TaggedValues.identityBytes(wide)))
                .isEqualTo(wide);
        assertThat(TaggedValues.decodeKeyValues(TaggedValues.encodeKeyValues(new Object[] {a, "x", wide})))
                .containsExactly(a, "x", wide);

        SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT},
                100);
        state.update(1L, 31L, new Object[] {a}, SECOND, new long[] {0}, 1);
        state.update(1L, 31L, new Object[] {b}, SECOND, new long[] {0}, 1);
        List<SlicedAggregateState.WindowResult> results = state.fire(10 * SECOND);
        // Same digest, different key columns: two groups, because the columns are the identity.
        assertThat(results).hasSize(2);
        assertThat(results.stream()
                        .map(r -> java.util.Objects.requireNonNull(r.keyValues())[0])
                        .toList())
                .containsExactlyInAnyOrder(a, b);
    }
}
