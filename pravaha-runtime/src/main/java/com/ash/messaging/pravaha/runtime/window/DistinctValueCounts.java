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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.runtime.state.VariableKeyStateMap;

/**
 * {@code COUNT(DISTINCT x)}'s state, off-heap: how many times each value is currently present, per
 * group, per slice, per distinct column (ADR-044).
 *
 * <p>This was an on-heap {@code HashMap<Object, Long>} per accumulator, and so the one shape of
 * windowed state that could not spill: an aggregate containing it was refused outright when the
 * overflow tier was configured. The set does not need to be a set. Flattened, it is one entry per
 * {@code (group, slice, column, value)} with a count -- a key of variable width and an eight-byte
 * value, which is exactly what {@link VariableKeyStateMap} holds, in a {@link
 * com.ash.messaging.pravaha.state.RowStore} that takes an overflow tier like every other one.
 *
 * <p>The key is {@code keyHigh}, {@code keyLow}, {@code sliceStart} (eight bytes each), the column
 * (four), then the value's {@linkplain TaggedValues#identityBytes identity bytes}. The value is the
 * count.
 *
 * <p><strong>Counted, not flagged</strong>, as before: a value seen three times and retracted once is
 * still present. A count that reaches zero removes the entry, and a retraction of a value that is not
 * present changes nothing -- both exactly what the on-heap map's {@code merge} then {@code remove} did,
 * which is what {@code DistinctValueCountsPropertyTest} holds this class to.
 */
final class DistinctValueCounts implements AutoCloseable {

    private static final int INDEX_INITIAL_CAPACITY = 64;
    private static final int OFFSET_KEY_LOW = Long.BYTES;
    private static final int OFFSET_SLICE = 2 * Long.BYTES;
    private static final int OFFSET_COLUMN = 3 * Long.BYTES;
    private static final int OFFSET_VALUE = 3 * Long.BYTES + Integer.BYTES;

    private final MemoryAccess access;
    private final MemoryAccess overflowAccess;
    private final int maxOverflowSlabs;
    private final int ramMaxSlabs;

    private VariableKeyStateMap map;
    private MemoryRegion keyScratch;

    DistinctValueCounts(MemoryAccess access, int ramMaxSlabs, MemoryAccess overflowAccess, int maxOverflowSlabs) {
        this.access = access;
        this.overflowAccess = overflowAccess;
        this.maxOverflowSlabs = maxOverflowSlabs;
        this.ramMaxSlabs = ramMaxSlabs;
        this.keyScratch = access.allocate(256);
        this.map = newMap();
    }

    private VariableKeyStateMap newMap() {
        return new VariableKeyStateMap(
                access,
                INDEX_INITIAL_CAPACITY,
                OffHeapAccumulators.STORE_SLAB_BYTES,
                ramMaxSlabs,
                overflowAccess,
                maxOverflowSlabs);
    }

    /** Writes a key into the scratch region, growing it for a long string, and returns its length. */
    private int writeKey(long keyHigh, long keyLow, long sliceStart, int column, byte[] identity) {
        int length = OFFSET_VALUE + identity.length;
        if (keyScratch.capacity() < length) {
            keyScratch.close();
            keyScratch = access.allocate(Math.max(length, keyScratch.capacity() * 2));
        }
        keyScratch.putLong(0, keyHigh);
        keyScratch.putLong(OFFSET_KEY_LOW, keyLow);
        keyScratch.putLong(OFFSET_SLICE, sliceStart);
        keyScratch.putInt(OFFSET_COLUMN, column);
        keyScratch.putBytes(OFFSET_VALUE, identity, 0, identity.length);
        return length;
    }

    /**
     * Adds {@code weight} occurrences of a value.
     *
     * @return {@code +1} if the value became present, {@code -1} if its last occurrence went, {@code
     *     0} if its presence did not change -- which is what keeps a slice's own distinct count exact
     *     without counting the entries again
     */
    int add(long keyHigh, long keyLow, long sliceStart, int column, Object value, long weight) {
        if (weight == 0) {
            return 0;
        }
        int length = writeKey(keyHigh, keyLow, sliceStart, column, TaggedValues.identityBytes(value));
        if (weight > 0) {
            long handle = map.getOrCreate(keyScratch, 0, length, Long.BYTES);
            long before = countOf(handle);
            setCount(handle, before + weight);
            return before == 0 ? 1 : 0;
        }
        long handle = map.find(keyScratch, 0, length);
        if (handle == ArenaHandle.NULL) {
            // A retraction of a value that is not present. The on-heap map merged it in as a
            // negative count and removed it at once; nothing is held either way.
            return 0;
        }
        long remaining = countOf(handle) + weight;
        if (remaining <= 0) {
            map.remove(keyScratch, 0, length);
            return -1;
        }
        setCount(handle, remaining);
        return 0;
    }

