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
    /**
     * One inbox per input, and one input unless the query has a join.
     *
     * <p>A second inbox rather than a tag on each row. A tag would mean the two sides of a join
     * sharing one buffer, so a burst on the left could fill it and starve the right -- and a join
     * starved on one side does not slow down, it stops producing while still reading. Separate
     * buffers make the two sides independently backpressurable, which is the only arrangement that
     * survives one source being faster than the other.
     */
    private final RowInbox[] inboxes;

    /** {@code inboxes[0]}, kept as a field because the single-input path reads it on every batch. */
    private final RowInbox inbox;

    private final RowArena arena;
    private final WaitStrategy waitStrategy;
    private final LaneProcessor processor;
    private final LaneContext context;
    private final LaneExchange exchange;
    private final Thread thread;
    private final LongAdder rejectedOffers = new LongAdder();

    /**
     * Work to run on the lane thread, between batches.
     *
     * <p>The single-writer principle makes a lane's state unreachable from anywhere else, which is
     * exactly what makes it fast and exactly what makes a checkpoint awkward: snapshotting from the
     * coordinator's thread would be a second reader of state the lane is actively mutating. So the
     * coordinator submits the work and the lane runs it, between batches, where nothing is
     * half-updated.
     */
    /**
     * A queued control task and the point in the inbox it must not run ahead of.
     *
     * <p>{@code barrier[i]} is input {@code i}'s claimed-cell count at the moment the task was
     * submitted. The task runs once the lane has drained past every one of them, which is what
     * makes "advance the watermark" mean "advance it over the rows I had already been handed"
     * rather than "over whichever of them happen to have been applied by now".
     */
    private record ControlTask(Runnable task, long[] barrier, long id) {}

    private final java.util.concurrent.ConcurrentLinkedQueue<ControlTask> control =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final java.util.concurrent.atomic.AtomicLong controlRun = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Identities for control tasks, so a waiter can name the one it is waiting for.
     *
     * <p>{@code controlRun} counts completions and cannot do this. A ticket read off a counter is
     * satisfied by whichever task finishes next, and four things submit control tasks to a lane --
     * a watermark advance, a continuous-aggregate publish, a checkpoint and a restore -- with at
     * least two of them routinely in flight at once. Tasks run strictly in submission order, so
     * "the last completed id is at least mine" is exactly "mine has run".
     */
    private final java.util.concurrent.atomic.AtomicLong controlSubmitted =
            new java.util.concurrent.atomic.AtomicLong();

    private volatile long controlCompleted = -1L;

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
    private volatile long exchangedIn;

    /**
     * Builds a lane and everything it owns.
     *
     * @param virtualPartitions the partitions this lane is responsible for (design section 21.1);
     *     may be empty for a lane fed directly, as in a single-lane query
     */
    public Lane(
            int laneId, LaneConfig config, MemoryAccess access, int[] virtualPartitions, LaneProcessorFactory factory) {
        this(laneId, config, access, virtualPartitions, factory, null);
    }

    /**
     * Builds a lane that takes part in an exchange.
     *
     * @param exchange shared with the other lanes of its group. This lane sends on its own row of it
     *     and drains its own column; it touches no other part, which is what keeps the exchange from
     *     becoming the shared thing the lane model exists to avoid.
     */
    public Lane(
            int laneId,
            LaneConfig config,
            MemoryAccess access,
            int[] virtualPartitions,
            LaneProcessorFactory factory,
            LaneExchange exchange) {
        this(laneId, config, access, virtualPartitions, factory, exchange, 1);
    }

    /**
     * Builds a lane with more than one input.
     *
     * @param inputs how many separate inboxes this lane has. One for everything except a join,
     *     which has one per side
     */
    public Lane(
            int laneId,
            LaneConfig config,
            MemoryAccess access,
            int[] virtualPartitions,
            LaneProcessorFactory factory,
            LaneExchange exchange,
            int inputs) {
        this.laneId = laneId;
        this.exchange = exchange;
        this.config = config;
        this.inboxes = new RowInbox[Math.max(1, inputs)];
        for (int i = 0; i < inboxes.length; i++) {
            this.inboxes[i] = new RowInbox(access, config.inboxCells(), config.inboxCellBytes());
        }
        this.inbox = inboxes[0];
        this.arena = new RowArena(access, config.arenaSlabBytes(), config.arenaMaxSlabs());
        this.waitStrategy = config.waitStrategy().strategy();
        this.context = new LaneContext(
                laneId, arena, virtualPartitions.clone(), config, exchange == null ? null : exchange.senderFor(laneId));
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

    /**
     * Queues work to run on this lane's thread between batches.
     *
     * <p>Between batches, never during one: a task that ran mid-batch would see operator state
     * partly updated by a batch that has not finished, which is the difference between a checkpoint
     * and a photograph of a car crash.
     *
     * @return a completion count to wait on; the task has run once {@link #controlTasksRun()}
     *     exceeds the value returned here
     */
    public long submitControlTask(Runnable task) {
        long ticket = controlSubmitted.getAndIncrement();
        long[] barrier = new long[inboxes.length];
        for (int input = 0; input < inboxes.length; input++) {
            barrier[input] = inboxes[input].producerCursor();
        }
        control.add(new ControlTask(task, barrier, ticket));
        return ticket;
    }

    /** How many control tasks this lane has run. */
    public long controlTasksRun() {
        return controlRun.get();
    }

    /**
     * Waits for a submitted control task to have run.
     *
     * @param ticket the value {@link #submitControlTask} returned
     * @return false if the deadline passed first, or the lane died
     */
    public boolean awaitControlTask(long ticket, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (controlCompleted >= ticket) {
                return true;
            }
            if (state == State.FAILED || state == State.STOPPED) {
                return controlCompleted >= ticket;
            }
            LockSupport.parkNanos(50_000L);
        }
        return controlCompleted >= ticket;
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
        return offer(0, source, offset, length);
    }

    /** Copies a row into one named input's inbox. */
    public boolean offer(int input, MemoryRegion source, int offset, int length) {
        boolean accepted = inboxes[input].offer(source, offset, length);
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
        return claim(0);
    }

    /** Claims a cell on one named input. */
    public long claim(int input) {
        long sequence = inboxes[input].claim();
        if (sequence == RowInbox.NO_SPACE) {
            rejectedOffers.increment();
        }
        return sequence;
    }

    /** Where a claimed cell begins in {@link #inboxRegion()}. */
    public int cellOffset(long sequence) {
        return cellOffset(0, sequence);
    }

    /** Where a claimed cell begins in {@link #inboxRegion(int)}. */
    public int cellOffset(int input, long sequence) {
        return inboxes[input].offsetOf(sequence);
    }

    /** Publishes a claimed cell, after the row has been written into it. */
    public void publish(long sequence) {
        publish(0, sequence);
    }

    /** Publishes a claimed cell on one named input. */
    public void publish(int input, long sequence) {
        inboxes[input].publish(sequence);
    }

    /** The region claimed cells live in. */
    public MemoryRegion inboxRegion() {
        return inbox.region();
    }

    /** The region one named input's claimed cells live in. */
    public MemoryRegion inboxRegion(int input) {
        return inboxes[input].region();
    }

    /** How many inputs this lane has: one, or one per join side. */
    public int inputCount() {
        return inboxes.length;
    }

    /** The largest row this lane accepts. */
    public int inboxCellBytes() {
        return inbox.cellBytes();
    }

    /** Cells in this lane's inbox. Its entire input buffer: there is no queue behind it. */
    public int inboxCells() {
        return inbox.cellCount();
    }

    /**
     * Inbox occupancy from 0 to 1.
     *
     * <p>What the backpressure watermarks are expressed in (design section 13.5), and it counts
     * cells that are claimed or drained-but-unreleased as occupied -- because they are: a producer
     * cannot have them, whatever the consumer has already read.
     */
    public double inboxFill() {
        double highest = 0;
        for (RowInbox each : inboxes) {
            highest = Math.max(highest, each.fill());
        }
        return highest;
    }

    /** One named input's occupancy, which is what that input's own pump backpressures on. */
    public double inboxFill(int input) {
        return inboxes[input].fill();
    }

    // ---------------------------------------------------------------- the loop

    private boolean allInboxesEmpty() {
        for (RowInbox each : inboxes) {
            if (!each.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void closeInboxes() {
        for (RowInbox each : inboxes) {
            each.close();
        }
    }

    private void run() {
        long[] batch = new long[config.batchSize()];
        long mark = arena.mark();
        int idle = 0;
        long localRowsIn = 0;
        long localRowsOut = 0;
        long localBatches = 0;
        long localIdle = 0;
        long localExchangedIn = 0;
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
                // Inbound exchange first. Those rows are already inside the engine and another
                // lane is blocked on the room they occupy, so draining them before pulling new work
                // from outside is what keeps a shuffle moving rather than merely correct.
                int exchanged = drainExchange(batch);
                if (exchanged > 0) {
                    localRowsIn += exchanged;
                    localExchangedIn += exchanged;
                }
                // Every input, in turn, one batch each. Round-robin rather than draining input 0
                // until it is empty: a join whose left side is faster would otherwise never reach
                // its right side, and a join that stops reading one side stops producing entirely
                // while still looking busy.
                int count = 0;
                for (int input = 0; input < inboxes.length; input++) {
                    RowInbox from = inboxes[input];
                    int taken = from.drain(batch, batch.length);
                    if (taken == 0) {
                        continue;
                    }
                    count += taken;
                    idle = 0;
                    localRowsIn += taken;
                    localRowsOut += processor.onBatch(input, from.region(), batch, taken);
                    localBatches++;

                    // Only now are this input's cells reusable and the output rows dead. See the
                    // class javadoc: this order is the difference between a correct lane and a
                    // rare, load-dependent corruption. Released per input, before the next one is
                    // drained, because the batch array is about to be overwritten.
                    from.release();
                }
                // After the drain, not before it. Control tasks used to run at the top of the loop,
                // which let a watermark advance close a window over rows still sitting in the inbox
                // -- a dense feed published a partial second as if it were final, non-deterministically
                // and without anything failing. Running here, behind a barrier, is the ordering the
                // rest of the engine already assumes.
                runControlTasks(false);
                if (count == 0 && exchanged == 0) {
                    inBatch = false;
                    if (!running) {
                        // Drained, so nothing is still coming and every barrier is moot. Anything
                        // still queued runs now rather than leaving its submitter waiting out a
                        // timeout on a lane that has stopped.
                        runControlTasks(true);
                        break; // stop only once the inboxes are drained, so shutdown loses nothing
                    }
                    localIdle++;
                    idleCycles = localIdle;
                    // Before parking, not after: an operator holding a finished result should
                    // release it now rather than after the wait it is about to take.
                    processor.onIdle();
                    waitStrategy.idle(++idle);
                    continue;
                }
                if (count == 0) {
                    // Exchange rows were processed this iteration; go back for more rather than
                    // treating an empty inbox as idle.
                    rowsIn = localRowsIn;
                    rowsOut = localRowsOut;
                    batches = localBatches;
                    exchangedIn = localExchangedIn;
                    continue;
                }
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
            exchangedIn = localExchangedIn;
            closeQuietly();
        }
    }

    /**
     * Drains every inbound exchange ring once and feeds the rows to the processor.
     *
     * <p>One pass per iteration rather than draining each ring dry: a lane that emptied one peer
     * completely before looking at the next would starve the others under load, and starvation in a
     * shuffle shows up as one slow partition rather than as an error.
     *
     * @return how many rows were processed
     */
    private int drainExchange(long[] batch) {
        if (exchange == null) {
            return 0;
        }
        int total = 0;
        for (int from = 0; from < exchange.laneCount(); from++) {
            if (from == laneId) {
                continue;
            }
            com.ash.messaging.pravaha.common.queue.SpscRowRing ring = exchange.ring(from, laneId);
            int count = ring.drain(batch, batch.length);
            if (count == 0) {
                continue;
            }
            inBatch = true;
            processor.onBatch(ring.region(), batch, count);
            // After processing, exactly as with the inbox: the rows were flyweights into those cells.
            ring.release();
            arena.resetTo(arena.mark());
            total += count;
        }
        return total;
    }

    /**
     * Runs whatever the control plane has queued whose rows have arrived. On this thread, between
     * batches, and never ahead of a row that was handed over before the task was.
     *
     * <p>Stops at the first task whose barrier is unmet rather than skipping it, because control
     * tasks are ordered with respect to each other: a checkpoint queued behind a watermark advance
     * must not photograph state the advance has not been applied to.
     *
     * @param force run every queued task regardless of its barrier. For shutdown, where the
     *     inboxes are drained and there is no producer left to wait for -- a task deferred forever
     *     is a coordinator waiting forever.
     */
    private void runControlTasks(boolean force) {
        ControlTask queued;
        while ((queued = control.peek()) != null) {
            if (!force && !barrierReached(queued.barrier())) {
                return;
            }
            control.poll();
            Runnable task = queued.task();
            try {
                task.run();
            } catch (Throwable t) {
                // Before the ticket is released, not after. The waiter's next act is checkHealth()
                // -- QueryExecution.restore is exactly that shape -- and leaving the recording to
                // run()'s outer catch means the exception must first unwind the control loop and
                // the batch loop. A waiter scheduled inside that window sees a lane that has failed
                // and does not say so, and restore returns success over a snapshot it refused.
                // The catch runs before the finally, so the failure is visible before the ticket
                // moves.
                failure = t;
                state = State.FAILED;
                throw t;
            } finally {
                // Released even when the task threw: a coordinator waiting on it must not wait
                // forever because the work failed, and a failure it cannot see is worse than one
                // it can. It finds out by calling checkHealth() once the wait returns.
                controlCompleted = queued.id();
                controlRun.incrementAndGet();
            }
        }
    }

    /** Whether every input has been drained past where it stood when the task was submitted. */
    private boolean barrierReached(long[] barrier) {
        for (int input = 0; input < inboxes.length; input++) {
            if (inboxes[input].drainCursor() < barrier[input]) {
                return false;
            }
        }
        return true;
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
            if (allInboxesEmpty() && !inBatch) {
                return true;
            }
            LockSupport.parkNanos(50_000L);
        }
        return allInboxesEmpty() && !inBatch;
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
                inboxFill(),
                exchangedIn);
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
            closeInboxes();
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
        closeInboxes();
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
