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
package com.ash.messaging.pravaha.serving;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;

/**
 * How many reads may be in flight at once, and what happens to the rest (ADR-030).
 *
 * <p>This exists because of what ADR-030 decided. Once one engine answers both continuous queries
 * and request/response, the reads and the streams share a machine, and an unbounded read path is a
 * way for a client with a loop to stop a continuous query from keeping up with its input. The
 * continuous query is the thing with a service level; the read is the thing that can be told to
 * come back.
 *
 * <p>Three limits, and they are separate because they fail differently:
 *
 * <ul>
 *   <li><b>Concurrency</b> bounds the work happening at once. Reads run on the calling thread, not
 *       on lane threads (§17), so this bounds memory and CPU contention rather than lanes.
 *   <li><b>Queue depth</b> bounds the work <em>waiting</em>. A queue deeper than the client's
 *       timeout is a queue of work nobody is waiting for any more, which the server will
 *       nevertheless do, at the expense of work somebody is.
 *   <li><b>Per-tenant share</b> bounds any one tenant's use of the first two. Without it, the
 *       fairest possible global limit still lets one tenant hold every permit, and the symptom
 *       reported by the other tenants is "Pravaha is down".
 * </ul>
 *
 * <p>Refusal is a feature. A server that queues without limit converts a load problem into a
 * latency problem and then into a memory problem; a server that refuses gives the client something
 * to retry or shed, and gives the operator a number that goes up before anything breaks.
 */
public final class ReadAdmission {

    /** Unlimited, for an embedded engine whose caller is already the thing being throttled. */
    public static final ReadAdmission UNLIMITED = new ReadAdmission(Integer.MAX_VALUE, 0, 1.0, Duration.ZERO);

    private final int maxConcurrent;
    private final int maxQueued;
    private final int perTenantLimit;
    private final long queueTimeoutNanos;

    private final Semaphore permits;
    private final AtomicInteger queued = new AtomicInteger();
    private final Map<String, AtomicInteger> perTenant = new ConcurrentHashMap<>();

    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong queueTimedOut = new AtomicLong();
    private final AtomicLong tenantRejected = new AtomicLong();

