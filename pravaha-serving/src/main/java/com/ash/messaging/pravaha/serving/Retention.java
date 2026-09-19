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

/**
 * How long a view keeps a row, in <strong>event time</strong>.
 *
 * <p>Time, and only time. A streaming answer is an answer about a period, and the period is the
 * thing a retention policy exists to state: "the last hour", "today". A row-count bound was tried
 * here and removed, because it makes the view's <em>meaning</em> depend on throughput -- "the last
 * million rows" is four hours on a quiet day and twenty minutes on a busy one, so nobody can say
 * what the view contains without also knowing the volume. That is exactly the property event-time
 * semantics exist to eliminate.
 *
 * <p>Counting rows is still worth doing; it is just not a retention policy. It is a <em>capacity
 * ceiling</em>, and the view already has one. The two answer different questions:
 *
 * <table border="1">
 *   <caption>Two different bounds</caption>
 *   <tr><th></th><th>Retention</th><th>Ceiling</th></tr>
 *   <tr><td>Says</td><td>what the view means</td><td>what the node can afford</td></tr>
 *   <tr><td>Measured in</td><td>event time</td><td>rows</td></tr>
 *   <tr><td>When exceeded</td><td>the oldest rows are forgotten — the policy working</td>
 *       <td>the view refuses — the capacity plan was wrong</td></tr>
 * </table>
 *
 * <p><strong>A registration that does not choose keeps its rows forever</strong> ({@link
 * #forever()}, since TY-21; {@code QueryRegistry}'s default retention says why). It used to be
 * {@link #DEFAULT}, a day of event time, and that silently shortened any view whose data spans
 * longer: one row near "now" evicted everything a day behind it, and a short answer looks exactly
 * like a complete one. Forever is not unbounded -- the view's key ceiling still refuses loudly
 * ({@code PRV-4001}) -- so a view whose key space grows is stopped rather than quietly trimmed. A
 * bound is still worth stating whenever the key space is not bounded by construction: keying a
 * windowed aggregate on a customer bounds it at the number of customers, keying a pass-through
 * feed on an event id does not bound it at all, and the two are one word apart in the SQL.
 *
 * <p><strong>Eviction is forgetting, not retraction.</strong> An evicted row is not published to
 * subscribers as a {@code -1}: the trade was not cancelled, it aged out of a cache. A consumer
 * keeping its own copy from the change stream therefore keeps whatever it chose to keep, and may
 * legitimately hold more than the view does. Emitting retractions instead would tell every consumer
 * that data had been withdrawn, which would be a lie with consequences.
 */
public record Retention(Duration maxAge) {

    private static final Retention FOREVER = new Retention(null);

    /**
     * A day of event time: the default before TY-21, and no longer what a registration gets when it
     * does not choose ({@link #forever()} is). Kept because a journal record written without a
     * retention decodes to it -- the retention such a registration had when it was written.
     */
    public static final Retention DEFAULT = new Retention(Duration.ofHours(24));

    public Retention {
        if (maxAge != null && (maxAge.isZero() || maxAge.isNegative())) {
            throw new IllegalArgumentException("a retention age must be positive, got " + maxAge);
        }
    }

    /**
     * Keep rows whose event time is within {@code age} of the committed frontier.
     *
     * <p>Event time, not wall clock, so a replay of yesterday retains the rows yesterday retained. A
     * policy that consulted the clock would make a reprocessed result differ from the original,
     * which is the property the whole engine is built to avoid.
     */
    public static Retention ofAge(Duration age) {
        return new Retention(age);
    }

    /**
     * Keep everything, and mean it.
     *
     * <p>Right when the key space is genuinely bounded and known -- a view keyed on branch, or on
     * instrument, where the ceiling is the number of branches. Asked for by name rather than
     * arrived at by omission, so a view that grows without limit is one somebody chose.
     */
    public static Retention forever() {
        return FOREVER;
    }

    public boolean isForever() {
        return maxAge == null;
    }

    /** The event-time frontier before which rows are no longer kept. */
    long horizonFor(long committedFrontier) {
        if (maxAge == null || committedFrontier == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        long nanos = maxAge.toNanos();
        // Saturating, so a frontier near the bottom of the range does not wrap into the future and
        // evict everything.
        return committedFrontier - nanos > committedFrontier ? Long.MIN_VALUE : committedFrontier - nanos;
    }

    @Override
    public String toString() {
        return isForever() ? "forever" : maxAge.toString();
    }
}
