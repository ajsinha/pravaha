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

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * Turns a Calcite {@code RexNode} into Pravaha's predicate IR.
 *
 * <p>It produces <em>structure</em>, not a closure. That distinction is load-bearing: the
 * interpreted path evaluates the IR and the code generator emits source from the same IR, so the
 * differential tests compare two implementations of one specification rather than two separate
 * translations that could each be wrong in their own way (design section 12.4).
 *
 * <p>An expression this cannot translate raises {@code PRV-2021} at <em>registration</em>, naming
 * it. A query that plans successfully and fails on its first record fails in production, where the
 * diagnosis is expensive.
 */
public final class PredicateCompiler {

    private final StreamSchema schema;
    private final BoundParameters parameters;

    public PredicateCompiler(StreamSchema schema) {
        this(schema, BoundParameters.none());
    }

    /**
     * @param parameters the values bound to this statement's {@code ?} placeholders. Resolved here,
     *     while the plan is being built, so no bound value ever passes through a parser
     */
    public PredicateCompiler(StreamSchema schema, BoundParameters parameters) {
        this.schema = schema;
        this.parameters = parameters == null ? BoundParameters.none() : parameters;
    }

    public Predicate compile(RexNode node) {
        return switch (node.getKind()) {
            case AND -> new Predicate.And(compileAll((RexCall) node));
            case OR -> new Predicate.Or(compileAll((RexCall) node));
            case NOT -> negate(((RexCall) node).getOperands().get(0));
            case EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL ->
                comparison((RexCall) node, false);
            case IS_NULL -> nullCheck((RexCall) node, true);
            case IS_NOT_NULL -> nullCheck((RexCall) node, false);
            // The truth tests, which Calcite writes on the user's behalf: `CASE WHEN c THEN TRUE
            // ELSE FALSE END` is rewritten to `c IS TRUE` before this sees it (finding TY-11), and
            // a WHERE clause spelling one out arrives the same way. Each maps onto a primitive the
            // two-valued IR already has, because `compile(x).test(row)` is by construction true
            // exactly where SQL says x is TRUE:
            //   x IS TRUE      -> that, unchanged
            //   x IS FALSE     -> negate(x), which is TRUE only where x is present and false
            //   x IS NOT TRUE  -> the *total* complement: FALSE or UNKNOWN
            //   x IS NOT FALSE -> the total complement of IS FALSE: TRUE or UNKNOWN
            // Predicate.Not is the total complement and its own javadoc warns against using it
            // over something that can be UNKNOWN. That warning is about modelling SQL's NOT, whose
            // result is UNKNOWN for an UNKNOWN operand. IS NOT TRUE is the other operator -- it is
            // defined as the complement and is never UNKNOWN -- so Not is exactly right here.
            case IS_TRUE -> compile(((RexCall) node).getOperands().get(0));
            case IS_FALSE -> negate(((RexCall) node).getOperands().get(0));
            case IS_NOT_TRUE ->
                new Predicate.Not(compile(((RexCall) node).getOperands().get(0)));
            case IS_NOT_FALSE ->
                new Predicate.Not(negate(((RexCall) node).getOperands().get(0)));
            case LIKE -> like((RexCall) node, false);
            case LITERAL ->
                Boolean.TRUE.equals(((RexLiteral) node).getValueAs(Boolean.class))
                        ? new Predicate.True()
                        : new Predicate.False();
            case INPUT_REF -> {
                RexInputRef ref = (RexInputRef) node;
                yield new Predicate.CompareBoolean(ref.getIndex(), columnName(ref.getIndex()), true);
            }
            default -> throw unsupported(node);
        };
    }

