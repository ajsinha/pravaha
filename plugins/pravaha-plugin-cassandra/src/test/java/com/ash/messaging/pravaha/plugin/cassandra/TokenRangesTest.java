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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TokenRanges}, the arithmetic the partition split depends on. A gap or an overlap here would
 * either silently drop a slice of the token ring or read part of it twice -- and both are the kind of
 * defect a container test proves only for the row counts it happens to insert, never for the boundary
 * values themselves.
 */
class TokenRangesTest {

    @Test
    void oneRangeSpansTheWholeRing() {
        long[] b = TokenRanges.boundaries(1);
        assertThat(b).containsExactly(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Test
    void boundariesAreStrictlyIncreasingAndSpanTheWholeRing() {
        long[] b = TokenRanges.boundaries(8);
        assertThat(b).hasSize(9);
        assertThat(b[0]).isEqualTo(Long.MIN_VALUE);
        assertThat(b[8]).isEqualTo(Long.MAX_VALUE);
        for (int i = 1; i < b.length; i++) {
            assertThat(b[i])
                    .as("boundary %d must be strictly greater than boundary %d, or two ranges collapse", i, i - 1)
                    .isGreaterThan(b[i - 1]);
        }
    }

    @Test
    void rangesAreEvenWithinOneTokenOfEachOther() {
        // 2^64 / 5 is not an integer, so the ranges cannot all be identical -- but no range should
        // be noticeably larger than another, or the partitions it feeds would be unevenly loaded.
        long[] b = TokenRanges.boundaries(5);
        java.math.BigInteger[] widths = new java.math.BigInteger[5];
        for (int i = 0; i < 5; i++) {
            widths[i] = java.math.BigInteger.valueOf(b[i + 1]).subtract(java.math.BigInteger.valueOf(b[i]));
        }
        java.math.BigInteger min = java.util.Arrays.stream(widths)
                .min(java.math.BigInteger::compareTo)
                .orElseThrow();
        java.math.BigInteger max = java.util.Arrays.stream(widths)
                .max(java.math.BigInteger::compareTo)
                .orElseThrow();
        assertThat(max.subtract(min).longValueExact())
                .as("the widest and narrowest range must differ by at most one token")
                .isLessThanOrEqualTo(1L);
    }

    @Test
    void zeroOrNegativeCountIsRejected() {
        assertThatThrownBy(() -> TokenRanges.boundaries(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenRanges.boundaries(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
