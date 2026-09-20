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
package com.ash.messaging.pravaha.bindings.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.FeedStatus;
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
     * How long to wait after the <em>first</em> poll that moves nothing.
     *
     * <p>Long enough not to burn a core spinning on an idle topic, short enough that it does not
     * become the dominant term in end-to-end latency for a stream that is merely slow rather than
     * empty. A busy source never reaches here at all, because a poll that moved rows loops
     * straight round.
     */
    private static final long IDLE_NAP_NANOS = 1_000_000L;

    /**
     * The longest this feed naps, reached by doubling while a source stays quiet (SRC-6).
     *
     * <p><strong>The nap was a flat millisecond.</strong> So every bound source was polled a
     * thousand times a second whether or not anything had happened, and in follow mode each of
     * those polls is a {@code stat} plus a {@code read}: measured at <strong>12.8 ms of process
     * CPU per second per source</strong> over 100 followed files with nothing being written to
     * them -- 1,782 ms/s against an unbound baseline of 504 -- and 16.9 ms/s per source at 50, so
     * a per-source constant rather than a constant of the node. A hundred idle followed files is
     * 1.8 cores, and nothing was reading any rows.
     *
     * <p>Doubling from a millisecond, reset to a millisecond by any poll that moves a row. A
     * stream that is merely slow pays a millisecond as it always did; one that has been quiet for
     * a while costs fifty wake-ups a second instead of a thousand.
     *
     * <p><strong>The ceiling is the publish interval</strong>, deliberately and not by
     * coincidence: this feed already publishes its applied frontier only every
     * {@link #PUBLISH_INTERVAL_NANOS}, so a row's visibility latency is already that. A nap that
     * cannot exceed it cannot become the dominant term in anything -- which is the property the
     * flat millisecond was chosen for, kept, and now paid for once rather than a thousand times a
     * second.
     */
    private static final long MAX_IDLE_NAP_NANOS = 20_000_000L;

    /** The next nap after another poll that moved nothing. See {@link #MAX_IDLE_NAP_NANOS}. */
    static long nextIdleNap(long currentNanos) {
        return Math.min(currentNanos * 2, MAX_IDLE_NAP_NANOS);
    }

    /** How often the applied frontier is published, and so the visibility latency of a new row. */
    private static final long PUBLISH_INTERVAL_NANOS = 20_000_000L;

    private static final System.Logger LOG = System.getLogger(PumpingFeed.class.getName());

    private final String queryName;
    private final List<IngestPump> pumps;
    private final List<FeedInput> inputs;
    private final List<AutoCloseable> resources;
    private final String description;
    private final Runnable afterDelivery;
    private final Thread thread;
    private final AtomicLong rowsFed = new AtomicLong();

    private volatile boolean paused;
    private long lastPublishedNanos;
    private volatile boolean closed;
    private volatile PravahaException failure;

    /** When {@link #failure} was recorded. Written before it, so a reader that sees one sees both. */
    private volatile Instant stoppedAt;

    /** The pump whose read raised {@link #failure}, or -1 when it was not a read that failed. */
    private volatile int failedPump = -1;

    /** The pump being polled right now, or -1 between polls. Feed thread only. */
    private int polling = -1;

    /** Whose option values a recorded failure must not carry. */
    private volatile List<SourceBinding> bindings = List.of();

    /**
     * @param inputs which stream and partition each pump reads, in the same order as {@code pumps},
     *     so a stop can say where it happened (FEED-1)
     */
    PumpingFeed(
            String queryName,
            List<IngestPump> pumps,
            List<FeedInput> inputs,
            List<AutoCloseable> resources,
            String description,
            Runnable afterDelivery) {
        if (inputs.size() != pumps.size()) {
            throw new IllegalArgumentException(
                    "one input per pump: " + pumps.size() + " pumps and " + inputs.size() + " inputs");
        }
        this.queryName = queryName;
        this.pumps = List.copyOf(pumps);
        this.inputs = List.copyOf(inputs);
        this.resources = List.copyOf(resources);
        this.description = description;
        this.afterDelivery = afterDelivery == null ? () -> {} : afterDelivery;
        // Virtual, and the paragraph above is why it can be. What that reasoning defends is
        // *confinement* -- one thread of execution owning the pump, its reader and its staging
        // buffer, seeing every poll -- and a virtual thread is exactly that. It differs only in not
        // occupying a carrier while parked, which is what this loop does almost all of the time:
        // IDLE_NAP_NANOS between polls that moved nothing, and a query whose source is quiet naps
        // for ever. A platform thread per registered query is the cost that makes QueryRegistry's
        // own note -- "fine at tens" -- true, and this is one of the three.
        //
        // The caveat, recorded because it is not visible from here: on JDK 21 a blocking *file*
        // read pins the carrier for its duration, so a filesystem source still occupies one while
        // it is actually reading. Socket-backed sources -- Aerospike, JDBC -- unmount properly, and
        // every source unmounts while napping. The win is in the parked time, which is most of it.
        this.thread = Thread.ofVirtual()
                // A virtual thread is always a daemon, so the backstop the platform version needed
                // is implicit: a feed thread can never be the reason a JVM will not exit. Close is
                // still what stops it properly.
                .name("pravaha-feed-" + queryName)
                .unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    private void run() {
        // SRC-6. Grows while nothing arrives and resets the moment something does, so an idle
        // source costs fifty wake-ups a second rather than a thousand and a busy one pays nothing
        // at all. Loop-local: one feed's quiet says nothing about another's.
        long nap = IDLE_NAP_NANOS;
        while (!closed) {
            if (paused) {
                // A paused feed is not waiting for anything, so it waits the longest it ever does.
                LockSupport.parkNanos(MAX_IDLE_NAP_NANOS);
                continue;
            }
            int moved = 0;
            try {
                for (int index = 0; index < pumps.size(); index++) {
                    polling = index;
                    moved += pumps.get(index).pumpOnce(BATCH);
                }
                polling = -1;
                if (moved > 0) {
                    rowsFed.addAndGet(moved);
                    nap = IDLE_NAP_NANOS;
                } else {
                    LockSupport.parkNanos(nap);
                    nap = nextIdleNap(nap);
                }
                // Inside the try, and that is the whole point of this arrangement. Publishing used
                // to sit below the catch, so a throw from commit killed this thread without even
                // recording why: the feed stopped, `failure` stayed null, describe() went on saying
                // "reading txn (1 partition)", and the query reported RUNNING for ever.
                publishPeriodically();
            } catch (PravahaException e) {
                // Recorded rather than retried. A source that fails mid-read fails for a reason --
                // a deleted file, a revoked credential, a schema that no longer matches -- and
                // spinning on it produces a log line per millisecond and no progress. The query
                // stays up and its view keeps answering at the frontier it reached; status() says
                // which source stopped, when, and why (FEED-1).
                stop(e);
                return;
            } catch (RuntimeException e) {
                stop(new PravahaException(
                        IngestErrors.FEED_FAILED, "the source feed for '" + queryName + "' stopped: " + e, e));
                return;
            } catch (Throwable e) {
                // Everything, including Error. A feed thread that dies leaves a query that looks
                // healthy and has silently stopped, which is the worst shape a failure can take --
                // so nothing is allowed to leave this loop unrecorded, whatever its type.
                stop(new PravahaException(
                        IngestErrors.FEED_FAILED, "the source feed for '" + queryName + "' stopped: " + e, e));
                return;
            }
        }
    }

    /**
     * Records why this feed stopped, and says so once in the log.
     *
     * <p>The log line is part of the fix rather than a courtesy: TIME-4 was a source reduced to zero
     * rows with no line anywhere, and a stop that only an API call can find is found by nobody who
     * is reading the log at the time.
     */
    private void stop(PravahaException cause) {
        PravahaException recorded = FeedRedaction.redact(cause, bindings);
        failedPump = polling;
        stoppedAt = Instant.now();
        failure = recorded;
        String where = polling >= 0 ? " reading " + inputs.get(polling).where() : "";
        LOG.log(
                System.Logger.Level.ERROR,
                "the source feed for query '" + queryName + "' stopped" + where + " with "
                        + cause.errorCode().code() + " and will not retry; the view keeps answering at the "
                        + "frontier it reached: " + recorded.getMessage(),
                cause);
    }

    /**
     * The bindings this feed reads, whose option values are struck out of a failure's message before
     * it is recorded (see {@link FeedRedaction}). Called before {@link #start()}.
     */
    PumpingFeed redacting(java.util.Collection<SourceBinding> read) {
        this.bindings = List.copyOf(read);
        return this;
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

    /**
     * Feeds one dead letter back through the pump that reads its stream (B5).
     *
     * <p>Not counted in {@link #rowsFed()}, which is what this feed's thread has moved: a replayed
     * row arrives on the caller's thread and is counted by the pump. A view that gains a row
     * without its feed's count moving is exactly what happened.
     */
    @Override
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry.Replay replay(
            String stream, byte[] raw, String sourceOffset, String schema, String id) {
        com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry.Replay outcome =
                FeedReplay.through(pumps, stream, raw, sourceOffset, schema, id);
        // The frontier has to be published or the replayed row sits applied and invisible until the
        // feed's own timer comes round -- which for a source that has gone quiet is never.
        afterDelivery.run();
        return outcome;
    }

    /** This feed's pumps, for a {@link SharedFeed} that owns this one as its unshared half. */
    List<IngestPump> pumps() {
        return pumps;
    }

    @Override
    public FeedStatus status() {
        return FeedStatus.of(description, sources(false));
    }

    /**
     * One entry per pump.
     *
     * @param shared how to label them; a {@link SharedFeed} lists its own unshared half through this
     */
    List<FeedStatus.Source> sources(boolean shared) {
        PravahaException stopped = failure;
        Instant at = stoppedAt;
        int origin = failedPump;
        List<FeedStatus.Source> sources = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            FeedInput input = inputs.get(index);
            if (stopped != null) {
                sources.add(new FeedStatus.Source(
                        input.stream(),
                        input.partition(),
                        shared,
                        FeedStatus.SourceState.STOPPED,
                        new FeedStatus.Stop(stopped, at, index == origin)));
            } else {
                sources.add(new FeedStatus.Source(
                        input.stream(),
                        input.partition(),
                        shared,
                        paused ? FeedStatus.SourceState.PAUSED : FeedStatus.SourceState.RUNNING,
                        null));
            }
        }
        return sources;
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
