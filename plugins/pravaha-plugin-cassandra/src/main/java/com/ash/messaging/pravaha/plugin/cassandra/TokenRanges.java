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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.math.BigInteger;

/**
 * Splits Murmur3Partitioner's whole token ring into evenly-sized ranges.
 *
 * <p>The ring is every {@code long}: {@code Long.MIN_VALUE} to {@code Long.MAX_VALUE}, 2^64 values.
 * {@link #boundaries(int)} returns {@code count + 1} values marking {@code count} ranges; range
 * {@code i} is {@code (boundaries[i], boundaries[i + 1]]} except range 0, which is closed at the
 * bottom -- {@code [boundaries[0], boundaries[1]]} -- because {@code Long.MIN_VALUE} is itself a
 * valid token and an exclusive lower bound on range 0 would silently drop whatever partition hashes
 * to it.
 *
 * <p>Plain {@code long} arithmetic overflows computing a span of 2^64, which is one more than
 * {@code long} can hold; {@link BigInteger} is used for exactly that reason and nowhere else here.
 */
final class TokenRanges {

    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger SPAN = BigInteger.ONE.shiftLeft(64);

    private TokenRanges() {}

    static long[] boundaries(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive, got " + count);
        }
        long[] boundaries = new long[count + 1];
        for (int i = 0; i <= count; i++) {
            if (i == count) {
                // Min + 2^64 does not fit in a long; the top of the ring is Long.MAX_VALUE exactly.
                boundaries[i] = Long.MAX_VALUE;
            } else {
                boundaries[i] = MIN.add(SPAN.multiply(BigInteger.valueOf(i)).divide(BigInteger.valueOf(count)))
                        .longValueExact();
            }
        }
        return boundaries;
    }
}
