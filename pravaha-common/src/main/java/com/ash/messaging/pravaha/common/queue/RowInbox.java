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
package com.ash.messaging.pravaha.common.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * The ingest-to-lane edge: many producers write row bytes into a bounded off-heap ring of
 * fixed-size cells, one lane reads them in place.
 *
 * <p><strong>Why this exists when {@link MpscLongRing} already does.</strong> A ring of handles
 * transfers a <em>reference</em>, and a reference is only as good as the lifetime of what it points
 * at. {@code MpscLongRing.drain} frees its slots the moment it hands the values over, so the
 * producer is free to reuse the memory those handles name while the lane is still reading rows out
 * of it -- and rows are flyweights (design section 8.4), so "still reading" lasts for the whole
 * batch. The ring is correct for transferring values whose memory somebody else owns for long
 * enough; it cannot by itself be the ingest edge. This carries the bytes and defers the release
 * until the lane says it is done with them, which is the same shape as Agrona's
 * {@code ManyToOneRingBuffer} in design section 13.3.
 *
 * <p><strong>Fixed cells.</strong> A claim is one cell, whatever the row's size. That wastes the
 * tail of every cell a small row lands in, and it refuses a row larger than one cell. The
 * alternative -- claiming a variable byte range -- makes the claim a two-word CAS and the wrap case
 * genuinely hard, and it buys nothing until rows vary wildly in size. Cell size is configuration, so
 * a deployment whose rows are 200 bytes does not pay for one whose rows are 8 kB.
 *
 * <p><strong>Protocol.</strong> A producer {@link #claim()}s a sequence, writes its row at
 * {@link #offsetOf(long)} in {@link #region()}, then {@link #publish(long)}es. The lane
 * {@link #drain(long[], int)}s offsets, processes them, then {@link #release()}s -- and only then
 * may those cells be claimed again. Publication is a release-store of the sequence into a per-cell
 * marker and the drain an acquire-load of it, so the row's bytes are visible before its offset is.
 *
 * <p>Many producers, exactly one consumer. Two threads calling {@link #drain} is a bug, not a
 * configuration.
 */
public final class RowInbox implements AutoCloseable {

    /** Returned by {@link #claim()} when every cell is in flight. Backpressure, not an error. */
    public static final long NO_SPACE = -1L;

    private static final VarHandle MARKER = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle PRODUCER;
    private static final VarHandle DRAIN;
    private static final VarHandle RELEASE;
    private static final VarHandle CACHED_RELEASE;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            PRODUCER = l.findVarHandle(RowInbox.class, "producerIndex", long.class);
            DRAIN = l.findVarHandle(RowInbox.class, "drainIndex", long.class);
            RELEASE = l.findVarHandle(RowInbox.class, "releaseIndex", long.class);
            CACHED_RELEASE = l.findVarHandle(RowInbox.class, "cachedReleaseIndex", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MemoryRegion cells;
    private final int cellBytes;
    private final int cellCount;
    private final int mask;

    /** Cell {@code i} holds the sequence published into it, or {@code -1} while it is unpublished. */
    private final long[] markers;

    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long producerIndex;

    // Producers write the claim cursor, the lane writes the other two. Sharing a cache line between
    // them costs a coherence miss on every operation, which is how a lock-free queue quietly runs at
    // a third of its speed (design section 13.6).
    @SuppressWarnings("unused")
    private long p1, p2, p3, p4, p5, p6, p7;

    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long drainIndex;

    @SuppressWarnings("unused")
    private long p8, p9, p10, p11, p12, p13, p14;

    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long releaseIndex;

    @SuppressWarnings("unused")
    private long p15, p16, p17, p18, p19, p20, p21;

    /**
     * A producer-side cache of {@link #releaseIndex}, refreshed only when the cached value says the
     * inbox is full.
     *
     * <p>Without it every single claim reads a cursor the lane thread writes, which is a coherence
     * miss per row on a line the producer never owns -- paid on every row rather than on the rare
     * row that actually finds the inbox full.
     *
     * <p><strong>Safe because it can only ever lag.</strong> A stale value is smaller than the truth,
     * so the worst it can do is report full when a cell has just been freed; the claim is then
     * retried against the real cursor. It can never report space that does not exist. Opaque rather
     * than plain: several producers write it, and a {@code long} is not guaranteed to be written
     * atomically.
     */
    @SuppressWarnings("unused") // accessed via VarHandle
    private long cachedReleaseIndex;

    @SuppressWarnings("unused")
    private long p22, p23, p24, p25, p26, p27, p28;

    private boolean closed;

    /**
     * @param requestedCells rounded up to a power of two, so the sequence-to-cell mapping is a mask
     * @param cellBytes the largest row this inbox can carry; rounded up to a multiple of 8 so every
     *     cell starts 8-byte aligned and no row header straddles a word
     */
    public RowInbox(MemoryAccess access, int requestedCells, int cellBytes) {
        if (requestedCells < 2) {
            throw new IllegalArgumentException("cell count must be at least 2, got " + requestedCells);
        }
        if (cellBytes <= 0) {
            throw new IllegalArgumentException("cell size must be positive, got " + cellBytes);
        }
        this.cellCount = nextPowerOfTwo(requestedCells);
        this.mask = cellCount - 1;
        this.cellBytes = (cellBytes + 7) & ~7;
        long total = (long) this.cellCount * this.cellBytes;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "an inbox of " + this.cellCount + " x " + this.cellBytes + " bytes exceeds 2 GB");
        }
        this.cells = access.allocate((int) total, 64);
        this.markers = new long[cellCount];
        Arrays.fill(markers, -1L);
    }

    private static int nextPowerOfTwo(int value) {
        int result = Integer.highestOneBit(value);
        return result == value ? value : result << 1;
    }

    /**
     * Off-heap bytes this inbox holds: every cell, whether or not a row is in it.
     *
     * <p>Reserved at construction and never released, so this is what an inbox costs a node however
     * quiet the query is. Exposed because a pool total cannot say who allocated it, and W9-7 is the
     * consequence of not being able to ask.
     */
    public long bytesAllocated() {
        return (long) cellCount * cellBytes;
    }

    /** Where the rows live. Valid for the inbox's lifetime; the cells within it are not. */
    public MemoryRegion region() {
        return cells;
    }

    public int cellBytes() {
        return cellBytes;
    }

    public int cellCount() {
        return cellCount;
    }

    /**
     * Claims one cell.
     *
     * @return a sequence to pass to {@link #offsetOf(long)} and {@link #publish(long)}, or
     *     {@link #NO_SPACE} when every cell is claimed or awaiting release. Full is the
     *     backpressure signal that eventually reaches {@code PartitionReader.pause()} (design
     *     section 13.5), not a condition to retry blindly.
     */
    public long claim() {
        long producer;
        do {
            producer = (long) PRODUCER.getVolatile(this);
            if (producer - (long) CACHED_RELEASE.getOpaque(this) >= cellCount) {
                // The cheap check says full. Only now is it worth touching the lane's cursor.
                long release = (long) RELEASE.getVolatile(this);
                CACHED_RELEASE.setOpaque(this, release);
                if (producer - release >= cellCount) {
                    return NO_SPACE;
                }
            }
        } while (!PRODUCER.compareAndSet(this, producer, producer + 1));
        return producer;
    }

    /** Where a claimed sequence's cell begins. */
    public int offsetOf(long sequence) {
        return (int) (sequence & mask) * cellBytes;
    }

    /**
     * Makes a claimed cell visible to the lane.
     *
     * <p>Release-store: everything the producer wrote into the cell happens-before the lane's
     * acquire-load in {@link #drain}. Without it the lane could see the offset before the row.
     */
    public void publish(long sequence) {
        MARKER.setRelease(markers, (int) (sequence & mask), sequence);
    }

    /**
     * Copies a row into a claimed cell and publishes it -- the ordinary ingest move.
     *
     * <p>This is the one copy on the ingest path, and it is unavoidable: the bytes arrive in a
     * plugin's decode buffer whose lifetime the lane cannot depend on.
     *
     * @return {@code false} if the inbox is full, which the caller propagates as backpressure
     * @throws IllegalArgumentException if the row does not fit in a cell, which is a sizing mistake
     *     rather than a runtime condition -- raise {@code pravaha.lane.inbox.cell-bytes}
     */
    public boolean offer(MemoryRegion source, int offset, int length) {
        if (length > cellBytes) {
            throw new IllegalArgumentException("row of " + length + " bytes exceeds the cell size of " + cellBytes
                    + "; raise pravaha.lane.inbox.cell-bytes for this node");
        }
        long sequence = claim();
        if (sequence == NO_SPACE) {
            return false;
        }
        cells.copyFrom(offsetOf(sequence), source, offset, length);
        publish(sequence);
        return true;
    }

    /**
     * Takes up to {@code limit} published cell offsets, without freeing them.
     *
     * <p>Stops at the first cell that is claimed but not yet published rather than skipping it: a
     * producer descheduled mid-write must not cost the lane the ordering of everything behind it.
     *
     * @return how many offsets were written into {@code into}
     */
    public int drain(long[] into, int limit) {
        long from = drainIndex;
        int max = Math.min(limit, into.length);
        int taken = 0;
        while (taken < max) {
            long sequence = from + taken;
            if ((long) MARKER.getAcquire(markers, (int) (sequence & mask)) != sequence) {
                break;
            }
            into[taken++] = offsetOf(sequence);
        }
        if (taken > 0) {
            DRAIN.setRelease(this, from + taken);
        }
        return taken;
    }

    /**
     * Frees every cell drained so far.
     *
     * <p>Called by the lane <em>after</em> it has finished with the batch, because until then the
     * rows it is holding are flyweights into these cells.
     */
    public void release() {
        RELEASE.setRelease(this, drainIndex);
    }

    /**
     * How many cells have ever been claimed.
     *
     * <p>A conservative upper bound on what a producer has handed over: the cell is counted from
     * the moment it is claimed, before its row has been written. A consumer that wants to know
     * "has everything given to me so far been applied" takes this and waits for {@link
     * #drainCursor()} to reach it, and errs towards waiting rather than towards racing.
     */
    public long producerCursor() {
        return (long) PRODUCER.getVolatile(this);
    }

    /** How many cells the lane has taken. Reaching a {@link #producerCursor()} means past it. */
    public long drainCursor() {
        return (long) DRAIN.getVolatile(this);
    }

    /** Published but not yet drained. Exact when quiescent, a hint while producers are running. */
    public int size() {
        long size = (long) PRODUCER.getVolatile(this) - (long) DRAIN.getVolatile(this);
        return (int) Math.max(0, Math.min(cellCount, size));
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Occupancy as a fraction, which is what the backpressure watermarks are expressed in. */
    public double fill() {
        long inFlight = (long) PRODUCER.getVolatile(this) - (long) RELEASE.getVolatile(this);
        return (double) Math.max(0, Math.min(cellCount, inFlight)) / cellCount;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cells.close();
        }
    }

    @Override
    public String toString() {
        return "RowInbox[" + size() + "/" + cellCount + " cells x " + cellBytes + "B" + (closed ? ", closed" : "")
                + "]";
    }
}
