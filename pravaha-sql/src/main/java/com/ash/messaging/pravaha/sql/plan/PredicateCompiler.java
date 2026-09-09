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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * Turns a Calcite {@code RexNode} predicate into something the engine can run.
 *
 * <p>Interpreted, and deliberately so for now. Wave 3 generates fused Java for the same expressions;
 * this stays as the fallback every operator keeps (design section 12.4) and as the independent
 * implementation the differential tests compare generated code against. Correctness never depends
 * on code generation succeeding.
 *
 * <p>An expression this cannot compile raises {@code PRV-2021} at <em>registration</em>, naming the
 * expression. That is the important part: the alternative is a query that plans successfully and
 * then fails on the first record, by which time it is in production and the diagnosis is much
 * harder.
 */
public final class PredicateCompiler {

    private final StreamSchema schema;

    public PredicateCompiler(StreamSchema schema) {
        this.schema = schema;
    }

    /** Compiles a predicate, or fails naming what it could not handle. */
    public Predicate compile(RexNode node) {
        return switch (node.getKind()) {
            case AND -> combine((RexCall) node, true);
            case OR -> combine((RexCall) node, false);
            case NOT -> negate(compile(((RexCall) node).getOperands().get(0)));
            case EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL ->
                comparison((RexCall) node);
            case IS_NULL -> isNull((RexCall) node, true);
            case IS_NOT_NULL -> isNull((RexCall) node, false);
            case LITERAL -> literalPredicate((RexLiteral) node);
            case INPUT_REF -> booleanColumn((RexInputRef) node);
            default -> throw unsupported(node);
        };
    }

    private Predicate combine(RexCall call, boolean conjunction) {
        List<Predicate> parts = new ArrayList<>(call.getOperands().size());
        for (RexNode operand : call.getOperands()) {
            parts.add(compile(operand));
        }
        return new Predicate() {
            @Override
            public boolean test(RowView row) {
                for (Predicate part : parts) {
                    boolean value = part.test(row);
                    if (conjunction != value) {
                        return !conjunction;
                    }
                }
                return conjunction;
            }

            @Override
            public String describe() {
                List<String> rendered = parts.stream().map(Predicate::describe).toList();
                return "(" + String.join(conjunction ? " AND " : " OR ", rendered) + ")";
            }
        };
    }

    private static Predicate negate(Predicate inner) {
        return new Predicate() {
            @Override
            public boolean test(RowView row) {
                return !inner.test(row);
            }

            @Override
            public String describe() {
                return "NOT " + inner.describe();
            }
        };
    }

    private Predicate comparison(RexCall call) {
        RexNode left = call.getOperands().get(0);
        RexNode right = call.getOperands().get(1);

        // Only column-versus-literal for now, in either order. Column-to-column comparison is a
        // Wave 3 item; refusing it here is better than compiling something subtly different.
        if (left instanceof RexInputRef ref && right instanceof RexLiteral literal) {
            return compare(ref.getIndex(), call.getKind(), literal, false);
        }
        if (left instanceof RexLiteral literal && right instanceof RexInputRef ref) {
            return compare(ref.getIndex(), call.getKind(), literal, true);
        }
        throw unsupported(call);
    }

