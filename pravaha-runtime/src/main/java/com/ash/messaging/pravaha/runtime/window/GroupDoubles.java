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
 * How a floating-point value is grouped (NANGROUP-1): {@code -0.0} and {@code 0.0} are one group, as
 * SQL equality says ({@code d = 0} keeps both), and every {@code NaN} is one group whatever its bit
 * pattern, as SQL (and Calcite's grouping) treats {@code NaN}s as not distinct from one another.
 *
 * <p>The windowed aggregate hashed a group by the value's raw bits, so {@code -0.0} and {@code 0.0}
 * were two groups and two {@code NaN} payloads two more -- which the view, keying rows by {@link
 * Double#equals}, then collapsed into one row, losing a count. Every place that groups or counts
 * distinct values -- windowed and unwindowed aggregates, {@code COUNT(DISTINCT)}, a view's key --
 * now uses {@link #canonical}, and publishes the canonical value: {@code 0.0} and {@code NaN}.
 *
 * <p>Only grouping: a projected {@code -0.0} is still published as {@code -0.0}, because {@code 1 /
 * d} tells the two apart.
 */
public final class GroupDoubles {

    private static final long NEGATIVE_ZERO = Double.doubleToRawLongBits(-0.0);
    private static final long CANONICAL_NAN = Double.doubleToRawLongBits(Double.NaN);
    private static final int NEGATIVE_ZERO_FLOAT = Float.floatToRawIntBits(-0.0f);
    private static final int CANONICAL_NAN_FLOAT = Float.floatToRawIntBits(Float.NaN);

    private GroupDoubles() {}

    /** {@code 0.0} for either zero, {@link Double#NaN} for any NaN, the value otherwise. */
    public static double canonical(double value) {
        if (value == 0.0) {
            return 0.0;
        }
        return Double.isNaN(value) ? Double.NaN : value;
    }

    /** {@code 0.0f} for either zero, {@link Float#NaN} for any NaN, the value otherwise. */
    public static float canonical(float value) {
        if (value == 0.0f) {
            return 0.0f;
        }
        return Float.isNaN(value) ? Float.NaN : value;
    }

    /** Whether {@code value} is already the one its group is published as. */
    public static boolean isCanonical(double value) {
        long bits = Double.doubleToRawLongBits(value);
        return bits != NEGATIVE_ZERO && (!Double.isNaN(value) || bits == CANONICAL_NAN);
    }

    /** Whether {@code value} is already the one its group is published as. */
    public static boolean isCanonical(float value) {
        int bits = Float.floatToRawIntBits(value);
        return bits != NEGATIVE_ZERO_FLOAT && (!Float.isNaN(value) || bits == CANONICAL_NAN_FLOAT);
    }

    /**
     * Refuses a group key or distinct value read back from a checkpoint written before NANGROUP-1
     * that is not canonical: restored as it was, it would stay a group of its own beside the canonical
     * one rows now join, and the view would show the two as one key. Refused, the query rebuilds from
     * its sources, which is exact.
     */
    public static <T extends Number> T restored(T value) throws java.io.IOException {
        boolean canonical = value instanceof Float f ? isCanonical(f.floatValue()) : isCanonical(value.doubleValue());
        if (!canonical) {
            throw new java.io.IOException("this checkpoint holds the group key or distinct value " + value
                    + (Double.isNaN(value.doubleValue())
                            ? " (bits " + Long.toHexString(Double.doubleToRawLongBits(value.doubleValue())) + ")"
                            : "")
                    + ", written before NANGROUP-1 made -0.0 and 0.0 one group and every NaN one. Restored, it "
                    + "would stay a group apart from the one new rows join, so it is not restored and the "
                    + "query rebuilds from its sources");
        }
        return value;
    }
}
