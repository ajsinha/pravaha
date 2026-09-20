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
 * <p><strong>That is the design, and in process it is not yet the behaviour</strong> (STRM-8).
 * {@code ViewSink.commit} hands each committed batch to its listeners <em>serially, on the
 * committing thread</em>, and {@code Subscription.onCommit} drains the buffer before returning --
 * so a consumer that takes two seconds makes the commit take two seconds, three of them make it
 * take six, and in a configured node that caller is {@code PumpingFeed}'s publish timer, which
 * drives every query on that feed. Measured at 2001 ms and 6002 ms against a 20 ms publish
 * cadence.
 *
 * <p>It also means {@code bufferRows} does not bound how far behind a subscriber may fall, which
 * is what this javadoc used to say it did: nothing is ever left in the buffer between commits, so
 * it bounds <em>one commit</em>. A slow consumer conflates nothing and a fast one conflates
 * whatever a single large commit overflows -- the opposite of what the names suggest.
 *
 * <p><strong>Over Flight none of this applies</strong>, and that is where most subscribers are: the
 * gateway's consumer only offers the batch to a bounded hand-over and returns, so the network and
 * the client are already off the engine's thread. What is exposed is an <em>in-process</em>
 * subscriber -- an embedder, the Spring starter's listener container without its own executor, the
 * console's own consumer. Closing it means delivering off the committing thread, which changes the
 * delivery contract for every in-process subscriber and every test that asserts on one, so it is
 * scheduled as its own batch rather than done in passing.
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
