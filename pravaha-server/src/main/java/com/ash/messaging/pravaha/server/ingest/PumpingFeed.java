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
package com.ash.messaging.pravaha.server.ingest;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;

/**
 * One thread, polling a query's pumps until it is closed.
 *
 * <p>A thread per computation rather than a shared pool, and the reason is the thing a pool would
 * break. A pump is not thread-confined by accident: it holds a {@link
 * com.ash.messaging.pravaha.api.plugin.PartitionReader} and a staging buffer that only one thread
 * may touch, and it applies backpressure with edge-triggered hysteresis that assumes it sees every
 * poll. A pool that moved a pump between threads would be correct only by luck; a pool that pinned
 * one pump per worker is a thread per pump wearing a costume.
 *
 * <p>Queries sharing a fingerprint share this feed, because they share the execution behind it.
 * Feeding each name separately would deliver every row as many times as the query was registered.
 */
final class PumpingFeed implements SourceFeed {

    /**
     * Rows per pump per poll.
     *
     * <p>Large enough that the loop is not dominated by its own bookkeeping, small enough that one
     * greedy partition cannot hold the others off for long. The pump clamps this to the inbox's
     * free space anyway, so it is an upper bound rather than a promise.
     */
    private static final int BATCH = 1024;

    /**
     * How long to wait after a poll that moved nothing.
     *
     * <p>Long enough not to burn a core spinning on an idle topic, short enough that it does not
     * become the dominant term in end-to-end latency for a stream that is merely slow rather than
     * empty. A source with nothing to say costs one wake-up per millisecond; a busy one never
     * reaches here at all, because a poll that moved rows loops straight round.
     */
    private static final long IDLE_NAP_NANOS = 1_000_000L;

    /** How often the applied frontier is published, and so the visibility latency of a new row. */
    private static final long PUBLISH_INTERVAL_NANOS = 20_000_000L;

    private final String queryName;
    private final List<IngestPump> pumps;
    private final List<AutoCloseable> resources;
    private final String description;
    private final Runnable afterDelivery;
    private final Thread thread;
    private final AtomicLong rowsFed = new AtomicLong();

    private volatile boolean paused;
    private long lastPublishedNanos;
    private volatile boolean closed;
    private volatile PravahaException failure;

    PumpingFeed(
            String queryName,
            List<IngestPump> pumps,
            List<AutoCloseable> resources,
            String description,
            Runnable afterDelivery) {
        this.queryName = queryName;
        this.pumps = List.copyOf(pumps);
        this.resources = List.copyOf(resources);
        this.description = description;
        this.afterDelivery = afterDelivery == null ? () -> {} : afterDelivery;
        this.thread = new Thread(this::run, "pravaha-feed-" + queryName);
        // A daemon, so a feed thread cannot be the reason a JVM will not exit. Close is what stops
        // it properly; this is only the backstop for a process that skipped that.
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    private void run() {
        while (!closed) {
            if (paused) {
                LockSupport.parkNanos(IDLE_NAP_NANOS);
                continue;
            }
            int moved = 0;
            try {
                for (IngestPump pump : pumps) {
                    moved += pump.pumpOnce(BATCH);
                }
            } catch (PravahaException e) {
                // Recorded rather than retried. A source that fails mid-read fails for a reason --
                // a deleted file, a revoked credential, a schema that no longer matches -- and
                // spinning on it produces a log line per millisecond and no progress. The query
                // stays up and its view keeps answering at the frontier it reached; describe()
                // says why it stopped moving.
                failure = e;
                return;
            } catch (RuntimeException e) {
                failure = new PravahaException(
                        IngestErrors.FEED_FAILED, "the source feed for '" + queryName + "' stopped: " + e, e);
                return;
            }
            if (moved > 0) {
                rowsFed.addAndGet(moved);
            } else {
                LockSupport.parkNanos(IDLE_NAP_NANOS);
            }
            publishPeriodically();
        }
    }

    /**
     * Publishes what the lanes have applied, on a cadence of its own.
     *
     * <p>A served view shows its <em>committed</em> frontier, and nothing else on the ingest path
     * moves it: rows arrived, the lanes processed them, and every reader saw an empty view. An
     * end-to-end run reported five thousand rows in and zero rows out.
     *
     * <p>The cadence is separate from the pump's for a reason that cost a debugging round. Rows are
     * handed to a lane and applied on <em>its</em> thread, so committing immediately after a poll
     * publishes a frontier from before those rows were applied. Committing only on the edge into
     * idle looks like it fixes that and does not: the lane may apply after the last edge, and then
     * nothing ever commits again -- which is precisely how a fully-read file stays invisible.
     * Committing on a timer, idle or not, has no such edge to miss.
     *
     * <p>Twenty milliseconds is the visibility latency this adds, against a frontier update per
     * tick when a query is quiet -- a commit with nothing pending does almost nothing.
     */
    private void publishPeriodically() {
        long now = System.nanoTime();
        if (now - lastPublishedNanos < PUBLISH_INTERVAL_NANOS) {
            return;
        }
        lastPublishedNanos = now;
        afterDelivery.run();
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
        LockSupport.unpark(thread);
    }

    @Override
    public long rowsFed() {
        return rowsFed.get();
    }

    @Override
    public String describe() {
        if (failure != null) {
            return description + " -- stopped: " + failure.getMessage();
        }
        return description + (paused ? " (paused)" : "");
    }

    @Override
    public void close() {
        closed = true;
        LockSupport.unpark(thread);
        try {
            // Bounded: a reader wedged in a blocking call must not hold up a drop indefinitely. The
            // pumps and plugins are closed below either way, which is what unblocks it.
            thread.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Pumps first, then what they were reading: closing a reader under a live pump is the same
        // ordering mistake as closing an execution under a live feed, one level down.
        pumps.forEach(IngestPump::close);
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                // A plugin that will not close cleanly must not stop the rest from closing. Its
                // resources are its own; the lane and arena behind this feed are released by the
                // caller regardless.
            }
        }
    }
}
