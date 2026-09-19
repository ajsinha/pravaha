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

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.runtime.state.VariableKeyStateMap;

/**
 * Off-heap storage for every accumulator a {@link SlicedAggregateState} holds.
 *
 * <p>Indexed by {@link VariableKeyStateMap}, keyed by the same 24 fixed bytes -- {@code keyHigh},
 * {@code keyLow}, {@code sliceStart} -- the aggregate has always identified a slice by. The value is
 * one {@link com.ash.messaging.pravaha.state.RowStore} block per accumulator: a fixed header ({@code
 * count}, then {@code values[]}, then {@code nonNull[]}, all eight-byte longs) followed by {@code
 * keyValues} encoded exactly as {@link TaggedValues#writeKeyValues} writes it to a checkpoint -- reused
 * rather than re-invented, and safe to reuse because a group's {@code keyValues} are fixed at creation
 * and never rewritten, so the block never needs to grow.
 *
 * <p>{@code COUNT(DISTINCT)} columns keep their per-slice distinct count in the {@code values[]}
 * slot like any other column; the values themselves live beside this, in {@link DistinctValueCounts}.
 */
final class OffHeapAccumulators implements AutoCloseable {

    private static final int INDEX_INITIAL_CAPACITY = 64;
    static final int STORE_SLAB_BYTES = 1 << 16;
    private static final int KEY_BYTES = 3 * Long.BYTES;

    /**
     * A deliberately generous per-accumulator estimate -- fixed header plus a modest {@code
     * keyValues} encoding -- used only to size the RAM tier from {@code maxSlices}, never to bound
     * anything: a bigger accumulator than this simply fits fewer per slab, and a query genuinely
     * holding more accumulators than {@code maxSlices} was ever sized for is exactly what the
     * overflow tier, once it is reached, exists to keep running through.
     */
    private static final int ESTIMATED_BYTES_PER_ENTRY = 256;

    private final int columns;
    private final int fixedHeaderBytes;
    private final MemoryAccess access;
    private final MemoryAccess overflowAccess;
    private final int maxOverflowSlabs;
    private final int ramMaxSlabs;
    private final MemoryRegion keyScratch;

    private VariableKeyStateMap map;

    /**
     * @param ramMaxSlabs the RAM tier's own slab ceiling, in {@link #STORE_SLAB_BYTES}-sized slabs --
     *     derived from {@code maxSlices} by {@link #ramSlabsFor}, not a separately configured number,
     *     so that a small {@code maxSlices} does not carry a RAM budget sized for a large one
     */
    OffHeapAccumulators(
            int columns, MemoryAccess access, int ramMaxSlabs, MemoryAccess overflowAccess, int maxOverflowSlabs) {
        this.columns = columns;
        this.fixedHeaderBytes = Long.BYTES + 2 * Long.BYTES * columns;
        this.access = access;
        this.overflowAccess = overflowAccess;
        this.maxOverflowSlabs = maxOverflowSlabs;
        this.ramMaxSlabs = ramMaxSlabs;
        this.keyScratch = access.allocate(KEY_BYTES);
        this.map = newMap();
    }

    /** How many RAM slabs comfortably hold {@code maxSlices} entries at the estimate above -- at
     * least one, however small {@code maxSlices} is. */
    static int ramSlabsFor(int maxSlices) {
        long estimatedBytes = (long) maxSlices * ESTIMATED_BYTES_PER_ENTRY;
        return (int) Math.max(1, (estimatedBytes + STORE_SLAB_BYTES - 1) / STORE_SLAB_BYTES);
    }

    private VariableKeyStateMap newMap() {
        return new VariableKeyStateMap(
                access, INDEX_INITIAL_CAPACITY, STORE_SLAB_BYTES, ramMaxSlabs, overflowAccess, maxOverflowSlabs);
    }

    private void writeKey(long keyHigh, long keyLow, long sliceStart) {
        keyScratch.putLong(0, keyHigh);
        keyScratch.putLong(Long.BYTES, keyLow);
        keyScratch.putLong(2 * Long.BYTES, sliceStart);
    }

    long find(long keyHigh, long keyLow, long sliceStart) {
        writeKey(keyHigh, keyLow, sliceStart);
        return map.find(keyScratch, 0, KEY_BYTES);
    }

    /** Creates a new, zeroed accumulator (the store zeroes a fresh block's value bytes) with {@code
     * keyValues} written into its variable tail. */
    long create(long keyHigh, long keyLow, long sliceStart, Object[] keyValues) {
        writeKey(keyHigh, keyLow, sliceStart);
        byte[] encodedKeyValues = TaggedValues.encodeKeyValues(keyValues);
        long handle = map.getOrCreate(keyScratch, 0, KEY_BYTES, fixedHeaderBytes + encodedKeyValues.length);
        map.valueRegionOf(handle)
                .putBytes(map.valueOffsetOf(handle) + fixedHeaderBytes, encodedKeyValues, 0, encodedKeyValues.length);
        return handle;
    }

    Object[] keyValuesOf(long handle) {
        MemoryRegion region = map.valueRegionOf(handle);
        int base = map.valueOffsetOf(handle);
        int length = map.valueLengthOf(handle) - fixedHeaderBytes;
        byte[] encoded = new byte[length];
        region.getBytes(base + fixedHeaderBytes, encoded, 0, length);
        return TaggedValues.decodeKeyValues(encoded);
    }

    long count(long handle) {
        return map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle));
    }

    void setCount(long handle, long value) {
        map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), value);
    }

    long value(long handle, int column) {
        return map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * column);
    }

    void setValue(long handle, int column, long value) {
        map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * column, value);
    }

    long nonNull(long handle, int column) {
        return map.valueRegionOf(handle)
                .getLong(map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * columns + Long.BYTES * column);
    }

    void setNonNull(long handle, int column, long value) {
        map.valueRegionOf(handle)
                .putLong(map.valueOffsetOf(handle) + Long.BYTES + Long.BYTES * columns + Long.BYTES * column, value);
    }

    long keyHighOf(long handle) {
        return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle));
    }

    long keyLowOf(long handle) {
        return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle) + Long.BYTES);
    }

    long sliceStartOf(long handle) {
        return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle) + 2 * Long.BYTES);
    }

    /** A temporary, on-heap copy of one accumulator, for merging and for the checkpoint writer. */
    SliceAccumulator read(long handle) {
        SliceAccumulator accumulator = new SliceAccumulator(columns);
        accumulator.count = count(handle);
        for (int i = 0; i < columns; i++) {
            accumulator.values[i] = value(handle, i);
            accumulator.nonNull[i] = nonNull(handle, i);
        }
        accumulator.keyValues = keyValuesOf(handle);
        return accumulator;
    }

    void remove(long keyHigh, long keyLow, long sliceStart) {
        writeKey(keyHigh, keyLow, sliceStart);
        map.remove(keyScratch, 0, KEY_BYTES);
    }

    void forEach(java.util.function.LongConsumer visitor) {
        map.forEach(visitor::accept);
    }

    int size() {
        return map.size();
    }

    VariableKeyStateMap map() {
        return map;
    }

    /** Discards every accumulator, for a restore that replaces rather than merges. */
    void clear() {
        map.close();
        map = newMap();
    }

    @Override
    public void close() {
        map.close();
        keyScratch.close();
    }
}
