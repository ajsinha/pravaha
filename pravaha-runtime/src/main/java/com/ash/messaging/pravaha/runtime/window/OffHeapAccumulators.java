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

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.runtime.state.VariableKeyStateMap;

/**
 * Off-heap storage for every accumulator a {@link SlicedAggregateState} holds.
 *
 * <p>Indexed by {@link VariableKeyStateMap}, keyed by {@code keyHigh}, {@code keyLow}, {@code
 * sliceStart} and then <strong>the group's own key columns</strong>, written by {@link
 * TaggedValues#writeGroupIdentity}. The digest alone was the key until W8-14, and a digest is not an
 * identity: two groups whose 128 bits collided became one accumulator and one merged answer, with
 * nothing anywhere to say so. The map compares the whole key byte for byte against the arena, so two
 * groups sharing a digest are two entries, and the digest still leads the key so that a probe which
 * meets a different group almost always fails on its first eight bytes. The value is one {@link
 * com.ash.messaging.pravaha.state.RowStore} block per accumulator: {@code count}, then {@code
 * values[]}, then {@code nonNull[]}, all eight-byte longs. The group's values are not repeated there;
 * they are read back from the key.
 *
 * <p>{@code COUNT(DISTINCT)} columns keep their per-slice distinct count in the {@code values[]}
 * slot like any other column; the values themselves live beside this, in {@link DistinctValueCounts}.
 */
final class OffHeapAccumulators implements AutoCloseable {

    private static final int INDEX_INITIAL_CAPACITY = 64;
    static final int STORE_SLAB_BYTES = 1 << 16;
    /** Where the group's key columns start in an entry's key, after the digest and the slice. */
    static final int GROUP_OFFSET = 3 * Long.BYTES;

    private static final int SLICE_OFFSET = 2 * Long.BYTES;

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
    private final @Nullable MemoryAccess overflowAccess;
    private final int maxOverflowSlabs;
    private final int ramMaxSlabs;
    private MemoryRegion keyScratch;

    private VariableKeyStateMap map;

    /**
     * @param ramMaxSlabs the RAM tier's own slab ceiling, in {@link #STORE_SLAB_BYTES}-sized slabs --
     *     derived from {@code maxSlices} by {@link #ramSlabsFor}, not a separately configured number,
     *     so that a small {@code maxSlices} does not carry a RAM budget sized for a large one
     */
    OffHeapAccumulators(
            int columns,
            MemoryAccess access,
            int ramMaxSlabs,
            @Nullable MemoryAccess overflowAccess,
            int maxOverflowSlabs) {
        this.columns = columns;
        this.fixedHeaderBytes = Long.BYTES + 2 * Long.BYTES * columns;
        this.access = access;
        this.overflowAccess = overflowAccess;
        this.maxOverflowSlabs = maxOverflowSlabs;
        this.ramMaxSlabs = ramMaxSlabs;
        this.keyScratch = access.allocate(256);
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

    /** Writes a full key into the scratch region, growing it for a long key, and returns its length. */
    private int writeKey(long keyHigh, long keyLow, long sliceStart, Object @Nullable [] keyValues) {
        int length = GROUP_OFFSET + TaggedValues.groupIdentityLength(keyValues);
        if (keyScratch.capacity() < length) {
            keyScratch.close();
            keyScratch = access.allocate(Math.max(length, keyScratch.capacity() * 2));
        }
        keyScratch.putLong(0, keyHigh);
        keyScratch.putLong(Long.BYTES, keyLow);
        keyScratch.putLong(2 * Long.BYTES, sliceStart);
        TaggedValues.writeGroupIdentity(keyScratch, GROUP_OFFSET, keyValues);
        return length;
    }

    long find(long keyHigh, long keyLow, long sliceStart, Object @Nullable [] keyValues) {
        int length = writeKey(keyHigh, keyLow, sliceStart, keyValues);
        return map.find(keyScratch, 0, length);
    }

    /** Creates a new, zeroed accumulator (the store zeroes a fresh block's value bytes). */
    long create(long keyHigh, long keyLow, long sliceStart, Object @Nullable [] keyValues) {
        int length = writeKey(keyHigh, keyLow, sliceStart, keyValues);
        return map.getOrCreate(keyScratch, 0, length, fixedHeaderBytes);
    }

    /**
     * The same group's accumulator in another slice, or {@link
     * com.ash.messaging.pravaha.common.arena.ArenaHandle#NULL}: the entry's own key, copied with its
     * slice replaced, looked up without allocating. Firing a window combines a group's slices this way
     * instead of gathering every accumulator into an on-heap map first (SPILL-3).
     */
    long findInSlice(long handle, long otherSlice) {
        int length = map.keyLengthOf(handle);
        if (keyScratch.capacity() < length) {
            keyScratch.close();
            keyScratch = access.allocate(Math.max(length, keyScratch.capacity() * 2));
        }
        copyBytes(map.keyRegionOf(handle), map.keyOffsetOf(handle), keyScratch, 0, length);
        keyScratch.putLong(SLICE_OFFSET, otherSlice);
        return map.find(keyScratch, 0, length);
    }

    /**
     * Copies bytes between two regions of any kinds, eight at a time where it can.
     *
     * <p>Not {@link MemoryRegion#copyFrom}, which accepts only a source of its own concrete type: an
     * entry may sit in a mapped overflow slab while the scratch region is RAM.
     */
    static void copyBytes(MemoryRegion from, int fromOffset, MemoryRegion to, int toOffset, int length) {
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            to.putLong(toOffset + i, from.getLong(fromOffset + i));
        }
        for (; i < length; i++) {
            to.putByte(toOffset + i, from.getByte(fromOffset + i));
        }
    }

    /** The region holding an entry's key, the offset it starts at and its length -- for building another store's key from it. */
    MemoryRegion keyRegionOf(long handle) {
        return map.keyRegionOf(handle);
    }

    int keyOffsetOf(long handle) {
        return map.keyOffsetOf(handle);
    }

    int groupLengthOf(long handle) {
        return map.keyLengthOf(handle) - GROUP_OFFSET;
    }

    Object @Nullable [] keyValuesOf(long handle) {
        return TaggedValues.readGroupIdentity(map.keyRegionOf(handle), map.keyOffsetOf(handle) + GROUP_OFFSET);
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

    /**
     * Removes an accumulator. The entry's own key is handed to the map as the key to remove: the map
     * hashes and compares it before it releases the block, so nothing is read after it has gone.
     */
    void remove(long handle) {
        map.remove(map.keyRegionOf(handle), map.keyOffsetOf(handle), map.keyLengthOf(handle));
    }

    void forEach(java.util.function.LongConsumer visitor) {
        map.forEach(visitor::accept);
    }

    /** {@link #forEach}, in the order accumulators lie in the store: one pass over it (SPILL-4). */
    void forEachInStoreOrder(java.util.function.LongConsumer visitor) {
        map.forEachInStoreOrder(visitor::accept);
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
