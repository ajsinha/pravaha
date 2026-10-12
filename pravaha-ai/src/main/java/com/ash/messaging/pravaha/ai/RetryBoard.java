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
package com.ash.messaging.pravaha.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Providers that could not be built, and when each is tried again (Drishti's {@code RetryBoard}): the
 * wait doubles from {@code initial} to {@code max} with every failure. A failure that will not mend by
 * itself (a configuration mistake: a literal key, an unknown type) is not retried until its file changes,
 * which {@link #forget} records.
 */
public final class RetryBoard {

    /**
     * One failing provider.
     *
     * @param attempts failures so far
     * @param nextRetry when it is tried again ({@link Instant#MAX} when only a changed file will mend it)
     * @param lastError why it failed last
     * @param permanent whether only a changed file will mend it
     */
    public record Status(int attempts, Instant nextRetry, String lastError, boolean permanent) {}

    private final Map<String, Status> failing = new ConcurrentHashMap<>();
    private final Duration initial;
    private final Duration max;
    private final Clock clock;

    public RetryBoard(Duration initial, Duration max, Clock clock) {
        this.initial = initial;
        this.max = max;
        this.clock = clock;
    }

    /** Five seconds doubling to five minutes. */
    public static RetryBoard defaults() {
        return new RetryBoard(Duration.ofSeconds(5), Duration.ofMinutes(5), Clock.systemUTC());
    }

    /** Records a failure. */
    public void failed(String name, String error, boolean permanent) {
        failing.compute(name, (k, prev) -> {
            int attempts = prev == null ? 1 : prev.attempts() + 1;
            long factor = 1L << Math.min(20, attempts - 1);
            Duration wait = initial.multipliedBy(factor);
            if (wait.compareTo(max) > 0) {
                wait = max;
            }
            Instant next = permanent ? Instant.MAX : clock.instant().plus(wait);
            return new Status(attempts, next, error, permanent);
        });
    }

    /** Records a success: no longer failing. */
    public void succeeded(String name) {
        failing.remove(name);
    }

    /** Forgets a provider (its file changed or went away). */
    public void forget(String name) {
        failing.remove(name);
    }

    /** Whether a provider is failing. */
    public boolean failing(String name) {
        return failing.containsKey(name);
    }

    /** The failing providers whose time to try again has come. */
    public List<String> due() {
        Instant now = clock.instant();
        return failing.entrySet().stream()
                .filter(e -> !e.getValue().nextRetry().isAfter(now))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Every failing provider, by name. */
    public Map<String, Status> statuses() {
        return new TreeMap<>(failing);
    }

    /** "Retry now": every failing provider is due at once. Returns how many were failing. */
    public int retryAllNow() {
        Instant now = clock.instant();
        failing.replaceAll((k, s) -> new Status(s.attempts(), now, s.lastError(), s.permanent()));
        return failing.size();
    }
}