    private Predicate compare(int ordinal, SqlKind kind, RexLiteral literal, boolean flipped) {
        TypeName type = schema.field(ordinal).type().typeName();
        String column = schema.field(ordinal).name();
        SqlKind effective = flipped ? flip(kind) : kind;

        return switch (type) {
            case INT8, INT16, INT32, INT64, DATE, TIME, TIMESTAMP_LTZ -> {
                long value = literalAsLong(literal);
                yield new Predicate() {
                    @Override
                    public boolean test(RowView row) {
                        if (row.isNull(ordinal)) {
                            // SQL three-valued logic: a comparison with NULL is UNKNOWN, and a
                            // WHERE clause treats UNKNOWN as false.
                            return false;
                        }
                        return matches(effective, Long.compare(readLong(row, ordinal, type), value));
                    }

                    @Override
                    public String describe() {
                        return column + " " + symbol(effective) + " " + value;
                    }
                };
            }
            case FLOAT32, FLOAT64 -> {
                double value = literalAsDouble(literal);
                yield new Predicate() {
                    @Override
                    public boolean test(RowView row) {
                        if (row.isNull(ordinal)) {
                            return false;
                        }
                        double actual = type == TypeName.FLOAT32 ? row.getFloat(ordinal) : row.getDouble(ordinal);
                        return matches(effective, Double.compare(actual, value));
                    }

                    @Override
                    public String describe() {
                        return column + " " + symbol(effective) + " " + value;
                    }
                };
            }
            case STRING -> {
                String text = literal.getValueAs(String.class);
                byte[] utf8 = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
                yield new Predicate() {
                    @Override
                    public boolean test(RowView row) {
                        if (row.isNull(ordinal)) {
                            return false;
                        }
                        if (effective == SqlKind.EQUALS || effective == SqlKind.NOT_EQUALS) {
                            // A UTF-8 byte comparison; no String is materialised. This is the shape
                            // the generated version keeps (design 12.3).
                            boolean equal = utf8Equals(row, ordinal, utf8);
                            return effective == SqlKind.EQUALS == equal;
                        }
                        return matches(effective, row.getString(ordinal).compareTo(text));
                    }

                    @Override
                    public String describe() {
                        return column + " " + symbol(effective) + " '" + text + "'";
                    }
                };
            }
            case BOOLEAN -> {
                boolean value = Boolean.TRUE.equals(literal.getValueAs(Boolean.class));
                yield new Predicate() {
                    @Override
                    public boolean test(RowView row) {
                        return !row.isNull(ordinal)
                                && (row.getBoolean(ordinal) == value) == (effective == SqlKind.EQUALS);
                    }

                    @Override
                    public String describe() {
                        return column + " " + symbol(effective) + " " + value;
                    }
                };
            }
            default ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "cannot compare column '" + column + "' of type " + type
                                + " against a literal yet; supported: integers, floats, strings, booleans");
        };
    }

    private static boolean utf8Equals(RowView row, int ordinal, byte[] literal) {
        if (row instanceof com.ash.messaging.pravaha.common.row.BinaryRowView binary) {
            return binary.utf8Equals(ordinal, literal);
        }
        return row.getString(ordinal).equals(new String(literal, StandardCharsets.UTF_8));
    }

    private static long readLong(RowView row, int ordinal, TypeName type) {
        return switch (type) {
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            default -> row.getLong(ordinal);
        };
    }

    private Predicate isNull(RexCall call, boolean wantNull) {
        RexNode operand = call.getOperands().get(0);
        if (!(operand instanceof RexInputRef ref)) {
            throw unsupported(call);
        }
        int ordinal = ref.getIndex();
        String column = schema.field(ordinal).name();
        return new Predicate() {
            @Override
            public boolean test(RowView row) {
                return row.isNull(ordinal) == wantNull;
            }

            @Override
            public String describe() {
                return column + (wantNull ? " IS NULL" : " IS NOT NULL");
            }
        };
    }

    private static Predicate literalPredicate(RexLiteral literal) {
        boolean value = Boolean.TRUE.equals(literal.getValueAs(Boolean.class));
        return value
                ? Predicate.ALWAYS_TRUE
                : new Predicate() {
                    @Override
                    public boolean test(RowView row) {
                        return false;
                    }

                    @Override
                    public String describe() {
                        return "false";
                    }
                };
    }

    private Predicate booleanColumn(RexInputRef ref) {
        int ordinal = ref.getIndex();
        String column = schema.field(ordinal).name();
        return new Predicate() {
            @Override
            public boolean test(RowView row) {
                return !row.isNull(ordinal) && row.getBoolean(ordinal);
            }

            @Override
            public String describe() {
                return column;
            }
        };
    }

    private static long literalAsLong(RexLiteral literal) {
        Object value = literal.getValueAs(BigDecimal.class);
        if (value instanceof BigDecimal decimal) {
            return decimal.longValue();
        }
        Long asLong = literal.getValueAs(Long.class);
        if (asLong == null) {
            throw new PravahaException(
                    SqlErrors.UNSUPPORTED_EXPRESSION, "cannot read literal " + literal + " as a number");
        }
        return asLong;
    }

    private static double literalAsDouble(RexLiteral literal) {
        BigDecimal decimal = literal.getValueAs(BigDecimal.class);
        return decimal == null ? 0d : decimal.doubleValue();
    }

    private static boolean matches(SqlKind kind, int comparison) {
        return switch (kind) {
            case EQUALS -> comparison == 0;
            case NOT_EQUALS -> comparison != 0;
            case GREATER_THAN -> comparison > 0;
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case LESS_THAN -> comparison < 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
            default -> throw new IllegalArgumentException("not a comparison: " + kind);
        };
    }

    /** {@code 100 > amount} means {@code amount < 100}. */
    private static SqlKind flip(SqlKind kind) {
        return switch (kind) {
            case GREATER_THAN -> SqlKind.LESS_THAN;
            case GREATER_THAN_OR_EQUAL -> SqlKind.LESS_THAN_OR_EQUAL;
            case LESS_THAN -> SqlKind.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> SqlKind.GREATER_THAN_OR_EQUAL;
            default -> kind;
        };
    }

    private static String symbol(SqlKind kind) {
        return switch (kind) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "<>";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            default -> kind.toString();
        };
    }

    private static PravahaException unsupported(RexNode node) {
        return new PravahaException(
                SqlErrors.UNSUPPORTED_EXPRESSION,
                "cannot compile the expression '" + node + "' (" + node.getKind() + ") yet. "
                        + "Supported: AND, OR, NOT, comparisons against a literal, IS [NOT] NULL, "
                        + "and boolean columns.");
    }
}
