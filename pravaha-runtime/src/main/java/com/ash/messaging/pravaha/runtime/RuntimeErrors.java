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
     * An operator that holds rows -- a top-N -- was asked to retract a row it does not hold.
     *
     * <p>The input then retracts more than it inserted, which has no answer as a set of rows: a
     * computation from scratch over it is undefined, so the maintained one refuses rather than
     * holding a row a negative number of times and numbering the rest around it.
     */
    public static final ErrorCode RETRACTED_UNHELD_ROW = new ErrorCode(3024, "RUNTIME_RETRACTED_UNHELD_ROW");

    /**
     * A {@code SUM}, {@code COUNT} or {@code AVG} total left the 64-bit range it accumulates in.
     *
     * <p>SUMWRAP-1. The total used to wrap round silently and be served as the answer; see {@link
     * AggregateTotals}.
     */
    public static final ErrorCode AGGREGATE_OVERFLOW = new ErrorCode(3025, "RUNTIME_AGGREGATE_OVERFLOW");

    /**
     * A window so fine against its own size that one row belongs to more windows, or one window to
     * more slices, than {@code pravaha.lane.max-windows-per-row} allows; refused at registration.
     *
     * <p>FINEHOP-1. {@code HOP(INTERVAL '0.001' SECOND, INTERVAL '1' DAY)} puts every row in 86.4
     * million windows of 86.4 million slices each. It registered, and one row and a minute of
     * watermark held its lane for good and gigabytes of heap; every push to the stream then timed
     * out. See {@link com.ash.messaging.pravaha.runtime.window.WindowLimits}.
     */
    public static final ErrorCode WINDOW_TOO_FINE = new ErrorCode(3026, "RUNTIME_WINDOW_TOO_FINE");

    /**
     * A row whose evaluation failed -- a division by zero, an overflow, a cast with no answer --
     * before it reached any state, and which went to the query's dead-letter queue rather than
     * stopping the query (DLQPROJ-1). The code a dead letter of that kind carries; such a letter is
     * not replayed, because the same row would fail the same way. See {@code exec.RowGuard}.
     */
    public static final ErrorCode ROW_EVALUATION_FAILED = new ErrorCode(3027, "RUNTIME_ROW_EVALUATION_FAILED");

    // 3023 is retired, not free. It was RUNTIME_COUNT_DISTINCT_CANNOT_SPILL: an aggregate containing
    // COUNT(DISTINCT) kept its distinct sets on the heap and was refused when the overflow tier was
    // configured. ADR-044 moved those sets into RowStore, so they spill like every other state and
    // nothing can throw it any more. The number is not reused -- an operator who met it once must
    // never find something else behind it.

    private RuntimeErrors() {}
}
