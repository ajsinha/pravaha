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
package com.ash.messaging.pravaha.registry.alert;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the alerts have done since this node started, counted for a meter registry to read: keys fired
 * and cleared, notifications delivered, failed and retried per channel, how long each channel took, and
 * journal writes that failed.
 *
 * <p>Plain counters, not meters: the engine has no metrics library, and the server publishes these
 * through function counters that read them at scrape time -- the same way it publishes every per-query
 * number. Counted from zero at each start; a scrape's {@code rate()} makes that invisible.
 *
 * <p><strong>Labels are bounded by configuration.</strong> An alert's name and a channel's name are
 * both things an administrator made; a key, a row or a notification's content is never counted by.
 */
public final class AlertStatistics {

    /** A transition's kind, as the {@code kind} label says it. */
    public static final String FIRED = "fired";

    public static final String CLEARED = "cleared";

    /** One channel's deliveries. */
    public static final class Channel {
        private final LongAdder delivered = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder retries = new LongAdder();
        private final LongAdder sends = new LongAdder();
        private final LongAdder nanos = new LongAdder();

        /** Notifications the channel accepted. */
        public long delivered() {
            return delivered.sum();
        }

        /** Notifications the channel did not accept, each attempt counted. */
        public long failed() {
            return failed.sum();
        }

        /** Attempts that were a second or later try at a notification a channel had refused before. */
        public long retries() {
            return retries.sum();
        }

        /** Sends timed, whatever they answered. */
        public long sends() {
            return sends.sum();
        }

        /** Their total time, in nanoseconds. */
        public long totalNanos() {
            return nanos.sum();
        }
    }

    private final Map<String, LongAdder> fired = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> cleared = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final LongAdder journalFailures = new LongAdder();

    void transition(String alert, String kind) {
        (FIRED.equals(kind) ? fired : cleared)
                .computeIfAbsent(alert, a -> new LongAdder())
                .increment();
    }

    void sent(String channel, boolean delivered, boolean retry, long nanos) {
        Channel counted = channel(channel);
        (delivered ? counted.delivered : counted.failed).increment();
        if (retry) {
            counted.retries.increment();
        }
        counted.sends.increment();
        counted.nanos.add(Math.max(0, nanos));
    }

    void journalFailed() {
        journalFailures.increment();
    }

    /** Keys of {@code alert} that fired ({@link #FIRED}) or cleared ({@link #CLEARED}) since the start. */
    public long transitions(String alert, String kind) {
        LongAdder counted = (FIRED.equals(kind) ? fired : cleared).get(alert);
        return counted == null ? 0 : counted.sum();
    }

    /** The alerts that have made a transition, by name. */
    public Set<String> alertsCounted() {
        Set<String> names = new TreeSet<>(fired.keySet());
        names.addAll(cleared.keySet());
        return names;
    }

    /** One channel's counts; a channel nothing was sent through yet reads zero everywhere. */
    public Channel channel(String channel) {
        return channels.computeIfAbsent(channel, c -> new Channel());
    }

    /** Journal writes that failed: decisions that could not be made durable, so were not made. */
    public long journalFailures() {
        return journalFailures.sum();
    }

    /** Forgets a dropped alert's counts, so a meter for it is not kept. */
    void forget(String alert) {
        fired.remove(alert);
        cleared.remove(alert);
    }
}
