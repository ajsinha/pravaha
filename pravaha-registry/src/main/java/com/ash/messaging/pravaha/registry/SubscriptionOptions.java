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
package com.ash.messaging.pravaha.registry;

/**
 * What a subscriber wants done when it cannot keep up (ADR-026).
 *
 * <p>Every subscription has a bounded buffer, and the only real question is what happens when it
 * fills. Blocking is not on the list: a subscriber that blocks the engine applies backpressure to
 * the <em>query</em>, so one slow dashboard would slow the computation for everybody reading it,
 * including the people who are keeping up.
 *
 * @param bufferRows how many changes may wait for this subscriber
 * @param overflow what to do when that is exceeded
 */
public record SubscriptionOptions(int bufferRows, Overflow overflow) {

    /** A sensible default: ten thousand rows, conflated by key. */
    public static final SubscriptionOptions DEFAULT = new SubscriptionOptions(10_000, Overflow.CONFLATE);

    public SubscriptionOptions {
        if (bufferRows < 1) {
            throw new IllegalArgumentException("bufferRows must be at least 1, got " + bufferRows);
        }
        overflow = overflow == null ? Overflow.CONFLATE : overflow;
    }

    public static SubscriptionOptions of(int bufferRows, Overflow overflow) {
        return new SubscriptionOptions(bufferRows, overflow);
    }

    /** What happens to a subscriber that falls behind. */
    public enum Overflow {

        /**
         * Replace the waiting change for a key with the newer one.
         *
         * <p>Right for a dashboard, which wants the current value and does not care how many times
         * it changed while nobody was looking. Wrong for anything maintaining its own aggregate
         * from the weights, because conflating drops the intermediate weights that aggregate is
         * built from.
         */
        CONFLATE,

        /**
         * Drop the oldest waiting change and count it.
         *
         * <p>Right when recency matters more than completeness and keys are not meaningful. The
         * count is reported, because a subscriber silently missing data is the failure this whole
         * enum exists to make visible.
         */
        DROP_OLDEST,

        /**
         * Fail the subscription.
         *
         * <p>Right when missing a change is not acceptable -- a ledger, an audit feed. The
         * subscriber finds out immediately and can reconnect and re-read the view, rather than
         * carrying on with a gap it does not know about.
         */
        FAIL
    }
}
