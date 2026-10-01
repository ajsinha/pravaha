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
package com.ash.messaging.pravaha.runtime.state;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.state.RowStore;
import com.ash.messaging.pravaha.state.SpillStatistics;
import com.ash.messaging.pravaha.state.StateErrors;

/**
 * An off-heap hash index over a key of any width, keyed by the key's own bytes rather than a digest.
 *
 * <p>ADR-039 item 4 (W8-12). {@code L0StateMap} was deleted in Wave 8 because its key width was a
 * constructor argument -- one fixed size for every key, which is what let a slot be a contiguous
 * {@code [key | value]} pair with no indirection -- and a {@code GROUP BY} column that is a
 * {@code STRING} has no fixed width (see {@code docs/design/adr/006-tiered-state.md}, "Implementation
 * status"). This is the replacement, and it does not resurrect that shape unchanged: the fixed-width
 * assumption is the thing that has to go, so the indirection it existed to avoid is exactly what this
 * class accepts.
 *
 * <h2>The shape</h2>
 *
 * <p>Two structures, not one:
 *
 * <ul>
 *   <li>A fixed-width, off-heap, open-addressed <strong>slot table</strong> -- sixteen bytes per
 *       slot, a 64-bit fingerprint and a 64-bit handle -- which is what a probe walks. This is the
 *       part {@code L0StateMap} got right and this class keeps: linear probing over cache-resident
 *       fixed-width slots, no object per entry, no collector involvement in a lookup. It is held
 *       as segments ({@link SlotTable}), so it can pass two gigabytes, and with an overflow tier
 *       the segments past its RAM budget are mapped files (ADR-044).
 *   <li>A {@link RowStore} holding the actual key bytes, value bytes and a small header, one block
 *       per live entry, individually freed on removal and reused by later insertions of a similar
 *       size. This is the indirection: a slot holds a <em>handle</em> into the store rather than the
 *       key and value themselves, which is exactly what makes the key's width no longer the table's
 *       problem.
 * </ul>
 *
 * <p>The fingerprint is what keeps this fast despite the indirection: a probe compares the stored
 * 64-bit fingerprint first, entirely within the slot table, and only dereferences the {@code
 * RowStore} block -- the one memory access that can miss cache -- when the fingerprint already
 * matches. Two different keys sharing a fingerprint is handled correctly rather than assumed away:
 * on a fingerprint hit this class compares the actual key bytes before calling it a match. That is
 * the check {@code JoinSide}'s own bucket index is missing today (its comment names the gap directly:
 * "becomes load-bearing the moment the index moves off-heap to a masked table") -- if this class
 * were built to make the same shortcut, wiring it into a masked table would resurrect precisely the
 * bug that comment predicts.
 *
 * <h2>Arena discipline</h2>
 *
 * <p><strong>Not a {@code RowArena}.</strong> A lane's arena is a bump-pointer allocator reset whole
 * at the end of a batch (see {@code RowArena}'s own javadoc) -- exactly wrong for state that must
 * outlive the batch that touched it. This class copies every key it is given into a {@link RowStore}
 * block it owns, via {@link MemoryRegion#copyFrom}, before returning a handle; nothing here retains a
 * reference into a caller's row. A key read out of an incoming row's arena and handed to
 * {@link #getOrCreate} is safe to use the moment that call returns, however soon afterwards the
 * lane rewinds the arena that row came from.
 *
 * <p>Value bytes reserved for a <em>new</em> entry are explicitly zeroed before the handle is handed
 * back, for the same reason {@code JoinSide.insert} explicitly zeroes its own {@code MATCHED} flag: a
 * {@link RowStore} block recycled from a released entry is not cleared by the store, and an
 * accumulator seeded from a previous key's leftover bytes is a silent wrong answer with nothing to
 * notice it by.
 *
 * <p>Not thread-safe, like everything else on this project's row path -- owned by one lane, and the
 * single-writer principle is what makes that safe rather than an oversight.
 */
public final class VariableKeyStateMap implements AutoCloseable {

