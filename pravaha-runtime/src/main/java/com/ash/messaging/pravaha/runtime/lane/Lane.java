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

import org.jspecify.annotations.Nullable;

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

    /**
     * The processor this lane drives.
     *
     * <p>Exposed for W9-8: a query hosted on a shared lane closes by dropping its pipeline from the
     * lane's {@link LaneMultiplexer}, which means the caller has to be able to reach it. Read-only
     * and reference-only — nothing outside the lane thread may call into the processor.
     */
    public LaneProcessor processor() {
        return processor;
    }

    private final LaneContext context;
    private final @Nullable LaneExchange exchange;
    private final Thread thread;
    private final LongAdder rejectedOffers = new LongAdder();

    /**
     * How long producers into this lane have spent unable to place a row, and whose they were.
     *
     * <p>Written by the producers, at the two ends of an episode, and never by the lane thread --
     * so nothing here is on the drain loop at all. See {@link LaneBackpressure}.
     */
    private final LaneBackpressure backpressure = new LaneBackpressure();

    /**
     * A queued control task and the point in the inbox it must not run ahead of.
     *
     * <p>{@code barrier[i]} is input {@code i}'s claimed-cell count at the moment the task was
     * submitted -- a marker in the stream, addressed by position rather than occupying a cell. The
     * task runs when the lane reaches it: not before, which is what makes "advance the watermark"
     * mean "advance it over the rows I had already been handed"; and not after, which is what makes
     * a checkpoint's snapshot cover exactly the rows its recorded source offset excludes.
     */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record ControlTask(Runnable task, long[] barrier, long id, boolean cut) {}

    /**
     * How many queued tasks are cuts, so the hot path can skip scanning for one when there are none.
     *
     * <p>W9-10. Clamping the batch is what makes a marker a barrier, and it costs a batch boundary
     * every time. A checkpoint needs that; a watermark does not, and the difference is the whole of
     * this counter. Under {@code LaneMultiplexer} a lane carries hundreds of pipelines, each
     * advancing a watermark about once a second -- so paying a boundary per advance would cut the
     * lane's batches short hundreds of times a second, which is the reason multiplexing was blocked.
     */
    private final java.util.concurrent.atomic.AtomicInteger pendingCuts =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Work to run on the lane thread, between batches.
     *
     * <p>The single-writer principle makes a lane's state unreachable from anywhere else, which is
     * exactly what makes it fast and exactly what makes a checkpoint awkward: snapshotting from the
     * coordinator's thread would be a second reader of state the lane is actively mutating. So the
     * coordinator submits the work and the lane runs it, between batches, where nothing is
     * half-updated.
     */
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

    /**
     * Odd while a submitter is choosing its marker, even the rest of the time.
     *
     * <p>The lane reads this either side of working out how far it may drain, and starts again if it
     * changed. That is the whole of the ordering between a submit and a batch, and it is a seqlock
     * rather than a mutual exclusion because of which side pays: the lane crosses it on every
     * iteration of the hottest loop in the engine and a submitter crosses it on a watermark tick or
     * a checkpoint. Two volatile reads for the one that goes round a million times a second; the
     * monitor for the one that goes round once a second.
     *
     * <p>Written only inside {@code synchronized (control)}, so the increments cannot be lost even
     * though {@code volatile} does not make {@code ++} atomic.
     */
    private volatile long submitSequence;

    private volatile boolean running;
    private volatile State state = State.NEW;
    private volatile @Nullable Throwable failure;
    private volatile boolean inBatch;

    /** Whether a thread of this lane's own runs the loop, or a shared runner steps it. */
    private volatile boolean hosted;

    /** Set by each step so a runner can tell an idle round from a busy one. */
    private volatile boolean workedLastStep;

    /**
     * Set once a hosted lane's final step has run and released what it owned.
     *
     * <p>What {@link #close()} waits on where there is no thread to join. Releasing the arena and
     * the inbox while a runner is still inside a step for this lane is a use-after-free, and that
     * the runner thread is alive says nothing -- it is alive for every other lane it carries.
     */
    private volatile boolean drained;

    // Loop state. Fields rather than locals of run(), so one iteration can be a method call and a
    // lane can be stepped by a thread it does not own. Touched only by the thread currently pumping
    // this lane, which is the confinement the whole class rests on; the volatile counters above are
    // the copies other threads are allowed to read.
    @SuppressWarnings("NullAway.Init") // allocated when the lane starts, before its loop reads it
    private long[] batch;

    @SuppressWarnings("NullAway.Init") // allocated when the lane starts, before its loop reads it
    private long[] room;

    private long mark;
    private int idle;
    private long localRowsIn;
    private long localRowsOut;
    private long localBatches;
    private long localIdle;
    private long localExchangedIn;

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
            @Nullable LaneExchange exchange) {
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
            @Nullable LaneExchange exchange,
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
     * How many cells a producer has ever claimed on one input.
     *
     * <p>The coordinate a barrier is expressed in. Read by whoever is about to cut the stream --
     * {@code QueryExecution.checkpoint} reads it with ingest frozen, so the number it gets names a
     * point no row can be inserted before.
     */
    public long producerCursor(int input) {
        return inboxes[input].producerCursor();
    }

    /**
     * Queues work to run on this lane's thread, at the exact point in the stream it was submitted
     * at.
     *
     * <p>Between batches, never during one: a task that ran mid-batch would see operator state
     * partly updated by a batch that has not finished, which is the difference between a checkpoint
     * and a photograph of a car crash.
     *
     * <p><strong>This is the barrier.</strong> The cursors read here are a marker placed in the
     * inbox, at the position the producers had reached -- addressed by position rather than
     * occupying a cell, because a cell would need a tag byte on every row to tell the two apart.
     * The run loop cuts its batch at a marker that is queued when the batch begins, so the task
     * sees the state at that position and not a batch's worth beyond it. "Not before the marker" is
     * not enough on its own: a lane that overshoots and then snapshots puts rows into the
     * checkpoint that the recorded source offset says will be replayed, and replaying them is a
     * double count nothing downstream would catch.
     *
     * <p>That alone leaves one window -- a task submitted while a batch is already in flight was
     * not there to be seen when the batch was sized, so the lane can pass it. {@code
     * QueryExecution.checkpoint} closes it from the other end by freezing ingest before it submits
     * anything: with the producers held between rows the marker sits at the end of the stream, so
     * there is nothing past it to overshoot into. The two together are what make the cut exact.
     *
     * <p>The cut is this lane's own coordinate and means nothing to another lane. What makes a set
     * of them <em>one</em> cut is again the freeze: no row reaches any lane between the first
     * submission and the last, and the source offsets recorded under the same freeze name that same
     * instant.
     *
     * @return a ticket naming this task, to pass to {@link #awaitControlTask}
     */
    public long submitControlTask(Runnable task) {
        return submitControlTask(task, true);
    }

    /**
     * Submits a task that must run no earlier than this point in the stream, but need not stop the
     * batch here.
     *
     * <p>W9-10, and the distinction the lane was missing. <strong>A checkpoint is a cut; a watermark
     * is a level.</strong> A checkpoint that runs late photographs rows its own recorded source
     * offset says will be replayed, so its batch must stop exactly at the marker. A watermark that
     * is applied late merely closes a window one batch later than it could have — it is a monotonic
     * level, and applying it further along the stream is never wrong, only less prompt.
     *
     * <p>So a level keeps the half of the barrier that is load-bearing — queue order, which still
     * puts it after every row handed over before it — and drops the half that costs a batch
     * boundary. That matters only under {@link LaneMultiplexer}, where one lane carries hundreds of
     * pipelines: at a tick a second each, clamping per advance would cut the lane's batches short
     * hundreds of times a second, and that cost is why multiplexing has never been switched on.
     *
     * @return a ticket naming this task, to pass to {@link #awaitControlTask}
     */
    public long submitLevelTask(Runnable task) {
        return submitControlTask(task, false);
    }

    private long submitControlTask(Runnable task, boolean cutsTheBatch) {
        // Reading the cursors, allocating the id and queueing under one lock, because all three
        // have to agree. A ticket means "the last completed id is at least mine", which is only
        // "mine has run" while queue order is id order -- and two threads doing getAndIncrement
        // then add can interleave so that id 1 is queued ahead of id 0. Completion would then run
        // backwards, and a waiter for id 1 arriving after both had run would read controlCompleted
        // as 0 and wait out its timeout on a task that had finished. Holding the cursor reads here
        // too keeps the queue's cuts non-decreasing, which is what lets the run loop honour them by
        // looking only at the task at the head. Submits happen per tick and per checkpoint, never
        // per row, so the lock costs nothing that matters.
        synchronized (control) {
            submitSequence++; // odd: a marker is being chosen
            try {
                long[] cut = new long[inboxes.length];
                for (int input = 0; input < inboxes.length; input++) {
                    cut[input] = inboxes[input].producerCursor();
                }
                long ticket = controlSubmitted.getAndIncrement();
                if (cutsTheBatch) {
                    // Incremented before the task is visible to the run loop, so the loop never sees
                    // a queued cut while the counter still says there are none.
                    pendingCuts.incrementAndGet();
                }
                control.add(new ControlTask(task, cut, ticket, cutsTheBatch));
                return ticket;
            } finally {
                submitSequence++; // even: chosen and queued
            }
        }
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

    /**
     * Starts this lane on a thread it does not own, driven by {@code runner}.
     *
     * <p>ADR-027's multiplexing. Confinement is unchanged: one thread still steps this lane and
     * nothing else steps it, which is all {@link #pumpOnce()} and everything under it require. What
     * changes is that the thread is shared, so a node's thread count follows its cores rather than
     * its queries.
     */
    public void startOn(LaneRunner runner) {
        if (state != State.NEW) {
            throw new IllegalStateException("lane " + laneId + " has already been started; it is " + state);
        }
        hosted = true;
        running = true;
        state = State.RUNNING;
        batch = new long[config.batchSize()];
        room = new long[inboxes.length];
        mark = arena.mark();
        runner.host(this);
    }

    /**
     * Tells a hosted lane to finish, called by its runner as that runner shuts down.
     *
     * <p>Not {@link #close()}: this does not wait and does not release anything. It asks the loop to
     * reach its shutdown branch, which is where a hosted lane lets go of its arena and inbox -- on
     * the runner's thread, which is the only thread allowed to.
     */
    void stopForRunnerShutdown() {
        running = false;
        if (state == State.RUNNING) {
            state = State.STOPPING;
        }
    }

    /** Whether the last {@link #pumpOnce()} moved anything, so a runner knows whether to park. */
    boolean didWorkLastStep() {
        return workedLastStep;
    }

    /**
     * Records a failure raised by a step, for a runner that caught it on this lane's behalf.
     *
     * <p>Same two fields, same order as {@code run()}'s own catch, because everything that asks
     * whether a query is alive reads them and must not be able to tell which path set them.
     */
    void recordFailure(Throwable t) {
        failure = t;
        state = State.FAILED;
        inBatch = false;
        running = false;
        closeQuietly();
        // Last, and it must be here. A failed lane is dropped by its runner and never stepped
        // again, so the shutdown branch that normally sets this is unreachable for it -- and
        // close() would then wait out its whole timeout for a step that can never come, reporting
        // a stall on a lane that had already finished failing. closeQuietly() above has released
        // what it owned, so by this line there is genuinely nothing left to wait for.
        drained = true;
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

    /**
     * Cells published into this lane and not yet drained, on whichever input holds the most.
     *
     * <p>The number {@link #inboxFill()} is a ratio of, in cells. A fill of 0.98 says nothing about
     * how many rows are queued until you also know the inbox is 64 cells or 65,536 of them, and an
     * operator reading a dashboard has only one of those to hand.
     */
    public int inboxDepth() {
        int deepest = 0;
        for (RowInbox each : inboxes) {
            deepest = Math.max(deepest, each.size());
        }
        return deepest;
    }

    /** One named input's depth, in cells. */
    public int inboxDepth(int input) {
        return inboxes[input].size();
    }

    /**
     * This lane's backpressure: how often and how long its writers waited for room, and whose
     * writers they were.
     *
     * <p>Handed to the pumps feeding this lane, which are the only things that know they are
     * waiting -- the lane itself sees an empty inbox and cannot tell "nothing has arrived" from
     * "the producer is parked outside".
     */
    public LaneBackpressure backpressure() {
        return backpressure;
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
        batch = new long[config.batchSize()];
        room = new long[inboxes.length];
        mark = arena.mark();
        try {
            while (pumpOnce()) {
                // Each iteration is one step of this lane and nothing else's. Extracted so a lane
                // can be driven by a thread it does not own -- ADR-027's multiplexing -- without
                // the loop itself changing, because the loop is where every ordering rule in this
                // class lives and rewriting it to share a thread would rewrite those too.
            }
            state = State.STOPPED;
        } catch (Throwable t) {
            failure = t;
            state = State.FAILED;
            inBatch = false;
        } finally {
            running = false;
            publishCounters();
            closeQuietly();
        }
    }

    /**
     * One iteration: drain the exchange, take a batch from each input, run any control task whose
     * marker has been reached.
     *
     * <p>Returns whether this lane wants another iteration. {@code false} means it has stopped and
     * its inboxes are drained, which is the only condition under which a lane may be abandoned --
     * anything still queued has already been run by then.
     *
     * <p>Package-private and stateless across calls except through this lane's own fields, so a
     * runner may call it for several lanes in turn on one thread. What it must never be is called
     * for one lane from two threads: every ordering rule in this class assumes a single caller, and
     * the arena and operator state are confined to it.
     */
    boolean pumpOnce() {
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
        //
        // How far this iteration may go, per input, worked out before a single row is
        // taken. See the note on `room`.
        takeableRows(room);
        if (workedLastStep && running && worthWaiting()) {
            if (!hosted) {
                coalesce();
            } else if (deferrals < COALESCE_SPINS) {
                // A hosted lane does not spin on its runner's time: it gives the step back, after
                // a pause, and looks again on its next turn. Still "worked", so the runner does not
                // park while a producer is writing.
                deferrals++;
                pause();
                return true;
            }
        }
        deferrals = 0;
        int count = 0;
        for (int input = 0; input < inboxes.length; input++) {
            RowInbox from = inboxes[input];
            if (room[input] <= 0) {
                continue; // at a marker on this input, or nothing new on it
            }
            int taken = from.drain(batch, (int) Math.min(batch.length, room[input]));
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
        int controlRan = runControlTasks(false);
        if (count == 0 && exchanged == 0) {
            if (controlRan > 0) {
                // A marker was reached and its task has run, which is why this iteration
                // took no rows: the batch was cut short at it. Going round again rather
                // than parking, because the clamp has just been lifted and the rows behind
                // the marker are already sitting in the inbox. Parking here would put a
                // wait-strategy backoff between every barrier and the rows after it.
                return true;
            }
            inBatch = false;
            workedLastStep = false;
            if (!running) {
                // Drained, so nothing is still coming and every barrier is moot. Anything
                // still queued runs now rather than leaving its submitter waiting out a
                // timeout on a lane that has stopped.
                runControlTasks(true);
                if (hosted) {
                    // No run() around this step to do it. A hosted lane finishes here, and close()
                    // waits on `drained` before releasing anything this step still had in hand.
                    if (state != State.FAILED) {
                        state = State.STOPPED;
                    }
                    publishCounters();
                    closeQuietly();
                    drained = true;
                }
                return false; // stop only once the inboxes are drained, so shutdown loses nothing
            }
            localIdle++;
            idleCycles = localIdle;
            // Before parking, not after: an operator holding a finished result should
            // release it now rather than after the wait it is about to take.
            processor.onIdle();
            if (!hosted) {
                // A hosted lane does not park: its runner parks once for every lane it carries. A
                // thousand idle lanes parking individually would wake a thousand times to each
                // discover that it is still idle.
                waitStrategy.idle(++idle);
            }
            return true;
        }
        if (count == 0) {
            // Exchange rows were processed this iteration; go back for more rather than
            // treating an empty inbox as idle.
            rowsIn = localRowsIn;
            rowsOut = localRowsOut;
            batches = localBatches;
            exchangedIn = localExchangedIn;
            return true;
        }
        arena.resetTo(mark);

        rowsIn = localRowsIn;
        rowsOut = localRowsOut;
        batches = localBatches;
        workedLastStep = true;
        return true;
    }

    /**
     * How many spins a lane that has caught up with a busy producer waits for a batch worth taking.
     *
     * <p>Gate P2, 2026-09-26. A lane that processes rows faster than its producer writes them drains
     * whatever has arrived -- six or seven rows -- and pays the whole per-batch cost for them: the
     * frontier reads, the release, the output's end of batch, the arena reset. Worse, it reads each
     * cell while the producer is still writing the cells beside it, so the cache lines of the inbox
     * move between the two cores row by row. Measured on Profile A, the generated pipeline -- more
     * than twice as fast as the interpreted one in isolation -- ran end to end at 0.58x of it, at 7
     * rows a batch against the interpreter's 120 to 340.
     *
     * <p>So a lane whose last step worked, and which finds less than an eighth of a batch waiting,
     * spins briefly for more before draining. Bounded at a few microseconds, and only while a
     * producer is demonstrably writing: a lane whose last step found nothing does not wait, so a
     * lone row on a quiet stream is taken at once. Not while a barrier is pending, whose task
     * should run without delay, nor once the lane is stopping. A hosted lane, whose runner has
     * other lanes to step, gives its step back instead of spinning, the same number of times.
     *
     * <p>It looks at the producer's frontier only every {@link #COALESCE_PAUSES} pauses. Looking on
     * every pause was measured to cost the producer more than the batching saved: the frontier is
     * a line the producer writes on every row, and a consumer polling it takes that line away from
     * the producer each time.
     */
    static final int COALESCE_SPINS = 32;

    /** Pauses between two looks at the producer frontier while waiting. */
    static final int COALESCE_PAUSES = 16;

    /** The fraction of a batch below which a caught-up lane waits for more. */
    static final int COALESCE_FRACTION = 8;

    /** Times a hosted lane has given its step back since it last drained. */
    private int deferrals;

    private boolean worthWaiting() {
        return pendingCuts.get() == 0 && takeable() < Math.max(1, batch.length / COALESCE_FRACTION);
    }

    private void coalesce() {
        for (int spin = 0; spin < COALESCE_SPINS && worthWaiting(); spin++) {
            pause();
            takeableRows(room);
        }
    }

    private static void pause() {
        for (int pause = 0; pause < COALESCE_PAUSES; pause++) {
            Thread.onSpinWait();
        }
    }

    private long takeable() {
        long total = 0;
        for (long rows : room) {
            total += Math.max(0, rows);
        }
        return total;
    }

    /** Publishes this lane's counters, which are read by other threads and written only here. */
    private void publishCounters() {
        rowsIn = localRowsIn;
        rowsOut = localRowsOut;
        batches = localBatches;
        idleCycles = localIdle;
        exchangedIn = localExchangedIn;
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
     * How many rows this iteration may take from each input, which is what makes a marker a barrier.
     *
     * <p>Two bounds, and both are needed.
     *
     * <p>The first is the marker already queued. A task must not run over rows that arrived after
     * the position it names, so the batch is cut short at that position rather than finished and
     * then followed by the task. Queue cuts are non-decreasing -- {@link #submitControlTask} reads
     * the cursors under the same monitor that orders the queue -- so the task at the head is the
     * only one that can bind.
     *
     * <p>The second is the producer frontier, and it is the subtler half: it bounds the batch
     * against a marker that <em>does not exist yet</em>. A submitter reads the cursors after this
     * method has read them, so whatever marker it chooses sits at or beyond this frontier, and a
     * batch that stops at the frontier cannot have passed it. Without that, a task submitted while
     * a batch was already in flight was overshot by however much of the batch remained -- 5 of 200
     * markers on a loaded machine, each one a checkpoint holding rows its own source offset says
     * will be replayed.
     *
     * <p>{@link #submitSequence} is read either side and the whole thing repeated if it moved,
     * which is what excludes a submitter that is choosing its marker right now. The loop spins
     * rather than blocking: the only thing it is waiting for is a handful of instructions under a
     * monitor.
     */
    private void takeableRows(long[] into) {
        while (true) {
            long sequence = submitSequence;
            if ((sequence & 1L) != 0L) {
                Thread.onSpinWait(); // a marker is being chosen; it will be queued in a moment
                continue;
            }
            // Only a CUT binds the batch. Scanning for it is skipped entirely when none is queued,
            // which is the normal state: checkpoints are periodic and watermarks are constant.
            //
            // It is the FIRST cut in the queue, not the head of the queue. A level at the head
            // imposes no clamp, but a cut behind it still does -- and taking only the head would let
            // the batch run past that cut's marker, which is exactly the double count the marker
            // exists to prevent. Tasks still RUN in queue order, so the level ahead of it is applied
            // first either way.
            ControlTask binding = null;
            if (pendingCuts.get() > 0) {
                for (ControlTask queued : control) {
                    if (queued.cut()) {
                        binding = queued;
                        break;
                    }
                }
            }
            for (int input = 0; input < inboxes.length; input++) {
                long frontier = inboxes[input].producerCursor();
                if (binding != null) {
                    frontier = Math.min(frontier, binding.barrier()[input]);
                }
                into[input] = frontier - inboxes[input].drainCursor();
            }
            if (submitSequence == sequence) {
                return;
            }
        }
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
     * @return how many tasks ran
     */
    private int runControlTasks(boolean force) {
        int ran = 0;
        ControlTask queued;
        while ((queued = control.peek()) != null) {
            if (!force && !barrierReached(queued.barrier())) {
                return ran;
            }
            control.poll();
            if (queued.cut()) {
                // Decremented after the poll, so the counter never says "no cuts" while one is still
                // reachable from the queue -- which would let a batch overshoot the marker it names.
                pendingCuts.decrementAndGet();
            }
            Runnable task = queued.task();
            try {
                task.run();
            } catch (Throwable t) {
                // The failure, and only the failure. The waiter's next act is checkHealth() --
                // QueryExecution.restore is exactly that shape -- and checkHealth reads this field,
                // so it has to be set before the finally below retires the ticket. Leaving it to
                // run()'s outer catch means the exception must first unwind the control loop and
                // the batch loop, and a waiter scheduled inside that window reads a lane that has
                // failed and does not say so.
                //
                // State.FAILED is deliberately NOT set here, and the ordering is the whole reason.
                // awaitControlTask gives up early when it sees FAILED, returning whether the ticket
                // has moved -- so marking the lane failed before retiring the ticket makes the
                // waiter answer "no" and the caller report a timeout instead of the refusal that
                // actually happened. run()'s outer catch sets it, after this finally has run.
                failure = t;
                throw t;
            } finally {
                // Released even when the task threw: a coordinator waiting on it must not wait
                // forever because the work failed, and a failure it cannot see is worse than one
                // it can. It finds out by calling checkHealth() once the wait returns.
                controlCompleted = queued.id();
                controlRun.incrementAndGet();
                ran++;
            }
        }
        return ran;
    }

    /** Whether every input has been drained to where it stood when the task was submitted. */
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
            if (state == State.STOPPED) {
                // LIFE-067. A stopped lane drains nothing more, so a row a producer claimed after it
                // drained -- one racing the drop that stopped it -- is never taken, and waiting for
                // the inbox to empty waited out the whole timeout: the pushing thread sat in
                // awaitApplied for its ten seconds past the drop. Answered now, as it stands.
                return allInboxesEmpty() && !inBatch;
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

    /**
     * Off-heap this lane holds, by the part of it that holds them.
     *
     * <p>Attribution, because W9-7 measured 2,068 KiB per active query from the JVM's buffer pool and
     * could not say what 1,044 of it was. A pool total is a sum with no names in it.
     */
    public java.util.Map<String, Long> offHeapBytes() {
        java.util.Map<String, Long> byPart = new java.util.LinkedHashMap<>();
        long inboxBytes = 0;
        for (RowInbox each : inboxes) {
            inboxBytes += each.bytesAllocated();
        }
        byPart.put("inbox", inboxBytes);
        byPart.put("arena", arena.bytesAllocated());
        byPart.put("exchange", exchange == null ? 0L : exchange.bytesAllocated());
        return byPart;
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
                exchangedIn,
                backpressure.snapshot(inboxDepth(), inboxCells()));
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
        if (hosted) {
            // No thread of our own to join. The runner is alive either way -- it is alive for every
            // other lane it carries -- so what has to be waited for is this lane's own last step
            // having finished and let go of the arena and the inbox.
            long deadline =
                    System.nanoTime() + Math.max(1L, config.shutdownTimeout().toMillis()) * 1_000_000L;
            while (!drained && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
            }
            if (!drained) {
                throw new PravahaException(
                        RuntimeErrors.LANE_FAILED,
                        // Opens with the same words as the unhosted message on purpose. A caller
                        // asking "did this lane stop" should not have to know which of the two
                        // threading modes it was in to recognise the answer.
                        "lane " + laneId + " did not stop within " + config.shutdownTimeout()
                                + ": its last step on the runner driving it has not finished, so the inbox and "
                                + "arena it owns cannot be released. Another lane on that runner is most likely "
                                + "stuck in its processor -- which is the cost of a shared thread, and the place "
                                + "to look.");
            }
            return;
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
