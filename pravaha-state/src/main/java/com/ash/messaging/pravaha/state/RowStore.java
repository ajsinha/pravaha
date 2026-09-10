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
package com.ash.messaging.pravaha.state;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * Off-heap storage for blocks that are freed individually.
 *
 * <p>{@code RowArena} deliberately has no free: a lane allocates for one batch and drops the whole
 * thing at the end, which is why it costs a pointer bump and involves no book-keeping. State is the
 * opposite shape. A join holds rows across batches and releases them one at a time as retractions
 * arrive or a window expires, and a bump pointer cannot express that -- the arena would grow to the
 * high-water mark of everything the join ever held.
 *
 * <p>So: slabs, bump-allocated, with a free list per size class. A released block goes back to its
 * class and is handed out again for the next request that fits, which is what stops a long-running
 * join from growing without bound while its row count is flat. Size classes are powers of two,
 * which wastes up to half a block on a bad fit and in exchange makes reuse exact -- a released block
 * is always usable by any later request in the same class, with no splitting, no coalescing and no
 * search.
 *
 * <p><strong>Not thread-safe, on purpose.</strong> One store belongs to one lane, like everything
 * else on the row path. The single-writer rule is what makes the free list a plain field rather than
 * a compare-and-swap loop.
 *
 * <p>Handles are {@link ArenaHandle} values and mean nothing outside the store that issued them. A
 * handle to a released block is dangling in the same way a freed pointer is; the store detects the
 * obvious case -- releasing the same block twice -- because that one corrupts the free list into a
 * cycle and would otherwise show up much later as a block handed out to two owners at once.
 */
public final class RowStore implements AutoCloseable {

    /** Smallest block. Below this the header dominates and the class table gains nothing. */
    public static final int MIN_BLOCK_BYTES = 64;

    /** Bytes reserved before the payload: the size class, then the free-list link. */
    private static final int HEADER_BYTES = 16;

    private static final int OFFSET_CLASS = 0;
    private static final int OFFSET_STATE = 4;
    private static final int OFFSET_LINK = 8;

    private static final int STATE_LIVE = 0x4C495645;
    private static final int STATE_FREE = 0x46524545;

    private final MemoryAccess access;
    private final int slabBytes;
    private final int maxSlabs;
    private final List<MemoryRegion> slabs = new ArrayList<>();
    private final long[] freeHeads;

    private int currentSlab = -1;
    private int cursor;
    private long bytesLive;
    private long bytesFree;
    private long liveBlocks;
    private long reuses;
    private boolean closed;

    /**
     * @param slabBytes size of each slab; the largest single block is this minus the header
     * @param maxSlabs the ceiling. A store that hits it throws rather than growing, because a join
     *     whose state is unbounded should fail where the cause is visible rather than take the node
     *     down with it later
     */
    public RowStore(MemoryAccess access, int slabBytes, int maxSlabs) {
        if (Integer.bitCount(slabBytes) != 1 || slabBytes < MIN_BLOCK_BYTES) {
            throw new IllegalArgumentException(
                    "slab size must be a power of two of at least " + MIN_BLOCK_BYTES + ", got " + slabBytes);
        }
        if (maxSlabs < 1) {
            throw new IllegalArgumentException("a store needs at least one slab, got " + maxSlabs);
        }
        this.access = access;
        this.slabBytes = slabBytes;
        this.maxSlabs = maxSlabs;
        this.freeHeads = new long[classCount(slabBytes)];
        java.util.Arrays.fill(this.freeHeads, ArenaHandle.NULL);
    }

    /**
     * Reserves a block with at least {@code payloadBytes} of payload.
     *
     * @return a handle whose payload starts at {@link #offsetOf(long)}
     * @throws PravahaException when the store is at its slab limit, which is a bounded-state
     *     violation rather than a transient condition -- unlike the row arena, where exhaustion is a
     *     backpressure signal and a sentinel is the right answer
     */
    public long allocate(int payloadBytes) {
        checkOpen();
        if (payloadBytes <= 0) {
            throw new IllegalArgumentException("block size must be positive, got " + payloadBytes);
        }
        int sizeClass = classOf(payloadBytes + HEADER_BYTES);
        if (sizeClass >= freeHeads.length) {
            throw new PravahaException(
                    StateErrors.STATE_TOO_LARGE,
                    "a single row of " + payloadBytes + " bytes does not fit a " + slabBytes
                            + "-byte slab; raise state.slab.size for this query");
        }

        long recycled = freeHeads[sizeClass];
        if (recycled != ArenaHandle.NULL) {
            MemoryRegion region = slabs.get(ArenaHandle.slab(recycled));
            int base = ArenaHandle.offset(recycled);
            freeHeads[sizeClass] = region.getLong(base + OFFSET_LINK);
            region.putInt(base + OFFSET_STATE, STATE_LIVE);
            bytesFree -= blockBytes(sizeClass);
            bytesLive += blockBytes(sizeClass);
            liveBlocks++;
            reuses++;
            return recycled;
        }
        return carveFresh(sizeClass);
    }