    /**
     * Slots per slot-table segment: 2<sup>20</sup>, sixteen MiB -- one mapped file each once the table
     * is past its RAM budget, so the index of a hundred million keys is about a hundred files, far
     * inside {@code vm.max_map_count}.
     */
    public static final int DEFAULT_SEGMENT_SLOTS = 1 << 20;

    /** A slot that has never held anything. */
    private static final long EMPTY = ArenaHandle.NULL;

    /**
     * A slot whose entry was removed. Distinct from {@link #EMPTY} so a lookup that reaches a
     * tombstone keeps probing rather than concluding the key is absent -- the same reason {@code
     * L0StateMap} needed a third state. {@code ArenaHandle}'s encoding makes {@code -2L} just as
     * impossible a real handle as {@code -1L}: both decode to a slab index of {@code -1}, which
     * {@link RowStore} never issues.
     */
    private static final long TOMBSTONE = -2L;

    private final MemoryAccess access;
    private final MemoryAccess overflowAccess;
    private final RowStore store;
    private final double maxLoadFactor = 0.7;
    private final long slotTableRamBytes;
    private final int segmentSlots;

    private SlotTable slotTable;
    private int capacity;
    private int mask;
    private int size;
    private int tombstones;
    private long peakSize;
    private long probes;
    private long lookups;
    private long resizes;

    /**
     * @param initialCapacity slot-table capacity, rounded up to a power of two
     * @param storeSlabBytes size of each slab the key/value store carves blocks from
     * @param storeMaxSlabs the ceiling on the key/value store, in slabs. Reaching it is a refusal
     *     ({@link RowStore} throws {@code PRV-4001}), not silent eviction -- state this map is asked
     *     to hold and cannot is exactly the case ADR-037 item B2 exists to soften; see the other
     *     constructor for the overflow tier that does.
     */
    public VariableKeyStateMap(MemoryAccess access, int initialCapacity, int storeSlabBytes, int storeMaxSlabs) {
        this(access, initialCapacity, storeSlabBytes, storeMaxSlabs, null, 0);
    }

    /**
     * ADR-037 item B2: the key/value store's ceiling can be moved by carving overflow slabs from a
     * second {@link MemoryAccess} instead of refusing outright -- see {@link RowStore}'s own
     * overflow-aware constructor, which this passes straight through to. The slot table follows it
     * (ADR-044): with a tier, the table may hold as many bytes of RAM as the store's own RAM ceiling,
     * {@code storeMaxSlabs * storeSlabBytes}, and its segments past that are mapped. It used to stay
     * in RAM at sixteen bytes a slot whatever the key count -- the one part of a spilled map whose
     * memory still grew with its keys.
     *
     * @param overflowAccess where slabs beyond {@code storeMaxSlabs} are carved from, or {@code null}
     *     for no overflow tier -- today's behaviour, unchanged
     * @param maxOverflowSlabs the ceiling on {@code overflowAccess} slabs, ignored when {@code
     *     overflowAccess} is {@code null}
     */
    public VariableKeyStateMap(
            MemoryAccess access,
            int initialCapacity,
            int storeSlabBytes,
            int storeMaxSlabs,
            MemoryAccess overflowAccess,
            int maxOverflowSlabs) {
        this(
                access,
                initialCapacity,
                storeSlabBytes,
                storeMaxSlabs,
                overflowAccess,
                maxOverflowSlabs,
                (long) storeMaxSlabs * storeSlabBytes,
                DEFAULT_SEGMENT_SLOTS);
    }

