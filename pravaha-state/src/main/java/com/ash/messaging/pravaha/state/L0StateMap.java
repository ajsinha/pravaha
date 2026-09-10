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

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * The hot state tier: an open-addressed hash table in off-heap memory, owned by one lane.
 *
 * <p>Design section 14.2's L0. A windowed aggregate at a hundred thousand keys does a lookup per
 * record, so this is on the hot path in the most literal sense, and the two things that make it fast
 * are the two things that make it awkward. Everything lives in one contiguous region, so a probe is
 * a sequential read of adjacent cache lines rather than a chase through the heap. And there are no
 * objects, so a million live keys cost the collector nothing -- which matters because the
 * alternative, a {@code HashMap} of a million entries, is a million objects the collector must trace
 * on every cycle to conclude that all of them are still live.
 *
 * <p><strong>Keys are bytes, not hashes.</strong> The windowed aggregate currently keys its state by
 * a 64-bit hash of the grouping columns, which cannot distinguish two key combinations that collide
 * and cannot reconstruct the key for output. This stores the key itself, so both problems disappear
 * -- and the collision probability stops being a number anybody has to reason about.
 *
 * <p><strong>Linear probing</strong> rather than chaining, for the same cache reason: a collision
 * resolves by reading the next slot, which is almost certainly in a line already fetched. The cost
 * is that deletion cannot simply clear a slot -- that would break the probe chain for every key
 * behind it -- so a removed slot is marked and skipped on lookup but reused on insert.
 *
 * <p>Owned by one lane and confined to its thread. No locks, because there is nothing to lock
 * against: that is the single-writer principle paying for itself again.
 */
public final class L0StateMap implements AutoCloseable {

    /** A slot that has never held anything. */
    private static final byte EMPTY = 0;
    /** A slot holding a live entry. */
    private static final byte LIVE = 1;
    /** A slot whose entry was removed. Skipped on lookup, reused on insert. */
    private static final byte TOMBSTONE = 2;

    private final MemoryAccess access;
    private final int keyBytes;
    private final int valueBytes;
    private final int slotBytes;
    private final double maxLoadFactor;

    private MemoryRegion table;
    private byte[] states;
    private int capacity;
    private int mask;
    private int size;
    private int tombstones;
    private long probes;
    private long lookups;
    private long resizes;

    /**
     * @param keyBytes fixed key width. Fixed because a variable-width key means an indirection, and
     *     an indirection per lookup is the thing this exists to avoid; a composite key is packed by
     *     the caller, which knows its own schema.
     * @param valueBytes fixed value width, for the same reason
     * @param initialCapacity rounded up to a power of two
     */
    public L0StateMap(MemoryAccess access, int keyBytes, int valueBytes, int initialCapacity) {
        if (keyBytes < 1 || valueBytes < 1) {
            throw new IllegalArgumentException("key and value widths must be positive");
        }
        if (initialCapacity < 2) {
            throw new IllegalArgumentException("initial capacity must be at least 2, got " + initialCapacity);
        }
        this.access = access;
        this.keyBytes = keyBytes;
        this.valueBytes = valueBytes;
        this.slotBytes = keyBytes + valueBytes;
        this.maxLoadFactor = 0.7;
        allocate(nextPowerOfTwo(initialCapacity));
    }

    private void allocate(int newCapacity) {
        this.capacity = newCapacity;
        this.mask = newCapacity - 1;
        this.table = access.allocate(newCapacity * slotBytes, 64);
        this.states = new byte[newCapacity];
        this.size = 0;
        this.tombstones = 0;
    }

    private static int nextPowerOfTwo(int value) {
        int result = Integer.highestOneBit(value);
        return result == value ? value : result << 1;
    }

