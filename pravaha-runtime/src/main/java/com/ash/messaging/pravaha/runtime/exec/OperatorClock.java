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
package com.ash.messaging.pravaha.runtime.exec;

/**
 * Decides which rows are timed, and keeps the call stack that turns inclusive time into self time.
 *
 * <p>One per pipeline, touched only by the lane thread that pumps it -- the same confinement every
 * operator's state already has, so there is nothing volatile here and nothing to synchronise.
 *
 * <p><strong>Whole rows are sampled, not whole operators.</strong> The decision is taken once, when
 * a row enters at a scan, and every operator on that row's path is then timed. Sampling per
 * operator instead would charge an untimed child's work to its timed parent, and the parent's
 * "self" time would silently include the subtree underneath it.
 *
 * <p>The stack holds each level's charged child time. A level pushes a slot, runs, reads back what
 * its children charged, and charges its own inclusive time to its parent -- so a nanosecond is
 * counted against exactly one operator however deep the plan is.
 */
final class OperatorClock {

    /**
     * One row in this many is timed.
     *
     * <p>A power of two so the test is a mask. 1,024 was chosen against the measurement in
     * {@code OperatorMetricsOverheadIT}: it keeps four {@code nanoTime} calls per operator down to
     * about a thousandth of the rows while still giving thousands of samples a second on any
     * stream worth looking at.
     */
    static final int SAMPLE_EVERY = 1024;

    private static final int MASK = SAMPLE_EVERY - 1;

    /**
     * Deeper than any plan this engine builds. A plan deeper than this simply stops being timed
     * below that point rather than growing an array on the hot path.
     */
    private static final int MAX_DEPTH = 64;

    private final long[] childNanos = new long[MAX_DEPTH];

    private long rows;
    private boolean sampling;
    private int depth = -1;

    /**
     * A row is entering the pipeline. Decides whether it is timed and resets the stack.
     *
     * <p>Resetting here rather than trusting the last row to have unwound is deliberate: a stage
     * that throws -- an exhausted arena, a refused aggregate -- leaves the stack part-way up, and
     * without this every row after it would be mis-timed for the life of the query.
     */
    void beginRow() {
        depth = -1;
        sampling = (++rows & MASK) == 0;
    }

    /** That row is done. */
    void endRow() {
        sampling = false;
        depth = -1;
    }

    /** Whether the row being processed is one of the timed ones. */
    boolean sampling() {
        return sampling;
    }

    /** Opens a level. Returns the slot to give {@link #pop}, or -1 when the plan is too deep. */
    int push() {
        if (depth + 1 >= MAX_DEPTH) {
            return -1;
        }
        depth++;
        childNanos[depth] = 0;
        return depth;
    }

    /** Closes a level and returns what its children charged to it. */
    long pop(int slot) {
        if (slot < 0) {
            return 0;
        }
        depth = slot - 1;
        return childNanos[slot];
    }

    /** Charges an inclusive time to the level above, so the parent can subtract it. */
    void charge(long nanos) {
        if (depth >= 0) {
            childNanos[depth] += nanos;
        }
    }
}