    /**
     * With the slot table's RAM budget and segment size given, which is how a test puts a small
     * table's segments in the overflow tier.
     *
     * @param slotTableRamBytes bytes of slot table held in RAM before further segments are mapped;
     *     ignored without an overflow tier
     * @param segmentSlots slots per slot-table segment, a power of two
     */
    public VariableKeyStateMap(
            MemoryAccess access,
            int initialCapacity,
            int storeSlabBytes,
            int storeMaxSlabs,
            MemoryAccess overflowAccess,
            int maxOverflowSlabs,
            long slotTableRamBytes,
            int segmentSlots) {
        if (initialCapacity < 2 || initialCapacity > SlotTable.MAX_CAPACITY) {
            throw new IllegalArgumentException("initial capacity must be from 2 to 2^30, got " + initialCapacity);
        }
        if (segmentSlots < 2 || Integer.bitCount(segmentSlots) != 1 || segmentSlots > SlotTable.MAX_CAPACITY) {
            throw new IllegalArgumentException("segment slots must be a power of two, got " + segmentSlots);
        }
        this.access = access;
        this.overflowAccess = overflowAccess;
        this.slotTableRamBytes = slotTableRamBytes;
        this.segmentSlots = segmentSlots;
        this.store = new RowStore(access, storeSlabBytes, storeMaxSlabs, overflowAccess, maxOverflowSlabs);
        useTable(newTable(nextPowerOfTwo(initialCapacity)));
    }

    private SlotTable newTable(int newCapacity) {
        return SlotTable.allocate(newCapacity, segmentSlots, access, slotTableRamBytes, overflowAccess);
    }

    private void useTable(SlotTable table) {
        this.slotTable = table;
        this.capacity = table.capacity();
        this.mask = capacity - 1;
    }

    private static int nextPowerOfTwo(int value) {
        int result = Integer.highestOneBit(value);
        return result == value ? value : result << 1;
    }

    /**
     * Finds a key, without creating it.
     *
     * @return the entry's handle, or {@link ArenaHandle#NULL} if the key is absent
     */
    public long find(MemoryRegion keyRegion, int keyOffset, int keyLength) {
        long fingerprint = hash(keyRegion, keyOffset, keyLength);
        int slot = (int) (fingerprint & mask);
        lookups++;
        for (int probe = 0; probe <= capacity; probe++) {
            probes++;
            long handle = handleAt(slot);
            if (handle == EMPTY) {
                return ArenaHandle.NULL;
            }
            if (handle != TOMBSTONE
                    && fingerprintAt(slot) == fingerprint
                    && keyEquals(handle, keyRegion, keyOffset, keyLength)) {
                return handle;
            }
            slot = (slot + 1) & mask;
        }
        throw noFreeSlot();
    }

    /**
     * Finds a key's entry, creating one with {@code valueBytes} of zeroed value space if it is
     * absent.
     *
     * <p>One probe rather than a {@link #find} followed by a separate insert, which is not an
     * optimisation detail here: a caller on the row-processing hot path -- an aggregate folding one
     * record in -- calls this once per record, and a second probe would be a second walk of the same
     * cache lines for no new information.
     *
     * @return the existing handle, or a freshly created one
     */
    public long getOrCreate(MemoryRegion keyRegion, int keyOffset, int keyLength, int valueBytes) {
        long fingerprint = hash(keyRegion, keyOffset, keyLength);
        int slot = (int) (fingerprint & mask);
        int firstTombstone = -1;
        lookups++;
        for (int probe = 0; probe <= capacity; probe++) {
            probes++;
            long handle = handleAt(slot);
            if (handle == EMPTY) {
                // Absent. The table grows (or sweeps its tombstones) before the entry exists, not
                // after: a table that cannot be had -- the tier's quota, a full disk, the largest
                // table there is -- is then refused with the map exactly as it was, rather than
                // holding an entry whose caller never got its handle back to fill in.
                int occupiedAfter = size + tombstones + (firstTombstone >= 0 ? 0 : 1);
                if ((double) occupiedAfter / capacity > maxLoadFactor) {
                    rehash();
                    return getOrCreate(keyRegion, keyOffset, keyLength, valueBytes);
                }
                int insertSlot = firstTombstone >= 0 ? firstTombstone : slot;
                long created = createEntry(keyRegion, keyOffset, keyLength, valueBytes);
                if (firstTombstone >= 0) {
                    tombstones--;
                }
                slotTable.put(insertSlot, fingerprint, created);
                size++;
                peakSize = Math.max(peakSize, size);
                return created;
            }
            if (handle == TOMBSTONE) {
                if (firstTombstone < 0) {
                    firstTombstone = slot;
                }
            } else if (fingerprintAt(slot) == fingerprint && keyEquals(handle, keyRegion, keyOffset, keyLength)) {
                return handle;
            }
            slot = (slot + 1) & mask;
        }
        throw noFreeSlot();
    }

