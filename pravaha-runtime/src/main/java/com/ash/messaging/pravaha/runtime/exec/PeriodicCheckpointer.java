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
package com.ash.messaging.pravaha.runtime.exec;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.CheckpointStore;

/**
 * Takes checkpoints on a schedule, stores them, and deletes the ones that are no longer worth
 * keeping.
 *
 * <p>Until this existed, checkpointing was entirely caller-driven: {@link QueryExecution#checkpoint}
 * produced one and something else had to decide when to call it and what to do with the result.
 * Nothing did, so a long-running query kept no checkpoints, and anything that did call it kept all
 * of them for ever. {@code CheckpointStore.prune} had been written and was never called from
 * anywhere but its own test.
 *
 * <p><strong>Retention here is counted, not timed, and that is deliberate.</strong> Served view
 * retention is time-based because a view holds data and streaming data is about what is true now, so
 * "older than an hour" is a statement about relevance. A checkpoint is not data; it is a fallback.
 * The question it answers is "how many chances do I have to recover", and the count is the answer.
 * An age-based rule would delete the last fallback precisely when nothing is happening -- an idle
 * system takes no new checkpoints, so after a quiet night every checkpoint is old and a time rule
 * would remove them all, leaving nothing to recover from at the moment recovery is most likely to be
 * needed. So: keep the newest {@code keep}, never fewer than one, whatever their age.
 *
 * <p>More than one is kept because the newest is the one most likely to be unreadable: it is the one
 * that was being written if the process died during a write. Falling back to the previous one costs
 * reprocessing; having no previous one costs the state.
 *
 * <p>A failed checkpoint does not stop the schedule. Checkpointing is a background concern, and a
 * transient failure -- a full disk that is later emptied, a lane busy past its timeout -- should
 * degrade recovery rather than end it. Failures are reported every time rather than once, because a
 * query that has silently not checkpointed for six hours looks exactly like one that has.
 */
public final class PeriodicCheckpointer implements AutoCloseable {

    /** How often, if nothing says otherwise. Frequent enough to bound replay, rare enough to be cheap. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    /** How many to keep. Three gives two fallbacks behind a corrupt newest. */
    public static final int DEFAULT_KEEP = 3;

    /** How long a single checkpoint may take before it is abandoned. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final QueryExecution execution;
    private final CheckpointStore store;
    private final Duration interval;
    private final Duration timeout;
    private final int keep;
    private final Consumer<String> log;

    /**
     * Where failures go, separately from the narrative.
     *
     * <p>{@code log} carries three different kinds of line -- the one-off "checkpointing every
     * ...ms" at {@link #start()}, a "checkpoint N stored" per success, and a "checkpoint failed"
     * per failure -- and a caller that wanted only the third had no way to ask for it. The registry
     * wired a failure counter to {@code log} and counted all three, so a query whose checkpoints
     * were all succeeding reported a rising failure count and held a success message as its "last
     * failure". A count of log lines is not a count of failures.
     */
    private volatile Consumer<String> onFailure = message -> {};

    private volatile java.util.concurrent.ScheduledFuture<?> schedule;
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicLong taken = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong pruned = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();

    public PeriodicCheckpointer(QueryExecution execution, CheckpointStore store, Consumer<String> log) {
        this(execution, store, DEFAULT_INTERVAL, DEFAULT_KEEP, DEFAULT_TIMEOUT, log);
    }

    public PeriodicCheckpointer(
            QueryExecution execution,
            CheckpointStore store,
            Duration interval,
            int keep,
            Duration timeout,
            Consumer<String> log) {
        if (keep < 1) {
            throw new IllegalArgumentException("at least one checkpoint must be kept, asked to keep " + keep
                    + ". Keeping none means every restart starts from nothing");
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("checkpoint interval must be positive, got " + interval);
        }
        this.execution = execution;
        this.store = store;
        this.interval = interval;
        this.keep = keep;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.log = log == null ? message -> {} : log;
        // Resume numbering above whatever is already stored, so ids stay monotonic across restarts
        // and "checkpoint 4" means one thing for the life of the directory.
        store.availableIds().stream().max(Long::compare).ifPresent(highest -> nextId.set(highest + 1));
    }

