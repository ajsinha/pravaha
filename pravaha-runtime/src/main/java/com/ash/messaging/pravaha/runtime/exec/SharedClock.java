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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One timer for the whole process, firing each query's periodic work on a virtual thread.
 *
 * <p>W9-3. A watermark clock and a checkpointer were each a {@code newSingleThreadScheduledExecutor}
 * per query -- two platform threads per registration, on top of the lane's, and the binding
 * constraint on how many continuous queries a node holds once ADR-027's multiplexing removed the
 * lane's.
 *
 * <h2>Why not simply share one scheduler</h2>
 *
 * <p>Because the work is not quick. A watermark tick calls {@code advanceWatermarkQuietly}, which
 * submits a control task to every lane and waits for each with a ten-second timeout; a checkpoint
 * writes and fsyncs. On a shared single-threaded scheduler, one slow lane or one slow disk would
 * stall every other query's clock -- turning a local stall into a node-wide one, which is a worse
 * failure than the threads it saves.
 *
 * <p>So the timer only keeps time. Each firing is handed to a virtual thread, where blocking parks
 * rather than occupies, and a tick that waits ten seconds for a lane costs a parked continuation
 * instead of the only scheduler thread. This is the same division Flight got in W9-1: a bounded set
 * of threads for the timing, virtual threads for the waiting.
 *
 * <p>The timer itself is one daemon platform thread for the JVM. Not shut down, and deliberately:
 * a process-wide clock with no owner is a fixed cost of one thread, and handing it a lifecycle would
 * mean every test that starts a query having to end it in the right order to avoid a leak that does
 * not exist.
 *
 * <h2>A firing never overlaps its own previous firing</h2>
 *
 * <p>{@code scheduleWithFixedDelay} guarantees that by measuring the delay from completion, which is
 * a guarantee lost the moment the work is handed to another thread. It is restored explicitly: a
 * task already running is skipped rather than queued. Two watermark advances for one query at once
 * would race each other through the same lanes, and a backlog of skipped ticks is meaningless anyway
 * -- a watermark is a level, not an event, and the next tick carries whatever the skipped one would
 * have.
 */
public final class SharedClock {

    private static final System.Logger LOG = System.getLogger(SharedClock.class.getName());

    /** Keeps time and nothing else, so nothing it fires can delay the next firing. */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pravaha-clock");
        thread.setDaemon(true);
        return thread;
    });

    /** Where the work happens. Virtual, because most of it is waiting. */
    private static final ExecutorService WORK = Executors.newVirtualThreadPerTaskExecutor();

    private SharedClock() {}

    /**
     * Runs {@code task} every {@code period}, on a virtual thread, never overlapping itself.
     *
     * @return a handle the caller cancels when its query goes; nothing else stops it
     */
    public static ScheduledFuture<?> every(Duration period, String what, Runnable task) {
        // TIME-11. This was Math.max(1L, period.toMillis()), and a clamp is the one thing the
        // engine's duration settings do not do: zero, half a millisecond and minus one second were
        // all accepted here and all became a millisecond, while the caller went on logging what was
        // asked for. A period this timer cannot count is a refusal, with the value in it.
        if (period == null || period.toMillis() < 1L) {
            throw new IllegalArgumentException("a period of " + period + " cannot be scheduled for '" + what
                    + "': this timer counts in milliseconds, so anything shorter than one -- including zero "
                    + "and any negative -- has no honest reading. Ask for at least PT0.001S.");
        }
        long millis = period.toMillis();
        AtomicBoolean running = new AtomicBoolean();
        // When the firing in flight started, and how many ticks it has cost so far (OBS-1).
        java.util.concurrent.atomic.AtomicLong startedAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong skipped = new java.util.concurrent.atomic.AtomicLong();
        return TIMER.scheduleAtFixedRate(
                () -> {
                    if (!running.compareAndSet(false, true)) {
                        // The previous firing has not finished. Skipping rather than queueing: two
                        // of these at once would race through the same lanes, and a backlog of them
                        // has nothing to contribute that the next one does not carry.
                        //
                        // OBS-1: said, once per overrunning firing. A checkpoint that takes four
                        // intervals costs three checkpoints, and silently that looked exactly like a
                        // schedule that had stopped -- one was seen once and could not be explained.
                        if (skipped.incrementAndGet() == 1) {
                            LOG.log(
                                    System.Logger.Level.WARNING,
                                    "'" + what + "' is still running after "
                                            + (System.nanoTime() - startedAt.get()) / 1_000_000L
                                            + " ms, longer than its " + millis + " ms period: this tick "
                                            + "and any more until it finishes are skipped");
                        }
                        return;
                    }
                    startedAt.set(System.nanoTime());
                    try {
                        WORK.execute(() -> {
                            try {
                                task.run();
                            } catch (Throwable failure) {
                                // A periodic task that throws must not stop the schedule. It is the
                                // caller's business what the failure means -- both of this clock's
                                // users record it on the query -- and this is only the backstop that
                                // keeps the timer alive for every other query sharing it.
                                LOG.log(System.Logger.Level.WARNING, what + " failed on this tick", failure);
                            } finally {
                                long missed = skipped.getAndSet(0);
                                if (missed > 0) {
                                    LOG.log(
                                            System.Logger.Level.WARNING,
                                            "'" + what + "' finished after "
                                                    + (System.nanoTime() - startedAt.get()) / 1_000_000L
                                                    + " ms; " + missed + " tick(s) were skipped while it ran");
                                }
                                running.set(false);
                            }
                        });
                    } catch (Throwable notStarted) {
                        // OBS-1: the firing could not even be handed to a thread (a virtual thread
                        // that could not be created). Clear the flag, or every later tick would
                        // find it set and skip for ever -- and do not rethrow: an exception out of
                        // a fixed-rate task cancels the schedule, silently, for good.
                        running.set(false);
                        LOG.log(System.Logger.Level.WARNING, what + " could not start on this tick", notStarted);
                    }
                },
                millis,
                millis,
                TimeUnit.MILLISECONDS);
    }
}