    /**
     * @param maxConcurrent reads running at once
     * @param maxQueued reads permitted to wait; zero means a read either starts now or is refused
     * @param tenantShare the fraction of {@code maxConcurrent} any one tenant may hold, in (0, 1].
     *     One means no per-tenant limit
     * @param queueTimeout how long a read waits for a permit before giving up
     */
    public ReadAdmission(int maxConcurrent, int maxQueued, double tenantShare, Duration queueTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1, got " + maxConcurrent);
        }
        if (maxQueued < 0) {
            throw new IllegalArgumentException("maxQueued must not be negative, got " + maxQueued);
        }
        if (!(tenantShare > 0.0) || tenantShare > 1.0) {
            throw new IllegalArgumentException("tenantShare must be in (0, 1], got " + tenantShare);
        }
        this.maxConcurrent = maxConcurrent;
        this.maxQueued = maxQueued;
        // Rounded up, and never below one: a share that rounds to zero would refuse every read from
        // every tenant, which is a configuration mistake that should not become an outage.
        this.perTenantLimit = Math.max(1, (int) Math.ceil(maxConcurrent * tenantShare));
        this.queueTimeoutNanos = queueTimeout == null ? 0L : Math.max(0L, queueTimeout.toNanos());
        // Fair, because the alternative is that a steady stream of new arrivals starves whoever
        // waited longest, and the starved request is the one whose user is already unhappy.
        this.permits = new Semaphore(maxConcurrent, true);
    }

    /** A sensible default for a node serving reads alongside continuous queries. */
    public static ReadAdmission of(int maxConcurrent) {
        return new ReadAdmission(maxConcurrent, maxConcurrent * 2, 0.5, Duration.ofSeconds(2));
    }

    /**
     * Takes a permit, or refuses.
     *
     * <p>The lease must be closed, which is why it is {@link AutoCloseable} and why every caller
     * uses try-with-resources. A leaked lease does not fail here -- it fails later, as a server that
     * has quietly lost some of its capacity, which is the hardest kind of problem to attribute.
     */
    public Lease acquire(Principal principal) {
        String tenant = principal == null ? "public" : principal.tenant();
        AtomicInteger held = perTenant.computeIfAbsent(tenant, ignored -> new AtomicInteger());

        int afterTenant = held.incrementAndGet();
        if (afterTenant > perTenantLimit) {
            held.decrementAndGet();
            tenantRejected.incrementAndGet();
            throw new PravahaException(
                    ServingErrors.TENANT_QUOTA_EXCEEDED,
                    "tenant '" + tenant + "' already has " + perTenantLimit
                            + " reads in flight, which is its share of this node's " + maxConcurrent
                            + ". Retry shortly; this limit exists so that one tenant's load is not "
                            + "reported as an outage by the others");
        }

        boolean acquired = false;
        try {
            if (permits.tryAcquire()) {
                acquired = true;
            } else {
                if (!reserveQueueSlot()) {
                    rejected.incrementAndGet();
                    throw new PravahaException(
                            ServingErrors.READ_REJECTED,
                            "this node is running " + maxConcurrent + " reads with " + maxQueued
                                    + " waiting, and is refusing rather than queueing deeper. A queue "
                                    + "longer than the client's timeout is work nobody is waiting for");
                }
                // The slot is already ours (reserveQueueSlot); every path out of the wait gives it back.
                try {
                    acquired = permits.tryAcquire(queueTimeoutNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PravahaException(
                            ServingErrors.READ_QUEUE_TIMED_OUT, "interrupted while waiting for a read permit", e);
                } finally {
                    queued.decrementAndGet();
                }
                if (!acquired) {
                    queueTimedOut.incrementAndGet();
                    throw new PravahaException(
                            ServingErrors.READ_QUEUE_TIMED_OUT,
                            "waited " + Duration.ofNanos(queueTimeoutNanos).toMillis()
                                    + "ms for a read permit and did not get one; the node is saturated");
                }
            }
            admitted.incrementAndGet();
            return new Lease(held);
        } finally {
            if (!acquired) {
                held.decrementAndGet();
            }
        }
    }

    /**
     * Takes one of the {@code maxQueued} waiting places, or says there is none.
     *
     * <p>A compare-and-set, not a read then an increment: with the two separate, a burst of arrivals
     * could each see room and together queue past the limit (J21-4).
     */
    private boolean reserveQueueSlot() {
        while (true) {
            int depth = queued.get();
            if (depth >= maxQueued) {
                return false;
            }
            if (queued.compareAndSet(depth, depth + 1)) {
                return true;
            }
        }
    }

    /** Reads running right now. */
    public int inFlight() {
        return maxConcurrent - permits.availablePermits();
    }

    /** Reads waiting for a permit. */
    public int waiting() {
        return queued.get();
    }

    public long admittedCount() {
        return admitted.get();
    }

    /** Refused because the node was saturated and the queue was full. */
    public long rejectedCount() {
        return rejected.get();
    }

    /** Refused after waiting. Counted apart from {@link #rejectedCount()} because the fix differs. */
    public long queueTimedOutCount() {
        return queueTimedOut.get();
    }

    /** Refused because one tenant was over its share, with capacity possibly still free. */
    public long tenantRejectedCount() {
        return tenantRejected.get();
    }

    @Override
    public String toString() {
        return "ReadAdmission[concurrent=" + inFlight() + "/" + maxConcurrent + ", waiting=" + waiting() + "/"
                + maxQueued + ", perTenant=" + perTenantLimit + "]";
    }

    /** A permit held for the duration of one read. */
    public final class Lease implements AutoCloseable {

        private final AtomicInteger tenantCount;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(AtomicInteger tenantCount) {
            this.tenantCount = tenantCount;
        }

        @Override
        public void close() {
            // Idempotent, because a caller that closes in a finally *and* on an error path should
            // not hand the node a permit it never had. Atomic, because a plain flag let two threads
            // closing one lease at once both release (J21-5).
            if (!released.compareAndSet(false, true)) {
                return;
            }
            tenantCount.decrementAndGet();
            permits.release();
        }
    }
}
