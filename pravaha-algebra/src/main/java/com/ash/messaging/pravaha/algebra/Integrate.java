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
package com.ash.messaging.pravaha.algebra;

/**
 * The {@code I} operator: a stream of changes becomes a running value.
 *
 * <p>{@code I(s)[t] = s[0] + s[1] + ... + s[t]}. O(1) per update, and its state is exactly the
 * current value -- which is why an integrated operator's state and a served materialized view are
 * the same thing (design section 17.1). Serving a query is reading {@code I}'s state, not a separate
 * copy kept in step with it.
 *
 * <p><strong>Integration is where incremental engines die in production.</strong> The state grows
 * with the support of everything ever added, so an unbounded integrate over an unbounded key space
 * never stops growing. That is why every integrating operator in Pravaha must declare a bound and a
 * query whose state cannot be bounded is rejected at planning rather than accepted and left to
 * exhaust memory at 3 a.m. (design section 9.6).
 *
 * <p>Not thread-safe: one instance per operator instance per lane.
 */
public final class Integrate<T> {

    private ZSet<T> accumulated = ZSet.empty();

    /** Applies a change and returns the new running value. */
    public ZSet<T> apply(ZSet<T> delta) {
        accumulated = accumulated.plus(delta);
        return accumulated;
    }

    /** The running value without applying anything. */
    public ZSet<T> current() {
        return accumulated;
    }

    /** Distinct rows currently held -- the number a state-size bound is enforced against. */
    public int stateSize() {
        return accumulated.size();
    }

    public void reset() {
        accumulated = ZSet.empty();
    }
}