    /** Removes a key, releasing its {@link RowStore} block. */
    public boolean remove(MemoryRegion keyRegion, int keyOffset, int keyLength) {
        long fingerprint = hash(keyRegion, keyOffset, keyLength);
        int slot = (int) (fingerprint & mask);
        lookups++;
        for (int probe = 0; probe <= capacity; probe++) {
            probes++;
            long handle = handleAt(slot);
            if (handle == EMPTY) {
                return false;
            }
            if (handle != TOMBSTONE
                    && fingerprintAt(slot) == fingerprint
                    && keyEquals(handle, keyRegion, keyOffset, keyLength)) {
                // Marked rather than cleared, for the reason L0StateMap's javadoc gave: clearing
                // would break the probe chain for every key that collided with this one and landed
                // behind it.
                slotTable.putHandle(slot, TOMBSTONE);
                store.release(handle);
                size--;
                tombstones++;
                return true;
            }
            slot = (slot + 1) & mask;
        }
        return false;
    }

    private long createEntry(MemoryRegion keyRegion, int keyOffset, int keyLength, int valueBytes) {
        long handle = store.allocate(EntryLayout.HEADER_BYTES + keyLength + valueBytes);
        MemoryRegion region = store.regionOf(handle);
        int base = store.offsetOf(handle);
        region.putInt(base + EntryLayout.OFFSET_KEY_LENGTH, keyLength);
        region.putInt(base + EntryLayout.OFFSET_VALUE_LENGTH, valueBytes);
        region.copyFrom(base + EntryLayout.HEADER_BYTES, keyRegion, keyOffset, keyLength);
        // Explicit rather than trusting a recycled block to be clean -- see the class javadoc.
        region.setMemory(base + EntryLayout.HEADER_BYTES + keyLength, valueBytes, (byte) 0);
        return handle;
    }

    /** The value bytes' region. Read and write it directly; this map never looks inside them. */
    public MemoryRegion valueRegionOf(long handle) {
        return store.regionOf(handle);
    }

    public int valueOffsetOf(long handle) {
        return store.offsetOf(handle) + EntryLayout.HEADER_BYTES + keyLengthOf(handle);
    }

    public int valueLengthOf(long handle) {
        return store.regionOf(handle).getInt(store.offsetOf(handle) + EntryLayout.OFFSET_VALUE_LENGTH);
    }

    /** The key bytes' region, for reconstructing output -- the thing a digest-keyed map cannot do. */
    public MemoryRegion keyRegionOf(long handle) {
        return store.regionOf(handle);
    }

    public int keyOffsetOf(long handle) {
        return store.offsetOf(handle) + EntryLayout.HEADER_BYTES;
    }

    public int keyLengthOf(long handle) {
        return store.regionOf(handle).getInt(store.offsetOf(handle) + EntryLayout.OFFSET_KEY_LENGTH);
    }

    /** Visits every live entry by handle. The order is the slot table's: arbitrary, stable within a run. */
    public void forEach(EntryVisitor visitor) {
        for (int slot = 0; slot < capacity; slot++) {
            long handle = handleAt(slot);
            if (handle != EMPTY && handle != TOMBSTONE) {
                visitor.visit(handle);
            }
        }
    }

    /**
     * Visits every entry in the order entries lie in the store rather than in the slot table
     * (SPILL-4): a sequential read of the store instead of a random one per entry, which is the
     * difference that matters once the store has spilled past the page cache. Every live block in
     * this map's store is an entry. Nothing may be added or removed while it runs.
     */
    public void forEachInStoreOrder(EntryVisitor visitor) {
        store.forEachLive(visitor::visit);
    }

    @FunctionalInterface
    public interface EntryVisitor {
        void visit(long handle);
    }

    private long handleAt(int slot) {
        return slotTable.handle(slot);
    }

    private long fingerprintAt(int slot) {
        return slotTable.fingerprint(slot);
    }

