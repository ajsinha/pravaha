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

/**
 * A windowing scheme, and the slice size that implements it.
 *
 * <p><strong>Slicing is the whole optimisation</strong> (design section 15.3). A 60-second window
 * hopping every 10 seconds overlaps six windows, and the obvious implementation updates six
 * accumulators per record. Instead the engine keeps one accumulator per 10-second <em>slice</em> and
 * combines six of them when a window fires: O(1) per record instead of O(6), and six times less
 * state. At a hundred thousand keys that is the difference between a query that fits in memory and
 * one that does not.
 *
 * <p>The slice size is {@code gcd(size, slide)}, which is the largest interval that never straddles
 * a window boundary -- so every record belongs to exactly one slice, and every window is an exact
 * whole number of slices. A hop that does not divide the size evenly still works; it just produces
 * smaller slices and more of them.
 *
 * @param kind which scheme
 * @param sizeNanos the window's width; for a session, the inactivity gap
 * @param slideNanos how often a window starts. Equal to {@code sizeNanos} for tumbling.
 */
public record WindowSpec(Kind kind, long sizeNanos, long slideNanos) {

    public enum Kind {
        TUMBLING,
        HOPPING,
        SESSION
    }

    public WindowSpec {
        if (sizeNanos <= 0) {
            throw new IllegalArgumentException("window size must be positive, got " + sizeNanos);
        }
        if (kind != Kind.SESSION && slideNanos > sizeNanos) {
            throw new IllegalArgumentException("a window slide of " + slideNanos + "ns is larger than the window "
                    + "size of " + sizeNanos + "ns, which leaves gaps: rows falling between windows would "
                    + "belong to none and be silently dropped. Use a slide no larger than the size.");
        }
        if (kind != Kind.SESSION && slideNanos <= 0) {
            throw new IllegalArgumentException("window slide must be positive, got " + slideNanos);
        }
        if (kind == Kind.HOPPING && slideNanos > sizeNanos) {
            // A slide wider than the size leaves gaps between windows, so some records belong to no
            // window at all. That is almost always a typo -- and when it is not, it is a filter
            // followed by a tumble, which says what it means.
            throw new IllegalArgumentException("a hop of " + slideNanos + " ns over a window of " + sizeNanos
                    + " ns leaves gaps: records between windows would belong to none. Use a smaller slide, or "
                    + "express the gap as a filter.");
        }
    }

    public static WindowSpec tumbling(long sizeNanos) {
        return new WindowSpec(Kind.TUMBLING, sizeNanos, sizeNanos);
    }

    public static WindowSpec hopping(long sizeNanos, long slideNanos) {
        return new WindowSpec(Kind.HOPPING, sizeNanos, slideNanos);
    }

    /** A session window closes after {@code gapNanos} of inactivity for a key. */
    public static WindowSpec session(long gapNanos) {
        return new WindowSpec(Kind.SESSION, gapNanos, gapNanos);
    }

    /**
     * The slice width: the largest interval that never straddles a window boundary.
     *
     * <p>For tumbling that is the window itself -- one slice per window, no combining at all.
     */
    public long sliceSizeNanos() {
        if (kind == Kind.SESSION) {
            throw new UnsupportedOperationException(
                    "session windows are not sliced: their boundaries are decided by the data, not by the "
                            + "clock, so there is no fixed interval that never straddles one");
        }
        return gcd(sizeNanos, slideNanos);
    }

    /** How many slices make up one window. */
    public int slicesPerWindow() {
        return (int) (sizeNanos / sliceSizeNanos());
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
