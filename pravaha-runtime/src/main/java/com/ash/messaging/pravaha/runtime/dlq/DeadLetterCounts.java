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

import java.time.Instant;
import java.util.Optional;

/**
 * How deep one query's dead-letter queue is, and how much of it has already been lost to retention.
 *
 * <p>The depth is the number to alert on and the evicted count is the number that makes the depth
 * honest: a queue holding a steady two thousand entries is either a feed that rejected two thousand
 * records once, or a feed rejecting two thousand a minute against a bound that is throwing the same
 * number away. Those need opposite responses and the depth alone cannot tell them apart.
 *
 * @param entries how many are in the file now
 * @param bytes how large the file is
 * @param evicted how many entries retention has removed since the file was created, from the
 *     eviction record beside it; it survives a restart, because the loss did
 * @param evictedBytes how many bytes those were
 * @param oldestMillis the wall clock of the oldest entry kept, or 0 when unknown
 * @param newestMillis the wall clock of the newest, or 0 when unknown
 * @param replayed how many entries have been replayed successfully
 * @param failedAgain how many were replayed and failed to decode a second time
 */
public record DeadLetterCounts(
        long entries,
        long bytes,
        long evicted,
        long evictedBytes,
        long oldestMillis,
        long newestMillis,
        long replayed,
        long failedAgain) {

    public static DeadLetterCounts empty() {
        return new DeadLetterCounts(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public Optional<Instant> oldest() {
        return oldestMillis <= 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(oldestMillis));
    }

    public Optional<Instant> newest() {
        return newestMillis <= 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(newestMillis));
    }

    /** True when retention has thrown something away, which every surface says out loud. */
    public boolean lossy() {
        return evicted > 0;
    }
}
