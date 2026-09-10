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
package com.ash.messaging.pravaha.runtime.plan;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;

/**
 * A keyed aggregate whose state is bounded by a window.
 *
 * <p>The operator that makes {@code GROUP BY} legal. An unwindowed keyed aggregate is refused at
 * planning because its state grows with the key space and never shrinks (design section 9.6); this
 * one releases each window's state when the window closes, so the state is bounded by
 * <em>keys times open windows</em> rather than by keys times history.
 *
 * <p>The window boundaries are carried as ordinary group keys -- {@code window_start} and
 * {@code window_end} arrive as columns from the {@link WindowAssignOperator} below -- which is why
 * this needs no special grouping machinery. What it does need is the spec, so it knows when a window
 * can be fired and when its slices can be dropped.
 *
 * @param groupKeys ordinals of the grouping columns, window boundaries included
 * @param windowStartOrdinal which of the input columns carries the window start
 * @param windowEndOrdinal which carries the window end
 * @param maxSlices the ceiling on live accumulators. Bounded by a window is not the same as
 *     unconditionally bounded: a window over an unbounded key space is still unbounded within any
 *     one window, so the ceiling stays.
 * @param allowedLatenessNanos how long after a window closes its state is kept so that a late record
 *     can still correct it (design section 15.4). Zero means a record arriving after the watermark
 *     has passed its window is too late, full stop -- which is a defensible default precisely
 *     because it is visible: the late counter moves and somebody can decide what the number should
 *     be, rather than a silent allowance deciding for them.
 */
public record WindowedAggregateOperator(
        PhysicalOperator input,
        StreamSchema outputSchema,
        WindowSpec spec,
        List<Integer> groupKeys,
        List<AggregateOperator.AggregateCall> aggregates,
        int windowStartOrdinal,
        int windowEndOrdinal,
        int maxSlices,
        long allowedLatenessNanos)
        implements PhysicalOperator {

    public WindowedAggregateOperator {
        groupKeys = List.copyOf(groupKeys);
        aggregates = List.copyOf(aggregates);
        if (windowStartOrdinal < 0 || windowEndOrdinal < 0) {
            throw new IllegalArgumentException("a windowed aggregate needs its window boundary columns resolved");
        }
        if (maxSlices < 1) {
            throw new IllegalArgumentException("the slice ceiling must be at least 1, got " + maxSlices);
        }
        if (allowedLatenessNanos < 0) {
            throw new IllegalArgumentException("allowed lateness must not be negative, got " + allowedLatenessNanos);
        }
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public String label() {
        return "WindowedAggregate(" + spec.kind() + " " + spec.sizeNanos() / 1_000_000 + "ms, keys=" + groupKeys + ", "
                + aggregates.size() + " aggregate(s))";
    }
}
