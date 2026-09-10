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
package com.ash.messaging.pravaha.runtime.time;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-lane timers, on a wheel rather than a priority queue.
 *
 * <p>A windowed query schedules one timer per open window per key. At a hundred thousand keys and a
 * few overlapping windows each, that is millions of live timers on one lane, and the difference
 * between O(1) and O(log n) per schedule stops being academic -- as does the difference between
 * touching one bucket and walking a heap that does not fit in cache.
 *
 * <p>The wheel is an array of buckets covering one rotation. A deadline inside the rotation goes
 * straight into its bucket; one beyond it waits in an overflow map and is re-filed when the wheel
 * has turned far enough to hold it. That is the hierarchy in the only form this engine needs, since
 * windows fire within seconds to minutes and a far-future deadline is rare enough to cost a map
 * insertion rather than a second wheel.
 *
 * <p><strong>One timer per key, structurally.</strong> Each bucket is a map keyed by the timer's
 * key, so rescheduling replaces rather than accumulates. That is not an optimisation: a session
 * window extends on every record, and an implementation that appended would hold one dead entry per
 * record until its deadline passed -- growth proportional to throughput, shaped exactly like normal
 * operation. The first version of this class used a tombstone scheme that deduped a live-timer map
 * but left the buckets growing, and a test asserting {@code size()} could not see it. The bucket
 * maps make the two numbers identical by construction, which is why {@link #pendingEntries()} is
 * worth exposing: if it ever diverges from {@link #size()}, the structure has broken.
 *
 * <p><strong>Event time, not wall clock.</strong> {@link #advanceTo} is driven by the watermark, so
 * a replay fires exactly the timers the original run fired, in the same order. A wheel driven by a
 * clock would make replay non-deterministic and take the time-travel debugger (design section 16.4)
 * with it.
 *
 * <p>Owned by one lane and confined to its thread.
 */
public final class TimerWheel {

    /** A scheduled timer. The payload is whatever the operator needs in order to fire it. */
    public record Timer(long deadlineNanos, long key, Object payload) {}

    private final long tickNanos;
    private final int bucketCount;
    private final Map<Long, Timer>[] buckets;
    private final Map<Long, Timer> overflow = new HashMap<>();

    /** Each key's current deadline, so a reschedule knows which bucket to clear. */
    private final Map<Long, Long> deadlines = new HashMap<>();

    private long currentTimeNanos = Long.MIN_VALUE;
    private long scheduledCount;
    private long firedCount;
    private long cancelledCount;
    private long ticksWalked;

    @SuppressWarnings("unchecked")
    public TimerWheel(long tickNanos, int bucketCount) {
        if (tickNanos <= 0) {
            throw new IllegalArgumentException("tick must be positive, got " + tickNanos);
        }
        if (bucketCount < 2 || Integer.bitCount(bucketCount) != 1) {
            throw new IllegalArgumentException("bucket count must be a power of two of at least 2, got " + bucketCount);
        }
        this.tickNanos = tickNanos;
        this.bucketCount = bucketCount;
        this.buckets = new Map[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new HashMap<>();
        }
    }

    /** A 100 ms tick over 1024 buckets: roughly 102 seconds a rotation. */
    public static TimerWheel defaults() {
        return new TimerWheel(100_000_000L, 1024);
    }

    /** Schedules a timer, replacing any the key already has. */
    public void schedule(long deadlineNanos, long key, Object payload) {
        Long previous = deadlines.get(key);
        if (previous != null) {
            bucketFor(previous).remove(key);
        }
        if (currentTimeNanos == Long.MIN_VALUE) {
            currentTimeNanos = deadlineNanos - Math.floorMod(deadlineNanos, tickNanos);
        }
        deadlines.put(key, deadlineNanos);
        bucketFor(deadlineNanos).put(key, new Timer(deadlineNanos, key, payload));
        scheduledCount++;
    }

    private Map<Long, Timer> bucketFor(long deadlineNanos) {
        long ticksAhead = Math.floorDiv(deadlineNanos - currentTimeNanos, tickNanos);
        if (ticksAhead >= bucketCount) {
            return overflow;
        }
        long tick = Math.floorDiv(deadlineNanos, tickNanos);
        return buckets[(int) Math.floorMod(tick, bucketCount)];
    }

    /** Cancels a key's timer, removing it rather than marking it. */
    public boolean cancel(long key) {
        Long deadline = deadlines.remove(key);
        if (deadline == null) {
            return false;
        }
        bucketFor(deadline).remove(key);
        cancelledCount++;
        return true;
    }

    /**
     * Fires every timer due at or before {@code timeNanos}, in deadline order.
     *
     * <p>Deadline order costs a sort per advance and is worth it: two windows for one key firing out
     * of order emit their results out of order, and a consumer applying them as they arrive keeps
     * the older one.
     *
     * @param timeNanos the watermark
     */
    public List<Timer> advanceTo(long timeNanos) {
        if (currentTimeNanos == Long.MIN_VALUE) {
            currentTimeNanos = timeNanos;
            return List.of();
        }
        if (timeNanos < currentTimeNanos) {
            // Watermarks do not regress, but a caller may pass an older value during recovery.
            // Firing on it would re-fire windows that have already fired.
            return List.of();
        }

        List<Timer> fired = new ArrayList<>();
        long fromTick = Math.floorDiv(currentTimeNanos, tickNanos);
        // At most one rotation, however far the watermark jumps. A backfill catching up moves it by
        // hours, and walking tick by tick would visit millions of empty buckets to reach the same
        // answer -- every bucket has been covered once the rotation is complete.
        long ticks = Math.min(Math.floorDiv(timeNanos - currentTimeNanos, tickNanos) + 1, bucketCount);
        ticksWalked += ticks;
        for (long i = 0; i < ticks; i++) {
            collectDue(buckets[(int) Math.floorMod(fromTick + i, bucketCount)], timeNanos, fired);
        }
        collectDue(overflow, timeNanos, fired);

        currentTimeNanos = timeNanos;
        // Re-file whatever has come inside a rotation. This is what makes one wheel behave as a
        // hierarchy, and it must happen after the cursor moves or nothing would qualify.
        refileOverflow();

        fired.sort((a, b) -> Long.compare(a.deadlineNanos(), b.deadlineNanos()));
        firedCount += fired.size();
        return fired;
    }

    private void collectDue(Map<Long, Timer> bucket, long timeNanos, List<Timer> fired) {
        Iterator<Map.Entry<Long, Timer>> entries = bucket.entrySet().iterator();
        while (entries.hasNext()) {
            Timer timer = entries.next().getValue();
            if (timer.deadlineNanos() <= timeNanos) {
                entries.remove();
                deadlines.remove(timer.key());
                fired.add(timer);
            }
        }
    }

    private void refileOverflow() {
        if (overflow.isEmpty()) {
            return;
        }
        List<Timer> arrived = new ArrayList<>();
        for (Timer timer : overflow.values()) {
            if (Math.floorDiv(timer.deadlineNanos() - currentTimeNanos, tickNanos) < bucketCount) {
                arrived.add(timer);
            }
        }
        for (Timer timer : arrived) {
            overflow.remove(timer.key());
            long tick = Math.floorDiv(timer.deadlineNanos(), tickNanos);
            buckets[(int) Math.floorMod(tick, bucketCount)].put(timer.key(), timer);
        }
    }

    /** Timers scheduled and not yet fired or cancelled. */
    public int size() {
        return deadlines.size();
    }

    /**
     * Entries physically held in the buckets.
     *
     * <p>Equal to {@link #size()} by construction, and exposed so that a test can assert it. The
     * first version of this class deduped a live-timer map while letting bucket entries accumulate
     * on every reschedule, and no assertion on {@code size()} could see the difference -- seeding
     * "reschedule adds instead of replaces" passed. If these two numbers ever diverge, the bucket
     * maps have stopped being the single place a timer lives.
     */
    public int pendingEntries() {
        int total = overflow.size();
        for (Map<Long, Timer> bucket : buckets) {
            total += bucket.size();
        }
        return total;
    }

    /**
     * Bucket-ticks visited by {@link #advanceTo}, cumulative.
     *
     * <p>The bound that makes a catching-up backfill affordable. Counted rather than timed: a
     * 500 ms budget on a two-hour jump passed with the bound removed, because 72 000 empty lookups
     * take under a millisecond -- so a timing assertion would only have failed at a jump size nobody
     * would have thought to write.
     */
    public long ticksWalked() {
        return ticksWalked;
    }

    public long currentTimeNanos() {
        return currentTimeNanos;
    }

    public long scheduledCount() {
        return scheduledCount;
    }

    public long firedCount() {
        return firedCount;
    }

    public long cancelledCount() {
        return cancelledCount;
    }

    @Override
    public String toString() {
        return "TimerWheel[" + size() + " live, tick=" + tickNanos / 1_000_000 + "ms, " + bucketCount + " buckets, "
                + overflow.size() + " in overflow]";
    }
}
