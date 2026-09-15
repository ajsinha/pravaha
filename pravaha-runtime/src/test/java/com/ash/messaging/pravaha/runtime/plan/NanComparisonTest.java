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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NaN is not greater than zero, and it is not equal to itself.
 *
 * <p>TY-3. Both floating-point comparison sites in {@link Predicate} routed through
 * {@link Double#compare}, whose contract is a <em>total order</em> — it places {@code NaN} above
 * every other double and {@code -0.0} below {@code 0.0}. Correct for sorting, wrong for a filter.
 * {@code WHERE x/y > 0} kept a row whose {@code x/y} was {@code 0.0/0.0}, and {@code NaN = NaN}
 * passed: silently wrong results under exit 0, not refusals.
 *
 * <p>The divergence from PostgreSQL is deliberate and recorded on {@code Op.matchesDoubles}.
 */
class NanComparisonTest {

    private static final double NAN = 0.0d / 0.0d;

    @Test
    void nanIsNotGreaterThanAnything() {
        assertThat(Predicate.Op.GT.matchesDoubles(NAN, 0.0d))
                .as("this is the reported case: WHERE x/y > 0 kept the 0.0/0.0 row, because "
                        + "Double.compare ranks NaN above every value")
                .isFalse();
        assertThat(Predicate.Op.GE.matchesDoubles(NAN, 0.0d)).isFalse();
        assertThat(Predicate.Op.GT.matchesDoubles(NAN, Double.MAX_VALUE)).isFalse();
    }

    @Test
    void nanIsNotLessThanAnything() {
        assertThat(Predicate.Op.LT.matchesDoubles(NAN, 0.0d)).isFalse();
        assertThat(Predicate.Op.LE.matchesDoubles(NAN, 0.0d)).isFalse();
        assertThat(Predicate.Op.LT.matchesDoubles(Double.MIN_VALUE, NAN)).isFalse();
    }

    @Test
    void nanIsNotEqualToItself() {
        assertThat(Predicate.Op.EQ.matchesDoubles(NAN, NAN))
                .as("Double.compare(NaN, NaN) == 0, so `NaN = NaN` wrongly passed")
                .isFalse();
        assertThat(Predicate.Op.NE.matchesDoubles(NAN, NAN))
                .as("and IEEE 754 says the inequality holds, which is the one comparison that is true")
                .isTrue();
    }

    @Test
    void negativeZeroEqualsZero() {
        // The same root cause, quieter. Double.compare puts -0.0 below 0.0, so `f = 0` missed a row
        // holding -0.0 -- a row that every other part of the system treats as zero.
        assertThat(Predicate.Op.EQ.matchesDoubles(-0.0d, 0.0d)).isTrue();
        assertThat(Predicate.Op.LT.matchesDoubles(-0.0d, 0.0d)).isFalse();
    }

    @Test
    void ordinaryComparisonsAreUnchanged() {
        // The property the fix must not cost: everything that is not NaN or signed zero must
        // compare exactly as it did.
        assertThat(Predicate.Op.GT.matchesDoubles(2.0d, 1.0d)).isTrue();
        assertThat(Predicate.Op.LT.matchesDoubles(1.0d, 2.0d)).isTrue();
        assertThat(Predicate.Op.EQ.matchesDoubles(1.5d, 1.5d)).isTrue();
        assertThat(Predicate.Op.GE.matchesDoubles(Double.MAX_VALUE, Double.MAX_VALUE))
                .isTrue();
        assertThat(Predicate.Op.LE.matchesDoubles(Double.NEGATIVE_INFINITY, 0.0d))
                .isTrue();
    }
}
