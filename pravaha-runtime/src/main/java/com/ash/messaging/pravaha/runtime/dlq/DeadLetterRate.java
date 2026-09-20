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
package com.ash.messaging.pravaha.runtime.dlq;

/**
 * Watches how fast records are being rejected, and says when that stops being normal.
 *
 * <p>Every real feed produces some rejects, so a DLQ that alerts on the first one is a DLQ whose
 * alerts get muted in week two. What matters is the <em>rate</em>: a steady trickle of malformed
 * records from one partner is Tuesday, and the same feed suddenly rejecting a third of its records
 * is a schema change nobody announced.
 *
 * <p>Past the threshold the query moves to {@code DEGRADED} (design section 15.6) rather than
 * stopping. Stopping would discard the records that <em>are</em> valid, which is a bigger loss than
 * the ones that are not; degraded says the answer is incomplete and names why, which is what
 * somebody deciding whether to trust a dashboard actually needs.
 *
 * <p>Time is passed in rather than read, so the behaviour is deterministic under replay and testable
 * without sleeping.
 *
 * <p><strong>Synchronised</strong>, because one of these is shared by every pump of a query --
 * several partitions on several threads -- and read by whatever scrapes the metrics. The lock is
 * taken once per poll rather than once per row: a poll reports its whole batch with {@link
 * #recordAccepted(long, long)}.
 */
public final class DeadLetterRate {

    private final double maxFraction;
    private final long windowNanos;
    private final long minimumSample;

    private long windowStartNanos = Long.MIN_VALUE;
    private long accepted;
    private long rejected;
    private boolean degraded;
    private long degradedTransitions;

    /**
     * @param maxFraction the share of records that may be rejected before the query is degraded
     * @param windowNanos how long a measurement window lasts; counts reset at its end so a bad hour
     *     last week does not keep a healthy query degraded
     * @param minimumSample the fewest records a window must see before its fraction means anything.
     *     Without it the first rejected record in a window is a 100 % failure rate, and the query is
     *     degraded by a sample of one.
     */
    public DeadLetterRate(double maxFraction, long windowNanos, long minimumSample) {
        if (maxFraction <= 0 || maxFraction > 1) {
            throw new IllegalArgumentException("the rejected fraction must be in (0, 1], got " + maxFraction);
        }
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("the window must be positive, got " + windowNanos);
        }
        if (minimumSample < 1) {
            throw new IllegalArgumentException("the minimum sample must be at least 1, got " + minimumSample);
        }
        this.maxFraction = maxFraction;
        this.windowNanos = windowNanos;
        this.minimumSample = minimumSample;
    }

    /** One percent of a thousand records a minute: noticeable, and not a hair trigger. */
    public static DeadLetterRate defaults() {
        return new DeadLetterRate(0.01, 60_000_000_000L, 1_000);
    }

    public void recordAccepted(long nowNanos) {
        recordAccepted(nowNanos, 1);
    }

    /**
     * Several accepted records at once.
     *
     * <p>A poll moves up to a thousand rows and knows only the total, so counting them one call at
     * a time would put a method call per row on the hottest path in the engine to reach the same
     * number. The window rolls once for the batch, which is right: they arrived together.
     */
    public synchronized void recordAccepted(long nowNanos, long records) {
        if (records <= 0) {
            return;
        }
        rollWindow(nowNanos);
        accepted += records;
    }

    public synchronized void recordRejected(long nowNanos) {
        rollWindow(nowNanos);
        rejected++;
        long total = accepted + rejected;
        if (total >= minimumSample && (double) rejected / total > maxFraction && !degraded) {
            degraded = true;
            degradedTransitions++;
        }
    }

    private void rollWindow(long nowNanos) {
        if (windowStartNanos == Long.MIN_VALUE) {
            windowStartNanos = nowNanos;
            return;
        }
        if (nowNanos - windowStartNanos >= windowNanos) {
            windowStartNanos = nowNanos;
            accepted = 0;
            rejected = 0;
            // Recovery is automatic and deliberate: a feed that was broken for an hour and has been
            // fine since should not need somebody to notice and clear a flag.
            degraded = false;
        }
    }

    /** Whether the query should be reported as degraded. */
    public synchronized boolean isDegraded() {
        return degraded;
    }

    public synchronized long rejectedInWindow() {
        return rejected;
    }

    public synchronized long acceptedInWindow() {
        return accepted;
    }

    /** The share rejected in this window, or zero before the sample is large enough to mean anything. */
    public synchronized double rejectedFraction() {
        long total = accepted + rejected;
        return total < minimumSample ? 0 : (double) rejected / total;
    }

    /** How often the query has entered the degraded state. Flapping is its own signal. */
    public synchronized long degradedTransitions() {
        return degradedTransitions;
    }
}
