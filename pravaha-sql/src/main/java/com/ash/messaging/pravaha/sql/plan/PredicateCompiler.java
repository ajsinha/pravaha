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
import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
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

    public PredicateCompiler(StreamSchema schema) {
        this.schema = schema;
    }

    public Predicate compile(RexNode node) {
        return switch (node.getKind()) {
            case AND -> new Predicate.And(compileAll((RexCall) node));
            case OR -> new Predicate.Or(compileAll((RexCall) node));
            case NOT -> new Predicate.Not(compile(((RexCall) node).getOperands().get(0)));
            case EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL ->
                comparison((RexCall) node);
            case IS_NULL -> nullCheck((RexCall) node, true);
            case IS_NOT_NULL -> nullCheck((RexCall) node, false);
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

    private List<Predicate> compileAll(RexCall call) {
        List<Predicate> parts = new ArrayList<>(call.getOperands().size());
        call.getOperands().forEach(operand -> parts.add(compile(operand)));
        return parts;
    }

    private Predicate comparison(RexCall call) {
        RexNode left = call.getOperands().get(0);
        RexNode right = call.getOperands().get(1);

        // Column against literal, in either order. Column-to-column comparison needs the general
        // expression compiler; refusing it is better than emitting something subtly different.
        if (left instanceof RexInputRef ref && right instanceof RexLiteral literal) {
            return compare(ref.getIndex(), opOf(call.getKind()), literal);
        }
        if (left instanceof RexLiteral literal && right instanceof RexInputRef ref) {
            return compare(ref.getIndex(), flip(opOf(call.getKind())), literal);
        }
        throw unsupported(call);
    }

    private Predicate compare(int ordinal, Predicate.Op op, RexLiteral literal) {
        TypeName type = schema.field(ordinal).type().typeName();
        String column = columnName(ordinal);

        return switch (type) {
            case INT8, INT16, INT32, DATE ->
                new Predicate.CompareInt(ordinal, column, op, (int) literalAsLong(literal));
            case INT64, TIME, TIMESTAMP_LTZ -> new Predicate.CompareLong(ordinal, column, op, literalAsLong(literal));
            case FLOAT32, FLOAT64 -> new Predicate.CompareDouble(ordinal, column, op, literalAsDouble(literal));
            case STRING -> {
                if (op != Predicate.Op.EQ && op != Predicate.Op.NE) {
                    throw new PravahaException(
                            SqlErrors.UNSUPPORTED_EXPRESSION,
                            "only = and <> are supported on text column '" + column + "'; "
                                    + op.sql() + " needs a collation, and assuming one gives wrong "
                                    + "answers that look right");
                }
                yield new Predicate.CompareString(
                        ordinal, column, op, String.valueOf(literal.getValueAs(String.class)));
            }
            case BOOLEAN ->
                new Predicate.CompareBoolean(
                        ordinal,
                        column,
                        Boolean.TRUE.equals(literal.getValueAs(Boolean.class)) == (op == Predicate.Op.EQ));
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "cannot compare column '" + column + "' of type " + type + " against a literal yet");
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

    private static long literalAsLong(RexLiteral literal) {
        BigDecimal decimal = literal.getValueAs(BigDecimal.class);
        if (decimal != null) {
            return decimal.longValue();
        }
        Long value = literal.getValueAs(Long.class);
        if (value == null) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION, "cannot read literal " + literal + " as a number");
        }
        return value;
    }

    private static double literalAsDouble(RexLiteral literal) {
        BigDecimal decimal = literal.getValueAs(BigDecimal.class);
        return decimal == null ? 0d : decimal.doubleValue();
    }

    private static PravahaException unsupported(RexNode node) {
        return new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "cannot compile the expression '" + node + "' (" + node.getKind() + ") yet. "
                        + "Supported: AND, OR, NOT, comparisons against a literal, IS [NOT] NULL, "
                        + "and boolean columns.");
    }
}
