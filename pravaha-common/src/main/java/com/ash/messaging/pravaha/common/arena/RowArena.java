/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.common.arena;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * A bump-pointer allocator over a small number of large off-heap slabs.
 *
 * <p>The point is the reclaim, not the allocation. A batch is processed and the whole arena rewound
 * in one move, so there is no per-row free, no free list, no fragmentation and no GC involvement --
 * the cost of releasing five hundred rows is a single assignment (design section 8.5).
 *
 * <p>Owned by exactly one lane. Not thread-safe, and deliberately so: the single-writer principle is
 * what removes locking from the hot path (design section 13.1). Sharing an arena across threads is a bug,
 * not a tuning decision.
 *
 * <p>Slabs are retained across resets rather than freed, because the steady state is a lane
 * allocating and rewinding the same few megabytes forever. Growth is therefore one-way within a
 * lane's lifetime and bounded by {@code maxSlabs}.
 */
public final class RowArena implements AutoCloseable {

    /** Default slab size. Large enough that a batch rarely spans slabs, small enough to not waste. */
    public static final int DEFAULT_SLAB_BYTES = 4 * 1024 * 1024;

    private final MemoryAccess access;
    private final int slabBytes;
    private final int maxSlabs;
    private final List<MemoryRegion> slabs = new ArrayList<>();

    private int currentSlab;
    private int cursor;
    private long highWaterMark;
    private boolean closed;

    public RowArena(MemoryAccess access, int slabBytes, int maxSlabs) {
        if (slabBytes <= 0) {
            throw new IllegalArgumentException("slab size must be positive, got " + slabBytes);
        }
        if (maxSlabs <= 0) {
            throw new IllegalArgumentException("maxSlabs must be positive, got " + maxSlabs);
        }
        this.access = access;
        this.slabBytes = slabBytes;
        this.maxSlabs = maxSlabs;
        slabs.add(access.allocate(slabBytes));
    }

    /** An arena with default sizing, capped at {@code maxBytes} total. */
    public static RowArena withCapacity(MemoryAccess access, long maxBytes) {
        int slabs = (int) Math.max(1, Math.ceilDiv(maxBytes, DEFAULT_SLAB_BYTES));
        return new RowArena(access, DEFAULT_SLAB_BYTES, slabs);
    }

    /**
     * Reserves {@code bytes}, growing by one slab if the current one cannot hold it.
     *
     * @return a handle, or {@link ArenaHandle#NULL} when the arena is at its slab limit. Returning
     *     a sentinel rather than throwing is deliberate: exhaustion is a backpressure signal the
     *     lane handles (design section 13.5), not an exceptional condition.
     */
    public long allocate(int bytes) {
        checkOpen();
        if (bytes <= 0) {
            throw new IllegalArgumentException("allocation size must be positive, got " + bytes);
        }
        if (bytes > slabBytes) {
            throw new IllegalArgumentException("row of " + bytes + " bytes exceeds the slab size of " + slabBytes
                    + "; raise arena.slab.size for this query");
        }
        // Rows start 8-byte aligned so their header fields never straddle a word.
        int aligned = (cursor + 7) & ~7;
        if (aligned + bytes > slabBytes) {
            if (!advanceSlab()) {
                return ArenaHandle.NULL;
            }
            aligned = 0;
        }
        cursor = aligned + bytes;
        long used = (long) currentSlab * slabBytes + cursor;
        if (used > highWaterMark) {
            highWaterMark = used;
        }
        return ArenaHandle.of(currentSlab, aligned);
    }

    private boolean advanceSlab() {
        if (currentSlab + 1 >= maxSlabs) {
            return false;
        }
        currentSlab++;
        if (currentSlab == slabs.size()) {
            slabs.add(access.allocate(slabBytes));
        }
        cursor = 0;
        return true;
    }

    /**
     * Gives back the tail of the most recent allocation.
     *
     * <p>Rows with variable-width fields are sized conservatively and then trimmed to what was
     * actually written, which is why this exists rather than requiring the size up front.
     */
    public void trimTo(long handle, int actualBytes) {
        checkOpen();
        if (ArenaHandle.slab(handle) != currentSlab) {
            return; // not the most recent allocation; nothing safe to reclaim
        }
        int offset = ArenaHandle.offset(handle);
        if (offset + actualBytes <= cursor) {
            cursor = offset + actualBytes;
        }
    }

    /** The slab a handle points into. */
    public MemoryRegion regionOf(long handle) {
        return slabs.get(ArenaHandle.slab(handle));
    }

    /** The byte offset within that slab. */
    public int offsetOf(long handle) {
        return ArenaHandle.offset(handle);
    }

    /** The current allocation position, for {@link #resetTo(long)}. */
    public long mark() {
        return ArenaHandle.of(currentSlab, cursor);
    }

    /**
     * Rewinds to a mark. O(1) regardless of how many rows were allocated since.
     *
     * <p>Memory is not cleared: the writer zeroes a row's header and null bitmap when it begins one,
     * which is what makes reuse safe without paying to wipe megabytes between batches.
     */
    public void resetTo(long mark) {
        checkOpen();
        currentSlab = ArenaHandle.slab(mark);
        cursor = ArenaHandle.offset(mark);
    }

    /** Rewinds the whole arena. The usual end-of-batch move. */
    public void reset() {
        checkOpen();
        currentSlab = 0;
        cursor = 0;
    }

    public long bytesInUse() {
        return (long) currentSlab * slabBytes + cursor;
    }

    /** Peak usage since construction. The number capacity planning actually needs. */
    public long highWaterMark() {
        return highWaterMark;
    }

    public long bytesAllocated() {
        return (long) slabs.size() * slabBytes;
    }

    public int slabCount() {
        return slabs.size();
    }

    public int slabBytes() {
        return slabBytes;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("arena is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            slabs.forEach(MemoryRegion::close);
            slabs.clear();
        }
    }

    @Override
    public String toString() {
        return "RowArena[" + slabs.size() + " slabs x " + slabBytes + "B, in use " + bytesInUse() + "B, peak "
                + highWaterMark + "B" + (closed ? ", closed" : "") + "]";
    }
}