    private boolean keyEquals(long handle, MemoryRegion keyRegion, int keyOffset, int keyLength) {
        if (keyLengthOf(handle) != keyLength) {
            return false;
        }
        MemoryRegion stored = store.regionOf(handle);
        int base = keyOffsetOf(handle);
        for (int i = 0; i < keyLength; i++) {
            if (stored.getByte(base + i) != keyRegion.getByte(keyOffset + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Grows the slot table when its load factor is exceeded, or rehashes at the same capacity when
     * the load is mostly tombstones -- both for the reason {@code L0StateMap} gave: tombstones
     * occupy probe positions even though they hold nothing, so a table full of them probes as slowly
     * as one that is actually full, and a churny workload that only ever holds a few live keys must
     * not grow without bound because of it.
     *
     * <p>Cheaper than {@code L0StateMap}'s rehash: only the sixteen-byte slots move. The key and
     * value bytes stay exactly where they are in the {@link RowStore}, because a slot holds a handle
     * to them rather than the bytes themselves -- resizing the index never touches the data.
     *
     * <p>The new table is built whole before the old one is let go, so a refusal part-way -- the
     * overflow tier's quota or free space ({@code PRV-4005}, {@code PRV-4006}), or a table already at
     * {@link SlotTable#MAX_CAPACITY} ({@code PRV-4001}) -- leaves the map as it was.
     */
    private void rehash() {
        int newCapacity = capacity;
        if (size + 1 > capacity / 2) {
            if (capacity >= SlotTable.MAX_CAPACITY) {
                throw new PravahaException(
                        StateErrors.STATE_TOO_LARGE,
                        "a state index holds " + size + " keys in " + capacity + " slots, the most one index can "
                                + "have (2^30 slots, 16 GiB of slot table), and cannot take another. Bound the "
                                + "key space the query keeps -- a window, a tighter predicate -- or split it "
                                + "into queries over disjoint key ranges.");
            }
            newCapacity = capacity * 2;
        }
        SlotTable old = slotTable;
        SlotTable rebuilt = newTable(newCapacity);
        int oldCapacity = capacity;
        useTable(rebuilt);
        for (int slot = 0; slot < oldCapacity; slot++) {
            long handle = old.handle(slot);
            if (handle != EMPTY && handle != TOMBSTONE) {
                reinsert(old.fingerprint(slot), handle);
            }
        }
        tombstones = 0;
        old.close();
        resizes++;
    }

    /** Places an already-known-distinct (fingerprint, handle) pair during a rehash. No comparison needed. */
    private void reinsert(long fingerprint, long handle) {
        int slot = (int) (fingerprint & mask);
        while (handleAt(slot) != EMPTY) {
            slot = (slot + 1) & mask;
        }
        slotTable.put(slot, fingerprint, handle);
    }

    private static IllegalStateException noFreeSlot() {
        // Cannot happen while the load factor is enforced. A hang is the worse failure this loop
        // could have -- an infinite probe reads as an infrastructure problem and gets retried rather
        // than read -- so this throws instead of spinning past capacity.
        return new IllegalStateException(
                "the state table has no free slot; the load factor is not being enforced, which is a bug in "
                        + "this class rather than in its caller");
    }

    /**
     * FNV-1a over the key's bytes, then a murmur finaliser -- the same construction {@code
     * L0StateMap} used, kept for the reason its own comment gave: the finaliser guards against keys
     * whose low bits are regular (sequential ids, millisecond timestamps) clustering into one probe
     * chain, a failure that degrades gradually and never errors.
     */
    private static long hash(MemoryRegion region, int offset, int length) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            hash ^= region.getByte(offset + i) & 0xFF;
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return hash;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public long peakSize() {
        return peakSize;
    }

    public long resizes() {
        return resizes;
    }

    /** Average probes per lookup; near one is healthy, the same signal {@code L0StateMap} used. */
    public double averageProbes() {
        return lookups == 0 ? 0 : (double) probes / lookups;
    }

    /** Bytes the fixed-width slot table occupies, RAM and mapped together. Independent of key or value width. */
    public long indexBytesAllocated() {
        return slotTable.bytes();
    }

    /** Bytes of the slot table held in the overflow tier rather than RAM (ADR-044). */
    public long indexBytesMapped() {
        return slotTable.bytesMapped();
    }

    /** Bytes the key/value store has reserved from the operating system, live and free together. */
    public long dataBytesReserved() {
        return store.bytesReserved();
    }

    /** Bytes the key/value store holds in live entries -- excludes size-class slack sitting on a free list. */
    public long dataBytesLive() {
        return store.bytesLive();
    }

    /** Total off-heap footprint: the index plus everything the key/value store has reserved. */
    public long bytesAllocated() {
        return indexBytesAllocated() + dataBytesReserved();
    }

    /**
     * Average bytes per live entry, index and data together -- the number ADR-036's whole argument
     * runs on. Zero with nothing stored, rather than a divide-by-zero exception, because "no entries
     * yet" is the ordinary state of a freshly registered query and not a caller error.
     */
    public double bytesPerEntry() {
        return size == 0 ? 0 : (double) bytesAllocated() / size;
    }

    /** How many size-class blocks were handed out from a free list rather than carved fresh. */
    public long reuses() {
        return store.reuses();
    }

    /** Whether this map's key/value store has ever carved a slab from its overflow tier, or its slot table is mapped now. */
    public boolean hasSpilled() {
        return store.hasSpilled() || slotTable.bytesMapped() > 0;
    }

    /** How many overflow-tier slabs this map's key/value store has used. */
    public int overflowSlabsUsed() {
        return store.overflowSlabsUsed();
    }

    /** Whether this map's key/value store is fragmented enough for {@link #compactOverflow} to be worth it. */
    public boolean needsCompaction(double threshold) {
        return store.needsCompaction(threshold);
    }

    /**
     * Compacts the key/value store's overflow slabs (ADR-044), releasing the sparse ones.
     *
     * <p>This map is the only holder of handles into its store -- one per occupied slot -- so it is
     * the one that can do this: each slot's handle is presented to the store once and the slot
     * rewritten if the entry moved. The fingerprint does not change, because the key did not, so no
     * slot moves and no probe chain is disturbed. A caller must not hold a handle this map returned
     * across the call; every caller in this codebase takes handles within one operation and lets
     * them go, which is why compaction runs between batches.
     *
     * @return how many slabs were released
     */
    public int compactOverflow(double threshold) {
        return store.compactOverflow(threshold, relocation -> {
            for (int slot = 0; slot < capacity; slot++) {
                long handle = handleAt(slot);
                if (handle != EMPTY && handle != TOMBSTONE) {
                    long moved = relocation.relocate(handle);
                    if (moved != handle) {
                        slotTable.putHandle(slot, moved);
                    }
                }
            }
        });
    }

    /** Compacts if {@link #needsCompaction}; the call an owner makes between batches. */
    public int compactIfFragmented(double threshold) {
        return needsCompaction(threshold) ? compactOverflow(threshold) : 0;
    }

    /**
     * The overflow tier's numbers for this map: its key/value store's, with the slot table's mapped
     * segments counted as held and live -- they are files in the spill directory like any slab, and
     * every byte of them is table in use.
     */
    public SpillStatistics spillStatistics() {
        long tableMapped = slotTable.bytesMapped();
        SpillStatistics ofStore = SpillStatistics.of(store);
        return tableMapped == 0 ? ofStore : ofStore.plus(new SpillStatistics(tableMapped, tableMapped, 0, 0, 0));
    }

    @Override
    public void close() {
        slotTable.close();
        store.close();
    }

    @Override
    public String toString() {
        return "VariableKeyStateMap[" + size + "/" + capacity + " slots, " + tombstones + " tombstones, "
                + String.format("%.2f", averageProbes()) + " probes/lookup, " + bytesAllocated() + "B ("
                + String.format("%.1f", bytesPerEntry()) + "B/entry)]";
    }

    /** Layout of one {@link RowStore} block: a small header, then the key bytes, then the value bytes. */
    private static final class EntryLayout {
        static final int OFFSET_KEY_LENGTH = 0;
        static final int OFFSET_VALUE_LENGTH = 4;
        static final int HEADER_BYTES = 8;

        private EntryLayout() {}
    }
}