    /**
     * Compiles {@code NOT node} into a predicate that is true exactly where SQL says the negation
     * is TRUE -- never where it is UNKNOWN.
     *
     * <p>This exists because the predicate IR is two-valued. Every comparison here returns false for
     * a null operand, which is right for a WHERE clause: UNKNOWN and FALSE both drop the row. But
     * wrapping that in a Java {@code !} turns the dropped row into a kept one, and {@code WHERE NOT
     * (bonus > 1)} over a null bonus then returns rows SQL says it must not. Calcite hands the plan
     * over with the NOT intact, so pushing it down is Pravaha's job.
     *
     * <p>Doing it here rather than at runtime means the three-valued reasoning happens once per
     * query instead of once per row, and the runtime keeps returning a plain boolean.
     */
    private Predicate negate(RexNode node) {
        return switch (node.getKind()) {
            // NOT (A AND B) is TRUE where either half is FALSE; NOT (A OR B) where both are.
            case AND -> new Predicate.Or(negateAll((RexCall) node));
            case OR -> new Predicate.And(negateAll((RexCall) node));
            // NOT NOT A is TRUE exactly where A is TRUE, which is what compile already means.
            case NOT -> compile(((RexCall) node).getOperands().get(0));
            case EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL ->
                comparison((RexCall) node, true);
            // A null check is total: it is never UNKNOWN, so its negation is the other one.
            case IS_NULL -> nullCheck((RexCall) node, false);
            case IS_NOT_NULL -> nullCheck((RexCall) node, true);
            // The truth tests are total for the same reason, so each negates to its partner rather
            // than needing the three-valued care a comparison does (finding TY-11).
            case IS_TRUE ->
                new Predicate.Not(compile(((RexCall) node).getOperands().get(0)));
            case IS_NOT_TRUE -> compile(((RexCall) node).getOperands().get(0));
            case IS_FALSE ->
                new Predicate.Not(negate(((RexCall) node).getOperands().get(0)));
            case IS_NOT_FALSE -> negate(((RexCall) node).getOperands().get(0));
            // NOT LIKE is TRUE only where the column is present and does not match. Compiled as a
            // flag rather than wrapped, so the null row is dropped by both forms.
            case LIKE -> like((RexCall) node, true);
            case LITERAL ->
                Boolean.TRUE.equals(((RexLiteral) node).getValueAs(Boolean.class))
                        ? new Predicate.False()
                        : new Predicate.True();
            // NOT flagged is TRUE only where flagged is present and false.
            case INPUT_REF -> {
                RexInputRef ref = (RexInputRef) node;
                yield new Predicate.CompareBoolean(ref.getIndex(), columnName(ref.getIndex()), false);
            }
            default -> throw unsupported(node);
        };
    }

    private List<Predicate> negateAll(RexCall call) {
        List<Predicate> parts = new ArrayList<>(call.getOperands().size());
        call.getOperands().forEach(operand -> parts.add(negate(operand)));
        return parts;
    }

    private List<Predicate> compileAll(RexCall call) {
        List<Predicate> parts = new ArrayList<>(call.getOperands().size());
        call.getOperands().forEach(operand -> parts.add(compile(operand)));
        return parts;
    }

