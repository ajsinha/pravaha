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
 * How long a view keeps a row it is no longer being told about.
 *
 * <p><strong>A default applies unless a registration chooses otherwise</strong>
 * ({@link #DEFAULT} -- a day, or a million rows, whichever binds first). {@link #forever()} exists
 * and has to be asked for by name, because a view is bounded only if its <em>key space</em> is
 * bounded, and nothing can tell in advance whether it is. Keying a windowed aggregate on a customer
 * bounds it at the number of customers; keying a pass-through feed on an event id does not bound it
 * at all, and the two are one word apart in the SQL. Defaulting to forever would make every
 * registration a leak that nobody had decided to accept.
 *
 * <p>Some views bound themselves. A windowed aggregate releases a window's state when the window
 * closes, so the view holds one row per live window and nothing accumulates. For those,
 * {@link #forever()} is right and the ceiling is a backstop against a query somebody got wrong.
 *
 * <p>A view over a query that does not aggregate bounds nothing. Every event is a new key, so the
 * view grows with the feed for as long as it runs -- and "the node dies eventually" is not a design.
 * A feed has a useful lifetime, usually a session or a day, and after that the rows are history that
 * belongs in the store the data came from rather than in memory. Saying so is what retention is.
 *
 * <p><strong>Eviction is forgetting, not retraction.</strong> An evicted row is not published to
 * subscribers as a {@code -1}: the trade was not cancelled, it aged out of a cache. A consumer
 * keeping its own copy from the change stream therefore keeps whatever it chose to keep, and may
 * legitimately hold more than the view does. Emitting retractions instead would tell every consumer
 * that data had been withdrawn, which would be a lie with consequences.
 */
public record Retention(Duration maxAge, int maxRows) {

    private static final Retention FOREVER = new Retention(null, Integer.MAX_VALUE);

    /** The default when a registration does not choose: a day, or a million rows, whichever binds. */
    public static final Retention DEFAULT = new Retention(Duration.ofHours(24), 1_000_000);

    public Retention {
        if (maxAge != null && (maxAge.isZero() || maxAge.isNegative())) {
            throw new IllegalArgumentException("a retention age must be positive, got " + maxAge);
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException("a view must be allowed at least one row, got " + maxRows);
        }
    }

    /**
     * Keep everything, and mean it.
     *
     * <p>Right when the key space is genuinely bounded and known -- a view keyed on branch, or on
     * instrument, where the ceiling is the number of branches. Asked for by name rather than
     * arrived at by omission, so that a view which grows without limit is one somebody chose.
     */
    public static Retention forever() {
        return FOREVER;
    }

    /**
     * Keep rows whose event time is within {@code age} of the committed frontier.
     *
     * <p>Measured in <em>event time</em>, not wall clock, so a replay of yesterday retains the same
     * rows it would have retained yesterday. A retention policy that consulted the clock would make
     * a reprocessed result differ from the original, which is the property the whole engine is built
     * to avoid.
     */
    public static Retention ofAge(Duration age) {
        return new Retention(age, Integer.MAX_VALUE);
    }

    /** Keep the most recently updated {@code rows} keys, evicting the oldest first. */
    public static Retention ofRows(int rows) {
        return new Retention(null, rows);
    }

    /** Both bounds; a row is evicted when it fails either. */
    public static Retention of(Duration age, int rows) {
        return new Retention(age, rows);
    }

    public boolean isForever() {
        return maxAge == null && maxRows == Integer.MAX_VALUE;
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
        if (isForever()) {
            return "forever";
        }
        StringBuilder text = new StringBuilder();
        if (maxAge != null) {
            text.append(maxAge);
        }
        if (maxRows != Integer.MAX_VALUE) {
            text.append(text.isEmpty() ? "" : " or ").append(maxRows).append(" rows");
        }
        return text.toString();
    }
}