    /**
     * Finds a key's slot, or the slot it would go in.
     *
     * @return the slot index; check {@link #isLive(int)} to tell a hit from a free slot
     */
    private int slotFor(MemoryRegion key, int keyOffset) {
        int slot = (int) (hash(key, keyOffset) & mask);
        int firstTombstone = -1;
        lookups++;
        for (int probe = 0; probe <= capacity; probe++) {
            probes++;
            byte state = states[slot];
            if (state == EMPTY) {
                // A key that exists would have been found before reaching an empty slot, because
                // insertion never skips one. So this is a miss, and the first tombstone passed on
                // the way is the best place to put the key if it is about to be inserted.
                return firstTombstone >= 0 ? firstTombstone : slot;
            }
            if (state == TOMBSTONE) {
                if (firstTombstone < 0) {
                    firstTombstone = slot;
                }
            } else if (keyEquals(slot, key, keyOffset)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }

        // Every slot visited and none of them empty. That cannot happen while the load factor is
        // enforced -- and if the enforcement is ever broken, the loop above would otherwise spin
        // forever rather than fail. A hang is the worst failure this code can have: it reads as an
        // infrastructure problem, gets retried, and tells nobody anything. Found by seeding exactly
        // that break and watching a test hang instead of failing.
        throw new IllegalStateException("the state table has no free slot: " + size + " live and " + tombstones
                + " removed entries in " + capacity + " slots. The load factor is not being enforced, which is "
                + "a bug in this class rather than in its caller.");
    }

    private boolean isLive(int slot) {
        return states[slot] == LIVE;
    }

    /** Whether a key is present. */
    public boolean containsKey(MemoryRegion key, int keyOffset) {
        return isLive(slotFor(key, keyOffset));
    }

    /**
     * Reads a key's value into {@code into}.
     *
     * @return {@code false} if the key is absent, in which case {@code into} is untouched
     */
    public boolean get(MemoryRegion key, int keyOffset, MemoryRegion into, int intoOffset) {
        int slot = slotFor(key, keyOffset);
        if (!isLive(slot)) {
            return false;
        }
        into.copyFrom(intoOffset, table, slot * slotBytes + keyBytes, valueBytes);
        return true;
    }

    /** Inserts or replaces a key's value. */
    public void put(MemoryRegion key, int keyOffset, MemoryRegion value, int valueOffset) {
        int slot = slotFor(key, keyOffset);
        int base = slot * slotBytes;
        if (!isLive(slot)) {
            if (states[slot] == TOMBSTONE) {
                tombstones--;
            }
            table.copyFrom(base, key, keyOffset, keyBytes);
            states[slot] = LIVE;
            size++;
        }
        table.copyFrom(base + keyBytes, value, valueOffset, valueBytes);

        // Tombstones count towards the load factor: they occupy probe positions even though they
        // hold nothing, and a table full of them probes as slowly as one that is actually full.
        if ((double) (size + tombstones) / capacity > maxLoadFactor) {
            // Growing is not always the answer, and assuming it is makes a churny workload grow
            // without bound: insert-then-remove a thousand keys and the live size never exceeds one
            // while the tombstones fill the table repeatedly. If the entries are mostly tombstones,
            // rehashing at the same capacity clears them and costs nothing in memory.
            rehash(size > capacity / 2 ? capacity * 2 : capacity);
        }
    }

    /** Removes a key. */
    public boolean remove(MemoryRegion key, int keyOffset) {
        int slot = slotFor(key, keyOffset);
        if (!isLive(slot)) {
            return false;
        }
        // Marked rather than cleared: clearing would break the probe chain for every key that
        // collided with this one and landed behind it, and those keys would silently become
        // unreachable while still occupying space. That one is correctness; reusing the marked slot
        // on a later insert, below, is only efficiency -- seeding it away leaves every answer right
        // and merely wastes slots until the next rehash.
        states[slot] = TOMBSTONE;
        size--;
        tombstones++;
        return true;
    }

    /**
     * Rebuilds the table at {@code newCapacity}, re-inserting every live entry.
     *
     * <p>Re-insertion rather than a copy, because slot positions come from the mask and the mask may
     * have changed. At the same capacity this is purely a tombstone sweep, which is frequently the
     * real reason the load factor was exceeded.
     */
    private void rehash(int newCapacity) {
        MemoryRegion oldTable = table;
        byte[] oldStates = states;
        int oldCapacity = capacity;

        allocate(newCapacity);
        if (newCapacity != oldCapacity) {
            resizes++;
        }

        try (MemoryRegion keyScratch = access.allocate(keyBytes);
                MemoryRegion valueScratch = access.allocate(valueBytes)) {
            for (int slot = 0; slot < oldCapacity; slot++) {
                if (oldStates[slot] != LIVE) {
                    continue;
                }
                keyScratch.copyFrom(0, oldTable, slot * slotBytes, keyBytes);
                valueScratch.copyFrom(0, oldTable, slot * slotBytes + keyBytes, valueBytes);
                put(keyScratch, 0, valueScratch, 0);
            }
        } finally {
            oldTable.close();
        }
    }

    /** Visits every live entry. The order is the table's, which is to say arbitrary and stable. */
    public void forEach(EntryVisitor visitor) {
        for (int slot = 0; slot < capacity; slot++) {
            if (states[slot] == LIVE) {
                visitor.visit(table, slot * slotBytes, slot * slotBytes + keyBytes);
            }
        }
    }

    /** Called for each live entry, with the region and the offsets of its key and value. */
    @FunctionalInterface
    public interface EntryVisitor {
        void visit(MemoryRegion region, int keyOffset, int valueOffset);
    }

    private long hash(MemoryRegion key, int keyOffset) {
        // FNV-1a over the key's bytes, then a murmur finaliser.
        //
        // The finaliser is defensive rather than load-bearing, and that is worth saying because the
        // comment here used to claim otherwise: removing it entirely still passes the clustering
        // test, because FNV-1a already avalanches well for byte keys. It is kept because it costs
        // three instructions on a path that is already touching memory, and because the failure it
        // guards against -- keys clustering into one probe chain -- degrades gradually and never
        // errors.
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < keyBytes; i++) {
            hash ^= key.getByte(keyOffset + i) & 0xFF;
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return hash;
    }

    private boolean keyEquals(int slot, MemoryRegion key, int keyOffset) {
        int base = slot * slotBytes;
        for (int i = 0; i < keyBytes; i++) {
            if (table.getByte(base + i) != key.getByte(keyOffset + i)) {
                return false;
            }
        }
        return true;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public long bytesAllocated() {
        return (long) capacity * slotBytes;
    }

    /**
     * Average probes per lookup.
     *
     * <p>The number that says whether the table is healthy. Near one is ideal; climbing means either
     * the load factor is too high or the keys are clustering, and both degrade gradually rather than
     * failing -- which is exactly the kind of problem that needs a metric rather than an alarm.
     */
    public double averageProbes() {
        return lookups == 0 ? 0 : (double) probes / lookups;
    }

    public long resizes() {
        return resizes;
    }

    @Override
    public void close() {
        table.close();
    }

    @Override
    public String toString() {
        return "L0StateMap[" + size + "/" + capacity + " slots, " + tombstones + " tombstones, "
                + String.format("%.2f", averageProbes()) + " probes/lookup]";
    }
}
