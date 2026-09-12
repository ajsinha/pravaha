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
        Predicate.Op op = negated ? opOf(call.getKind()).negated() : opOf(call.getKind());

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
     * The general comparison: both sides compiled as expressions.
     *
     * <p>Text is excluded deliberately. The expression tree evaluates to a long or a double, so a
     * string comparison reaching here would compare something that is not the string -- and the
     * only string comparisons Pravaha supports at all are equality against a literal, which the
     * fast path above already handled.
     */
    private Predicate compareExpressions(RexCall call, Predicate.Op op) {
        ExpressionCompiler expressions = new ExpressionCompiler(schema);
        Expression left = expressions.compile(call.getOperands().get(0));
        Expression right = expressions.compile(call.getOperands().get(1));
        rejectText(call, left);
        rejectText(call, right);
        return new Predicate.CompareExpressions(left, op, right);
    }

    private void rejectText(RexCall call, Expression side) {
        if (side.type() == TypeName.STRING) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION,
                    "'" + call + "' compares text inside a larger expression, which Pravaha cannot do; "
                            + "only = and <> between a text column and a literal are supported");
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
            case INT8, INT16, INT32, DATE -> new Predicate.CompareInt(ordinal, column, op, (int) constant.asLong());
            case INT64, TIME, TIMESTAMP_LTZ -> new Predicate.CompareLong(ordinal, column, op, constant.asLong());
            case FLOAT32, FLOAT64 -> new Predicate.CompareDouble(ordinal, column, op, constant.asDouble());
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

    private Predicate nullCheck(RexCall call, boolean wantNull) {
        if (!(call.getOperands().get(0) instanceof RexInputRef ref)) {
            throw unsupported(call);
        }
        return new Predicate.IsNull(ref.getIndex(), columnName(ref.getIndex()), wantNull);
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
        return new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "cannot compile the expression '" + node + "' (" + node.getKind() + ") yet. "
                        + "Supported: AND, OR, NOT, comparisons against a literal, IS [NOT] NULL, "
                        + "LIKE against a literal pattern, and boolean columns.");
    }
}
