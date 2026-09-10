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

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * A computed value: what a projection produces when it is not simply a column.
 *
 * <p>Until this existed, {@code SELECT amount * 2 FROM txn} was refused. That refusal was the right
 * call at the time -- a projection that silently produced the wrong column would be far worse -- but
 * it is a large hole in a SQL surface, and "compute it in the source query" is not an answer when
 * the source is a Kafka topic.
 *
 * <p><strong>Sealed and inspectable</strong>, exactly like {@link Predicate} and for the same
 * reason: a lambda can be <em>evaluated</em> but not <em>read</em>, and the code generator has to
 * read it to generate anything. That lesson was learned once already, in Wave 2, when {@code
 * Predicate} started life as a functional interface and had to be rebuilt as a tree.
 *
 * <p>Arithmetic is on {@code long} and {@code double} only. That is a real limitation rather than a
 * simplification: {@code DECIMAL} arithmetic needs the 128-bit path the row layout already has, and
 * doing it in {@code double} would produce a rounding error in somebody's ledger. A DECIMAL
 * expression is refused rather than approximated.
 */
public sealed interface Expression {

    /** The type this expression produces. */
    TypeName type();

    /** Evaluates over a row. */
    long evaluateLong(RowView row);

    /** Evaluates over a row, for the floating-point types. */
    double evaluateDouble(RowView row);

    /** Whether this expression is null for the given row. */
    boolean isNull(RowView row);

    /** Renders the expression for {@code EXPLAIN}. */
    String describe();

    /**
     * A column read. The leaf of every expression, and the only one that can be null.
     *
     * <p>Carries the column's name purely so {@code EXPLAIN} can print {@code amount * 2 > 100}
     * rather than {@code $2 * 2 > 100}. Evaluation uses the ordinal; the name is never read on the
     * row path, and nothing should start depending on it there.
     */
    record Column(int ordinal, String name, TypeName type) implements Expression {

        @Override
        public long evaluateLong(RowView row) {
            return switch (type) {
                case INT8 -> row.getByte(ordinal);
                case INT16 -> row.getShort(ordinal);
                case INT32, DATE -> row.getInt(ordinal);
                case BOOLEAN -> row.getBoolean(ordinal) ? 1 : 0;
                default -> row.getLong(ordinal);
            };
        }

        @Override
        public double evaluateDouble(RowView row) {
            return switch (type) {
                case FLOAT32 -> row.getFloat(ordinal);
                case FLOAT64 -> row.getDouble(ordinal);
                default -> evaluateLong(row);
            };
        }

        @Override
        public boolean isNull(RowView row) {
            return row.isNull(ordinal);
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /**
     * A numeric conversion.
     *
     * <p>Present because mixing types forces one: {@code rate * 2 > amount} over a DOUBLE rate and a
     * BIGINT amount arrives from Calcite with a CAST around the amount, and refusing it would refuse
     * the query for a reason that has nothing to do with what the user wrote.
     *
     * <p>Only numeric conversions. Narrowing is allowed and truncates towards zero, which is what
     * the SQL standard calls implementation-defined and what Java does anyway; what is not allowed
     * is a conversion that would silently change a value's meaning rather than its width.
     */
    record Cast(Expression source, TypeName type) implements Expression {

        @Override
        public long evaluateLong(RowView row) {
            return source.isFloatingPoint() ? (long) source.evaluateDouble(row) : source.evaluateLong(row);
        }

        @Override
        public double evaluateDouble(RowView row) {
            return source.isFloatingPoint() ? source.evaluateDouble(row) : source.evaluateLong(row);
        }

        @Override
        public boolean isNull(RowView row) {
            return source.isNull(row);
        }

        @Override
        public String describe() {
            // Deliberately invisible in EXPLAIN: the cast is Pravaha's, not the user's, and printing
            // it makes a plan harder to match against the query that produced it.
            return source.describe();
        }
    }

    /** A constant. */
    record Literal(long longValue, double doubleValue, TypeName type, boolean isNull) implements Expression {

        public static Literal ofLong(long value) {
            return new Literal(value, value, TypeName.INT64, false);
        }

        public static Literal ofDouble(double value) {
            return new Literal((long) value, value, TypeName.FLOAT64, false);
        }

        public static Literal ofNull(TypeName type) {
            return new Literal(0, 0, type, true);
        }

        @Override
        public long evaluateLong(RowView row) {
            return longValue;
        }

        @Override
        public double evaluateDouble(RowView row) {
            return doubleValue;
        }

        @Override
        public boolean isNull(RowView row) {
            return isNull;
        }

        @Override
        public String describe() {
            return isNull
                    ? "NULL"
                    : (type == TypeName.FLOAT64 ? String.valueOf(doubleValue) : String.valueOf(longValue));
        }
    }

    /** Arithmetic. */
    enum Operator {
        ADD("+"),
        SUBTRACT("-"),
        MULTIPLY("*"),
        DIVIDE("/"),
        MODULO("%");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /**
     * A binary arithmetic expression.
     *
     * <p>Null propagates: if either side is null the result is null, which is SQL's rule and not
     * Java's. Getting this wrong produces zeroes where nulls belong, and a SUM over those zeroes is
     * a number that looks entirely reasonable.
     */
    record Arithmetic(Expression left, Operator operator, Expression right, TypeName type) implements Expression {

        @Override
        public long evaluateLong(RowView row) {
            long l = left.evaluateLong(row);
            long r = right.evaluateLong(row);
            return switch (operator) {
                case ADD -> Math.addExact(l, r);
                case SUBTRACT -> Math.subtractExact(l, r);
                case MULTIPLY -> Math.multiplyExact(l, r);
                // Integer division by zero is an exception in Java and NULL in SQL. Neither is
                // obviously right for a stream, and throwing is the one that cannot be mistaken for
                // an answer -- the record goes to the dead-letter queue with the reason.
                case DIVIDE -> r == 0 ? divideByZero() : l / r;
                case MODULO -> r == 0 ? divideByZero() : l % r;
            };
        }

        private static long divideByZero() {
            throw new ArithmeticException("division by zero in a projection; the record is routed to the DLQ "
                    + "rather than given a value that could be mistaken for an answer");
        }

        @Override
        public double evaluateDouble(RowView row) {
            double l = left.evaluateDouble(row);
            double r = right.evaluateDouble(row);
            return switch (operator) {
                case ADD -> l + r;
                case SUBTRACT -> l - r;
                case MULTIPLY -> l * r;
                // Floating point division by zero is infinity rather than an error, which is IEEE's
                // answer and stays IEEE's answer here: silently converting it would hide a data
                // problem behind an arithmetic one.
                case DIVIDE -> l / r;
                case MODULO -> l % r;
            };
        }

        @Override
        public boolean isNull(RowView row) {
            return left.isNull(row) || right.isNull(row);
        }

        @Override
        public String describe() {
            return "(" + left.describe() + " " + operator.symbol() + " " + right.describe() + ")";
        }
    }

    /** Whether this expression produces a floating-point value. */
    default boolean isFloatingPoint() {
        return type() == TypeName.FLOAT32 || type() == TypeName.FLOAT64;
    }
}
