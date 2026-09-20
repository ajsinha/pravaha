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
        if (sqlType == SqlTypeName.BOOLEAN) {
            // getValue4 hands back a Boolean here, and the code below casts it to BigDecimal -- so
            // SELECT TRUE reached the user as a raw ClassCastException with no error code at all.
            return Expression.Literal.ofBoolean(Boolean.TRUE.equals(literal.getValue2()));
        }
        if (sqlType == SqlTypeName.TIMESTAMP || sqlType == SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE) {
            // Nanoseconds, because that is what the engine holds a timestamp in throughout
            // (ADR-012). Calcite carries the literal in milliseconds. Reading it through getValue4
            // threw a java.lang.AssertionError -- an Error, not an exception, which round 2 recorded
            // killing a Flight worker thread rather than failing the query.
            return Expression.Literal.ofLong(literal.getValueAs(Long.class) * 1_000_000L);
        }
        if (sqlType == SqlTypeName.DATE) {
            // Days since the epoch, which is what a DATE column holds.
            return Expression.Literal.ofLong(literal.getValueAs(Integer.class));
        }
        if (sqlType == SqlTypeName.TIME) {
            // Nanoseconds into the day; Calcite counts milliseconds.
            return Expression.Literal.ofLong(literal.getValueAs(Integer.class) * 1_000_000L);
        }
        if (sqlType == SqlTypeName.CHAR || sqlType == SqlTypeName.VARCHAR) {
            // getValue2 rather than getValue: the latter hands back an NlsString carrying charset
            // and collation, whose toString is the SQL rendering -- quotes included -- and would
            // put a literal pair of apostrophes inside the row.
            return Expression.Literal.ofText(String.valueOf(literal.getValue2()));
        }
        // Finding TY-4(a). Calcite carries an approximate literal as a Double once it is written
        // with an exponent -- `3.0E0` -- and as a BigDecimal when it is written `3.0`, and the cast
        // below assumed the second. `r / 3.0E0` therefore reached the user as a raw
        // ClassCastException with no code, from an ordinary division. Asking for the value as a
        // Double rather than casting what happens to be stored handles both spellings.
        if (isApproximate(sqlType)) {
            return Expression.Literal.ofDouble(literal.getValueAs(Double.class));
        }
        if (!(literal.getValue4() instanceof BigDecimal value)) {
            // Every remaining branch reads a BigDecimal. A literal that is something else is a
            // shape this does not know, and a coded refusal naming it is the only honest answer --
            // an uncoded ClassCastException reaching a client through Flight carries nothing.
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "literal '" + literal + "' of SQL type " + sqlType + " is carried as a "
                            + literal.getValue4().getClass().getSimpleName()
                            + ", which this compiler has no conversion for.");
        }
        return switch (sqlType) {
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
        if (call.getType().getSqlTypeName() == SqlTypeName.BOOLEAN) {
            Expression truth = booleanValued(call);
            if (truth != null) {
                return truth;
            }
        }
        Expression floatingModulo = floatingModulo(call);
        if (floatingModulo != null) {
            return floatingModulo;
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
                    default -> {
                        // X-7. A correlated scalar subquery arrived here as an unsupported
                        // "$SCALAR_QUERY" function, which is Calcite's internal spelling and not a
                        // thing the person wrote.
                        if (CorrelatedSubqueries.isCorrelated(call)) {
                            throw CorrelatedSubqueries.refusal(call);
                        }
                        throw new PravahaException(
                                SqlErrors.UNSUPPORTED_EXPRESSION,
                                "function '" + call.getOperator().getName() + "' in '" + call
                                        + "' is not supported in a projection. Supported: + - * / %, "
                                        + "ABS, FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, "
                                        + "SUBSTRING and || .");
                    }
                };
        if (call.getOperands().size() == 1) {
            // Unary. The refusal here used to say unary minus was supported "which Calcite
            // normalises to 0 - x" while refusing exactly that -- Calcite does not normalise it,
            // and the message sent anybody reading it looking for a different problem. So do the
            // normalisation rather than describing it.
            Expression operand = compile(call.getOperands().get(0));
            TypeName unaryType = typeOf(call.getType().getSqlTypeName(), call.toString());
            return switch (operator) {
                case SUBTRACT ->
                    new Expression.Arithmetic(
                            unaryType == TypeName.FLOAT32 || unaryType == TypeName.FLOAT64
                                    ? Expression.Literal.ofDouble(0)
                                    : Expression.Literal.ofLong(0),
                            Expression.Operator.SUBTRACT,
                            operand,
                            unaryType);
                // Unary plus is the identity, and SQL says so.
                case ADD -> operand;
                default ->
                    throw new PravahaException(
                            SqlErrors.UNSUPPORTED_EXPRESSION,
                            "'" + call + "' applies " + call.getOperator().getName()
                                    + " to one operand, which has no meaning. Only unary - and unary + take one.");
            };
        }
        if (call.getOperands().size() != 2) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' has " + call.getOperands().size()
                            + " operands; only the one- and two-operand forms are supported.");
        }

        TypeName type = typeOf(call.getType().getSqlTypeName(), call.toString());
        return new Expression.Arithmetic(
                compile(call.getOperands().get(0)),
                operator,
                compile(call.getOperands().get(1)),
                type);
    }

    /**
     * A boolean-valued call in a projection: {@code IS TRUE(c)}, a bare comparison, an {@code AND}.
     *
     * <p>Finding TY-11. Nobody writes {@code IS TRUE} -- Calcite does. {@code CASE WHEN c THEN TRUE
     * ELSE FALSE END} is rewritten before the planner sees it, into {@code IS TRUE(c)} when {@code
     * c} can be UNKNOWN and into the bare condition when it cannot, and neither shape had a
     * compiled path here. So the ordinary way to normalise a comparison into a boolean column was
     * refused for every query that wrote it, while the same CASE returning {@code 1}/{@code 0}
     * worked -- which is the tell that the CASE was never the problem.
     *
     * <p>Compiled by handing the whole call to {@link PredicateCompiler} and choosing between two
     * boolean literals on the result. That reuses the three-valued reasoning that already exists
     * rather than writing a second copy of it in the expression tree: a predicate's {@code test} is
     * true exactly where SQL says the condition is TRUE, so {@code CASE WHEN p THEN true ELSE
     * false} <em>is</em> {@code p IS TRUE}. {@code describe()} then prints the CASE the user wrote.
     *
     * <p><strong>A nullable boolean is refused rather than flattened.</strong> {@code SELECT status
     * = 'ok'} over a nullable column must produce three values, and the predicate IR has two: every
     * comparison returns false for a null operand. Projecting it would turn UNKNOWN into {@code
     * false} silently, which is a wrong answer under exit 0 rather than a missing feature. Calcite
     * marks exactly these calls nullable, so the check is one line and needs no analysis of its
     * own. {@code IS TRUE}/{@code IS NOT TRUE} are NOT NULL by definition and so always pass it --
     * which is why the CASE this finding is about works while the bare comparison under it does not.
     *
     * @return null if this call is not something {@link PredicateCompiler} understands, so that the
     *     ordinary projection refusal names the function instead of a predicate-shaped message
     */
    private Expression booleanValued(RexCall call) {
        Predicate predicate;
        try {
            predicate = new PredicateCompiler(inputSchema).compile(call);
        } catch (PravahaException notAPredicate) {
            // Swallowed on purpose, and only here. A boolean call this cannot compile as a
            // predicate -- SEARCH over a Sarg, a subquery -- is better refused by the projection's
            // own message, which lists what a projection supports, than by a predicate-shaped one
            // about WHERE clauses. The caller re-raises with that message a few lines down; nothing
            // is accepted as a result of this catch.
            return null;
        }
        if (call.getType().isNullable()) {
            // HLP-14(a). This advised adding IS NOT NULL to the operand, and a query that did so was
            // refused the same way: the comparison stays nullable in Calcite's typing. IS TRUE and
            // IS NOT FALSE are NOT NULL by definition.
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' is a boolean that can be UNKNOWN, and a projected column holds TRUE or "
                            + "FALSE. Projecting it would report UNKNOWN as FALSE, which is a wrong answer "
                            + "rather than a missing one. Say which answer UNKNOWN should be: (<condition>) IS TRUE "
                            + "reads it as FALSE, and (<condition>) IS NOT FALSE as TRUE -- for example "
                            + "(status = 'ok') IS TRUE. Both are never UNKNOWN, so both plan.");
        }
        return new Expression.Case(predicate, Expression.Literal.ofBoolean(true), Expression.Literal.ofBoolean(false));
    }

    /**
     * {@code %}/{@code MOD} over floating point, which arrives dressed as DECIMAL arithmetic.
     *
     * <p>Finding TY-1. Calcite casts both operands of {@code MOD} to {@code DECIMAL} before the
     * planner sees them -- unlike {@code + - * /}, which keep their native floating type -- so
     * {@code x % y} over two FLOAT64 columns reached {@link #refuseDecimalType} and was told it was
     * decimal arithmetic in a ledger. Neither operand was ever declared DECIMAL, and floating
     * modulo was unreachable through SQL entirely: {@code x % y}, {@code MOD(x, y)} and every mixed
     * floating pair produced the same sentence.
     *
     * <p>The coercion is undone rather than the refusal weakened. A {@code CAST(x):DECIMAL} wrapper
     * over an approximate operand is Calcite's doing and carries no information, so it is stripped
     * and the result type re-derived the way {@code *} derives it -- widest approximate operand
     * wins. That makes {@code price % 1.5} behave like {@code price * 1.5}, which already planned,
     * and leaves {@code amount % 1.5} refused, which {@code amount * 1.5} also is. The refusal
     * still fires for everything that is genuinely decimal; what changed is that a query with no
     * decimal in it stops being told otherwise.
     *
     * <p>Rejected: special-casing {@code refuseDecimalType} to let any MOD through. That would also
     * let {@code MOD(decimal_column, 3)} through and evaluate it in a double -- exactly the
     * rounding error the refusal exists to prevent.
     *
     * @return null if this call is not a floating modulo, leaving every other path unchanged
     */
    private Expression floatingModulo(RexCall call) {
        String name = call.getOperator().getName().toUpperCase(java.util.Locale.ROOT);
        if ((!name.equals("MOD") && !name.equals("%"))
                || call.getOperands().size() != 2
                || call.getType().getSqlTypeName() != SqlTypeName.DECIMAL) {
            return null;
        }
        RexNode left = withoutDecimalCoercion(call.getOperands().get(0));
        RexNode right = withoutDecimalCoercion(call.getOperands().get(1));
        TypeName type = widestApproximate(left, right);
        if (type == null) {
            return null;
        }
        return new Expression.Arithmetic(compile(left), Expression.Operator.MODULO, compile(right), type);
    }

    /** Strips the DECIMAL cast Calcite wraps an approximate MOD operand in, and nothing else. */
    private static RexNode withoutDecimalCoercion(RexNode operand) {
        if (operand instanceof RexCall cast
                && cast.getKind() == org.apache.calcite.sql.SqlKind.CAST
                && cast.getType().getSqlTypeName() == SqlTypeName.DECIMAL
                && isApproximate(cast.getOperands().get(0).getType().getSqlTypeName())) {
            return cast.getOperands().get(0);
        }
        return operand;
    }

    /** FLOAT64 if either side is a DOUBLE, FLOAT32 if either is a REAL, null if neither floats. */
    private static TypeName widestApproximate(RexNode left, RexNode right) {
        SqlTypeName leftType = left.getType().getSqlTypeName();
        SqlTypeName rightType = right.getType().getSqlTypeName();
        if (leftType == SqlTypeName.DOUBLE || rightType == SqlTypeName.DOUBLE) {
            return TypeName.FLOAT64;
        }
        if (isApproximate(leftType) || isApproximate(rightType)) {
            return TypeName.FLOAT32;
        }
        return null;
    }

    private static boolean isApproximate(SqlTypeName sqlType) {
        return sqlType == SqlTypeName.DOUBLE || sqlType == SqlTypeName.FLOAT || sqlType == SqlTypeName.REAL;
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
        if (from == 0) {
            // Finding TY-4(b). `CASE WHEN n > 5 THEN 1 ELSE 1.5 END` reached the user as a raw
            // IllegalArgumentException out of Expression.Case's constructor -- one branch compiled
            // to an INT64 and the other to a FLOAT64 -- with no code and nothing said about why the
            // query cannot be answered. Calcite types that CASE DECIMAL(2,1), exactly as it types
            // `amount * 1.5`, so the refusal it deserves is the decimal one that already exists.
            // Asking for the whole CASE's type first is what makes the two agree.
            typeOf(call.getType().getSqlTypeName(), call.toString());
        }
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
        Expression then = compile(operands.get(from + 1));
        Expression otherwise = caseWhen(call, from + 2);
        try {
            return new Expression.Case(when, then, otherwise);
        } catch (IllegalArgumentException branchesDisagree) {
            // The net under the type check above, for a shape where Calcite's declared type is one
            // this engine has and the branches still compile to two different ones. Coded, because
            // an IllegalArgumentException crossing Flight carries no code at all (TY-4(b)).
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' is a CASE whose branches produce different types: " + branchesDisagree.getMessage(),
                    branchesDisagree);
        }
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
        if (isIdentityCast(call)) {
            // Calcite inserts a cast of a VARCHAR onto itself to settle a charset or a nullability
            // difference -- `CAST($4):VARCHAR CHARACTER SET "UTF-8"` over a VARCHAR column. It
            // converts nothing, and refusing it as "converts between STRING and STRING" refused
            // ordinary queries (a text CASE, TY-5) for a wrapper the user never wrote.
            //
            // Two guards keep this from widening into a wrong answer. Identity is decided on
            // Calcite's types, not Pravaha's, so `CAST(s AS VARCHAR(3))` -- which has to truncate,
            // and this engine does not -- is not identity and stays refused rather than passing
            // through unchanged. And the type still has to be one the expression tree can carry:
            // a DECIMAL cast onto itself converts nothing either, but letting it through would put
            // a 128-bit decimal behind an expression that reads it as a long.
            typeOf(call.getType().getSqlTypeName(), call.toString());
            return compile(call.getOperands().get(0));
        }
        Expression source = compile(call.getOperands().get(0));
        TypeName target = typeOf(call.getType().getSqlTypeName(), call.toString());
        if (!isNumeric(source.type()) || !isNumeric(target)) {
            // Y-5 and DOCX-20. This is the refusal a person actually meets for `name || amount`:
            // SQL's own coercion inserts the cast, so the concatenation never gets as far as
            // Expression.Concat's message. It used to stop at "numeric conversions only", which
            // leaves the reader to guess whether some other spelling of the cast would work. None
            // would, and saying so is the difference between a refusal and a riddle.
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' converts between " + source.type() + " and " + target
                            + ", and this engine converts between numbers only -- there is no number-to-text "
                            + "or text-to-number conversion here, and writing the CAST out by hand is refused "
                            + "identically, so it is not a spelling to look for. If the cast was not written, "
                            + "SQL's own coercion inserted it: || joins text, and a number beside it is "
                            + "coerced. Format the value where the text is assembled, or carry it as a "
                            + "separate column.");
        }
        return source.type() == target ? source : new Expression.Cast(source, target);
    }

    /** True when the cast's source and target are the same SQL type at the same width. */
    private static boolean isIdentityCast(RexCall call) {
        org.apache.calcite.rel.type.RelDataType from = call.getOperands().get(0).getType();
        org.apache.calcite.rel.type.RelDataType to = call.getType();
        return from.getSqlTypeName() == to.getSqlTypeName()
                && from.getPrecision() == to.getPrecision()
                && from.getScale() == to.getScale();
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
            case TIME -> TypeName.TIME;
            case CHAR, VARCHAR -> TypeName.STRING;
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
                        + "A query need not mention DECIMAL to be this: a literal written with a decimal point "
                        + "-- 2.5, 0.01 -- is a DECIMAL literal in SQL, and an integer column beside one makes "
                        + "the whole expression DECIMAL. (The same literal beside a DOUBLE column does not: "
                        + "there the result is DOUBLE and the expression plans.) "
                        + "Evaluating it in double would pass every test anybody writes and produce a rounding "
                        + "error in a ledger. The row layout carries 128-bit decimals; the arithmetic over them "
                        + "is not built. Write the literal as approximate -- 2.5e0 -- or cast the column with "
                        + "CAST(col AS DOUBLE), if approximate is genuinely acceptable.");
    }
}
