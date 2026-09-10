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

/**
 * What a read is asking for (design section 17.3).
 *
 * <p>Declared per read, not configured per view, because the same view is read by a dashboard that
 * wants the freshest possible number and by a reconciliation that needs a defensible one. Making it
 * a property of the view forces the stricter answer on everybody or the looser one on everybody,
 * and the second is how a person ends up acting on a half-applied batch.
 *
 * <p>Every answer carries its staleness back with it. That is the part competitors omit and the part
 * an auditor asks about: not "is this fresh" but "how stale is this, exactly".
 */
public sealed interface Consistency {

    /**
     * Whatever the view holds right now, including work not yet committed.
     *
     * <p>The lowest latency and the weakest promise. Right for a dashboard, wrong for anything that
     * compares two views: they may be at different points, and the difference between them is not a
     * fact about the data.
     */
    record Latest() implements Consistency {}

    /**
     * As of the latest committed frontier.
     *
     * <p>The default, and the only mode under which two views agree on the same prefix of the input.
     * A read that joins or compares needs this; so does any number a person is going to act on.
     */
    record Consistent() implements Consistency {}

    /**
     * Blocks until the view has committed through {@code frontier}, then reads.
     *
     * <p>Read-your-writes, for a caller that knows an input event's position and wants the view to
     * have caught up with it. Bounded by a timeout, because the alternative is a read that hangs
     * when a source goes quiet -- and a source going quiet is normal.
     */
    record AtLeast(long frontier) implements Consistency {}

    /**
     * As of a past frontier.
     *
     * <p>Reconciliation's mode: what did we see at four o'clock. It needs history the view does not
     * keep -- a view holds the present, and the past lives in checkpoints -- so it is refused with
     * that explanation rather than silently answered with the current value, which would be the
     * worst possible response to an audit question.
     */
    record AsOf(long frontier) implements Consistency {}

    /** The default: what a read gets when it does not say. */
    static Consistency defaultMode() {
        return new Consistent();
    }
}
