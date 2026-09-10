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

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * Turns Calcite's expression trees into Pravaha's.
 *
 * <p>Two trees rather than reusing Calcite's, for the same reason the plan is translated at all
 * (ADR-002): nothing below the SQL layer imports Calcite, so a {@code RexNode} cannot cross the
 * boundary. The translation is also where unsupported constructs are refused, at registration, with
 * the expression that could not be translated printed -- rather than at the first record that
 * reaches it.
 *
 * <p><strong>DECIMAL is refused rather than approximated.</strong> Calcite will happily hand over a
 * {@code DECIMAL} multiplication, and evaluating it in {@code double} would work for every test
 * anybody writes and produce a rounding error in somebody's ledger. The row layout already carries
 * 128-bit decimals; the arithmetic on them is work that has not been done, and saying so is the only
 * honest option.
 */
final class ExpressionCompiler {

    private final StreamSchema inputSchema;

    ExpressionCompiler(StreamSchema inputSchema) {
        this.inputSchema = inputSchema;
    }

    /** Compiles one expression, or refuses it by name. */
    Expression compile(RexNode node) {
        return switch (node) {
            case RexInputRef ref ->
                new Expression.Column(
                        ref.getIndex(), inputSchema.field(ref.getIndex()).type().typeName());
            case RexLiteral literal -> literal(literal);
            case RexCall call -> call(call);
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "expression '" + node + "' is a " + node.getClass().getSimpleName()
                                + ", which Pravaha cannot evaluate yet. Supported: column references, numeric "
                                + "literals, and + - * / % over integers and floating point.");
        };
    }

    private Expression literal(RexLiteral literal) {
        if (literal.getValue() == null) {
            return Expression.Literal.ofNull(typeOf(literal.getType().getSqlTypeName(), literal.toString()));
        }
        SqlTypeName sqlType = literal.getType().getSqlTypeName();
        BigDecimal value = (BigDecimal) literal.getValue4();
        return switch (sqlType) {
            case DOUBLE, FLOAT, REAL -> Expression.Literal.ofDouble(value.doubleValue());
            // A literal written as 2.5 arrives as DECIMAL(2,1) regardless of what it multiplies,
            // so refusing it here would refuse `rate * 2.5` on a DOUBLE column -- not decimal
            // arithmetic in any sense that matters. The refusal belongs to the operation's type,
            // which is checked separately.
            case DECIMAL ->
                value.stripTrailingZeros().scale() > 0
                        ? Expression.Literal.ofDouble(value.doubleValue())
                        : Expression.Literal.ofLong(value.longValue());
            default -> Expression.Literal.ofLong(value.longValue());
        };
    }

    private Expression call(RexCall call) {
        Expression.Operator operator =
                switch (call.getOperator().getName().toUpperCase(java.util.Locale.ROOT)) {
                    case "+" -> Expression.Operator.ADD;
                    case "-" -> Expression.Operator.SUBTRACT;
                    case "*" -> Expression.Operator.MULTIPLY;
                    case "/" -> Expression.Operator.DIVIDE;
                    case "%", "MOD" -> Expression.Operator.MODULO;
                    default ->
                        throw new PravahaException(
                                SqlErrors.UNSUPPORTED_EXPRESSION,
                                "function '" + call.getOperator().getName() + "' in '" + call
                                        + "' is not supported in a projection yet. Supported: + - * / %.");
                };
        if (call.getOperands().size() != 2) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' has " + call.getOperands().size()
                            + " operands; only the two-operand form is supported (unary minus included, which "
                            + "Calcite normalises to 0 - x).");
        }

        TypeName type = typeOf(call.getType().getSqlTypeName(), call.toString());
        return new Expression.Arithmetic(
                compile(call.getOperands().get(0)),
                operator,
                compile(call.getOperands().get(1)),
                type);
    }

    private TypeName typeOf(SqlTypeName sqlType, String context) {
        return switch (sqlType) {
            case TINYINT -> TypeName.INT8;
            case SMALLINT -> TypeName.INT16;
            case INTEGER -> TypeName.INT32;
            case BIGINT -> TypeName.INT64;
            case FLOAT, REAL -> TypeName.FLOAT32;
            case DOUBLE -> TypeName.FLOAT64;
            case BOOLEAN -> TypeName.BOOLEAN;
            case DATE -> TypeName.DATE;
            case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> TypeName.TIMESTAMP_LTZ;
            case DECIMAL -> refuseDecimalType(context);
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "'" + context + "' has SQL type " + sqlType + ", which Pravaha cannot compute with yet");
        };
    }

    private TypeName refuseDecimalType(String context) {
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "'" + context + "' is DECIMAL arithmetic, which Pravaha refuses rather than approximates. "
                        + "Evaluating it in double would pass every test anybody writes and produce a rounding "
                        + "error in a ledger. The row layout carries 128-bit decimals; the arithmetic over them "
                        + "is not built. Cast to DOUBLE explicitly if approximate is genuinely acceptable.");
    }
}
