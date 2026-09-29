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
import java.util.Arrays;
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
 * <p><strong>Compaction (ADR-044).</strong> Reuse keeps a flat row count from growing the store, but it
 * never gives a slab back: a churning query whose live state shrank still holds every slab it ever
 * carved, and in the overflow tier every one of those is a file. {@link #compactOverflow} moves the live
 * blocks out of sparse overflow slabs and releases those slabs -- their files truncated and deleted.
 * A moved block has a new handle, so compaction is driven by the owner of every handle into this
 * store, on the thread that owns it, between batches: the store says which handles moved, and the
 * owner writes the new ones back where it keeps them. See {@link #compactOverflow} for the contract.
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

    /**
     * The fragmentation at which a spilled store is worth compacting, when nothing else is said:
     * half of what the overflow tier has carved is free.
     */
    public static final double DEFAULT_COMPACTION_THRESHOLD = 0.5;

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
    private final MemoryAccess overflowAccess;
    private final int maxOverflowSlabs;

    /**
     * Every slab, by index. RAM slabs are indices {@code 0 .. maxSlabs - 1}, always contiguous; an
     * overflow slab's index is at or past {@code maxSlabs}, and a released one leaves a {@code null}
     * behind that the next overflow slab reuses -- which is what keeps every other slab's handles
     * meaning what they meant.
     */
    private final List<MemoryRegion> slabs = new ArrayList<>();

    private final long[] freeHeads;

    /** Per slab: how far it has been carved. Blocks lie back to back from zero to here. */
    private int[] carvedBytes = new int[8];

    /** Per slab: bytes in live blocks. What compaction reads to find the sparse ones. */
    private int[] liveBytesBySlab = new int[8];

    /** Released overflow slab indices, reused before a new index is appended. */
    private int[] releasedSlots = new int[8];

    private int releasedSlotCount;

    /** Non-null only while {@link #compactOverflow} runs: which slabs are being emptied. */
    private boolean[] evacuating;

    /** Set once a relocation cannot be placed, so the rest of that pass leaves blocks where they are. */
    private boolean relocationStalled;

    private int currentSlab = -1;
    private int cursor;
    private long bytesLive;
    private long bytesFree;
    private long liveBlocks;
    private long reuses;
    private int slabsHeld;
    private int overflowSlabsUsed;
    private int overflowSlabsLive;
    private long overflowLiveBytes;
    private long overflowCarvedBytes;
    private long releasesSinceCompaction;
    private long compactions;
    private long slabsReleased;
    private long blocksRelocated;
    private boolean closed;

    /**
     * @param slabBytes size of each slab; the largest single block is this minus the header
     * @param maxSlabs the ceiling. A store that hits it throws rather than growing, because a join
     *     whose state is unbounded should fail where the cause is visible rather than take the node
     *     down with it later
     */
    public RowStore(MemoryAccess access, int slabBytes, int maxSlabs) {
        this(access, slabBytes, maxSlabs, null, 0);
    }

    /**
     * ADR-037 item B2: a query whose state outgrows {@code maxSlabs} keeps running, slower, once
     * this is given a second tier to carve slabs from -- instead of being refused.
     *
     * <p>The tiers are not distinguished past this constructor. Every accessor below --
     * {@link #allocate}, {@link #release}, {@link #regionOf} -- addresses a slab by its index in one
     * list regardless of which {@code MemoryAccess} carved it, which is what lets a caller ({@code
     * JoinSide}, {@code VariableKeyStateMap}) read and write overflowed state exactly as it does
     * in-memory state, including through a checkpoint: {@code writeTo} walks live entries by handle
     * and asks each one's region for its bytes, never asking which tier the region came from, so
     * spilled rows are checkpointed as part of the same snapshot as everything else -- never a
     * second durable thing beside it.
     *
     * @param overflowAccess where slabs beyond {@code maxSlabs} are carved from, or {@code null} for
     *     no overflow tier -- today's behaviour, unchanged
     * @param maxOverflowSlabs the ceiling on {@code overflowAccess} slabs held at once, ignored when
     *     {@code overflowAccess} is {@code null}. Reaching {@code maxSlabs + maxOverflowSlabs} still
     *     throws {@link StateErrors#STATE_TOO_LARGE}: a second tier moves the ceiling, and does not
     *     remove it -- eviction is not an option for a Z-set (a retraction whose insert was evicted
     *     can never be withdrawn), so a bound has to exist somewhere and be enforced loudly when
     *     reached. A slab {@link #compactOverflow} released no longer counts against it.
     */
    public RowStore(
            MemoryAccess access, int slabBytes, int maxSlabs, MemoryAccess overflowAccess, int maxOverflowSlabs) {
        if (Integer.bitCount(slabBytes) != 1 || slabBytes < MIN_BLOCK_BYTES) {
            throw new IllegalArgumentException(
                    "slab size must be a power of two of at least " + MIN_BLOCK_BYTES + ", got " + slabBytes);
        }
        if (maxSlabs < 1) {
            throw new IllegalArgumentException("a store needs at least one slab, got " + maxSlabs);
        }
        if (overflowAccess != null && maxOverflowSlabs < 1) {
            throw new IllegalArgumentException(
                    "an overflow tier needs at least one overflow slab, got " + maxOverflowSlabs);
        }
        this.access = access;
        this.slabBytes = slabBytes;
        this.maxSlabs = maxSlabs;
        this.overflowAccess = overflowAccess;
        this.maxOverflowSlabs = overflowAccess == null ? 0 : maxOverflowSlabs;
        this.freeHeads = new long[classCount(slabBytes)];
        Arrays.fill(this.freeHeads, ArenaHandle.NULL);
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
                    // PF-3/DOCX-20: this used to send the operator to a `state.slab.size` setting
                    // that has never existed. Advice naming a key nothing reads costs an edit, a
                    // restart and the same failure, with nothing to search for. The slab size here
                    // is fixed per operator, so the honest remedy is the row.
                    "a single row of " + payloadBytes + " bytes does not fit a " + slabBytes
                            + "-byte state slab. The slab size is fixed per operator and is not a "
                            + "configuration key: the row has to be narrower -- fewer or smaller "
                            + "columns in the key or in what is accumulated against it.");
        }
        return allocateInClass(sizeClass, true);
    }

    private long allocateInClass(int sizeClass, boolean countReuse) {
        long recycled = freeHeads[sizeClass];
        if (recycled != ArenaHandle.NULL) {
            int slab = ArenaHandle.slab(recycled);
            MemoryRegion region = slabs.get(slab);
            int base = ArenaHandle.offset(recycled);
            freeHeads[sizeClass] = region.getLong(base + OFFSET_LINK);
            region.putInt(base + OFFSET_STATE, STATE_LIVE);
            bytesFree -= blockBytes(sizeClass);
            addLive(slab, blockBytes(sizeClass));
            if (countReuse) {
                reuses++;
            }
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
                        "state store is full at " + (long) (maxSlabs + maxOverflowSlabs) * slabBytes + " bytes across "
                                + maxSlabs
                                + (maxOverflowSlabs > 0
                                        ? (" in-memory slabs and " + maxOverflowSlabs + " overflow slabs ("
                                                + overflowSlabsLive + " of which are in use)")
                                        : " slabs")
                                + ", with " + liveBlocks + " live blocks. A join or aggregate is holding "
                                + "rows it will never match again; bound it with a window, a TTL or a tighter "
                                + "key range."
                                + (maxOverflowSlabs == 0
                                        ? " This store has no overflow tier configured; ADR-037 item B2 is what adds one."
                                        : ""));
            }
        }
        int offset = cursor;
        cursor += bytes;
        carvedBytes[currentSlab] = cursor;
        if (currentSlab >= maxSlabs) {
            overflowCarvedBytes += bytes;
        }
        long handle = ArenaHandle.of(currentSlab, offset);
        MemoryRegion region = slabs.get(currentSlab);
        region.putInt(offset + OFFSET_CLASS, sizeClass);
        region.putInt(offset + OFFSET_STATE, STATE_LIVE);
        addLive(currentSlab, bytes);
        return handle;
    }

    /**
     * Moves carving to a fresh slab: the next RAM slab while there is one, then an overflow slab --
     * into the lowest index {@link #compactOverflow} released, if any, or a new one.
     *
     * <p>The overflow tier may refuse ({@code StateErrors#SPILL_QUOTA_REACHED}, {@code
     * StateErrors#SPILL_DISK_FULL}); that happens before anything here changes, so a refused carve
     * leaves the store exactly as it was.
     */
    private boolean advanceSlab() {
        int next;
        if (slabs.size() < maxSlabs) {
            MemoryRegion region = access.allocate(slabBytes);
            next = slabs.size();
            slabs.add(region);
        } else {
            if (overflowAccess == null || overflowSlabsLive >= maxOverflowSlabs) {
                return false;
            }
            MemoryRegion region = overflowAccess.allocate(slabBytes);
            if (releasedSlotCount > 0) {
                next = releasedSlots[--releasedSlotCount];
                slabs.set(next, region);
            } else {
                next = slabs.size();
                slabs.add(region);
            }
            overflowSlabsUsed++;
            overflowSlabsLive++;
        }
        ensureSlabCapacity(next + 1);
        carvedBytes[next] = 0;
        liveBytesBySlab[next] = 0;
        slabsHeld++;
        currentSlab = next;
        cursor = 0;
        return true;
    }

    private void ensureSlabCapacity(int slabCount) {
        if (carvedBytes.length < slabCount) {
            int length = Math.max(slabCount, carvedBytes.length * 2);
            carvedBytes = Arrays.copyOf(carvedBytes, length);
            liveBytesBySlab = Arrays.copyOf(liveBytesBySlab, length);
        }
    }

    private void addLive(int slab, int bytes) {
        liveBytesBySlab[slab] += bytes;
        bytesLive += bytes;
        liveBlocks++;
        if (slab >= maxSlabs) {
            overflowLiveBytes += bytes;
        }
    }

    private void subtractLive(int slab, int bytes) {
        liveBytesBySlab[slab] -= bytes;
        bytesLive -= bytes;
        liveBlocks--;
        if (slab >= maxSlabs) {
            overflowLiveBytes -= bytes;
        }
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
        int slab = ArenaHandle.slab(handle);
        MemoryRegion region = slabs.get(slab);
        int base = ArenaHandle.offset(handle);
        if (region.getInt(base + OFFSET_STATE) != STATE_LIVE) {
            throw new IllegalStateException(
                    "block " + slab + ":" + base + " was released twice, or was never " + "allocated by this store");
        }
        int sizeClass = region.getInt(base + OFFSET_CLASS);
        region.putInt(base + OFFSET_STATE, STATE_FREE);
        subtractLive(slab, blockBytes(sizeClass));
        releasesSinceCompaction++;
        if (isEvacuating(slab)) {
            // Freed, but not handed out again: this slab is being emptied, and a block reused here
            // would be one more thing keeping it.
            return;
        }
        region.putLong(base + OFFSET_LINK, freeHeads[sizeClass]);
        freeHeads[sizeClass] = handle;
        bytesFree += blockBytes(sizeClass);
    }

    /**
     * Visits every live block's handle in the order the blocks lie in memory: slab by slab, and
     * within a slab by offset (SPILL-4).
     *
     * <p>The order an index's slot table gives -- hash order -- lands each visit at a random address
     * in the store, and once the store has spilled past the page cache every one of those is a fault
     * that reads far more than the block. This order reads each slab front to back, once. {@code
     * visitor} must not allocate or release in this store while it runs.
     */
    public void forEachLive(java.util.function.LongConsumer visitor) {
        checkOpen();
        for (int slab = 0; slab < slabs.size(); slab++) {
            MemoryRegion region = slabs.get(slab);
            if (region == null) {
                continue;
            }
            int end = carvedBytes[slab];
            int offset = 0;
            while (offset < end) {
                int bytes = blockBytes(region.getInt(offset + OFFSET_CLASS));
                if (region.getInt(offset + OFFSET_STATE) == STATE_LIVE) {
                    visitor.accept(ArenaHandle.of(slab, offset));
                }
                offset += bytes;
            }
        }
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

    // ------------------------------------------------------------------------------ compaction

    /**
     * Where a moved block now is. Given every handle an owner holds into this store, once each,
     * during {@link #compactOverflow}; returns the handle unchanged for a block that did not move, and for
     * {@link ArenaHandle#NULL}.
     */
    @FunctionalInterface
    public interface Relocation {
        long relocate(long handle);
    }

    /** The owner of every handle into a store: visits each of them, writing back what {@link Relocation} returns. */
    @FunctionalInterface
    public interface HandleOwner {
        void relocateAll(Relocation relocation);
    }

    /**
     * How much of what the overflow tier has carved is free: {@code 0} with nothing spilled or nothing
     * freed, approaching {@code 1} as a churning store's live state shrinks inside slabs it still holds.
     * The uncarved tail of the slab being filled is not counted -- it is about to be used, not wasted.
     */
    public double overflowFragmentation() {
        return overflowCarvedBytes == 0 ? 0 : 1.0 - (double) overflowLiveBytes / overflowCarvedBytes;
    }

    /**
     * Whether {@link #compactOverflow} could release something: at least two overflow slabs (the one being
     * carved is never a candidate), fragmentation at or past {@code threshold}, and something freed
     * since the last pass -- so a store that compacted and found nothing to move is not asked again
     * until its state changes.
     */
    public boolean needsCompaction(double threshold) {
        return overflowSlabsLive >= 2 && releasesSinceCompaction > 0 && overflowFragmentation() >= threshold;
    }

    /**
     * Moves every live block out of each overflow slab no more than {@code 1 - threshold} full, and
     * releases those slabs -- the memory unmapped as far as Java allows and the file truncated and
     * deleted, so the disk space comes back now rather than at the next collection.
     *
     * <p><strong>The contract.</strong> {@code owner} must present every handle it holds into this
     * store to the {@link Relocation} exactly once, and store what comes back in place of what it
     * gave -- a slot in an index, the link in the entry before it in a chain. A handle into a slab
     * being emptied is moved (a same-class block elsewhere, the payload copied whole) and the old
     * block freed; any other is returned as it is. Nothing may hold a handle anywhere else while this
     * runs, which is why it is called by the owner, on the lane thread, between batches -- the same
     * rule a checkpoint's {@code writeTo} already lives by. A slab that still has a live block when
     * {@code owner} returns -- one it did not present, or one that could not be placed because the
     * tier refused a new slab -- is simply kept: compaction never loses a block, it only fails to
     * free a slab.
     *
     * @param threshold the fraction of a slab that must be free for it to be emptied; see {@link
     *     #needsCompaction}
     * @return how many slabs were released
     */
    public int compactOverflow(double threshold, HandleOwner owner) {
        checkOpen();
        releasesSinceCompaction = 0;
        boolean[] candidates = new boolean[slabs.size()];
        int count = 0;
        for (int slab = maxSlabs; slab < slabs.size(); slab++) {
            if (slabs.get(slab) != null
                    && slab != currentSlab
                    && liveBytesBySlab[slab] <= (1 - threshold) * carvedBytes[slab]) {
                candidates[slab] = true;
                count++;
            }
        }
        if (count == 0) {
            return 0;
        }
        evacuating = candidates;
        relocationStalled = false;
        int released = 0;
        try {
            rebuildFreeLists();
            owner.relocateAll(this::relocate);
        } finally {
            for (int slab = 0; slab < candidates.length; slab++) {
                if (candidates[slab] && liveBytesBySlab[slab] == 0) {
                    releaseSlab(slab);
                    released++;
                }
            }
            evacuating = null;
            rebuildFreeLists();
            compactions++;
            slabsReleased += released;
        }
        return released;
    }

    private boolean isEvacuating(int slab) {
        return evacuating != null && slab < evacuating.length && evacuating[slab];
    }

    private long relocate(long handle) {
        if (handle == ArenaHandle.NULL || relocationStalled) {
            return handle;
        }
        int slab = ArenaHandle.slab(handle);
        if (!isEvacuating(slab)) {
            return handle;
        }
        MemoryRegion from = slabs.get(slab);
        int base = ArenaHandle.offset(handle);
        if (from.getInt(base + OFFSET_STATE) != STATE_LIVE) {
            throw new IllegalStateException("block " + slab + ":" + base + " was presented for relocation but is not "
                    + "live; its owner is holding a handle it already released, or presented one twice");
        }
        int sizeClass = from.getInt(base + OFFSET_CLASS);
        long moved;
        try {
            moved = allocateInClass(sizeClass, false);
        } catch (PravahaException | java.io.UncheckedIOException e) {
            // No room for it outside the slabs being emptied: the tier's ceiling, its quota or the
            // disk. The block stays where it is, its slab is kept, and nothing is lost -- this pass
            // simply frees less than it hoped to.
            relocationStalled = true;
            return handle;
        }
        int payload = blockBytes(sizeClass) - HEADER_BYTES;
        byte[] bytes = new byte[payload];
        // Through an array rather than MemoryRegion.copyFrom: the two regions are usually of
        // different kinds (a mapped slab and a RAM one), and a RAM region copies only from its own.
        from.getBytes(base + HEADER_BYTES, bytes, 0, payload);
        regionOf(moved).putBytes(offsetOf(moved), bytes, 0, payload);
        from.putInt(base + OFFSET_STATE, STATE_FREE);
        subtractLive(slab, blockBytes(sizeClass));
        blocksRelocated++;
        return moved;
    }

    private void releaseSlab(int slab) {
        MemoryRegion region = slabs.set(slab, null);
        region.close();
        slabsHeld--;
        overflowSlabsLive--;
        overflowCarvedBytes -= carvedBytes[slab];
        carvedBytes[slab] = 0;
        liveBytesBySlab[slab] = 0;
        if (releasedSlotCount == releasedSlots.length) {
            releasedSlots = Arrays.copyOf(releasedSlots, releasedSlots.length * 2);
        }
        releasedSlots[releasedSlotCount++] = slab;
    }

    /**
     * Rethreads every free list from the blocks themselves, skipping the slabs being emptied.
     *
     * <p>Every carved slab is a run of blocks back to back from offset zero, each starting with its
     * class and state, so the free blocks can be found by walking rather than by trusting lists
     * that point into slabs about to go. Highest index first, so a RAM block ends up at the head of
     * its list and is what a relocation lands in when one is free. Only compaction calls this.
     */
    private void rebuildFreeLists() {
        Arrays.fill(freeHeads, ArenaHandle.NULL);
        bytesFree = 0;
        for (int slab = slabs.size() - 1; slab >= 0; slab--) {
            MemoryRegion region = slabs.get(slab);
            if (region == null || isEvacuating(slab)) {
                continue;
            }
            int end = carvedBytes[slab];
            int offset = 0;
            while (offset < end) {
                int sizeClass = region.getInt(offset + OFFSET_CLASS);
                int bytes = blockBytes(sizeClass);
                if (region.getInt(offset + OFFSET_STATE) == STATE_FREE) {
                    region.putLong(offset + OFFSET_LINK, freeHeads[sizeClass]);
                    freeHeads[sizeClass] = ArenaHandle.of(slab, offset);
                    bytesFree += bytes;
                }
                offset += bytes;
            }
        }
    }

    // ------------------------------------------------------------------------------ accounting

    /** Bytes in live blocks, including their headers and the slack their size class wastes. */
    public long bytesLive() {
        return bytesLive;
    }

    /** Bytes sitting on free lists, available without touching a new slab. */
    public long bytesFree() {
        return bytesFree;
    }

    /** Bytes taken from the operating system -- or, for the overflow tier, mapped from files. */
    public long bytesReserved() {
        return (long) slabsHeld * slabBytes;
    }

    public long liveBlocks() {
        return liveBlocks;
    }

    /** How many allocations were served from a free list. Zero here means nothing is being reused. */
    public long reuses() {
        return reuses;
    }

    /** Slabs held now, both tiers. */
    public int slabCount() {
        return slabsHeld;
    }

    /** How many slabs have ever been carved from the overflow tier. Zero when nothing has spilled yet. */
    public int overflowSlabsUsed() {
        return overflowSlabsUsed;
    }

    /** Overflow slabs held now: {@link #overflowSlabsUsed} less what compaction released. */
    public int overflowSlabsLive() {
        return overflowSlabsLive;
    }

    /** Bytes of overflow slab held now -- the files this store has on disk. */
    public long overflowBytesReserved() {
        return (long) overflowSlabsLive * slabBytes;
    }

    /** Bytes in live blocks inside overflow slabs. */
    public long overflowBytesLive() {
        return overflowLiveBytes;
    }

    /** Compaction passes that found a slab worth emptying. */
    public long compactions() {
        return compactions;
    }

    /** Overflow slabs compaction released. */
    public long slabsReleased() {
        return slabsReleased;
    }

    /** Blocks compaction moved. */
    public long blocksRelocated() {
        return blocksRelocated;
    }

    /** Whether this store has ever carved a slab from its overflow tier. */
    public boolean hasSpilled() {
        return overflowSlabsUsed > 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MemoryRegion region : slabs) {
            if (region != null) {
                region.close();
            }
        }
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
                + bytesReserved() + "B, reuses=" + reuses + ", overflow " + overflowSlabsLive + " slabs "
                + String.format("%.0f%%", overflowFragmentation() * 100) + " fragmented, " + compactions
                + " compactions]";
    }
}
