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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NARROWINT-1: an {@code INT}, {@code SMALLINT} or {@code TINYINT} result outside its type's range is
 * an overflow where it is computed -- {@code +}, {@code -}, {@code *}, unary minus, {@code ABS} and
 * an integer narrowing cast -- and never its low bits. A filter and a projection of the same
 * expression meet the same exception, so they cannot disagree.
 */
class NarrowIntegerOverflowTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("s")
            .field("i", Types.int32())
            .field("sm", Types.int16())
            .field("ty", Types.int8())
            .field("a", Types.int64())
            .build();

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 4);

    @AfterEach
    void close() {
        arena.close();
    }

    private RowView row(int i, short sm, byte ty, long a) {
        RowLayout layout = RowLayout.of(SCHEMA);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setInt(0, i).setShort(1, sm).setByte(2, ty).setLong(3, a);
        writer.weight(1).eventTimestampNanos(0).sequence(0).commit();
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    private static final Expression I = new Expression.Column(0, "i", TypeName.INT32);
    private static final Expression SM = new Expression.Column(1, "sm", TypeName.INT16);
    private static final Expression TY = new Expression.Column(2, "ty", TypeName.INT8);
    private static final Expression A = new Expression.Column(3, "a", TypeName.INT64);

    private static Expression times(Expression left, long right, TypeName type) {
        return new Expression.Arithmetic(left, Expression.Operator.MULTIPLY, Expression.Literal.ofLong(right), type);
    }

    private static Expression plus(Expression left, Expression right, TypeName type) {
        return new Expression.Arithmetic(left, Expression.Operator.ADD, right, type);
    }

    @Test
    void anIntProductPastTheRangeIsAnOverflowAndNotTheWrappedValue() {
        RowView row = row(2_000_000_000, (short) 0, (byte) 0, 0);
        assertThatThrownBy(() -> times(I, 2, TypeName.INT32).evaluateLong(row))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("INT overflow")
                .hasMessageContaining("(i * 2)")
                .hasMessageContaining("4000000000")
                .hasMessageContaining("CAST an operand to BIGINT");
        assertThatThrownBy(() -> plus(I, I, TypeName.INT32).evaluateLong(row)).hasMessageContaining("INT overflow");
        // Typed BIGINT -- what CAST(i AS BIGINT) * 2 plans to -- the same operands answer.
        assertThat(times(I, 2, TypeName.INT64).evaluateLong(row)).isEqualTo(4_000_000_000L);
        // At the edges of the range, an answer.
        assertThat(plus(I, Expression.Literal.ofLong(147_483_647), TypeName.INT32)
                        .evaluateLong(row))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void negatingOrTakingTheMagnitudeOfTheSmallestIntOverflows() {
        RowView row = row(Integer.MIN_VALUE, Short.MIN_VALUE, Byte.MIN_VALUE, 0);
        Expression negate = new Expression.Arithmetic(
                Expression.Literal.ofLong(0), Expression.Operator.SUBTRACT, I, TypeName.INT32);
        assertThatThrownBy(() -> negate.evaluateLong(row)).hasMessageContaining("INT overflow");
        assertThatThrownBy(() -> new Expression.Unary(Expression.Function.ABS, I).evaluateLong(row))
                .hasMessageContaining("INT overflow")
                .hasMessageContaining("2147483648");
        assertThatThrownBy(() -> new Expression.Unary(Expression.Function.ABS, SM).evaluateLong(row))
                .hasMessageContaining("SMALLINT overflow");
        assertThatThrownBy(() -> new Expression.Unary(Expression.Function.ABS, TY).evaluateLong(row))
                .hasMessageContaining("TINYINT overflow");
        assertThat(new Expression.Unary(Expression.Function.ABS, I).evaluateLong(row(-5, (short) 0, (byte) 0, 0)))
                .isEqualTo(5);
    }

    @Test
    void smallintAndTinyintArithmeticOverflowAtTheirOwnWidths() {
        RowView row = row(0, (short) 30_000, (byte) 100, 0);
        assertThatThrownBy(() -> plus(SM, SM, TypeName.INT16).evaluateLong(row))
                .hasMessageContaining("SMALLINT overflow")
                .hasMessageContaining("60000");
        assertThatThrownBy(() -> plus(TY, TY, TypeName.INT8).evaluateLong(row))
                .hasMessageContaining("TINYINT overflow")
                .hasMessageContaining("200");
        assertThat(plus(TY, Expression.Literal.ofLong(27), TypeName.INT8).evaluateLong(row))
                .isEqualTo(127);
    }

    @Test
    void aNarrowingCastOfAValueThatDoesNotFitOverflows() {
        RowView row = row(0, (short) 0, (byte) 0, Long.MAX_VALUE);
        assertThatThrownBy(() -> new Expression.Cast(A, TypeName.INT32).evaluateLong(row))
                .hasMessageContaining("INT overflow");
        assertThat(new Expression.Cast(A, TypeName.INT32).evaluateLong(row(0, (short) 0, (byte) 0, -7)))
                .isEqualTo(-7);
    }

    @Test
    void aFilterOnTheExpressionMeetsTheSameOverflowAsItsProjection() {
        RowView row = row(2_000_000_000, (short) 0, (byte) 0, 0);
        Predicate negative = new Predicate.CompareExpressions(
                times(I, 2, TypeName.INT32), Predicate.Op.LT, Expression.Literal.ofLong(0));
        // It used to compare 4e9 < 0 in 64 bits -- false -- while the projection published -294967296.
        assertThatThrownBy(() -> negative.test(row))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("INT overflow");
        assertThat(negative.test(row(-3, (short) 0, (byte) 0, 0))).isTrue();
    }

    @Test
    void aBigintOverflowSaysWhatOverflowedOnEveryRunNotOnlyBeforeTheJit() {
        // UNCODEDAPI-1: Math.subtractExact's "long overflow" is dropped once the JIT compiles the
        // throw site (OmitStackTraceInFastThrow), and the failure read "java.lang.ArithmeticException"
        // alone. The arithmetic now throws its own exception, naming the expression, every time --
        // asserted across enough calls for the throw site to be compiled.
        Expression minus = new Expression.Arithmetic(
                A, Expression.Operator.SUBTRACT, Expression.Literal.ofLong(1), TypeName.INT64);
        RowView atMin = row(0, (short) 0, (byte) 0, Long.MIN_VALUE);
        for (int i = 0; i < 50_000; i++) {
            try {
                minus.evaluateLong(atMin);
                throw new AssertionError("Long.MIN_VALUE - 1 must overflow");
            } catch (ArithmeticException overflow) {
                assertThat(overflow.getMessage())
                        .as("call %d", i)
                        .contains("long overflow")
                        .contains("BIGINT overflow");
            }
        }
    }
}
