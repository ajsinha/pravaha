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
import com.ash.messaging.pravaha.runtime.plan.Predicate;
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
                        ref.getIndex(),
                        inputSchema.field(ref.getIndex()).name(),
                        inputSchema.field(ref.getIndex()).type().typeName());
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

        // An interval literal is a duration, and Calcite carries day-time ones in milliseconds while
        // this engine works in nanoseconds throughout (ADR-012). Left unconverted, `ts + INTERVAL
        // '10' SECOND` adds ten microseconds -- an answer that is wrong by six orders of magnitude
        // and still looks like a timestamp, which is the kind of wrong nobody spots in a result set.
        if (SqlTypeName.INTERVAL_TYPES.contains(sqlType)) {
            if (SqlTypeName.YEAR_INTERVAL_TYPES.contains(sqlType)) {
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "'" + literal + "' is a year-month interval, which has no fixed length in nanoseconds -- "
                                + "a month is 28 to 31 days. Use a day-time interval, or do the calendar "
                                + "arithmetic where a calendar is available.");
            }
            return Expression.Literal.ofLong(((BigDecimal) literal.getValue4()).longValue() * 1_000_000L);
        }
        if (sqlType == SqlTypeName.CHAR || sqlType == SqlTypeName.VARCHAR) {
            // getValue2 rather than getValue: the latter hands back an NlsString carrying charset
            // and collation, whose toString is the SQL rendering -- quotes included -- and would
            // put a literal pair of apostrophes inside the row.
            return Expression.Literal.ofText(String.valueOf(literal.getValue2()));
        }
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
        if (call.getKind() == org.apache.calcite.sql.SqlKind.CAST) {
            return cast(call);
        }
        if (call.getKind() == org.apache.calcite.sql.SqlKind.CASE) {
            return caseWhen(call, 0);
        }
        Expression.Function function = unaryFunction(call.getOperator().getName());
        if (function != null) {
            if (call.getOperands().size() != 1) {
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        call.getOperator().getName() + " is supported with one argument and was given "
                                + call.getOperands().size() + ". ROUND to a number of decimal places is not "
                                + "built; round the value and scale it, or cast it.");
            }
            return new Expression.Unary(function, compile(call.getOperands().get(0)));
        }
        Expression text = textCall(call);
        if (text != null) {
            return text;
        }
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
                                        + "' is not supported in a projection. Supported: + - * / %, "
                                        + "ABS, FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, "
                                        + "SUBSTRING and || .");
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

    /**
     * A numeric cast, which Calcite inserts on its own whenever operand types differ.
     *
     * <p>Accepted only between numbers. A cast to or from text, or anything else, is refused by name
     * rather than evaluated as whatever the underlying long happens to be.
     */
    /**
     * {@code CASE WHEN a THEN x WHEN b THEN y ELSE z END}.
     *
     * <p>Calcite flattens the chain into one call: condition, value, condition, value, …, else. It
     * is rebuilt here as nested two-way choices, which evaluates identically and keeps each node a
     * shape with one meaning.
     */
    private Expression caseWhen(RexCall call, int from) {
        java.util.List<RexNode> operands = call.getOperands();
        if (from == operands.size() - 1) {
            return compile(operands.get(from));
        }
        if (from >= operands.size()) {
            // Calcite always supplies an ELSE, adding a null one where the SQL omitted it, so
            // reaching here means the shape is not what this understands rather than that the
            // query was missing a branch.
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "this CASE has no ELSE branch and no value to fall through to: " + call);
        }
        Predicate when = new PredicateCompiler(inputSchema).compile(operands.get(from));
        return new Expression.Case(when, compile(operands.get(from + 1)), caseWhen(call, from + 2));
    }

    /**
     * Translates the text functions, or returns {@code null} if this call is not one.
     *
     * <p>Null rather than an exception for "not mine", because the caller tries arithmetic next and
     * a refusal raised here would pre-empt it with the wrong message.
     */
    private Expression textCall(RexCall call) {
        String name = call.getOperator().getName().toUpperCase(java.util.Locale.ROOT);
        java.util.List<RexNode> operands = call.getOperands();
        return switch (name) {
            case "UPPER" -> new Expression.TextFunction(Expression.TextOp.UPPER, compile(operands.get(0)));
            case "LOWER" -> new Expression.TextFunction(Expression.TextOp.LOWER, compile(operands.get(0)));
            case "TRIM" -> trim(call, operands);
            case "||", "CONCAT" -> concat(operands);
            case "SUBSTRING" -> substring(call, operands);
            default -> null;
        };
    }

    /**
     * {@code TRIM(BOTH ' ' FROM s)} only.
     *
     * <p>Calcite normalises every {@code TRIM} to the three-operand form, so the flag and the trim
     * character are always present and always checkable. A query asking to strip a different
     * character, or from one end only, is refused by name rather than silently given the default --
     * which would return the input unchanged for anything that has no leading spaces, and look like
     * it worked.
     */
    private Expression trim(RexCall call, java.util.List<RexNode> operands) {
        boolean bothEnds =
                operands.get(0).toString().toUpperCase(java.util.Locale.ROOT).contains("BOTH");
        boolean spaces = operands.get(1) instanceof RexLiteral literal && " ".equals(literal.getValue2());
        if (!bothEnds || !spaces) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' is not supported: TRIM strips spaces from both ends, and LEADING, "
                            + "TRAILING and a trim character other than a space are not built.");
        }
        return new Expression.TextFunction(Expression.TextOp.TRIM, compile(operands.get(2)));
    }

    private Expression concat(java.util.List<RexNode> operands) {
        // Flattened here rather than in the runtime record: `a || b || c` arrives as nested pairs,
        // and joining three strings in one pass beats building an intermediate for the inner pair.
        java.util.List<Expression> parts = new java.util.ArrayList<>();
        for (RexNode operand : operands) {
            Expression compiled = compile(operand);
            if (compiled instanceof Expression.Concat nested) {
                parts.addAll(nested.parts());
            } else {
                parts.add(compiled);
            }
        }
        return new Expression.Concat(parts);
    }

    private Expression substring(RexCall call, java.util.List<RexNode> operands) {
        if (operands.size() == 2) {
            return Expression.Substring.toEnd(compile(operands.get(0)), compile(operands.get(1)));
        }
        if (operands.size() == 3) {
            return new Expression.Substring(
                    compile(operands.get(0)), compile(operands.get(1)), compile(operands.get(2)));
        }
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "'" + call + "' has " + operands.size() + " arguments; SUBSTRING takes the string with "
                        + "FROM start, optionally FOR length.");
    }

    private static Expression.Function unaryFunction(String name) {
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "ABS" -> Expression.Function.ABS;
            case "FLOOR" -> Expression.Function.FLOOR;
            case "CEIL", "CEILING" -> Expression.Function.CEIL;
            case "ROUND" -> Expression.Function.ROUND;
            default -> null;
        };
    }

    private Expression cast(RexCall call) {
        Expression source = compile(call.getOperands().get(0));
        TypeName target = typeOf(call.getType().getSqlTypeName(), call.toString());
        if (!isNumeric(source.type()) || !isNumeric(target)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' converts between " + source.type() + " and " + target
                            + "; Pravaha evaluates numeric conversions only");
        }
        return source.type() == target ? source : new Expression.Cast(source, target);
    }

    private static boolean isNumeric(TypeName type) {
        return switch (type) {
            case INT8, INT16, INT32, INT64, FLOAT32, FLOAT64 -> true;
            default -> false;
        };
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
