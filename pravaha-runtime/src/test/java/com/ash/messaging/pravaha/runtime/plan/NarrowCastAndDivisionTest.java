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

import com.ash.messaging.pravaha.api.data.TypeName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NARROWCAST-1 and DIVMIN-1: a floating-point value cast to an integer must be a finite number in the
 * target's range, a finite DOUBLE cast to REAL must not become an infinity, and {@code Long.MIN_VALUE
 * / -1} is a BIGINT overflow. Each is an {@link ArithmeticException}, routed as every other overflow
 * is, never a number the value is not.
 */
class NarrowCastAndDivisionTest {

    private static Expression cast(double value, TypeName to) {
        return new Expression.Cast(Expression.Literal.ofDouble(value), to);
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void nanAndTheInfinitiesHaveNoIntegerValue() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> cast(value, TypeName.INT64).evaluateLong(null))
                    .as("%s", value)
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("BIGINT overflow")
                    .hasMessageContaining(String.valueOf(value));
        }
        assertThatThrownBy(() -> cast(Double.NaN, TypeName.INT32).evaluateLong(null))
                .hasMessageContaining("INT overflow");
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void aFiniteDoubleOutsideTheTargetOverflows() {
        assertThatThrownBy(() -> cast(1e300, TypeName.INT64).evaluateLong(null)).hasMessageContaining("BIGINT");
        assertThatThrownBy(() -> cast(0x1p63, TypeName.INT64).evaluateLong(null))
                .hasMessageContaining("BIGINT");
        assertThatThrownBy(() -> cast(3e9, TypeName.INT32).evaluateLong(null)).hasMessageContaining("INT overflow");
        assertThatThrownBy(() -> cast(40_000, TypeName.INT16).evaluateLong(null))
                .hasMessageContaining("SMALLINT overflow");
        // -2^63 is Long.MIN_VALUE exactly, so it has an answer.
        assertThat(cast(-0x1p63, TypeName.INT64).evaluateLong(null)).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void anInRangeDoubleTruncatesTowardsZeroAsBefore() {
        assertThat(cast(2.7, TypeName.INT64).evaluateLong(null)).isEqualTo(2);
        assertThat(cast(-2.7, TypeName.INT32).evaluateLong(null)).isEqualTo(-2);
        // Read as a double, an integer cast is the integer -- not the fractional source.
        assertThat(cast(2.7, TypeName.INT64).evaluateDouble(null)).isEqualTo(2.0);
        assertThatThrownBy(() -> cast(Double.NaN, TypeName.INT64).evaluateDouble(null))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void aFiniteDoubleBeyondRealIsRefusedNotMadeInfinite() {
        assertThatThrownBy(() -> cast(1e300, TypeName.FLOAT32).evaluateDouble(null))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("REAL overflow");
        // An infinity or NaN that already is one stays itself; a value in range passes.
        assertThat(cast(Double.POSITIVE_INFINITY, TypeName.FLOAT32).evaluateDouble(null))
                .isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(cast(1.5, TypeName.FLOAT32).evaluateDouble(null)).isEqualTo(1.5);
    }

    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    private static Expression divide(long left, long right, Expression.Operator operator, TypeName type) {
        return new Expression.Arithmetic(
                Expression.Literal.ofLong(left), operator, Expression.Literal.ofLong(right), type);
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void theSmallestBigintDividedByMinusOneOverflows() {
        assertThatThrownBy(() -> divide(Long.MIN_VALUE, -1, Expression.Operator.DIVIDE, TypeName.INT64)
                        .evaluateLong(null))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("BIGINT overflow")
                .hasMessageContaining("9223372036854775808");
        // The remainder has an answer, 0; the neighbouring quotients are unchanged.
        assertThat(divide(Long.MIN_VALUE, -1, Expression.Operator.MODULO, TypeName.INT64)
                        .evaluateLong(null))
                .isZero();
        assertThat(divide(Long.MIN_VALUE + 1, -1, Expression.Operator.DIVIDE, TypeName.INT64)
                        .evaluateLong(null))
                .isEqualTo(Long.MAX_VALUE);
        assertThat(divide(Long.MIN_VALUE, 1, Expression.Operator.DIVIDE, TypeName.INT64)
                        .evaluateLong(null))
                .isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void theSmallestNarrowIntegerDividedByMinusOneOverflowsAtItsOwnWidth() {
        assertThatThrownBy(() -> divide(Integer.MIN_VALUE, -1, Expression.Operator.DIVIDE, TypeName.INT32)
                        .evaluateLong(null))
                .hasMessageContaining("INT overflow");
        assertThatThrownBy(() -> divide(Short.MIN_VALUE, -1, Expression.Operator.DIVIDE, TypeName.INT16)
                        .evaluateLong(null))
                .hasMessageContaining("SMALLINT overflow");
        assertThatThrownBy(() -> divide(Byte.MIN_VALUE, -1, Expression.Operator.DIVIDE, TypeName.INT8)
                        .evaluateLong(null))
                .hasMessageContaining("TINYINT overflow");
        assertThat(divide(Integer.MIN_VALUE, -1, Expression.Operator.MODULO, TypeName.INT32)
                        .evaluateLong(null))
                .isZero();
    }
}
