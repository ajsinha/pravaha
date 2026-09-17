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
package com.ash.messaging.pravaha.runtime;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Runtime error codes. Stable, documented, and never renumbered. */
public final class RuntimeErrors {

    public static final ErrorCode ARENA_EXHAUSTED = new ErrorCode(3001, "RUNTIME_ARENA_EXHAUSTED");
    public static final ErrorCode BACKPRESSURED = new ErrorCode(3002, "RUNTIME_BACKPRESSURED");
    public static final ErrorCode LANE_FAILED = new ErrorCode(3010, "RUNTIME_LANE_FAILED");
    /**
     * A watermark advance that would fire an implausible number of windows.
     *
     * <p>TIME-1. Not a limit anybody wanted -- it is how a single stale event time announces itself.
     * One row timestamped 1970 in a stream of present-day data makes the first window start there,
     * and the emitter then walks every slide from then to now: for a one-second slide that is on the
     * order of a billion iterations, during which the lane does nothing else and looks hung.
     */
    public static final ErrorCode WINDOW_SPAN_IMPLAUSIBLE = new ErrorCode(3022, "RUNTIME_WINDOW_SPAN_IMPLAUSIBLE");

    public static final ErrorCode UNSUPPORTED_AGGREGATE = new ErrorCode(3020, "RUNTIME_UNSUPPORTED_AGGREGATE");

    /** A join Pravaha will not run: an unsupported key type, or a shape with no bounded execution. */
    public static final ErrorCode UNSUPPORTED_JOIN = new ErrorCode(3021, "RUNTIME_UNSUPPORTED_JOIN");

    /**
     * ADR-037 item B2: {@code COUNT(DISTINCT ...)} cannot spill.
     *
     * <p>Every other windowed aggregate's accumulator is a handful of fixed-width numbers, which is
     * what makes it possible to carve off-heap and, from there, to an overflow tier. {@code
     * COUNT(DISTINCT x)} holds one entry per distinct value per group per slice -- a set that grows
     * with cardinality, not a number -- and that has no fixed-width representation to spill. Refused
     * by name, at the moment spilling is configured for a query that needs it, rather than accepted
     * and left to hit {@link #UNSUPPORTED_AGGREGATE}'s ceiling later with no more room to grow into
     * than it had before spilling was ever turned on.
     */
    public static final ErrorCode COUNT_DISTINCT_CANNOT_SPILL =
            new ErrorCode(3023, "RUNTIME_COUNT_DISTINCT_CANNOT_SPILL");

    private RuntimeErrors() {}
}