    private long carveFresh(int sizeClass) {
        int bytes = blockBytes(sizeClass);
        if (currentSlab < 0 || cursor + bytes > slabBytes) {
            if (!advanceSlab()) {
                throw new PravahaException(
                        StateErrors.STATE_TOO_LARGE,
                        "state store is full at " + (long) maxSlabs * slabBytes + " bytes across " + maxSlabs
                                + " slabs, with " + liveBlocks + " live blocks. A join or aggregate is holding "
                                + "rows it will never match again; bound it with a window, a TTL or a tighter "
                                + "key range.");
            }
        }
        int offset = cursor;
        cursor += bytes;
        long handle = ArenaHandle.of(currentSlab, offset);
        MemoryRegion region = slabs.get(currentSlab);
        region.putInt(offset + OFFSET_CLASS, sizeClass);
        region.putInt(offset + OFFSET_STATE, STATE_LIVE);
        bytesLive += bytes;
        liveBlocks++;
        return handle;
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
     * Gives a block back.
     *
     * <p>The double-release check is not defensive politeness. Releasing twice links a block into
     * the free list ahead of itself, the list becomes a cycle, and the store then hands the same
     * block to two callers -- which surfaces as one join's rows appearing in another's output, a
     * very long way from the line that caused it.
     */
    public void release(long handle) {
        checkOpen();
        MemoryRegion region = slabs.get(ArenaHandle.slab(handle));
        int base = ArenaHandle.offset(handle);
        if (region.getInt(base + OFFSET_STATE) != STATE_LIVE) {
            throw new IllegalStateException("block " + ArenaHandle.slab(handle) + ":" + base
                    + " was released twice, or was never " + "allocated by this store");
        }
        int sizeClass = region.getInt(base + OFFSET_CLASS);
        region.putInt(base + OFFSET_STATE, STATE_FREE);
        region.putLong(base + OFFSET_LINK, freeHeads[sizeClass]);
        freeHeads[sizeClass] = handle;
        bytesLive -= blockBytes(sizeClass);
        bytesFree += blockBytes(sizeClass);
        liveBlocks--;
    }

    /** The slab a handle points into. */
    public MemoryRegion regionOf(long handle) {
        return slabs.get(ArenaHandle.slab(handle));
    }

    /** Where the payload starts, past the store's own header. */
    public int offsetOf(long handle) {
        return ArenaHandle.offset(handle) + HEADER_BYTES;
    }

    /** How much payload the block actually has, which is at least what was asked for. */
    public int capacityOf(long handle) {
        MemoryRegion region = slabs.get(ArenaHandle.slab(handle));
        return blockBytes(region.getInt(ArenaHandle.offset(handle) + OFFSET_CLASS)) - HEADER_BYTES;
    }

    /** Bytes in live blocks, including their headers and the slack their size class wastes. */
    public long bytesLive() {
        return bytesLive;
    }

    /** Bytes sitting on free lists, available without touching a new slab. */
    public long bytesFree() {
        return bytesFree;
    }

    /** Bytes taken from the operating system. */
    public long bytesReserved() {
        return (long) slabs.size() * slabBytes;
    }

    public long liveBlocks() {
        return liveBlocks;
    }

    /** How many allocations were served from a free list. Zero here means nothing is being reused. */
    public long reuses() {
        return reuses;
    }

    public int slabCount() {
        return slabs.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        slabs.forEach(MemoryRegion::close);
        slabs.clear();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("row store is closed");
        }
    }

    private static int blockBytes(int sizeClass) {
        return MIN_BLOCK_BYTES << sizeClass;
    }

    /** The smallest class whose block holds {@code bytes}. */
    private static int classOf(int bytes) {
        if (bytes <= MIN_BLOCK_BYTES) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(bytes - 1) - Integer.numberOfTrailingZeros(MIN_BLOCK_BYTES);
    }

    private static int classCount(int slabBytes) {
        return classOf(slabBytes) + 1;
    }

    @Override
    public String toString() {
        return "RowStore[live=" + bytesLive + "B in " + liveBlocks + " blocks, free=" + bytesFree + "B, reserved="
                + bytesReserved() + "B, reuses=" + reuses + "]";
    }
}
