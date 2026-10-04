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

        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {0, 100}, 1);
        state.update(1L, 1L * 31, new Object[] {1L}, 2 * SECOND, new long[] {0, 200}, 1);
        state.update(2L, 2L * 31, new Object[] {2L}, 3 * SECOND, new long[] {0, 50}, 1);

        List<SlicedAggregateState.WindowResult> results = state.fire(10 * SECOND);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).keyValues()[0])
                .as("results carry the group's own value; the digest is an implementation detail")
                .isEqualTo(1L);
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

        state.update(1L, 1L * 31, new Object[] {1L}, 5 * SECOND, new long[] {0}, 1);

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
            state.update(key, key * 31, new Object[] {key}, eventTime, new long[] {0, value}, 1);
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
                        // Identified by the key value the accumulator carries, not by a digest of
                        // it: the digest is an implementation detail and a test that reaches for it
                        // is testing the hash rather than the aggregate.
                        .filter(result -> ((Long) result.keyValues()[0]) == finalKey)
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

        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {0, 100}, 1);
        state.update(1L, 1L * 31, new Object[] {1L}, 2 * SECOND, new long[] {0, 200}, 1);
        assertThat(state.fire(10 * SECOND).get(0).values()).containsExactly(2, 300);

        state.update(1L, 1L * 31, new Object[] {1L}, 2 * SECOND, new long[] {0, 200}, -1);
        assertThat(state.fire(10 * SECOND).get(0).values())
                .as("the retraction undoes exactly what the insert did")
                .containsExactly(1, 100);
    }

    @Test
    void aGroupOfOnlyNullsIsNullForSumAvgMinAndMaxAcrossSlicesAndACheckpoint() throws Exception {
        // ALLNULLAGG-1: the non-null count each accumulator already keeps says the answer is NULL,
        // across a hop's slices, a retraction back to nothing but nulls, and a checkpoint.
        SlicedAggregateState.Kind[] kinds = {
            SlicedAggregateState.Kind.COUNT,
            SlicedAggregateState.Kind.SUM,
            SlicedAggregateState.Kind.AVG,
            SlicedAggregateState.Kind.MIN,
            SlicedAggregateState.Kind.MAX
        };
        SlicedAggregateState state = state(WindowSpec.hopping(20 * SECOND, 10 * SECOND), 100, kinds);
        boolean[] onlyNulls = {false, false, false, false, false};
        state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {0, 0, 0, 0, 0}, onlyNulls, 1);
        state.update(1L, 31L, new Object[] {1L}, 12 * SECOND, new long[] {0, 0, 0, 0, 0}, onlyNulls, 1);
        SlicedAggregateState.WindowResult fired = state.fire(20 * SECOND).get(0);
        assertThat(fired.count()).isEqualTo(2);
        assertThat(fired.nulls()).containsExactly(false, true, true, true, true);

        SlicedAggregateState sums = state(
                WindowSpec.tumbling(10 * SECOND),
                100,
                SlicedAggregateState.Kind.COUNT,
                SlicedAggregateState.Kind.SUM,
                SlicedAggregateState.Kind.AVG);
        boolean[] present = {true, true, true};
        boolean[] absent = {false, false, false};
        sums.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {0, 7, 7}, present, 1);
        sums.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {0, 0, 0}, absent, 1);
        assertThat(sums.fire(10 * SECOND).get(0).nulls()).containsExactly(false, false, false);
        sums.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {0, 7, 7}, present, -1);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        sums.writeTo(new java.io.DataOutputStream(bytes));
        SlicedAggregateState restored = state(
                WindowSpec.tumbling(10 * SECOND),
                100,
                SlicedAggregateState.Kind.COUNT,
                SlicedAggregateState.Kind.SUM,
                SlicedAggregateState.Kind.AVG);
        restored.readFrom(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())));
        SlicedAggregateState.WindowResult afterRetraction =
                restored.fire(10 * SECOND).get(0);
        assertThat(afterRetraction.count()).isEqualTo(1);
        assertThat(afterRetraction.nulls())
                .as("the value retracted, a null row left: SUM and AVG are NULL again, COUNT(*) is 1")
                .containsExactly(false, true, true);
    }

    @Test
    void aKeyWhoseWeightsCancelProducesNoResult() {
        // An empty group must not be reported as a present one with zeroes: a consumer cannot tell
        // "no rows" from "rows summing to nothing", and for a COUNT the difference is the answer.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.COUNT);

        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {0}, 1);
        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {0}, -1);

        assertThat(state.fire(10 * SECOND)).isEmpty();
    }

    @Test
    void minAndMaxCombineAcrossSlices() {
        SlicedAggregateState state = state(
                WindowSpec.hopping(30 * SECOND, 10 * SECOND),
                100,
                SlicedAggregateState.Kind.MIN,
                SlicedAggregateState.Kind.MAX);

        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {50, 50}, 1);
        state.update(1L, 1L * 31, new Object[] {1L}, 12 * SECOND, new long[] {10, 10}, 1);
        state.update(1L, 1L * 31, new Object[] {1L}, 22 * SECOND, new long[] {90, 90}, 1);

        assertThat(state.fire(30 * SECOND).get(0).values()).containsExactly(10, 90);
    }

    @Test
    void retractingFromAMinOrMaxIsRefusedRatherThanApproximated() {
        // Knowing the current extreme does not tell you the previous one. Returning a stale extreme
        // would be a wrong answer that looks exactly like a right one.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.MAX);
        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {50}, 1);

        assertThatThrownBy(() -> state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {50}, -1))
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
            state.update(key, key * 31, new Object[] {key}, SECOND, new long[] {0}, 1);
        }

        assertThatThrownBy(() -> state.update(99L, 99L * 31, new Object[] {99L}, SECOND, new long[] {0}, 1))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("key 99")
                .hasMessageContaining("key space is unbounded");
    }

    @Test
    void aStateCeilingRefusesRatherThanEvicting() {
        // Eviction would make the answer wrong instead of making the query fail, and a wrong answer
        // nobody is told about is worse than a stopped query.
        SlicedAggregateState state = state(WindowSpec.tumbling(10 * SECOND), 2, SlicedAggregateState.Kind.COUNT);
        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {0}, 1);
        state.update(2L, 2L * 31, new Object[] {2L}, SECOND, new long[] {0}, 1);

        assertThatThrownBy(() -> state.update(3L, 3L * 31, new Object[] {3L}, SECOND, new long[] {0}, 1))
                .isInstanceOf(PravahaException.class);
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
            state.update(1L, 1L * 31, new Object[] {1L}, eventTime, new long[] {0}, 1);
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
        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {0}, 1);

        assertThat(state.discardSlicesEndingBefore(15 * SECOND, 30 * SECOND))
                .as("still within the lateness allowance, so the window can be corrected")
                .isZero();
        assertThat(state.discardSlicesEndingBefore(45 * SECOND, 30 * SECOND)).isEqualTo(1);
    }

    @Test
    void theFoldedKeyIsNotAnIdentityAndTwoDistinctGroupsCanShareOne() {
        // W8-14. WindowResult.key() folds 128 bits of digest into 64: keyHigh ^ (keyLow * C). That
        // is a bucket, not an identity, and the collision is not hypothetical arithmetic -- it is
        // one line to construct, because XOR is its own inverse:
        //
        //     (H, 0)      -> H ^ (0 * C) = H
        //     (H ^ C, 1)  -> (H ^ C) ^ (1 * C) = H
        //
        // No birthday search, no 2^32 anything. Two distinct 128-bit group digests, one 64-bit key.
        //
        // WindowedAggregate used this as the key of its `emitted` map -- what each window last
        // published, so a correction can retract it exactly. A collision there does not merge sums:
        // it makes one group's retraction suppress another's, so the wrong row is withdrawn and a
        // stale one stands in the view for ever. That map is keyed by the group's own values now.
        // This test stays because the fold is still what it always was, and the next person to reach
        // for it as an identity should find this written down.
        long c = 0x9E3779B97F4A7C15L;
        long high = 0x0123456789ABCDEFL;

        SlicedAggregateState.WindowResult first =
                new SlicedAggregateState.WindowResult(high, 0L, new Object[] {"ann"}, 0L, 1_000L, new long[] {1L}, 1L);
        SlicedAggregateState.WindowResult second = new SlicedAggregateState.WindowResult(
                high ^ c, 1L, new Object[] {"bob"}, 0L, 1_000L, new long[] {2L}, 1L);

        assertThat(first.key())
                .as("two different groups, one folded key -- constructed, not searched for")
                .isEqualTo(second.key());
        assertThat(first.keyValues())
                .as("and they are plainly different groups, which is the whole point")
                .isNotEqualTo(second.keyValues());
    }

    @Test
    void twoGroupsSharingOneDigestKeepTheirOwnSums() {
        // W8-14's constructed collision: the state is handed its digest, so two groups can be given
        // the same one. Keyed by the digest alone they were one accumulator and both groups
        // reported 300.
        SlicedAggregateState state = state(
                WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM);

        state.update(7L, 7L, new Object[] {"ann"}, SECOND, new long[] {0, 100}, 1);
        state.update(7L, 7L, new Object[] {"bob"}, 2 * SECOND, new long[] {0, 200}, 1);

        assertThat(state.liveSlices())
                .as("two groups, one digest, two accumulators")
                .isEqualTo(2);
        java.util.Map<Object, List<Long>> byGroup = new java.util.TreeMap<>();
        for (SlicedAggregateState.WindowResult result : state.fire(10 * SECOND)) {
            byGroup.put(result.keyValues()[0], List.of(result.values()[0], result.values()[1]));
        }
        assertThat(byGroup)
                .containsExactly(
                        java.util.Map.entry("ann", List.of(1L, 100L)), java.util.Map.entry("bob", List.of(1L, 200L)));
    }

    @Test
    void aVersionTwoCheckpointIsRefusedByName() throws Exception {
        // Version 2 named a distinct value's group by digest alone, so it cannot say which of two
        // groups sharing a digest a value belonged to.
        SlicedAggregateState state =
                state(WindowSpec.tumbling(10 * SECOND), 100, SlicedAggregateState.Kind.COUNT_DISTINCT);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(bytes).writeInt(2);

        assertThatThrownBy(() -> state.readFrom(
                        new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("format version 2")
                .hasMessageContaining("W8-14");
    }

    @Test
    void firingAWindowHoldsNoResultItHasAlreadyHandedOn() throws Exception {
        // SPILL-3. fire() built the whole window on the heap and returned it, so a large window
        // needed a heap the size of the state and died there whether the state had spilled or not.
        // Streaming, a result is unreachable from the state once the consumer lets it go: this
        // keeps only a weak reference to the first result and asks for a collection half way
        // through. Built first and handed on afterwards, the first result is still in the list.
        SlicedAggregateState state = state(
                WindowSpec.hopping(30 * SECOND, 10 * SECOND),
                100_000,
                SlicedAggregateState.Kind.COUNT,
                SlicedAggregateState.Kind.COUNT_DISTINCT,
                SlicedAggregateState.Kind.SUM);
        int groups = 20_000;
        for (long g = 0; g < groups; g++) {
            for (int slice = 0; slice < 3; slice++) {
                state.update(
                        g,
                        g * 31,
                        new Object[] {g},
                        slice * 10L * SECOND + SECOND,
                        new long[] {0, g % 7, 5},
                        new boolean[] {true, true, true},
                        new Object[] {null, "v" + (g + slice) % 5, null},
                        1);
            }
        }

        java.lang.ref.WeakReference<?>[] first = {null};
        boolean[] collectedWhileFiring = {false};
        long[] seen = {0};
        long fired = state.fire(30 * SECOND, result -> {
            assertThat(result.count()).isEqualTo(3);
            assertThat(result.values()[1]).as("three values, one per slice").isEqualTo(3);
            assertThat(result.values()[2]).isEqualTo(15);
            if (seen[0] == 0) {
                first[0] = new java.lang.ref.WeakReference<>(result);
            } else if (seen[0] == groups / 2) {
                for (int attempt = 0; attempt < 20 && first[0].get() != null; attempt++) {
                    System.gc();
                }
                collectedWhileFiring[0] = first[0].get() == null;
            }
            seen[0]++;
        });

        assertThat(fired).isEqualTo(groups);
        assertThat(collectedWhileFiring[0])
                .as("the first result is collectable while the window is still firing: nothing holds the window")
                .isTrue();
    }
}
