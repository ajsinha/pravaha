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
package com.ash.messaging.pravaha.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Failed sign-ins counted per account <em>and source</em>, so the source that fails is the one barred
 * (LOCKENUM-1).
 *
 * <p>An account lock counted over every source let anyone who knew a user name lock that user out --
 * the bootstrap {@code admin} included -- for 30 minutes with five requests, and again every 30
 * minutes, for ever. Counted per source, five failures from one address bar that address from that
 * account for {@code lockoutFor}; the user signing in from anywhere else is not affected. What stops a
 * guesser spread over many addresses is the account-wide ceiling {@link IdentityService} keeps beside
 * this, at {@link #ACCOUNT_WIDE_FACTOR} times the per-source number.
 *
 * <p>In memory, not in the identity store: a restart forgets which addresses were barred, which costs
 * a guesser nothing it could cause. Bounded: at most {@link #MAX_TRACKED} (account, source) pairs, the
 * expired forgotten first; past that a new pair is not tracked, and the account-wide ceiling still
 * counts its failures, so filling the table buys a guesser nothing either.
 *
 * <p>Not thread-safe: {@link IdentityService} calls it under its own lock.
 */
final class SignInThrottle {

    /** The account-wide ceiling is this many times the per-source number of failures. */
    static final int ACCOUNT_WIDE_FACTOR = 10;

    /** At most this many (account, source) pairs are remembered. */
    static final int MAX_TRACKED = 10_000;

    private record Failures(
            int count, Instant first, @Nullable Instant barredUntil) {}

    private final Map<String, Failures> bySource = new LinkedHashMap<>();
    private final int failures;
    private final Duration window;
    private final Duration barFor;

    SignInThrottle(int failures, Duration window, Duration barFor) {
        this.failures = failures;
        this.window = window;
        this.barFor = barFor;
    }

    private static String key(String username, @Nullable String source) {
        return username + '\u0000' + (source == null ? "" : source);
    }

    /** Whether {@code source} is barred from signing in as {@code username} now. */
    boolean barred(String username, @Nullable String source, Instant now) {
        Failures f = bySource.get(key(username, source));
        return f != null && f.barredUntil() != null && now.isBefore(f.barredUntil());
    }

    /**
     * Counts a failure from {@code source} against {@code username}.
     *
     * @return true when this failure bars the source
     */
    boolean failed(String username, @Nullable String source, Instant now) {
        String key = key(username, source);
        Failures f = bySource.get(key);
        if (f == null && !room(now)) {
            return false;
        }
        boolean fresh = f == null || f.first().plus(window).isBefore(now);
        int count = f == null || fresh ? 1 : f.count() + 1;
        Instant first = f == null || fresh ? now : f.first();
        Instant barred = count >= failures ? now.plus(barFor) : null;
        bySource.put(key, new Failures(barred == null ? count : 0, barred == null ? first : now, barred));
        return barred != null;
    }

    /** A sign-in from {@code source} succeeded: its count for {@code username} starts again. */
    void succeeded(String username, @Nullable String source) {
        bySource.remove(key(username, source));
    }

    /** Forgets every source's count for {@code username}: its password was just set. */
    void forget(String username) {
        String prefix = username + '\u0000';
        bySource.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** How many pairs are remembered, for tests. */
    int tracked() {
        return bySource.size();
    }

    private boolean room(Instant now) {
        if (bySource.size() < MAX_TRACKED) {
            return true;
        }
        for (Iterator<Failures> it = bySource.values().iterator(); it.hasNext(); ) {
            Failures f = it.next();
            boolean over = f.barredUntil() != null
                    ? !now.isBefore(f.barredUntil())
                    : f.first().plus(window).isBefore(now);
            if (over) {
                it.remove();
            }
        }
        return bySource.size() < MAX_TRACKED;
    }
}
