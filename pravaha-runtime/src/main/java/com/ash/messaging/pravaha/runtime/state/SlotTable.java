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

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * {@link VariableKeyStateMap}'s slot table: sixteen bytes a slot, a 64-bit fingerprint then a 64-bit
 * handle, held as segments of at most {@code segmentSlots} slots rather than one region.
 *
 * <p><strong>Why segments.</strong> A {@link MemoryRegion} is addressed by {@code int}, so one region
 * cannot pass two gigabytes: a single-region table stopped at 2<sup>26</sup> slots, about 47 million
 * keys, and its next doubling overflowed {@code capacity * 16} to a negative size. Segments lift that
 * to 2<sup>30</sup> slots, and they are what lets the table follow its store into the overflow tier
 * (ADR-044): the first segments, up to {@code ramBudgetBytes}, are RAM; the rest are carved from the
 * overflow tier, one mapped file each, counted against {@code pravaha.state.spill.max-bytes} like any
 * slab, so a key index's RAM stops growing with its key count.
 *
 * <p><strong>A slot's handle word is stored complemented.</strong> {@code ArenaHandle.NULL} is
 * {@code -1}, so storing it as is would mean writing every slot of a new table before using it -- for a
 * mapped segment, dirtying every page of a file that a sparse file otherwise leaves unwritten until a
 * key lands there. Stored as {@code ~handle}, an empty slot is zero: a freshly created sparse file is
 * zero by definition, and a RAM segment is cleared with one {@code setMemory}.
 */
final class SlotTable implements AutoCloseable {

    static final int SLOT_BYTES = 16;

    /** The most slots a table may have: 2<sup>30</sup>, sixteen gigabytes of table. */
    static final int MAX_CAPACITY = 1 << 30;

    private static final int OFFSET_FINGERPRINT = 0;
    private static final int OFFSET_HANDLE = 8;

    private final MemoryRegion[] segments;
    private final int capacity;
    private final int shift;
    private final int segmentMask;
    private final long bytesMapped;

    private SlotTable(MemoryRegion[] segments, int capacity, int segmentSlots, long bytesMapped) {
        this.segments = segments;
        this.capacity = capacity;
        this.shift = Integer.numberOfTrailingZeros(segmentSlots);
        this.segmentMask = segmentSlots - 1;
        this.bytesMapped = bytesMapped;
    }

    /**
     * An empty table of {@code capacity} slots.
     *
     * <p>If any segment cannot be had -- the overflow tier refused it by quota or free space, or RAM is
     * exhausted -- the segments already taken are closed and the refusal propagates: nothing is left
     * half-built, and the caller's current table is untouched.
     *
     * @param capacity a power of two, at most {@link #MAX_CAPACITY}
     * @param segmentSlots the most slots one segment holds; a power of two
     * @param ramBudgetBytes how many bytes of table may be RAM; segments past it come from {@code
     *     overflow}. Ignored when {@code overflow} is {@code null}: every segment is then RAM
     */
    static SlotTable allocate(
            int capacity, int segmentSlots, MemoryAccess ram, long ramBudgetBytes, MemoryAccess overflow) {
        int perSegment = Math.min(capacity, segmentSlots);
        int count = capacity / perSegment;
        int segmentBytes = perSegment * SLOT_BYTES;
        MemoryRegion[] segments = new MemoryRegion[count];
        long mapped = 0;
        try {
            for (int i = 0; i < count; i++) {
                boolean inRam = overflow == null || (long) (i + 1) * segmentBytes <= ramBudgetBytes;
                if (inRam) {
                    segments[i] = ram.allocate(segmentBytes, MemoryAccess.CACHE_LINE_BYTES);
                    segments[i].setMemory(0, segmentBytes, (byte) 0);
                } else {
                    // A new sparse file: zero, i.e. every slot empty, without a byte written.
                    segments[i] = overflow.allocate(segmentBytes, MemoryAccess.CACHE_LINE_BYTES);
                    mapped += segmentBytes;
                }
            }
        } catch (RuntimeException | Error refused) {
            for (MemoryRegion segment : segments) {
                if (segment != null) {
                    segment.close();
                }
            }
            throw refused;
        }
        return new SlotTable(segments, capacity, perSegment, mapped);
    }

    int capacity() {
        return capacity;
    }

    /** Bytes of this table in the overflow tier. */
    long bytesMapped() {
        return bytesMapped;
    }

    long bytes() {
        return (long) capacity * SLOT_BYTES;
    }

    long handle(int slot) {
        return ~segments[slot >>> shift].getLong(((slot & segmentMask) << 4) + OFFSET_HANDLE);
    }

    long fingerprint(int slot) {
        return segments[slot >>> shift].getLong(((slot & segmentMask) << 4) + OFFSET_FINGERPRINT);
    }

    void put(int slot, long fingerprint, long handle) {
        MemoryRegion segment = segments[slot >>> shift];
        int offset = (slot & segmentMask) << 4;
        segment.putLong(offset + OFFSET_FINGERPRINT, fingerprint);
        segment.putLong(offset + OFFSET_HANDLE, ~handle);
    }

    void putHandle(int slot, long handle) {
        segments[slot >>> shift].putLong(((slot & segmentMask) << 4) + OFFSET_HANDLE, ~handle);
    }

    @Override
    public void close() {
        for (MemoryRegion segment : segments) {
            segment.close();
        }
    }
}
