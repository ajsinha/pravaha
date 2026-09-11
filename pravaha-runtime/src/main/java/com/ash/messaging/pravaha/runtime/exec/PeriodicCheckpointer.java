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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ScheduledExecutorService scheduler;
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
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-checkpointer");
            // Daemon: a checkpointer must never be the reason a JVM will not exit.
            thread.setDaemon(true);
            return thread;
        });
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
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(
                this::checkpointQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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

    private void checkpointQuietly() {
        try {
            Checkpoint checkpoint = checkpointNow();
            log.accept("checkpoint " + checkpoint.id() + " stored, " + checkpoint.sizeBytes() + " bytes");
        } catch (RuntimeException failure) {
            failed.incrementAndGet();
            // Reported every time, not once: a query that has silently not checkpointed for six
            // hours looks exactly like one that has.
            log.accept("checkpoint failed (" + failed.get() + " so far): " + failure.getMessage()
                    + ". Recovery will fall back to the newest stored checkpoint, which is getting older");
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
        scheduler.shutdownNow();
    }
}
