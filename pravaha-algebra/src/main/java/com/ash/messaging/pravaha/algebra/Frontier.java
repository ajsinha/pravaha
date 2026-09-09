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
 * The lower bound on timestamps an operator may still receive.
 *
 * <p>A frontier is a promise about the future: once it passes time {@code T}, no input at or before
 * {@code T} will arrive, so any result depending only on {@code <= T} is final and may be emitted.
 * Watermarks (design section 15.2) are the physical carrier; this is the semantic object.
 *
 * <p>Frontiers are what make cross-view consistency possible, which is the property no Flink
 * deployment offers and Materialize sells on: if every view advances only as its inputs' frontiers
 * advance, then reading several views "as of {@code T}" returns results reflecting exactly the same
 * prefix of the input. Without it, a dashboard joining two streaming aggregates shows numbers that
 * never quite reconcile, and every analyst learns to distrust it.
 *
 * <p>Monotonic by construction: {@link #advanceTo} refuses to regress. A frontier that could move
 * backwards would let an operator un-finalise a result it has already emitted.
 */
public final class Frontier implements Comparable<Frontier> {

    /** Nothing has arrived; every timestamp is still possible. */
    public static final Frontier INITIAL = new Frontier(Long.MIN_VALUE);

    /** No further input will ever arrive. The stream has ended. */
    public static final Frontier COMPLETE = new Frontier(Long.MAX_VALUE);

    private final long nanos;

    private Frontier(long nanos) {
        this.nanos = nanos;
    }

    public static Frontier at(long epochNanos) {
        return new Frontier(epochNanos);
    }

    public long nanos() {
        return nanos;
    }

    public boolean isInitial() {
        return nanos == Long.MIN_VALUE;
    }

    public boolean isComplete() {
        return nanos == Long.MAX_VALUE;
    }

    /**
     * Moves the frontier forward.
     *
     * @throws IllegalArgumentException if that would move it backwards
     */
    public Frontier advanceTo(long targetNanos) {
        if (targetNanos < nanos) {
            throw new IllegalArgumentException("a frontier cannot regress: at " + nanos + ", asked for " + targetNanos);
        }
        return targetNanos == nanos ? this : new Frontier(targetNanos);
    }

    /**
     * The earlier of two frontiers.
     *
     * <p>An operator with several inputs is only as advanced as its least advanced one -- it cannot
     * finalise anything a slow input might still contradict. This is also why an idle input must be
     * *excluded* rather than left to hold the minimum down (design section 15.2): one quiet partition
     * otherwise freezes every window in the query, which is the most common streaming production
     * incident there is.
     */
    public Frontier meet(Frontier other) {
        return nanos <= other.nanos ? this : other;
    }

    /** The later of two frontiers. */
    public Frontier join(Frontier other) {
        return nanos >= other.nanos ? this : other;
    }

    /** Whether a timestamp is final -- strictly before the frontier, so no later input can change it. */
    public boolean isClosed(long timestampNanos) {
        return timestampNanos < nanos;
    }

    public boolean isAtOrBefore(Frontier other) {
        return nanos <= other.nanos;
    }

    @Override
    public int compareTo(Frontier other) {
        return Long.compare(nanos, other.nanos);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Frontier other && nanos == other.nanos;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(nanos);
    }

    @Override
    public String toString() {
        if (isInitial()) {
            return "Frontier[initial]";
        }
        if (isComplete()) {
            return "Frontier[complete]";
        }
        return "Frontier[" + nanos + "]";
    }
}
