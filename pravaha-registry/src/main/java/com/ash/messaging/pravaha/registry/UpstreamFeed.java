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
package com.ash.messaging.pravaha.registry;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;

/**
 * Feeds a query from another query's answer (ADR-056): one pump over an {@link UpstreamReader},
 * driven by a thread of its own and woken by the upstream's commits.
 *
 * <p>The shape of every other feed -- pump, publish on a short timer, pause, and a stop recorded
 * rather than retried (FEED-1). What is its own is the stop it can have: the upstream failing. Its
 * view then refuses reads and its answer stops being current, so this feed stops too, naming it, and
 * the downstream keeps answering at the frontier it reached rather than following an answer nobody
 * maintains.
 */
final class UpstreamFeed implements SourceFeed {

    private static final System.Logger LOG = System.getLogger(UpstreamFeed.class.getName());

    private static final int BATCH = 1024;

    /** How long a quiet feed sleeps before looking again; an upstream commit wakes it sooner. */
    private static final long IDLE_NANOS = 20_000_000L;

    /** At most how often the downstream is committed while rows are moving, as a bound feed does. */
    private static final long PUBLISH_INTERVAL_NANOS = 20_000_000L;

    /** How long a commit waits for the lane to apply what it was handed before publishing anyway. */
    private static final java.time.Duration SETTLE = java.time.Duration.ofSeconds(1);

    private final String queryName;
    private final String upstream;
    private final RegisteredQuery upstreamQuery;
    private final IngestPump pump;
    private final UpstreamReader reader;
    private final RegisteredQuery downstream;
    private final Thread thread;
    private final AtomicLong rowsFed = new AtomicLong();

    private volatile boolean paused;
    private volatile boolean closed;
    private volatile @Nullable PravahaException failure;
    private volatile @Nullable Instant stoppedAt;

    UpstreamFeed(
            String queryName,
            String upstream,
            RegisteredQuery upstreamQuery,
            IngestPump pump,
            UpstreamReader reader,
            RegisteredQuery downstream) {
        this.queryName = queryName;
        this.upstream = upstream;
        this.upstreamQuery = upstreamQuery;
        this.pump = pump;
        this.reader = reader;
        this.downstream = downstream;
        this.thread = Thread.ofVirtual().name("pravaha-follow-" + queryName).unstarted(this::run);
    }

    /** Follows the upstream -- its snapshot is queued at once -- and starts pumping. */
    UpstreamFeed start() {
        reader.follow(() -> LockSupport.unpark(thread));
        thread.start();
        return this;
    }

    private void run() {
        boolean unpublished = false;
        long lastPublished = System.nanoTime();
        while (!closed) {
            if (paused) {
                LockSupport.parkNanos(IDLE_NANOS);
                continue;
            }
            try {
                if (upstreamQuery.state() == QueryState.FAILED) {
                    throw new PravahaException(
                            RegistryErrors.QUERY_FAILED,
                            "'" + queryName + "' follows '" + upstream + "', which has failed"
                                    + upstreamQuery
                                            .failure()
                                            .map(why -> ": " + why.getMessage())
                                            .orElse("")
                                    + ". Its answer is no longer maintained, so this query stops following it and "
                                    + "keeps answering at the frontier it reached (ADR-056).");
                }
                if (reader.needsSnapshot()) {
                    reader.resnapshot();
                }
                int moved = pump.pumpOnce(BATCH);
                if (moved > 0) {
                    rowsFed.addAndGet(moved);
                    unpublished = true;
                }
                long now = System.nanoTime();
                if (unpublished && (moved == 0 || now - lastPublished >= PUBLISH_INTERVAL_NANOS)) {
                    // Once the lane has applied what was handed to it: a commit publishes what has
                    // been applied, and a filter's rows still in the inbox would wait for the next
                    // change upstream -- which may never come -- to be published.
                    boolean applied = downstream.awaitApplied(SETTLE);
                    downstream.commit();
                    unpublished = !applied;
                    lastPublished = now;
                }
                if (moved == 0) {
                    LockSupport.parkNanos(IDLE_NANOS);
                }
            } catch (PravahaException e) {
                stop(e);
                return;
            } catch (RuntimeException | Error e) {
                stop(new PravahaException(
                        RegistryErrors.QUERY_FAILED,
                        "the feed of '" + queryName + "' from '" + upstream + "' stopped: " + e,
                        e));
                return;
            }
        }
    }

    private void stop(PravahaException cause) {
        stoppedAt = Instant.now();
        failure = cause;
        reader.close();
        LOG.log(
                System.Logger.Level.ERROR,
                "query '" + queryName + "' stopped following '" + upstream + "' with "
                        + cause.errorCode().code() + "; its view keeps answering at the frontier it reached: "
                        + cause.getMessage());
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
        String described = "following the answer of '" + upstream + "'";
        if (failure != null) {
            return described + " -- stopped: " + failure.getMessage();
        }
        return described + (paused ? " (paused)" : "");
    }

    @Override
    public FeedStatus status() {
        PravahaException stopped = failure;
        FeedStatus.Source source = stopped != null
                ? new FeedStatus.Source(
                        upstream,
                        0,
                        false,
                        FeedStatus.SourceState.STOPPED,
                        new FeedStatus.Stop(stopped, stoppedAt, true))
                : new FeedStatus.Source(
                        upstream,
                        0,
                        false,
                        paused ? FeedStatus.SourceState.PAUSED : FeedStatus.SourceState.RUNNING,
                        null);
        return FeedStatus.of(describe(), List.of(source));
    }

    /** The name this feed follows. */
    String upstream() {
        return upstream;
    }

    @Override
    public void close() {
        closed = true;
        LockSupport.unpark(thread);
        try {
            thread.join(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reader.close();
        pump.close();
    }
}