    /** Whether the value {@code handle} holds, for the same group and column, is present in {@code otherSlice}. */
    boolean presentIn(long handle, long otherSlice) {
        int length = copyKeyToScratch(handle);
        keyScratch.putLong(OFFSET_SLICE, otherSlice);
        return map.find(keyScratch, 0, length) != ArenaHandle.NULL;
    }

    /**
     * Copies an entry's own key into the scratch region, returning its length.
     *
     * <p>Through a byte array rather than {@link MemoryRegion#copyFrom}: the entry may sit in a mapped
     * overflow slab, and the scratch region's own {@code copyFrom} accepts only a source of its own
     * concrete type.
     */
    private int copyKeyToScratch(long handle) {
        int length = map.keyLengthOf(handle);
        if (keyScratch.capacity() < length) {
            keyScratch.close();
            keyScratch = access.allocate(Math.max(length, keyScratch.capacity() * 2));
        }
        byte[] key = new byte[length];
        map.keyRegionOf(handle).getBytes(map.keyOffsetOf(handle), key, 0, length);
        keyScratch.putBytes(0, key, 0, length);
        return length;
    }

    long keyHighOf(long handle) {
        return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle));
    }

    long keyLowOf(long handle) {
        return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle) + OFFSET_KEY_LOW);
    }

    long sliceStartOf(long handle) {
        return map.keyRegionOf(handle).getLong(map.keyOffsetOf(handle) + OFFSET_SLICE);
    }

    int columnOf(long handle) {
        return map.keyRegionOf(handle).getInt(map.keyOffsetOf(handle) + OFFSET_COLUMN);
    }

    Object valueOf(long handle) {
        int length = map.keyLengthOf(handle) - OFFSET_VALUE;
        byte[] identity = new byte[length];
        map.keyRegionOf(handle).getBytes(map.keyOffsetOf(handle) + OFFSET_VALUE, identity, 0, length);
        return TaggedValues.fromIdentityBytes(identity);
    }

    long countOf(long handle) {
        return map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle));
    }

    private void setCount(long handle, long count) {
        map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), count);
    }

    /** Every live entry's handle, taken before anything is changed. */
    List<Long> handles() {
        List<Long> handles = new ArrayList<>(map.size());
        map.forEach(handles::add);
        return handles;
    }

    /** Removes every entry whose slice {@code dead} accepts. */
    void removeSlices(LongPredicate dead) {
        for (long handle : handles()) {
            if (!dead.test(sliceStartOf(handle))) {
                continue;
            }
            int length = copyKeyToScratch(handle);
            map.remove(keyScratch, 0, length);
        }
    }

    /** Every entry, tagged the way the rest of a windowed checkpoint is. */
    void writeTo(DataOutput out) throws IOException {
        List<Long> handles = handles();
        out.writeInt(handles.size());
        for (long handle : handles) {
            out.writeLong(keyHighOf(handle));
            out.writeLong(keyLowOf(handle));
            out.writeLong(sliceStartOf(handle));
            out.writeInt(columnOf(handle));
            TaggedValues.writeTagged(out, valueOf(handle));
            out.writeLong(countOf(handle));
        }
    }

    /** Replaces whatever is held with what {@link #writeTo} wrote. */
    void readFrom(DataInput in, int columns) throws IOException {
        clear();
        int entries = in.readInt();
        for (int i = 0; i < entries; i++) {
            long keyHigh = in.readLong();
            long keyLow = in.readLong();
            long sliceStart = in.readLong();
            int column = in.readInt();
            Object value = TaggedValues.readTagged(in);
            long count = in.readLong();
            if (column < 0 || column >= columns || count <= 0) {
                throw new IOException("a distinct-value entry names column " + column + " with count " + count
                        + ", which this aggregate could not have written");
            }
            add(keyHigh, keyLow, sliceStart, column, value, count);
        }
    }

    int size() {
        return map.size();
    }

    VariableKeyStateMap map() {
        return map;
    }

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
