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

import java.time.Duration;

/**
 * How much of a query's dead-letter file is kept: a byte bound, a count bound, an age bound.
 *
 * <p><strong>Eviction, not refusal, and this is the decision the batch turned on.</strong> The
 * alternative -- refuse the write once the bound is reached -- reads like the safer choice and is
 * not. Refusing hands the writer a queue that has stopped accepting, and the writer is
 * {@code IngestPump}, which has exactly two things it can then do: fail the poll, which stops the
 * source and is the one outcome the queue exists to prevent, or drop the record, which is the
 * silent loss the queue exists to prevent. And it refuses the <em>newest</em> record, which during
 * an incident is the one somebody is looking at, in favour of keeping ten thousand copies of last
 * month's schema change.
 *
 * <p>So the oldest go, and <strong>the loss is recorded three ways</strong> so that it is never
 * silent: a line per eviction in {@code <query>.dlq.evicted} that survives a restart, a warning in
 * the node's log, and a running count on every surface that shows the queue --
 * {@code pravaha_query_dead_letters_evicted}, the REST and Flight listings, {@code pravaha dlq
 * list} and the console's screen. "Never drop a record silently" is a rule about silence; a bound
 * that announces what it dropped keeps it.
 *
 * <p>The byte bound is the one that is on by default, at {@link #DEFAULT_MAX_BYTES}, and it is
 * spelled {@code pravaha.dlq.max-bytes} after the spill tier's {@code pravaha.state.spill.max-bytes}
 * (ADR-044) -- the same question, the same unit, the same name. A bound that defaults to off is not
 * a bound: the failure it exists to stop is a feed rejecting every record of a renamed column and
 * filling the disk the node's checkpoints are on, and that happens to a deployment that configured a
 * directory and nothing else.
 *
 * @param maxBytes the largest the file may be, or 0 for no byte bound
 * @param maxEntries the most entries it may hold, or 0 for no count bound
 * @param maxAge how old an entry may be, or {@link Duration#ZERO} for no age bound
 */
public record DeadLetterRetention(long maxBytes, long maxEntries, Duration maxAge) {

    /**
     * 256 MiB a query.
     *
     * <p>Large enough to hold a bad hour of a busy feed -- a few hundred thousand records with
     * their bytes -- and small enough that ten queries all rejecting everything cannot take a
     * conventionally sized disk before anybody reads the warning.
     */
    public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    /** How far under the bound a compaction goes, so the rewrite is not paid per record at the ceiling. */
    private static final double TARGET_FRACTION = 0.8;

    public DeadLetterRetention {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("pravaha.dlq.max-bytes cannot be negative, got " + maxBytes);
        }
        if (maxEntries < 0) {
            throw new IllegalArgumentException("pravaha.dlq.max-entries cannot be negative, got " + maxEntries);
        }
        maxAge = maxAge == null ? Duration.ZERO : maxAge;
        if (maxAge.isNegative()) {
            throw new IllegalArgumentException("pravaha.dlq.max-age cannot be negative, got " + maxAge);
        }
    }

    /** The byte bound alone, at its default: what a deployment that set only a directory gets. */
    public static DeadLetterRetention defaults() {
        return new DeadLetterRetention(DEFAULT_MAX_BYTES, 0, Duration.ZERO);
    }

    /** No bound at all. For a test, and for a deployment that has said it wants to keep everything. */
    public static DeadLetterRetention unbounded() {
        return new DeadLetterRetention(0, 0, Duration.ZERO);
    }

    public boolean bounded() {
        return maxBytes > 0 || maxEntries > 0 || !maxAge.isZero();
    }

    /** Whether a file of this size and count is past a bound and has to give something up. */
    public boolean exceeded(long bytes, long entries) {
        return (maxBytes > 0 && bytes > maxBytes) || (maxEntries > 0 && entries > maxEntries);
    }

    /** The size a compaction aims for, comfortably under the bound so the next write does not trigger another. */
    public long targetBytes() {
        return maxBytes <= 0 ? Long.MAX_VALUE : (long) (maxBytes * TARGET_FRACTION);
    }

    /** The count a compaction aims for, for the same reason. */
    public long targetEntries() {
        return maxEntries <= 0 ? Long.MAX_VALUE : (long) (maxEntries * TARGET_FRACTION);
    }

    /** Whether an entry rejected at this wall-clock moment is older than the age bound allows. */
    public boolean tooOld(long wallMillis, long nowMillis) {
        // Zero is "the writer did not record a wall clock", not "1970": an entry from before the
        // clock was recorded is not evidence of age and is never evicted for it.
        return !maxAge.isZero() && wallMillis > 0 && nowMillis - wallMillis > maxAge.toMillis();
    }

    /** What the setting reads as on a surface: {@code 256 MiB, 30d} or {@code unbounded}. */
    public String describe() {
        if (!bounded()) {
            return "unbounded";
        }
        StringBuilder out = new StringBuilder();
        if (maxBytes > 0) {
            out.append(maxBytes).append(" bytes");
        }
        if (maxEntries > 0) {
            out.append(out.isEmpty() ? "" : ", ").append(maxEntries).append(" entries");
        }
        if (!maxAge.isZero()) {
            out.append(out.isEmpty() ? "" : ", ").append(maxAge);
        }
        return out.toString();
    }
}
