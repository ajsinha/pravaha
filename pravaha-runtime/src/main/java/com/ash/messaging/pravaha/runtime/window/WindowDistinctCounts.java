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

import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.runtime.state.VariableKeyStateMap;

/**
 * One window's {@code COUNT(DISTINCT)} answers while it fires, per group and column, off-heap
 * (SPILL-3).
 *
 * <p>A window's distinct count is not a sum of its slices' counts, so it is counted from the values
 * themselves before the groups are emitted: each value once, in the earliest of the window's slices
 * that holds it. Those counts used to land in an on-heap map entry per group, which is half of why
 * firing a large window needed a heap the size of the window. Here they land in a {@link
 * VariableKeyStateMap} keyed by the group -- digest, then key columns -- with one eight-byte count
 * per aggregate column, in the same RAM and overflow tiers as the state itself, and released when
 * the window has fired.
 *
 * <p>Both stores' keys begin with the sixteen-byte digest and carry the group's columns at an offset
 * of their own, so a key is built here from either by copying those two runs, allocation-free.
 */
final class WindowDistinctCounts implements AutoCloseable {

    private static final int DIGEST_BYTES = 2 * Long.BYTES;

    private final int columns;
    private final MemoryAccess access;
    private final VariableKeyStateMap counts;
    private MemoryRegion scratch;

    WindowDistinctCounts(
            int columns,
            MemoryAccess access,
            int ramMaxSlabs,
            @Nullable MemoryAccess overflowAccess,
            int maxOverflowSlabs) {
        this.columns = columns;
        this.access = access;
        this.counts = new VariableKeyStateMap(
                access, 64, OffHeapAccumulators.STORE_SLAB_BYTES, ramMaxSlabs, overflowAccess, maxOverflowSlabs);
        this.scratch = access.allocate(256);
    }

    /** Builds this map's key from another store's entry key and returns its length. */
    private int key(MemoryRegion region, int keyOffset, int groupOffset, int groupLength) {
        int length = DIGEST_BYTES + groupLength;
        if (scratch.capacity() < length) {
            scratch.close();
            scratch = access.allocate(Math.max(length, scratch.capacity() * 2));
        }
        OffHeapAccumulators.copyBytes(region, keyOffset, scratch, 0, DIGEST_BYTES);
        OffHeapAccumulators.copyBytes(region, keyOffset + groupOffset, scratch, DIGEST_BYTES, groupLength);
        return length;
    }

    /** Counts one more distinct value for the group whose key is at {@code keyOffset}. */
    void add(MemoryRegion region, int keyOffset, int groupOffset, int groupLength, int column) {
        int length = key(region, keyOffset, groupOffset, groupLength);
        long handle = counts.getOrCreate(scratch, 0, length, Long.BYTES * columns);
        MemoryRegion values = counts.valueRegionOf(handle);
        int at = counts.valueOffsetOf(handle) + Long.BYTES * column;
        values.putLong(at, values.getLong(at) + 1);
    }

    /** The group's distinct count in one column; zero for a group none of whose values is here. */
    long countOf(MemoryRegion region, int keyOffset, int groupOffset, int groupLength, int column) {
        int length = key(region, keyOffset, groupOffset, groupLength);
        long handle = counts.find(scratch, 0, length);
        return handle == ArenaHandle.NULL
                ? 0
                : counts.valueRegionOf(handle).getLong(counts.valueOffsetOf(handle) + Long.BYTES * column);
    }

    @Override
    public void close() {
        counts.close();
        scratch.close();
    }
}
