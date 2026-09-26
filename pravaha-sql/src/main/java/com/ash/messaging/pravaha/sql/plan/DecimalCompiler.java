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
package com.ash.messaging.pravaha.sql.plan;

import java.math.BigDecimal;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * DECIMAL arithmetic, compiled exactly or refused by name (Nexmark q1: {@code 0.908 * price}).
 *
 * <p>The result's precision and scale are SQL's, as Calcite derives them under Pravaha's type
 * system: a sum or difference of {@code DECIMAL(p1, s1)} and {@code DECIMAL(p2, s2)} has scale
 * {@code max(s1, s2)}, a product has scale {@code s1 + s2}, and an integer operand is a decimal at
 * scale 0. Those scales hold the exact answer, so nothing is rounded -- <em>unless</em> the
 * precision cap of 38 digits forced Calcite to shrink the scale, and then the answer would need
 * rounding to fit. That case is refused here, at registration, with the types printed. A row whose
 * answer has too many integer digits for the result type is refused at run time by the expression
 * itself, and goes to the dead-letter queue as an integer overflow does.
 *
 * <p><strong>Division and remainder are refused.</strong> A quotient of two decimals is exact at
 * some finite scale only when the divisor's prime factors are twos and fives, so any fixed result
 * scale rounds most quotients -- which is the silent rounding this engine does not do. {@code
 * CAST(x AS DOUBLE) / y} is the way to ask for an approximate quotient, and it is named in the
 * refusal.
 */
final class DecimalCompiler {

    private final ExpressionCompiler expressions;

    DecimalCompiler(ExpressionCompiler expressions) {
        this.expressions = expressions;
    }

    /** Whether this call is decimal arithmetic or a decimal conversion, which this class owns. */
    static boolean handles(RexCall call) {
        SqlTypeName result = call.getType().getSqlTypeName();
        if (call.getKind() == SqlKind.CAST) {
            RexNode source = call.getOperands().get(0);
            SqlTypeName from = source.getType().getSqlTypeName();
            if (source instanceof RexLiteral && from == SqlTypeName.DECIMAL && result != SqlTypeName.DECIMAL) {
                // CAST(1.5 AS DOUBLE), which Calcite writes for `f64 = 1.5`: the literal compiler
                // already reads a decimal literal as the double it is being converted to.
                return false;
            }
            return result == SqlTypeName.DECIMAL || from == SqlTypeName.DECIMAL;
        }
        return result == SqlTypeName.DECIMAL
                && switch (call.getKind()) {
                    case PLUS, MINUS, TIMES, DIVIDE, MOD, MINUS_PREFIX, PLUS_PREFIX -> true;
                    default -> false;
                };
    }

