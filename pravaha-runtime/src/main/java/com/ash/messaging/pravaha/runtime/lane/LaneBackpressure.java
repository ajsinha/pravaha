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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How long this lane's writers spend unable to place a row, and which query's writer it was.
 *
 * <p>Backpressure was visible only as {@code rejectedOffers} -- a count of refusals with no time in
 * it -- so "this lane is the limit" and "this lane refused twice in an hour" read the same. This is
 * the missing half: <strong>episodes</strong>, each with a start and an end, and the fraction of
 * wall clock they cover.
 *
 * <p><strong>An episode, not a row.</strong> A writer opens one the first time it finds no room and
 * closes it the first time it finds room again; the two {@link System#nanoTime()} calls are paid
 * once per episode, on the path that is already stalled, and never per row. The hot path pays one
 * branch per <em>poll</em> -- per batch of up to a few hundred rows -- which is the cost this class
 * exists to stay inside.
 *
 * <p><strong>What the number is not.</strong> It is the writer's view, taken at the resolution the
 * writer runs at, and it is honest about three things:
 *
 * <ul>
 *   <li>A pump notices that the inbox is full only when it polls, so an episode's start and end are
 *       each rounded to the poll that discovered them. A wait shorter than the gap between two
 *       polls is not seen at all, and one that is seen is measured to within one poll interval at
 *       each end.
 *   <li>Several writers can be blocked at once -- a join has one pump per side -- so their episodes
 *       overlap and their times add. {@link Snapshot#blockedFraction()} is clamped at 1; the raw
 *       sum is in {@link Snapshot#waitNanos()} and may exceed the elapsed time.
 *   <li>It says the writer waited, not who made it wait. On a shared lane the consumer is shared,
 *       so the query whose writer waited is usually <em>not</em> the slow one. The per-operator
 *       numbers on the plan are what name the slow one.
 * </ul>
 *
 * <p>Writes are rare -- once at each end of an episode -- so plain atomics are enough and there is
 * no reason to pad or shard them. Reads walk the per-query map, which holds one entry per query
 * that has ever waited on this lane: bounded by {@code pravaha.lane.multiplex.max-queries-per-lane}
 * on a shared lane and by one on a lane a query owns.
 */
public final class LaneBackpressure {

    /** When this lane started being watched, so a fraction has a denominator. */
    private final long startedNanos = System.nanoTime();

    private final ConcurrentHashMap<String, Waiter> byQuery = new ConcurrentHashMap<>();

    /** One writer's episodes. A query with two inputs has two pumps and one of these. */
    private static final class Waiter {
        private final AtomicLong waits = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();

        /** When the episode in progress began, or 0 when this writer is not waiting. */
        private final AtomicLong since = new AtomicLong();

        long nanosIncludingOpenEpisode(long now) {
            long open = since.get();
            return nanos.get() + (open == 0 ? 0 : Math.max(0, now - open));
        }
    }

    /**
     * A writer of {@code queryId} has found no room. Idempotent while the episode is open: a pump
     * that polls a hundred times into a full inbox has waited once, for as long as it took.
     */
    public void waitStarted(String queryId) {
        Waiter waiter = byQuery.computeIfAbsent(key(queryId), name -> new Waiter());
        if (waiter.since.compareAndSet(0L, Math.max(1L, System.nanoTime()))) {
            waiter.waits.incrementAndGet();
        }
    }

    /** That writer has found room. Does nothing when no episode was open. */
    public void waitEnded(String queryId) {
        Waiter waiter = byQuery.get(key(queryId));
        if (waiter == null) {
            return;
        }
        long began = waiter.since.getAndSet(0L);
        if (began != 0L) {
            waiter.nanos.addAndGet(Math.max(0, System.nanoTime() - began));
        }
    }

    /**
     * Records a wait that was measured elsewhere -- the shared-inbox park loop, which blocks inside
     * one call rather than across polls and so knows exactly how long it waited.
     */
    public void waited(String queryId, long nanos) {
        Waiter waiter = byQuery.computeIfAbsent(key(queryId), name -> new Waiter());
        waiter.waits.incrementAndGet();
        waiter.nanos.addAndGet(Math.max(0, nanos));
    }

    private static String key(String queryId) {
        return queryId == null || queryId.isEmpty() ? "" : queryId;
    }

    /**
     * This lane's backpressure now.
     *
     * @param inboxDepth cells published and not yet drained, the fullest of the lane's inputs
     * @param inboxCells what that depth is out of
     */
    public Snapshot snapshot(int inboxDepth, int inboxCells) {
        long now = System.nanoTime();
        long elapsed = Math.max(1L, now - startedNanos);
        long waits = 0;
        long nanos = 0;
        Map<String, QueryWaits> perQuery = new LinkedHashMap<>();
        for (Map.Entry<String, Waiter> entry : byQuery.entrySet()) {
            Waiter waiter = entry.getValue();
            long queryNanos = waiter.nanosIncludingOpenEpisode(now);
            long queryWaits = waiter.waits.get();
            waits += queryWaits;
            nanos += queryNanos;
            perQuery.put(entry.getKey(), new QueryWaits(queryWaits, queryNanos, fraction(queryNanos, elapsed)));
        }
        return new Snapshot(waits, nanos, fraction(nanos, elapsed), elapsed, inboxDepth, inboxCells, perQuery);
    }

    private static double fraction(long blocked, long elapsed) {
        return Math.min(1.0, (double) blocked / elapsed);
    }

    /**
     * A lane's backpressure at one instant.
     *
     * @param waits episodes in which a writer found no room and had to wait
     * @param waitNanos how long those episodes lasted in total. Writers overlap, so this may exceed
     *     {@code observedNanos} on a lane with more than one input
     * @param blockedFraction {@code waitNanos / observedNanos}, clamped at 1. Near zero is headroom;
     *     near one means this lane is the limit
     * @param observedNanos wall clock since the lane was built, which is the fraction's denominator
     * @param inboxDepth cells published and not yet drained, on the fullest input
     * @param inboxCells the inbox's capacity, so a depth can be read as a fraction
     * @param byQuery the same three numbers per query whose writer waited. One entry, keyed by the
     *     empty string, on a lane whose writers were never told a query name
     */
    public record Snapshot(
            long waits,
            long waitNanos,
            double blockedFraction,
            long observedNanos,
            int inboxDepth,
            int inboxCells,
            Map<String, QueryWaits> byQuery) {

        public Snapshot {
            byQuery = Map.copyOf(byQuery);
        }

        /** Nothing has waited: what a lane that has never been full reports. */
        public static final Snapshot NONE = new Snapshot(0, 0, 0, 1, 0, 0, Map.of());

        /** The query whose writer waited longest on this lane, or empty when none did. */
        public java.util.Optional<Map.Entry<String, QueryWaits>> longestWaiter() {
            return byQuery.entrySet().stream()
                    .filter(entry -> entry.getValue().waitNanos() > 0)
                    .max(java.util.Comparator.comparingLong(
                            entry -> entry.getValue().waitNanos()));
        }

        /** Two lanes' backpressure together, for a query that runs on more than one. */
        public Snapshot plus(Snapshot other) {
            Map<String, QueryWaits> merged = new LinkedHashMap<>(byQuery);
            other.byQuery.forEach((name, waits) -> merged.merge(name, waits, QueryWaits::plus));
            long elapsed = Math.max(observedNanos, other.observedNanos);
            long nanos = waitNanos + other.waitNanos;
            return new Snapshot(
                    waits + other.waits,
                    nanos,
                    Math.min(1.0, (double) nanos / Math.max(1L, elapsed)),
                    elapsed,
                    Math.max(inboxDepth, other.inboxDepth),
                    inboxCells + other.inboxCells,
                    merged);
        }
    }

    /** One query's share of a lane's waiting. */
    public record QueryWaits(long waits, long waitNanos, double blockedFraction) {

        QueryWaits plus(QueryWaits other) {
            return new QueryWaits(
                    waits + other.waits,
                    waitNanos + other.waitNanos,
                    Math.min(1.0, blockedFraction + other.blockedFraction));
        }
    }
}