    /**
     * @param negated compile {@code NOT (left op right)} instead, which for a comparison is the same
     *     comparison with the opposite operator -- and stays false for null operands, as it must
     */
    private Predicate comparison(RexCall call, boolean negated) {
        RexNode left = call.getOperands().get(0);
        RexNode right = call.getOperands().get(1);
        if (negated && (isFloatingPoint(left) || isFloatingPoint(right))) {
            return negatedFloatingComparison(call, left, right);
        }
        Predicate.Op op = negated ? opOf(call.getKind()).negated() : opOf(call.getKind());
        refuseIncomparableColumn(call, left);
        refuseIncomparableColumn(call, right);

        // A DECIMAL on either side is compared exactly, as decimals, by the general path: the
        // column-against-literal shapes below read a fixed-width primitive, which a 128-bit
        // decimal is not, and would read a decimal literal as a double.
        if (isDecimal(left) || isDecimal(right)) {
            // A decimal column against a decimal literal it can hold exactly: a typed comparison the
            // code generator emits (CG-1). Anything else -- arithmetic, a cast, a literal with more
            // fractional digits than the column -- is compared by the general path.
            Predicate typed = left instanceof RexInputRef ref && right instanceof RexLiteral literal
                    ? decimalAgainstLiteral(ref.getIndex(), op, literal)
                    : left instanceof RexLiteral literal && right instanceof RexInputRef ref
                            ? decimalAgainstLiteral(ref.getIndex(), flip(op), literal)
                            : null;
            // DECPARAM-1: a bound value is a literal that arrived late, and takes the same path.
            if (typed == null && left instanceof RexInputRef ref && right instanceof RexDynamicParam param) {
                typed = decimalAgainstParameter(ref.getIndex(), op, param);
            } else if (typed == null && left instanceof RexDynamicParam param && right instanceof RexInputRef ref) {
                typed = decimalAgainstParameter(ref.getIndex(), flip(op), param);
            }
            return typed != null ? typed : compareExpressions(call, op);
        }

        // Column against literal, in either order, first: those are the shapes the code generator
        // turns into a single typed load and compare, and they are the overwhelming majority of
        // real predicates. Anything else -- `amount * 2 > 100`, `a > b` -- goes to the general
        // expression compiler below, which is correct but interpreted.
        if (left instanceof RexInputRef ref && right instanceof RexLiteral literal) {
            return compare(ref.getIndex(), op, Constant.of(literal));
        }
        if (left instanceof RexLiteral literal && right instanceof RexInputRef ref) {
            return compare(ref.getIndex(), flip(op), Constant.of(literal));
        }
        // A bound parameter takes the same path a literal does, and produces the same predicate.
        // That equivalence is the point: `WHERE user_id = ?` and `WHERE user_id = 'u1'` execute
        // through identical code, so the parameterised form cannot be slower or subtly different.
        if (left instanceof RexInputRef ref && right instanceof RexDynamicParam param) {
            return compare(ref.getIndex(), op, boundValue(ref.getIndex(), param));
        }
        if (left instanceof RexDynamicParam param && right instanceof RexInputRef ref) {
            return compare(ref.getIndex(), flip(op), boundValue(ref.getIndex(), param));
        }
        return compareExpressions(call, op);
    }