    Expression compile(RexCall call) {
        if (call.getKind() == SqlKind.CAST) {
            return cast(call);
        }
        RelDataType type = call.getType();
        switch (call.getKind()) {
            case PLUS_PREFIX -> {
                return operand(call.getOperands().get(0));
            }
            case MINUS_PREFIX -> {
                RexNode only = call.getOperands().get(0);
                requireScale(call, scaleOf(only), type);
                return new Expression.DecimalArithmetic(
                        Expression.Literal.ofLong(0),
                        Expression.Operator.SUBTRACT,
                        operand(only),
                        type.getPrecision(),
                        type.getScale());
            }
            case DIVIDE, MOD ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "'" + call + "' is DECIMAL " + (call.getKind() == SqlKind.DIVIDE ? "division" : "remainder")
                                + ", which Pravaha refuses rather than rounds. A quotient of decimals is exact at a "
                                + "fixed scale only by luck -- 1 / 3 is not exact at any -- so the result type SQL "
                                + "gives it would round most rows silently. Multiplication, addition and subtraction "
                                + "are exact and supported. If an approximate quotient is acceptable, say so: "
                                + "CAST(x AS DOUBLE) / y.");
            default -> {
                // PLUS, MINUS or TIMES, which handles() admitted.
            }
        }
        RexNode left = call.getOperands().get(0);
        RexNode right = call.getOperands().get(1);
        int exactScale = call.getKind() == SqlKind.TIMES
                ? scaleOf(left) + scaleOf(right)
                : Math.max(scaleOf(left), scaleOf(right));
        requireScale(call, exactScale, type);
        Expression.Operator operator =
                switch (call.getKind()) {
                    case PLUS -> Expression.Operator.ADD;
                    case MINUS -> Expression.Operator.SUBTRACT;
                    default -> Expression.Operator.MULTIPLY;
                };
        return new Expression.DecimalArithmetic(
                operand(left), operator, operand(right), type.getPrecision(), type.getScale());
    }

    /**
     * {@code CAST(... AS DECIMAL(p, s))} from an integer or a decimal at no greater scale, or {@code
     * CAST(decimal AS DOUBLE)}. Every other conversion involving a decimal is refused.
     */
    private Expression cast(RexCall call) {
        RexNode source = call.getOperands().get(0);
        RelDataType target = call.getType();
        SqlTypeName to = target.getSqlTypeName();
        SqlTypeName from = source.getType().getSqlTypeName();
        if (to == SqlTypeName.DECIMAL && (from == SqlTypeName.DECIMAL || isInteger(from))) {
            requireScale(call, scaleOf(source), target);
            int sourceDigits = from == SqlTypeName.DECIMAL
                    ? source.getType().getPrecision() - source.getType().getScale()
                    : integerDigits(from);
            if (target.getPrecision() - target.getScale() < sourceDigits) {
                // A narrowing cast is exact for the rows that fit and has no answer for the rest, so
                // whether a query works would depend on its data. Only a cast every value survives is
                // compiled; the arithmetic's own result types always are one.
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "'" + call + "' narrows " + source.getType() + " to " + target + ", which holds "
                                + (target.getPrecision() - target.getScale()) + " integer digits where the source "
                                + "can have " + sourceDigits + ". Rows that do not fit would have no answer, so a "
                                + "cast to DECIMAL is compiled only when every value survives it: widen the "
                                + "target, or keep the value in integer minor units.");
            }
            return new Expression.DecimalRescale(operand(source), target.getPrecision(), target.getScale());
        }
        if (from == SqlTypeName.DECIMAL && (to == SqlTypeName.DOUBLE || to == SqlTypeName.FLOAT)) {
            return new Expression.DecimalToDouble(operand(source));
        }
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "'" + call + "' converts " + source.getType() + " to " + target + ". A DECIMAL is converted "
                        + "only exactly -- from an integer or a decimal to a decimal whose scale is at least as "
                        + "large -- or to DOUBLE, where the query asks for an approximation by name. Anything "
                        + "else would round or truncate without saying so.");
    }

    /**
     * One operand, compiled as an exact number: a decimal literal kept as written, a decimal column
     * or sub-expression, or an integer, which the arithmetic widens exactly.
     */
    Expression operand(RexNode node) {
        SqlTypeName type = node.getType().getSqlTypeName();
        if (node instanceof RexLiteral literal && type == SqlTypeName.DECIMAL) {
            if (literal.getValue() == null) {
                return Expression.Literal.ofNull(TypeName.INT64);
            }
            return new Expression.DecimalLiteral(literal.getValueAs(BigDecimal.class));
        }
        if (type != SqlTypeName.DECIMAL && !isInteger(type)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + node + "' is " + node.getType() + " inside DECIMAL arithmetic, which takes decimals and "
                            + "integers. Mixing in an approximate or non-numeric value would make the answer "
                            + "approximate without saying so.");
        }
        return expressions.compile(node);
    }

    /** Refuses a result type whose scale cannot hold the exact answer. */
    private static void requireScale(RexCall call, int exactScale, RelDataType type) {
        if (type.getScale() < exactScale) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' needs " + exactScale + " decimal places to be exact, and its result type is "
                            + type + ". SQL caps a DECIMAL at 38 digits and gives up scale to stay under the cap, "
                            + "so this answer would be rounded -- which Pravaha refuses rather than does. Reduce "
                            + "the operands' precision with an exact CAST, or compute in DOUBLE by name if an "
                            + "approximation is acceptable.");
        }
    }

    private static int scaleOf(RexNode node) {
        return node.getType().getSqlTypeName() == SqlTypeName.DECIMAL
                ? node.getType().getScale()
                : 0;
    }

    /** The decimal digits an integer type's largest value has. */
    private static int integerDigits(SqlTypeName type) {
        return switch (type) {
            case TINYINT -> 3;
            case SMALLINT -> 5;
            case INTEGER -> 10;
            default -> 19;
        };
    }

    private static boolean isInteger(SqlTypeName type) {
        return type == SqlTypeName.TINYINT
                || type == SqlTypeName.SMALLINT
                || type == SqlTypeName.INTEGER
                || type == SqlTypeName.BIGINT;
    }
}
