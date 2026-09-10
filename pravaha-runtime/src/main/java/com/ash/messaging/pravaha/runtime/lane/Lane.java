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
package com.ash.messaging.pravaha.runtime.lane;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.queue.RowInbox;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * One thread, one inbox, one arena, one processor, one loop.
 *
 * <p>This is the unit the whole performance story is expressed in: the gate is 1.2 M records per
 * second <em>per lane</em> and 90 % scaling to eight of them (design section 5.2), and both halves
 * of that are properties of this class. The first is what the loop costs; the second is what it
 * shares, which is nothing.
 *
 * <pre>{@code
 * while (running || inbox not empty) {
 *     n = inbox.drain(batch, batchSize);
 *     if (n == 0) { waitStrategy.idle(++idle); continue; }
 *     processor.onBatch(inbox.region(), batch, n);
 *     inbox.release();          // the cells were live flyweights until this line
 *     arena.resetTo(mark);      // the batch's output, reclaimed in one assignment
 * }
 * }</pre>
 *
 * <p><strong>Two orderings in that loop are load-bearing.</strong> The release comes after the
 * processor, not after the drain, because the rows the processor is reading live in those cells;
 * releasing early hands a producer permission to overwrite a row mid-batch, and the resulting
 * corruption would be rare, load-dependent and close to unattributable. The arena reset comes after
 * the processor for the same reason on the output side.
 *
 * <p><strong>"Pinned" is a deployment property, not a Java one.</strong> Each lane gets a dedicated
 * platform thread -- never a virtual one, because the hot loop is CPU-bound and a virtual thread
 * would trade a stable carrier and warm caches for a scheduler that is optimised for the opposite
 * workload. Actual core affinity needs a native call the JDK does not expose; it is left to
 * {@code taskset}, {@code numactl} or an affinity library in the host, and {@link #thread()} is
 * exposed so a deployment can apply one. Claiming the JVM pins threads would be untrue.
 *
 * <p><strong>A processor failure stops this lane and only this lane.</strong> The throwable is kept
 * and the lane moves to {@link State#FAILED}; sibling lanes carry on, because a lane that shares
 * nothing also fails alone. Routing a poison row to a dead-letter queue instead of failing the lane
 * needs the DLQ, which is Wave 4 (design section 15.6).
 */
public final class Lane implements AutoCloseable {

    /** Where a lane is in its life. Monotonic except that {@link State#FAILED} may be entered from any state. */
    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    private final int laneId;
    private final LaneConfig config;
    private final RowInbox inbox;
    private final RowArena arena;
    private final WaitStrategy waitStrategy;
    private final LaneProcessor processor;
    private final LaneContext context;
    private final Thread thread;
    private final LongAdder rejectedOffers = new LongAdder();

    private volatile boolean running;
    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean inBatch;

    // Written only by the lane thread, once per batch rather than once per row, and read by whoever
    // asks for metrics. A volatile store per batch is a rounding error; one per row would not be.
    private volatile long rowsIn;
    private volatile long rowsOut;
    private volatile long batches;
    private volatile long idleCycles;

    /**
     * Builds a lane and everything it owns.
     *
     * @param virtualPartitions the partitions this lane is responsible for (design section 21.1);
     *     may be empty for a lane fed directly, as in a single-lane query
     */
    public Lane(
            int laneId, LaneConfig config, MemoryAccess access, int[] virtualPartitions, LaneProcessorFactory factory) {
        this.laneId = laneId;
        this.config = config;
        this.inbox = new RowInbox(access, config.inboxCells(), config.inboxCellBytes());
        this.arena = new RowArena(access, config.arenaSlabBytes(), config.arenaMaxSlabs());
        this.waitStrategy = config.waitStrategy().strategy();
        this.context = new LaneContext(laneId, arena, virtualPartitions.clone(), config);
        // Built here rather than on the lane thread so that a processor that cannot be built fails
        // the caller synchronously, with a stack trace pointing at the registration that caused it.
        // Thread.start() then publishes it safely to the lane thread.
        this.processor = factory.create(context);
        this.thread = new Thread(this::run, config.threadNamePrefix() + "-" + laneId);
        this.thread.setDaemon(config.daemonThreads());
    }

    /** A lane with no partition assignment, which is what a single-lane query and most tests want. */
    public Lane(int laneId, LaneConfig config, MemoryAccess access, LaneProcessorFactory factory) {
        this(laneId, config, access, new int[0], factory);
    }

    public int laneId() {
        return laneId;
    }

    public State state() {
        return state;
    }

    public LaneContext context() {
        return context;
    }

    /**
     * The lane's thread, for a host that wants to set affinity or priority on it.
     *
     * <p>Deliberately not a place to call {@code interrupt()}: the loop stops on a flag, and
     * interrupting it mid-batch would leave the arena and the inbox mid-flight.
     */
    public Thread thread() {
        return thread;
    }

    /** Starts the loop. Idempotent in the sense that starting twice is a bug and says so. */
    public void start() {
        if (state != State.NEW) {
            throw new IllegalStateException("lane " + laneId + " has already been started; it is " + state);
        }
        running = true;
        state = State.RUNNING;
        thread.start();
    }

    // ---------------------------------------------------------------- producer side

    /**
     * Copies a row into the lane's inbox -- the ingest thread's move.
     *
     * @return {@code false} when the inbox is full. That is backpressure and the caller's cue to
     *     pause its source (design section 13.5), not a reason to spin.
     */
    public boolean offer(MemoryRegion source, int offset, int length) {
        boolean accepted = inbox.offer(source, offset, length);
        if (!accepted) {
            rejectedOffers.increment();
        }
        return accepted;
    }

    /**
     * Claims a cell for a producer that would rather encode straight into it than encode and copy.
     *
     * @return a sequence for {@link #cellOffset(long)} and {@link #publish(long)}, or
     *     {@link RowInbox#NO_SPACE}
     */
    public long claim() {
        long sequence = inbox.claim();
        if (sequence == RowInbox.NO_SPACE) {
            rejectedOffers.increment();
        }
        return sequence;
    }

    /** Where a claimed cell begins in {@link #inboxRegion()}. */
    public int cellOffset(long sequence) {
        return inbox.offsetOf(sequence);
    }

    /** Publishes a claimed cell, after the row has been written into it. */
    public void publish(long sequence) {
        inbox.publish(sequence);
    }

    /** The region claimed cells live in. */
    public MemoryRegion inboxRegion() {
        return inbox.region();
    }

    /** The largest row this lane accepts. */
    public int inboxCellBytes() {
        return inbox.cellBytes();
    }

    // ---------------------------------------------------------------- the loop

    private void run() {
        long[] batch = new long[config.batchSize()];
        long mark = arena.mark();
        int idle = 0;
        long localRowsIn = 0;
        long localRowsOut = 0;
        long localBatches = 0;
        long localIdle = 0;
        try {
            while (true) {
                // Raised before the drain, not after it. An observer that sees an empty inbox and
                // a lane not in a batch concludes the lane is quiescent; setting the flag after the
                // drain would leave a window where rows had been taken and nobody was accountable
                // for them. Written only on the transition, so an idle spin does not keep writing
                // to a line other threads read.
                if (!inBatch) {
                    inBatch = true;
                }
                int count = inbox.drain(batch, batch.length);
                if (count == 0) {
                    inBatch = false;
                    if (!running) {
                        break; // stop only once the inbox is drained, so shutdown loses nothing
                    }
                    localIdle++;
                    idleCycles = localIdle;
                    waitStrategy.idle(++idle);
                    continue;
                }
                idle = 0;
                localRowsIn += count;
                localRowsOut += processor.onBatch(inbox.region(), batch, count);
                localBatches++;

                // Only now are the input cells reusable and the output rows dead. See the class
                // javadoc: this order is the difference between a correct lane and a rare, load
                // -dependent corruption.
                inbox.release();
                arena.resetTo(mark);

                rowsIn = localRowsIn;
                rowsOut = localRowsOut;
                batches = localBatches;
            }
            state = State.STOPPED;
        } catch (Throwable t) {
            failure = t;
            state = State.FAILED;
            inBatch = false;
        } finally {
            running = false;
            rowsIn = localRowsIn;
            rowsOut = localRowsOut;
            batches = localBatches;
            idleCycles = localIdle;
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            processor.close();
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
                state = State.FAILED;
            }
        } finally {
            arena.close();
        }
    }

    // ---------------------------------------------------------------- lifecycle and observation

    /**
     * Waits until the lane has nothing left to do.
     *
     * <p>What a test needs instead of a sleep, and what a checkpoint barrier will need for real: a
     * lane is quiescent when its inbox holds nothing claimed or published and it is not mid-batch.
     *
     * @return {@code false} if the timeout expired first, or the lane failed
     */
    public boolean awaitQuiescent(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (state == State.FAILED) {
                return false;
            }
            if (inbox.isEmpty() && !inBatch) {
                return true;
            }
            LockSupport.parkNanos(50_000L);
        }
        return inbox.isEmpty() && !inBatch;
    }

    /** The failure that stopped the lane, if one did. */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Rethrows a lane failure to the caller's thread.
     *
     * <p>A lane dies on its own thread, where nothing is watching; without this the query above it
     * would simply stop producing and never say why.
     */
    public void checkHealth() {
        Throwable t = failure;
        if (t != null) {
            throw new PravahaException(
                    RuntimeErrors.LANE_FAILED, "lane " + laneId + " stopped after a failure: " + t, t);
        }
    }

    public LaneMetrics metrics() {
        return new LaneMetrics(
                laneId,
                rowsIn,
                rowsOut,
                batches,
                idleCycles,
                rejectedOffers.sum(),
                arena.highWaterMark(),
                inbox.fill());
    }

    /**
     * Stops the lane, letting it finish what it has already been given.
     *
     * <p>Blocks for at most {@code shutdownTimeout}. A lane still running after that is not killed:
     * a thread stuck in a processor holds the arena and the inbox, and tearing those out from under
     * it turns a hang into a crash. It is reported instead.
     */
    @Override
    public void close() {
        if (state == State.NEW) {
            // Never started, so nothing owns these but this call.
            processorCloseUnstarted();
            arena.close();
            inbox.close();
            state = State.STOPPED;
            return;
        }
        running = false;
        if (state == State.RUNNING) {
            state = State.STOPPING;
        }
        try {
            thread.join(Math.max(1L, config.shutdownTimeout().toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            throw new PravahaException(
                    RuntimeErrors.LANE_FAILED,
                    "lane " + laneId + " did not stop within " + config.shutdownTimeout()
                            + "; its thread is still in the processor, and the inbox and arena it owns cannot "
                            + "be released while it is. Raise the shutdown timeout or find the stall.");
        }
        if (state != State.FAILED) {
            state = State.STOPPED;
        }
        // The lane thread released the arena and the processor; the inbox outlives it because
        // producers may still have been claiming cells right up to the join.
        inbox.close();
    }

    private void processorCloseUnstarted() {
        try {
            processor.close();
        } catch (Exception e) {
            throw new PravahaException(
                    RuntimeErrors.LANE_FAILED, "lane " + laneId + "'s processor failed to close: " + e, e);
        }
    }

    @Override
    public String toString() {
        return "Lane[" + laneId + ", " + state + ", in=" + rowsIn + ", out=" + rowsOut + ", batches=" + batches + "]";
    }
}