    /**
     * Refuses a comparison against a column whose type this engine cannot compare, by name.
     *
     * <p>Finding TY-14. {@code WHERE bin = 'cafe'} over a BYTES column was refused with {@code
     * 'CAST('cafe'):VARBINARY NOT NULL' has SQL type VARBINARY, which Pravaha cannot compute with
     * yet} -- Calcite's rendering of a cast the person did not write, naming no column. The same
     * mistake against an ARRAY column got the column's name, because ARRAY maps to {@code ANY},
     * takes no implicit cast, and so reached {@link #compare}'s column-naming branch. Two type
     * families refusing the same mistake two different ways is the gap; this closes it from the
     * column's side, which is the side the person can see.
     */
    private void refuseIncomparableColumn(RexCall call, RexNode operand) {
        if (!(operand instanceof RexInputRef ref)) {
            return;
        }
        TypeName type = schema.field(ref.getIndex()).type().typeName();
        if (type != TypeName.BYTES && type != TypeName.ARRAY && type != TypeName.MAP && type != TypeName.ROW) {
            return;
        }
        throw new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "cannot compare column '" + columnName(ref.getIndex()) + "' of type " + type
                        + " in '" + call + "': this engine compares numbers, text, booleans, and the date "
                        + "and time types. A " + type + " column can be selected and null-checked, and "
                        + (type == TypeName.BYTES ? "compared only as a join key." : "nothing more than that."));
    }

    /**
     * The general comparison: both sides compiled as expressions.
     *
     * <p>Text is excluded deliberately. The expression tree evaluates to a long or a double, so a
     * string comparison reaching here would compare something that is not the string -- and the
     * only string comparisons Pravaha supports at all are equality against a literal, which the
     * fast path above already handled.
     */
    private Predicate compareExpressions(RexCall call, Predicate.Op op) {
        ExpressionCompiler expressions = new ExpressionCompiler(schema);
        boolean decimal = isDecimal(call.getOperands().get(0))
                || isDecimal(call.getOperands().get(1));
        DecimalCompiler decimals = new DecimalCompiler(expressions);
        Expression left = decimal
                ? decimalOperand(decimals, call.getOperands().get(0))
                : expressions.compile(call.getOperands().get(0));
        Expression right = decimal
                ? decimalOperand(decimals, call.getOperands().get(1))
                : expressions.compile(call.getOperands().get(1));
        rejectTextOrdering(call, op, left, right);
        return new Predicate.CompareExpressions(left, op, right);
    }

    /**
     * One side of a DECIMAL comparison: a bound placeholder as the exact decimal it holds, which is
     * how a literal is compiled, and anything else as {@link DecimalCompiler#operand} compiles it.
     */
    private Expression decimalOperand(DecimalCompiler decimals, RexNode node) {
        if (!(node instanceof RexDynamicParam param)) {
            return decimals.operand(node);
        }
        java.math.BigDecimal value = boundDecimal(param);
        return value == null ? Expression.Literal.ofNull(TypeName.INT64) : new Expression.DecimalLiteral(value);
    }

    /** A placeholder compared with a DECIMAL, exactly: a decimal or an integer, never a double. */
    private java.math.@Nullable BigDecimal boundDecimal(RexDynamicParam param) {
        Object value = parameters.at(param.getIndex());
        BoundParameters.checkAssignable(param.getIndex(), value, TypeName.DECIMAL);
        return BoundParameters.exactDecimal(value);
    }

    /**
     * {@code column op ?} over a DECIMAL column: the typed comparison a literal gets when the bound
     * value fits the column's scale, false for a bound NULL, and null -- the general, exact path --
     * otherwise.
     */
    private @Nullable Predicate decimalAgainstParameter(int ordinal, Predicate.Op op, RexDynamicParam param) {
        java.math.BigDecimal value = boundDecimal(param);
        return value == null ? new Predicate.False() : decimalAgainstValue(ordinal, op, value);
    }

    /**
     * {@code column op literal} over a DECIMAL column as a {@link Predicate.CompareDecimal}, or null
     * when the literal is not exactly representable at the column's scale in 128 bits, or either side
     * is not a plain decimal.
     */
    private @Nullable Predicate decimalAgainstLiteral(int ordinal, Predicate.Op op, RexLiteral literal) {
        if (literal.isNull() || literal.getType().getSqlTypeName() != org.apache.calcite.sql.type.SqlTypeName.DECIMAL) {
            return null;
        }
        java.math.BigDecimal value = literal.getValueAs(java.math.BigDecimal.class);
        return value == null ? null : decimalAgainstValue(ordinal, op, value);
    }

    private @Nullable Predicate decimalAgainstValue(int ordinal, Predicate.Op op, java.math.BigDecimal value) {
        if (!(schema.field(ordinal).type() instanceof com.ash.messaging.pravaha.api.data.DecimalType column)) {
            return null;
        }
        try {
            int scale = column.scale();
            return new Predicate.CompareDecimal(
                    ordinal,
                    columnName(ordinal),
                    op,
                    com.ash.messaging.pravaha.common.row.Decimals.high(value, scale),
                    com.ash.messaging.pravaha.common.row.Decimals.low(value, scale),
                    scale);
        } catch (ArithmeticException notExact) {
            // More fractional digits than the column has, or past 128 bits: the general path
            // compares it exactly without rescaling it into a shape it does not fit.
            return null;
        }
    }

    /**
     * {@code NOT (a op b)} where either side is floating point. Finding NANNOT-1: the opposite
     * operator is not the IEEE complement once NaN is in play -- {@code NaN > 5} is FALSE and so
     * is {@code NaN <= 5} -- so a NaN row was in neither {@code d > 5} nor {@code NOT (d > 5)}.
     * SQL's NOT is the complement wherever the comparison is not UNKNOWN, which over a double is
     * wherever both sides are present: the total complement of the comparison, restricted to rows
     * where neither operand is null. A null literal or a bound NULL makes the comparison UNKNOWN
     * for every row, and its negation with it.
     */
    private Predicate negatedFloatingComparison(RexCall call, RexNode left, RexNode right) {
        List<Predicate> parts = new ArrayList<>(3);
        parts.add(new Predicate.Not(comparison(call, false)));
        for (RexNode operand : List.of(left, right)) {
            Predicate present = present(operand);
            if (present instanceof Predicate.False) {
                return present;
            }
            if (present != null) {
                parts.add(present);
            }
        }
        return parts.size() == 1 ? parts.get(0) : new Predicate.And(parts);
    }

    /**
     * True where {@code operand} is not null; null when it can never be null (a present literal or
     * bound value); {@link Predicate.False} when it is always null.
     */
    private @Nullable Predicate present(RexNode operand) {
        if (operand instanceof RexLiteral literal) {
            return literal.isNull() ? new Predicate.False() : null;
        }
        if (operand instanceof RexDynamicParam param) {
            return parameters.at(param.getIndex()) == null ? new Predicate.False() : null;
        }
        if (operand instanceof RexInputRef ref) {
            return new Predicate.IsNull(ref.getIndex(), columnName(ref.getIndex()), false);
        }
        return new Predicate.IsNullExpression(new ExpressionCompiler(schema).compile(operand), false);
    }

    private static boolean isFloatingPoint(RexNode node) {
        return org.apache.calcite.sql.type.SqlTypeName.APPROX_TYPES.contains(
                node.getType().getSqlTypeName());
    }

    private static boolean isDecimal(RexNode node) {
        return node.getType().getSqlTypeName() == org.apache.calcite.sql.type.SqlTypeName.DECIMAL;
    }

    /**
     * Text inside a larger expression -- {@code LOWER(channel) = 'apple'}, Nexmark q21 -- compares
     * for equality, as a text column against a literal does. An ordering is refused: which of two
     * strings sorts first is a collation's question, and this engine has none to answer it with.
     * Text against a number is refused too; SQL's coercion would have inserted a cast, and a cast
     * between text and a number is refused before it gets here.
     */
    private void rejectTextOrdering(RexCall call, Predicate.Op op, Expression left, Expression right) {
        boolean decimal = left.type() == TypeName.DECIMAL || right.type() == TypeName.DECIMAL;
        if (decimal && (left.isFloatingPoint() || right.isFloatingPoint())) {
            // SQL makes this comparison approximate by casting the decimal to DOUBLE, and Calcite
            // writes that cast out when it types the call -- so reaching here means a shape where it
            // did not, and comparing a double as if it were exact would be a guess.
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' compares a DECIMAL with a floating-point value. Say which comparison is "
                            + "meant: CAST(<decimal> AS DOUBLE) compares approximately.");
        }
        boolean leftText = left.type() == TypeName.STRING;
        boolean rightText = right.type() == TypeName.STRING;
        if (!leftText && !rightText) {
            return;
        }
        if (leftText != rightText || (op != Predicate.Op.EQ && op != Predicate.Op.NE)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' orders text, or compares it with something that is not text. Text inside "
                            + "a larger expression is compared with = and <> only: ordering strings needs a "
                            + "collation, and this engine has none.");
        }
    }

    /**
     * Resolves one placeholder against the bound values, checked for type.
     *
     * <p>NULL deserves a note. A caller who binds NULL to {@code WHERE tier = ?} usually means "the
     * rows with no tier", and SQL does not: {@code x = NULL} is UNKNOWN for every row, so the
     * answer is empty. Pravaha follows the standard -- the predicate becomes {@code false} -- rather
     * than quietly rewriting it to IS NULL, because a client library that silently changed the
     * meaning of a comparison would be a worse surprise than an empty result. {@code IS NULL} says
     * what it means and is what the caller should write.
     */
    private Constant boundValue(int ordinal, RexDynamicParam param) {
        Object value = parameters.at(param.getIndex());
        BoundParameters.checkAssignable(
                param.getIndex(), value, schema.field(ordinal).type().typeName());
        return Constant.of(value, param.getIndex());
    }

    private Predicate compare(int ordinal, Predicate.Op op, Constant constant) {
        TypeName type = schema.field(ordinal).type().typeName();
        String column = columnName(ordinal);

        if (constant.isNull()) {
            // Standard three-valued logic: any comparison with NULL is UNKNOWN, and a filter keeps
            // only rows for which the predicate is TRUE.
            return new Predicate.False();
        }

        return switch (type) {
            case INT8, INT16, INT32, DATE ->
                new Predicate.CompareInt(ordinal, column, op, (int) constant.asLong(), type);
            case INT64, TIME, TIMESTAMP_LTZ -> new Predicate.CompareLong(ordinal, column, op, constant.asLong());
            case FLOAT32, FLOAT64 -> new Predicate.CompareDouble(ordinal, column, op, constant.asDouble(), type);
            case STRING -> {
                if (op != Predicate.Op.EQ && op != Predicate.Op.NE) {
                    throw new PravahaException(
                            SqlErrors.UNSUPPORTED_EXPRESSION,
                            "only = and <> are supported on text column '" + column + "'; "
                                    + op.sql() + " needs a collation, and assuming one gives wrong "
                                    + "answers that look right");
                }
                yield new Predicate.CompareString(ordinal, column, op, constant.asString());
            }
            case BOOLEAN ->
                new Predicate.CompareBoolean(ordinal, column, constant.asBoolean() == (op == Predicate.Op.EQ));
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "cannot compare column '" + column + "' of type " + type + " against a constant yet");
        };
    }

    /**
     * {@code IS [NOT] NULL}, over a column or over anything the expression compiler understands.
     *
     * <p>Finding TY-5. This required a column reference and refused everything else, so {@code
     * WHERE (CASE WHEN … END) IS NULL} -- an ordinary way to ask whether a computed value came out
     * empty -- was told the expression could not be compiled, while the same CASE in the SELECT
     * list compiled fine. The column form is kept as its own predicate because the code generator
     * emits it as a single bitmap test; everything else goes through the expression tree, which
     * has answered this question for every node it has since it was written.
     */
    private Predicate nullCheck(RexCall call, boolean wantNull) {
        if (call.getOperands().get(0) instanceof RexInputRef ref) {
            return new Predicate.IsNull(ref.getIndex(), columnName(ref.getIndex()), wantNull);
        }
        return new Predicate.IsNullExpression(
                new ExpressionCompiler(schema).compile(call.getOperands().get(0)), wantNull);
    }

    private String columnName(int ordinal) {
        return schema.field(ordinal).name();
    }

    private static Predicate.Op opOf(SqlKind kind) {
        return switch (kind) {
            case EQUALS -> Predicate.Op.EQ;
            case NOT_EQUALS -> Predicate.Op.NE;
            case GREATER_THAN -> Predicate.Op.GT;
            case GREATER_THAN_OR_EQUAL -> Predicate.Op.GE;
            case LESS_THAN -> Predicate.Op.LT;
            case LESS_THAN_OR_EQUAL -> Predicate.Op.LE;
            default -> throw new IllegalArgumentException("not a comparison: " + kind);
        };
    }

    /** {@code 100 > amount} means {@code amount < 100}. */
    private static Predicate.Op flip(Predicate.Op op) {
        return switch (op) {
            case GT -> Predicate.Op.LT;
            case GE -> Predicate.Op.LE;
            case LT -> Predicate.Op.GT;
            case LE -> Predicate.Op.GE;
            default -> op;
        };
    }

    /**
     * {@code column LIKE 'pattern'}, where the pattern is a literal.
     *
     * <p>A pattern that is itself an expression is refused. Supporting it would mean compiling a
     * regex per row, which is the difference between a filter and a performance incident, and a
     * per-row pattern is vanishingly rare in the queries this engine is for.
     */
    private Predicate like(RexCall call, boolean negated) {
        List<RexNode> operands = call.getOperands();
        if (operands.size() != 2) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' uses LIKE with an ESCAPE clause, which is not built. Without ESCAPE, "
                            + "% and _ are always wildcards and there is no way to match them literally.");
        }
        if (!(operands.get(0) instanceof RexInputRef ref)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' matches something other than a column. LIKE is supported as "
                            + "column LIKE 'pattern'.");
        }
        if (!(operands.get(1) instanceof RexLiteral literal)) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' uses a pattern that is not a literal. The pattern is compiled once "
                            + "when the query is registered; one that varies per row would be compiled "
                            + "per row.");
        }
        return new Predicate.Like(
                ref.getIndex(), columnName(ref.getIndex()), String.valueOf(literal.getValue2()), negated);
    }

    private static PravahaException unsupported(RexNode node) {
        if (CorrelatedSubqueries.isCorrelated(node)) {
            // X-7. A correlated EXISTS used to land in the sentence below, which lists AND, OR and
            // IS NULL at somebody who wrote a subquery.
            return CorrelatedSubqueries.refusal(node);
        }
        return new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "cannot compile the expression '" + node + "' (" + node.getKind() + ") yet. "
                        + "Supported: AND, OR, NOT, comparisons against a literal, IS [NOT] NULL, "
                        + "LIKE against a literal pattern, and boolean columns.");
    }
}
