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
 * The lane-to-lane edge: one lane writes row bytes, one lane reads them.
 *
 * <p>Same protocol as {@link RowInbox} -- fixed cells, publish by release-store, release deferred
 * until the consumer says it is done -- with the multi-producer machinery removed. There is exactly
 * one writer, so claiming a cell is an increment of a cursor only that thread writes rather than a
 * compare-and-set, and the failed-CAS retry loop disappears entirely. On a shuffle that moves every
 * row of a repartitioning query, that difference is paid once per row per hop.
 *
 * <p>Why a separate class rather than a flag on {@code RowInbox}: the single-writer guarantee is the
 * whole optimisation, and a runtime flag would leave the CAS in the code path for the JIT to
 * speculate about. It is also a correctness boundary worth being unable to cross by accident --
 * two threads writing one of these is undefined behaviour, not a slow path.
 *
 * <p><strong>One producer, one consumer.</strong> A pair of lanes gets one of these per direction;
 * a group of N lanes gets N(N-1) of them. That sounds like a lot and is the cheap part -- each is a
 * few hundred kilobytes of cells, and the alternative, a shared queue per consumer, reintroduces
 * exactly the contention the lane model exists to remove.
 */
public final class SpscRowRing implements AutoCloseable {

    /** Returned by {@link #claim()} when every cell is in flight. Backpressure, not an error. */
    public static final long NO_SPACE = -1L;

    private static final VarHandle MARKER = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle PRODUCER;
    private static final VarHandle DRAIN;
    private static final VarHandle RELEASE;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            PRODUCER = l.findVarHandle(SpscRowRing.class, "producerIndex", long.class);
            DRAIN = l.findVarHandle(SpscRowRing.class, "drainIndex", long.class);
            RELEASE = l.findVarHandle(SpscRowRing.class, "releaseIndex", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MemoryRegion cells;
    private final int cellBytes;
    private final int cellCount;
    private final int mask;
    private final long[] markers;

    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile long producerIndex;

    /** The producer's cache of {@link #releaseIndex}, read and written by the producer alone. */
    private long cachedRelease;

    // The producer writes the two fields above and the consumer the two below. Sharing a cache line
    // between them costs a coherence miss per operation (design section 13.6).
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

    private boolean closed;

    public SpscRowRing(MemoryAccess access, int requestedCells, int cellBytes) {
        if (requestedCells < 2) {
            throw new IllegalArgumentException("cell count must be at least 2, got " + requestedCells);
        }
        if (cellBytes <= 0) {
            throw new IllegalArgumentException("cell size must be positive, got " + cellBytes);
        }
        this.cellCount = Integer.highestOneBit(requestedCells) == requestedCells
                ? requestedCells
                : Integer.highestOneBit(requestedCells) << 1;
        this.mask = cellCount - 1;
        this.cellBytes = (cellBytes + 7) & ~7;
        long total = (long) this.cellCount * this.cellBytes;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("an exchange ring of " + total + " bytes exceeds 2 GB");
        }
        this.cells = access.allocate((int) total, 64);
        this.markers = new long[cellCount];
        Arrays.fill(markers, -1L);
    }

    /** Off-heap this ring holds: every cell, whether or not a row is in it. */
    public long bytesAllocated() {
        return (long) cellCount * cellBytes;
    }

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
     * <p>No compare-and-set: the producer owns the cursor. The consumer's release cursor is read
     * only when the cached copy says the ring is full, so the steady state touches no line the other
     * thread writes.
     */
    public long claim() {
        long producer = producerIndex;
        if (producer - cachedRelease >= cellCount) {
            cachedRelease = (long) RELEASE.getVolatile(this);
            if (producer - cachedRelease >= cellCount) {
                return NO_SPACE;
            }
        }
        PRODUCER.setRelease(this, producer + 1);
        return producer;
    }

    public int offsetOf(long sequence) {
        return (int) (sequence & mask) * cellBytes;
    }

    /** Makes a claimed cell visible: everything written into it happens-before the consumer's read. */
    public void publish(long sequence) {
        MARKER.setRelease(markers, (int) (sequence & mask), sequence);
    }

    /**
     * Copies a row in and publishes it.
     *
     * @return {@code false} if the ring is full, which the caller propagates as backpressure rather
     *     than spinning -- a lane that spins on a full exchange ring while holding rows the other
     *     lane is waiting for is how a shuffle deadlocks
     */
    public boolean offer(MemoryRegion source, int offset, int length) {
        if (length > cellBytes) {
            // PF-3/DOCX-20: this named `lane.exchange.cell.size`, which has never been a key.
            // The exchange ring is built from LaneConfig.inboxCellBytes (LaneExchange:85), so the
            // setting that actually widens it is the one named here.
            throw new IllegalArgumentException("row of " + length + " bytes exceeds the exchange cell size of "
                    + cellBytes + "; raise pravaha.lane.inbox.cell-bytes, which sizes this ring too");
        }
        long sequence = claim();
        if (sequence == NO_SPACE) {
            return false;
        }
        cells.copyFrom(offsetOf(sequence), source, offset, length);
        publish(sequence);
        return true;
    }

    /** Takes up to {@code limit} published cell offsets, without freeing them. */
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

    /** Frees every cell drained so far. Called after the rows have been processed, never before. */
    public void release() {
        RELEASE.setRelease(this, drainIndex);
    }

    public int size() {
        long size = (long) PRODUCER.getVolatile(this) - (long) DRAIN.getVolatile(this);
        return (int) Math.max(0, Math.min(cellCount, size));
    }

    public boolean isEmpty() {
        return size() == 0;
    }

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
        return "SpscRowRing[" + size() + "/" + cellCount + " cells x " + cellBytes + "B" + (closed ? ", closed" : "")
                + "]";
    }
}
