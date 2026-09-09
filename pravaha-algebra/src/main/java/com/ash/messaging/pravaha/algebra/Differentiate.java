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
 * The {@code D} operator: a stream of values becomes a stream of changes.
 *
 * <p>{@code D(s)[t] = s[t] - s[t-1]}. Stateless apart from the single previous value, and the exact
 * inverse of {@link Integrate} -- {@code D(I(s)) == s} for every stream, which is the identity the
 * whole incremental construction is built on (design section 9.3).
 *
 * <p>Not thread-safe: one instance per operator instance per lane.
 */
public final class Differentiate<T> {

    private ZSet<T> previous = ZSet.empty();

    /** Feeds the next value and returns the change since the last one. */
    public ZSet<T> apply(ZSet<T> value) {
        ZSet<T> delta = value.minus(previous);
        previous = value;
        return delta;
    }

    /** The last value seen. */
    public ZSet<T> current() {
        return previous;
    }

    public void reset() {
        previous = ZSet.empty();
    }
}
