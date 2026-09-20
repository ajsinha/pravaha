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
 * What one query's dead-letter queue looks like right now, read without touching the file.
 *
 * <p>Every number here comes from counters the writer keeps as it writes, which is the point: a
 * gauge is scraped every fifteen seconds, and a gauge that answered by walking a file of up to
 * {@link DeadLetterRetention#DEFAULT_MAX_BYTES} would make observing the queue more expensive than
 * filling it. {@link DeadLetterStore} reads the file and answers richer questions; this answers
 * the four a dashboard asks.
 *
 * @param depth entries in the file now -- what to alert on, since the running total keeps rising
 *     for a queue somebody is on top of
 * @param bytes how large the file is, against the byte bound
 * @param evicted entries retention has removed since the queue was opened, and will not give back
 * @param evictedBytes how many bytes those were
 * @param writeFailures entries the queue itself could not write; non-zero means the DLQ needs
 *     attention before the records do
 * @param rejectedFraction the share of records rejected in the current window, from {@link
 *     DeadLetterRate}; zero until the window has seen enough records for the share to mean
 *     anything
 * @param degraded whether that share has passed the threshold -- design 15.6's DEGRADED, which
 *     says the answer is incomplete and why rather than stopping a query whose valid records are
 *     still worth having
 */
public record DeadLetterHealth(
        long depth,
        long bytes,
        long evicted,
        long evictedBytes,
        long writeFailures,
        double rejectedFraction,
        boolean degraded) {

    /** A query with no dead-letter queue attached: not "zero rejected", but "nothing is counting". */
    public static DeadLetterHealth none() {
        return new DeadLetterHealth(0, 0, 0, 0, 0, 0, false);
    }
}
