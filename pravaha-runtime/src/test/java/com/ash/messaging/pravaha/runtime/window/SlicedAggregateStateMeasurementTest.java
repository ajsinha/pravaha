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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-037's own instruction, applied the way {@code VariableKeyStateMapTest} already applies it to
 * a different structure: measure the off-heap bytes for real, and estimate the on-heap object graph
 * it replaces honestly rather than pick a number that flatters the change.
 *
 * <p>The off-heap number here is measured directly ({@link SlicedAggregateState#offHeapBytesAllocated()}
 * divided by live accumulators). The on-heap number cannot be measured the same way without an
 * instrumentation agent this test suite does not have, so it is computed from HotSpot's own
 * documented object layout with compressed references (16-byte object header, 4-byte compressed
 * references, 8-byte alignment) -- the same class of estimate {@code VariableKeyStateMapTest} makes
 * for {@code java.util.HashMap<String, long[2]>}, and named as an estimate for the same reason.
 */
class SlicedAggregateStateMeasurementTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void offHeapBytesPerAccumulatorVersusTheOnHeapObjectGraphItReplaces() {
        SlicedAggregateState.Kind[] kinds = {SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM};
        int accumulators = 20_000;
        double offHeapBytesPerEntry;
        try (SlicedAggregateState state =
                new SlicedAggregateState(new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), kinds, 1_000_000)) {
            for (long key = 0; key < accumulators; key++) {
                state.update(key, key * 31, new Object[] {key}, SECOND, new long[] {0, key}, 1);
            }
            assertThat(state.liveSlices()).isEqualTo(accumulators);
            offHeapBytesPerEntry = (double) state.offHeapBytesAllocated() / accumulators;
        }

        double onHeapEstimate = estimatedOnHeapBytesPerAccumulator(kinds.length, 1);

        System.out.println("SlicedAggregateState B2 measurement -- " + accumulators + " accumulators, " + kinds.length
                + " aggregate columns, 1 group-key column:");
        System.out.printf("  off-heap (measured):     %.1f bytes/entry%n", offHeapBytesPerEntry);
        System.out.printf("  on-heap (estimated):     %.1f bytes/entry%n", onHeapEstimate);
        System.out.printf("  off-heap / on-heap ratio: %.2fx%n", offHeapBytesPerEntry / onHeapEstimate);

        assertThat(offHeapBytesPerEntry).isPositive();
    }

    /**
     * HotSpot with compressed oops: a 16-byte object header, 4-byte references, {@code long}/{@code
     * double} fields at 8 bytes, every object size rounded up to 8. Sums the whole per-accumulator
     * object graph {@code SlicedAggregateState}'s on-heap path allocates: the {@code HashMap.Node},
     * the {@code SliceKey} record it holds, the {@code Accumulator} it points to, that
     * accumulator's two {@code long[]} arrays, its {@code Object[] keyValues}, and one boxed {@code
     * Long} per key column -- everything a group with no {@code COUNT DISTINCT} column still costs
     * on the path this replaces.
     */
    private static long estimatedOnHeapBytesPerAccumulator(int columns, int keyColumns) {
        long hashMapNode = 32; // hash(4) + key ref(4) + value ref(4) + next ref(4) + header(16)
        long sliceKeyRecord = round8(16 + 3L * 8); // header + keyHigh, keyLow, sliceStart
        long accumulatorShell = round8(16 + 4L * 4 + 8); // header + 4 refs (values/nonNull/distinct/keyValues) + count
        long valuesArray = arrayBytes(columns, 8);
        long nonNullArray = arrayBytes(columns, 8);
        long keyValuesArray = arrayBytes(keyColumns, 4); // Object[] of compressed references
        long boxedKeyValues = keyColumns * round8(16 + 8); // one boxed Long per key column, worst case
        long hashMapTableSlotAmortized = 8; // ~1.3 refs/entry at the default load factor, rounded

        return hashMapNode
                + sliceKeyRecord
                + accumulatorShell
                + valuesArray
                + nonNullArray
                + keyValuesArray
                + boxedKeyValues
                + hashMapTableSlotAmortized;
    }

    private static long arrayBytes(int length, int elementBytes) {
        return round8(16 + 4L + (long) length * elementBytes);
    }

    private static long round8(long bytes) {
        return (bytes + 7) & ~7L;
    }
}
