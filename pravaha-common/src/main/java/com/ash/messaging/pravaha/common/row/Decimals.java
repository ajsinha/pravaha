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
package com.ash.messaging.pravaha.common.row;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Conversions between {@code BigDecimal} and the 128-bit unscaled form stored in a row.
 *
 * <p>The engine never calls these on the hot path. Generated code adds decimals as two-limb integer
 * arithmetic; a {@code BigDecimal} on every record is exactly the allocation the whole design exists
 * to avoid (design section 8.2). These exist for the boundaries -- decoding a plugin's value, rendering
 * for a human, handing a result to a client.
 *
 * <p>The representation is two's-complement 128-bit, split into a signed high limb and an unsigned
 * low limb.
 */
public final class Decimals {

    private Decimals() {}

    /** High 64 bits of {@code value}'s unscaled form, checked against the declared scale. */
    public static long high(BigDecimal value, int scale) {
        return unscaled(value, scale).shiftRight(64).longValue();
    }

    /** Low 64 bits of {@code value}'s unscaled form. */
    public static long low(BigDecimal value, int scale) {
        return unscaled(value, scale).longValue();
    }

    private static BigInteger unscaled(BigDecimal value, int scale) {
        BigInteger unscaled =
                value.setScale(scale, java.math.RoundingMode.UNNECESSARY).unscaledValue();
        if (unscaled.bitLength() > 127) {
            throw new ArithmeticException("decimal " + value + " does not fit in 128 bits at scale " + scale);
        }
        return unscaled;
    }

    /** Reassembles the two limbs into a signed integer. */
    public static BigInteger toBigInteger(long high, long low) {
        return BigInteger.valueOf(high).shiftLeft(64).or(BigInteger.valueOf(low).and(UNSIGNED_LONG_MASK));
    }

    /** Reassembles the two limbs and applies {@code scale}. */
    public static BigDecimal toBigDecimal(long high, long low, int scale) {
        return new BigDecimal(toBigInteger(high, low), scale);
    }

    private static final BigInteger UNSIGNED_LONG_MASK =
            BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
}