    /** Reads interval, keep and timeout from {@code pravaha.checkpoint.*}. */
    public static PeriodicCheckpointer from(
            QueryExecution execution, CheckpointStore store, Configuration configuration, Consumer<String> log) {
        return new PeriodicCheckpointer(
                execution,
                store,
                configuration.getDuration("pravaha.checkpoint.interval").orElse(DEFAULT_INTERVAL),
                configuration.getInt("pravaha.checkpoint.keep", DEFAULT_KEEP),
                configuration.getDuration("pravaha.checkpoint.timeout").orElse(DEFAULT_TIMEOUT),
                log);
    }

    /** Starts the schedule. The first checkpoint is one interval away, not immediate. */
    /**
     * Sends every checkpoint failure, and nothing else, to {@code consumer}.
     *
     * <p>Returns {@code this} so it can be chained onto a constructor or {@link #from}. Failures
     * continue to reach {@code log} as well: this is an additional channel, not a redirection, so
     * an operator reading the narrative still sees them in order against the successes.
     */
    public PeriodicCheckpointer reportingFailuresTo(Consumer<String> consumer) {
        this.onFailure = consumer == null ? message -> {} : consumer;
        return this;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // The process's clock, fired on a virtual thread. This was a scheduler of its own per
        // query -- a platform thread per checkpointed registration -- and a checkpoint writes and
        // fsyncs, so sharing one *worker* across queries would let one slow disk delay every other
        // query's checkpoint. SharedClock shares the timing and not the waiting (W9-3).
        schedule = SharedClock.every(interval, "checkpoint", this::checkpointQuietly);
        log.accept("checkpointing every " + interval.toMillis() + "ms, keeping the newest " + keep);
    }

    /**
     * Takes one checkpoint now, stores it and prunes.
     *
     * @return the checkpoint taken
     */
    public Checkpoint checkpointNow() {
        long id = nextId.getAndIncrement();
        Checkpoint checkpoint = execution.checkpoint(id, timeout);
        store.store(checkpoint);
        taken.incrementAndGet();
        int removed = store.prune(keep);
        if (removed > 0) {
            pruned.addAndGet(removed);
        }
        return checkpoint;
    }

    /**
     * Set while a firing is inside {@link #checkpointQuietly}, so {@link #close()} can wait for it.
     *
     * <p>The {@code running} guard alone is not enough and the difference is a race the schedule
     * tests catch: a firing already handed to a virtual thread can pass that guard before close()
     * clears it, and then take its checkpoint afterwards. {@code shutdownNow()} on a scheduler of
     * this query's own used to interrupt exactly that; a shared clock cannot, so close() waits.
     */
    private final AtomicBoolean checkpointing = new AtomicBoolean();

    private void checkpointQuietly() {
        if (!running.get()) {
            // close() cancels the schedule, which stops the timer firing again -- it does not reach
            // a firing already handed to a virtual thread. That used to be a shutdownNow() on this
            // checkpointer's own scheduler, which interrupted the thread mid-task; sharing the clock
            // means the guard has to be here instead. Without it, close() was followed by one more
            // checkpoint (STATE-007).
            return;
        }
        checkpointing.set(true);
        try {
            Checkpoint checkpoint = checkpointNow();
            log.accept("checkpoint " + checkpoint.id() + " stored, " + checkpoint.sizeBytes() + " bytes");
        } catch (RuntimeException failure) {
            failed.incrementAndGet();
            // Reported every time, not once: a query that has silently not checkpointed for six
            // hours looks exactly like one that has.
            String message = "checkpoint failed (" + failed.get() + " so far): " + failure.getMessage()
                    + ". Recovery will fall back to the newest stored checkpoint, which is getting older";
            log.accept(message);
            onFailure.accept(message);
        } finally {
            checkpointing.set(false);
        }
    }

    /** How many checkpoints have been taken, failed and deleted. For an operator asking if this is working. */
    public record Stats(long taken, long failed, long pruned) {}

    public Stats stats() {
        return new Stats(taken.get(), failed.get(), pruned.get());
    }

    @Override
    public void close() {
        running.set(false);
        // Cancels this query's schedule, not the clock: the clock is the process's and every other
        // checkpointed query is still on it.
        java.util.concurrent.ScheduledFuture<?> current = schedule;
        if (current != null) {
            current.cancel(true);
        }
        // Then wait out a firing that was already in flight when running was cleared. Bounded: a
        // checkpoint that will not finish must not make close() hang, and the schedule is cancelled
        // either way so nothing further can start.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (checkpointing.get() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
        }
    }
}
