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
package com.ash.messaging.pravaha.runtime.window;

import java.time.Duration;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowAssignOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;

/**
 * The most windows one row may belong to, refused at registration (FINEHOP-1).
 *
 * <p>A row updates one slice, so a fine hop costs nothing on the way in and everything on the way
 * out: every window that closes is combined from its slices, one per {@code gcd(size, slide)} of its
 * size, and every row is published in {@code size / slide} windows. {@code HOP(INTERVAL '0.001'
 * SECOND, INTERVAL '1' DAY)} is 86.4 million of each. It used to register; one row and a minute of
 * watermark -- 60,000 windows of 86.4 million slices -- then held the lane for good and took
 * gigabytes of heap, and every later push to the stream timed out ({@code PRV-8103}) while the
 * stream's other queries waited behind it. Nothing at run time can make that cheap, so it is refused
 * before it runs, with the arithmetic and the bound.
 *
 * <p>Both numbers are checked against one bound: windows per row ({@code size / slide}, rounded up)
 * and slices per window ({@code size / gcd(size, slide)}). The second is never smaller than the
 * first and is the one a slide that does not divide the size makes large: {@code HOP(7 s, 1 day)}
 * is 12,343 windows per row of 86,400 one-second slices. A tumble is one of each. Session windows
 * are not sliced and are not bounded here.
 *
 * <p>The default, 100,000, admits a day of one-second hops (86,400) and a week of one-minute ones
 * (10,080) and refuses a day of millisecond ones by three orders of magnitude.
 * {@code pravaha.lane.max-windows-per-row} raises or lowers it per node.
 */
public final class WindowLimits {

    /** The setting, read by the embedded engine and the server alike. */
    public static final String SETTING = "pravaha.lane.max-windows-per-row";

    /** The bound when none is configured. */
    public static final long DEFAULT_MAX_WINDOWS_PER_ROW = 100_000L;

    private WindowLimits() {}

    /**
     * Refuses {@code plan} if any window in it is finer than {@code maxWindowsPerRow} allows.
     *
     * @throws PravahaException {@code PRV-3026} naming the window's size and slide, both counts and
     *     the bound
     */
    public static void require(PhysicalOperator plan, long maxWindowsPerRow) {
        if (plan instanceof WindowAssignOperator assign) {
            require(assign.spec(), maxWindowsPerRow);
        } else if (plan instanceof WindowedAggregateOperator aggregate) {
            require(aggregate.spec(), maxWindowsPerRow);
        }
        for (PhysicalOperator input : plan.inputs()) {
            require(input, maxWindowsPerRow);
        }
    }

    /** Refuses one window spec finer than {@code maxWindowsPerRow} allows. */
    public static void require(WindowSpec spec, long maxWindowsPerRow) {
        if (spec.kind() == WindowSpec.Kind.SESSION) {
            return;
        }
        long windowsPerRow = windowsPerRow(spec);
        long slicesPerWindow = slicesPerWindow(spec);
        if (windowsPerRow <= maxWindowsPerRow && slicesPerWindow <= maxWindowsPerRow) {
            return;
        }
        throw new PravahaException(
                RuntimeErrors.WINDOW_TOO_FINE,
                "a window of " + Duration.ofNanos(spec.sizeNanos()) + " sliding every "
                        + Duration.ofNanos(spec.slideNanos()) + " puts each row in " + windowsPerRow
                        + " windows of " + slicesPerWindow + " slices each, past this node's bound of "
                        + maxWindowsPerRow + " (" + SETTING + "). Every closing window is combined from "
                        + "all its slices, so a window this fine holds its lane for as long as it takes and "
                        + "stalls every query on the stream. Slide by more -- a slide that divides the size, "
                        + "at most " + maxWindowsPerRow + " slides to a window -- or, if the work is "
                        + "intended and the node sized for it, raise " + SETTING + ".");
    }

    /** {@code size / slide}, rounded up: how many windows one row is published in. */
    public static long windowsPerRow(WindowSpec spec) {
        return (spec.sizeNanos() + spec.slideNanos() - 1) / spec.slideNanos();
    }

    /** {@code size / gcd(size, slide)}: how many slices one window is combined from. */
    public static long slicesPerWindow(WindowSpec spec) {
        return spec.sizeNanos() / spec.sliceSizeNanos();
    }

    /** {@code bound}, refused unless positive. */
    public static long requirePositive(long bound) {
        if (bound < 1) {
            throw new IllegalArgumentException(SETTING + " must be a positive whole number, got " + bound);
        }
        return bound;
    }

    /** The bound in {@code value}, or the default when it is blank; refuses one that is not a positive number. */
    public static long parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_WINDOWS_PER_ROW;
        }
        long bound;
        try {
            bound = Long.parseLong(value.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SETTING + " must be a positive whole number, got '" + value + "'");
        }
        return requirePositive(bound);
    }
}
