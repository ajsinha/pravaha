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
package com.ash.messaging.pravaha.runtime.ingest;

import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.runtime.lane.LaneBackpressure;

/**
 * One pump's backpressure episodes: how many times it found no room, and how long it went on.
 *
 * <p>Shared by {@link IngestPump} and {@link PartitionedIngestPump}, which pause and resume the
 * same way and had the same hole in the same place -- a pause count with no duration behind it, so
 * a source paused once for an hour and a source paused once for a microsecond reported the same
 * number.
 *
 * <p><strong>The cost is one branch per poll.</strong> {@link #blocked} and {@link #cleared} are
 * called once each per {@code pumpOnce}, which moves up to a few hundred rows, and the two
 * {@link System#nanoTime()} calls happen only at an episode's edges. Nothing here is per row.
 *
 * <p>The counters are atomics because a pump is polled by a feed thread and read by whatever is
 * drawing a dashboard, but they are only ever <em>written</em> under the pump's own ingest lock, so
 * there is no contention to shard away.
 */
final class BackpressureClock {

    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong waitNanos = new AtomicLong();

    /** When the episode in progress began, or 0 while there is room. Never 0 for a real start. */
    private volatile long since;

    /**
     * The lane counter the open episode was started against, so it is the one that is closed.
     *
     * <p>A partitioned pump attributes an episode to whichever of its lanes was fullest, and that
     * can be a different lane by the time room appears. Closing the episode on the lane that is
     * fullest <em>now</em> would leave the lane it was opened on waiting for ever, reporting a
     * blocked fraction that climbs to 1 and stays there.
     */
    private volatile @Nullable LaneBackpressure openedAgainst;

    /**
     * The writer has found no room. Idempotent while an episode is open: a pump that polls a
     * hundred times into a full inbox has waited once, for as long as it took.
     *
     * @param lane where to attribute the episode, or null when this pump has no lane counter
     * @param queryId whose writer this is, for a lane several queries share
     */
    void blocked(LaneBackpressure lane, String queryId) {
        if (since != 0L) {
            return;
        }
        // max(1) so that a nanoTime of exactly 0 -- possible, since the origin is arbitrary -- is
        // not read as "no episode open". One nanosecond of error on a wait nobody can measure
        // anyway is the right trade for not losing a stall entirely.
        since = Math.max(1L, System.nanoTime());
        openedAgainst = lane;
        waits.incrementAndGet();
        if (lane != null) {
            lane.waitStarted(queryId);
        }
    }

    /** The writer has found room. Does nothing when no episode was open. */
    void cleared(String queryId) {
        long began = since;
        if (began == 0L) {
            return;
        }
        since = 0L;
        waitNanos.addAndGet(Math.max(0L, System.nanoTime() - began));
        LaneBackpressure opened = openedAgainst;
        openedAgainst = null;
        if (opened != null) {
            opened.waitEnded(queryId);
        }
    }

    /** Records a wait measured elsewhere: the shared-inbox park loop times its own. */
    void waited(LaneBackpressure lane, String queryId, long nanos) {
        waits.incrementAndGet();
        waitNanos.addAndGet(Math.max(0L, nanos));
        if (lane != null) {
            lane.waited(queryId, nanos);
        }
    }

    /** Episodes in which this pump found no room. */
    long waits() {
        return waits.get();
    }

    /**
     * How long those episodes lasted, including one in progress.
     *
     * <p>Including the open one matters: a pump blocked for ten minutes and still blocked would
     * otherwise report zero, which is the exact case this counter exists for.
     */
    long waitNanos() {
        long open = since;
        return waitNanos.get() + (open == 0L ? 0L : Math.max(0L, System.nanoTime() - open));
    }

    /** Whether this pump is waiting for room right now. */
    boolean waiting() {
        return since != 0L;
    }
}
