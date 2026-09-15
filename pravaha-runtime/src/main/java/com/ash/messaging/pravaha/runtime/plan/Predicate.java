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

import java.util.List;

import com.ash.messaging.pravaha.api.data.RowView;

/**
 * A boolean test over a row, as <em>structure</em> rather than as a closure.
 *
 * <p>This started as a functional interface and had to change, which is worth recording. A closure
 * can be evaluated but not inspected, so the interpreted path worked and the code generator had
 * nothing to generate from -- it would have needed its own parallel translation from Calcite, and
 * two translations of the same expression are two chances to disagree.
 *
 * <p>Sealed, so both paths consume one description: {@link #test} interprets it,
 * and the generator switches over the same cases to emit source. That is what makes the differential
 * tests meaningful -- they compare two implementations of one specification, not two specifications
 * (design section 12.4).
 */
public sealed interface Predicate {

    /** Interprets this predicate. The fallback path, and the differential-test reference. */
    boolean test(RowView row);

    /** How it reads in EXPLAIN output. */
    String describe();

    /** Always true; what a filter reduces to when every conjunct is pushed into the source. */
    record True() implements Predicate {
        @Override
        public boolean test(RowView row) {
            return true;
        }

        @Override
        public String describe() {
            return "true";
        }
    }

    record False() implements Predicate {
        @Override
        public boolean test(RowView row) {
            return false;
        }

        @Override
        public String describe() {
            return "false";
        }
    }

    /** Comparison operators, kept as an enum so both paths switch over the same set. */
    enum Op {
        EQ("=", "=="),
        NE("<>", "!="),
        LT("<", "<"),
        LE("<=", "<="),
        GT(">", ">"),
        GE(">=", ">=");

        private final String sql;
        private final String java;

        Op(String sql, String java) {
            this.sql = sql;
            this.java = java;
        }

        /** The operator that is TRUE exactly where this one is FALSE, for both operands present. */
        public Op negated() {
            return switch (this) {
                case EQ -> NE;
                case NE -> EQ;
                case LT -> GE;
                case LE -> GT;
                case GT -> LE;
                case GE -> LT;
            };
        }

        public String sql() {
            return sql;
        }

        /** The Java operator the generator emits. */
        public String java() {
            return java;
        }

        public boolean matches(int comparison) {
            return switch (this) {
                case EQ -> comparison == 0;
                case NE -> comparison != 0;
                case LT -> comparison < 0;
                case LE -> comparison <= 0;
                case GT -> comparison > 0;
                case GE -> comparison >= 0;
            };
        }

        /**
         * Compares two doubles as IEEE 754 does, which {@link Double#compare} deliberately does not.
         *
         * <p>TY-3. Both floating-point comparison sites routed through {@code Double.compare}, whose
         * contract is a <em>total order</em>: it places {@code NaN} above every other double and
         * {@code -0.0} below {@code 0.0}. That is the right answer for sorting and the wrong one for
         * a {@code WHERE} clause. {@code WHERE x/y > 0} kept a row whose {@code x/y} was
         * {@code 0.0/0.0}, and {@code NaN = NaN} passed — silently wrong filter results rather than
         * refusals.
         *
         * <p>Java's own operators are IEEE 754: every ordering comparison involving {@code NaN} is
         * false, {@code NaN != NaN} is true, and {@code -0.0 == 0.0}. Using them fixes all three.
         *
         * <p><strong>This diverges from PostgreSQL on purpose.</strong> Postgres defines NaN as
         * equal to itself and greater than everything precisely so that its indexes have a total
         * order to work with. Pravaha has no such constraint here, and the reading that matters is
         * the one an operator has when they write {@code > 0} in a filter: a value that is not a
         * number is not greater than zero. Recorded as a decision rather than left to be discovered.
         */
        public boolean matchesDoubles(double left, double right) {
            return switch (this) {
                case EQ -> left == right;
                case NE -> left != right;
                case LT -> left < right;
                case LE -> left <= right;
                case GT -> left > right;
                case GE -> left >= right;
            };
        }
    }

    /** {@code column op <long literal>}, covering the integer, date, time and timestamp types. */
    record CompareLong(int ordinal, String columnName, Op op, long value) implements Predicate {
        @Override
        public boolean test(RowView row) {
            // SQL three-valued logic: a comparison with NULL is UNKNOWN, and WHERE treats it as false.
            return !row.isNull(ordinal) && op.matches(Long.compare(row.getLong(ordinal), value));
        }

        @Override
        public String describe() {
            return columnName + " " + op.sql() + " " + value;
        }
    }

    /** {@code column op <int literal>}, for the narrower integer widths. */
    record CompareInt(int ordinal, String columnName, Op op, int value) implements Predicate {
        @Override
        public boolean test(RowView row) {
            return !row.isNull(ordinal) && op.matches(Integer.compare(row.getInt(ordinal), value));
        }

        @Override
        public String describe() {
            return columnName + " " + op.sql() + " " + value;
        }
    }

    record CompareDouble(int ordinal, String columnName, Op op, double value) implements Predicate {
        @Override
        public boolean test(RowView row) {
            return !row.isNull(ordinal) && op.matchesDoubles(row.getDouble(ordinal), value);
        }

        @Override
        public String describe() {
            return columnName + " " + op.sql() + " " + value;
        }
    }

    /**
     * {@code column = 'literal'} or {@code <>}.
     *
     * <p>Equality only. Ordering comparisons on text need collation, and guessing one is worse than
     * refusing: a query that silently uses byte order where the user expected locale order gives
     * wrong answers that look right.
     */
    record CompareString(int ordinal, String columnName, Op op, String value) implements Predicate {
        public CompareString {
            if (op != Op.EQ && op != Op.NE) {
                throw new IllegalArgumentException("only = and <> are supported on text; " + op.sql()
                        + " needs a collation, and " + "assuming one produces wrong answers that look right");
            }
        }

        @Override
        public boolean test(RowView row) {
            if (row.isNull(ordinal)) {
                return false;
            }
            boolean equal = row.getString(ordinal).equals(value);
            return op == Op.EQ == equal;
        }

        @Override
        public String describe() {
            return columnName + " " + op.sql() + " '" + value + "'";
        }
    }

    /**
     * {@code LIKE} and {@code NOT LIKE}, against a pattern fixed at plan time.
     *
     * <p><strong>A class rather than a record</strong>, which is the exception in this file and
     * needs its reason stated. The pattern has to be translated to a regex and compiled once per
     * query instead of once per row, and a record cannot hold a derived field. Making the compiled
     * {@link java.util.regex.Pattern} a component instead would be worse than ugly: {@code Pattern}
     * inherits identity equality, so two registrations of the same query would compare unequal and
     * be given separate computations -- the exact sharing this engine exists to do. Equality is
     * defined on the SQL pattern text, where it belongs.
     *
     * <p>{@code negated} is resolved here rather than wrapped in a NOT for the reason the whole
     * predicate IR is two-valued (see {@code PredicateCompiler.negate}): a null column makes {@code
     * LIKE} UNKNOWN, and UNKNOWN drops the row under both {@code LIKE} and {@code NOT LIKE}. A Java
     * {@code !} around the result would keep it.
     */
    final class Like implements Predicate {

        private final int ordinal;
        private final String columnName;
        private final String pattern;
        private final boolean negated;
        private final java.util.regex.Pattern compiled;

        public Like(int ordinal, String columnName, String pattern, boolean negated) {
            this.ordinal = ordinal;
            this.columnName = columnName;
            this.pattern = pattern;
            this.negated = negated;
            this.compiled = java.util.regex.Pattern.compile(toRegex(pattern), java.util.regex.Pattern.DOTALL);
        }

        /**
         * Translates a SQL {@code LIKE} pattern to a regex.
         *
         * <p>Everything that is not {@code %} or {@code _} is quoted, so a pattern containing {@code
         * .} or {@code *} matches those characters rather than behaving as a regex -- a user writing
         * {@code LIKE '%.com'} means a dot. {@code DOTALL} is set because SQL's {@code _} matches
         * any character, and a regex {@code .} does not match a newline by default.
         *
         * <p>No escape character: standard {@code LIKE} has none unless {@code ESCAPE} is given, and
         * that form is refused at compile time rather than half-supported here.
         */
        private static String toRegex(String pattern) {
            StringBuilder regex = new StringBuilder(pattern.length() + 8);
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '%' && c != '_') {
                    literal.append(c);
                    continue;
                }
                if (!literal.isEmpty()) {
                    regex.append(java.util.regex.Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '%' ? ".*" : ".");
            }
            if (!literal.isEmpty()) {
                regex.append(java.util.regex.Pattern.quote(literal.toString()));
            }
            return regex.toString();
        }

        @Override
        public boolean test(RowView row) {
            if (row.isNull(ordinal)) {
                return false;
            }
            return negated != compiled.matcher(row.getString(ordinal)).matches();
        }

        public int ordinal() {
            return ordinal;
        }

        public String columnName() {
            return columnName;
        }

        public String pattern() {
            return pattern;
        }

        public boolean negated() {
            return negated;
        }

        @Override
        public String describe() {
            return columnName + (negated ? " NOT LIKE '" : " LIKE '") + pattern + "'";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Like like
                    && ordinal == like.ordinal
                    && negated == like.negated
                    && columnName.equals(like.columnName)
                    && pattern.equals(like.pattern);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(ordinal, columnName, pattern, negated);
        }

        @Override
        public String toString() {
            // Named components, matching what a record would print, because the query fingerprint
            // is a hash of the plan's toString and a bare pattern would collide across columns.
            return "Like[ordinal=" + ordinal + ", columnName=" + columnName + ", pattern=" + pattern + ", negated="
                    + negated + "]";
        }
    }

    record CompareBoolean(int ordinal, String columnName, boolean value) implements Predicate {
        @Override
        public boolean test(RowView row) {
            return !row.isNull(ordinal) && row.getBoolean(ordinal) == value;
        }

        @Override
        public String describe() {
            return columnName + " = " + value;
        }
    }

    record IsNull(int ordinal, String columnName, boolean wantNull) implements Predicate {
        @Override
        public boolean test(RowView row) {
            return row.isNull(ordinal) == wantNull;
        }

        @Override
        public String describe() {
            return columnName + (wantNull ? " IS NULL" : " IS NOT NULL");
        }
    }

    /**
     * A comparison between two computed expressions: {@code WHERE amount * 2 > threshold}.
     *
     * <p>The general case, and deliberately the <em>last</em> case. The specific forms above --
     * column against literal -- exist because they are what the code generator turns into a single
     * load and compare with constant offsets, and folding them into this one would hand the
     * generator an expression tree to walk for every row of the common case. So the compiler tries
     * the specific shapes first and reaches this only when the query genuinely needs it.
     *
     * <p>Null comparison follows SQL: if either side is null the comparison is <em>not true</em>,
     * which for a WHERE clause means the row is dropped. That is not the same as false -- {@code NOT
     * (null > 1)} is also not true -- and the difference matters the moment a NOT wraps it.
     */
    record CompareExpressions(Expression left, Op op, Expression right) implements Predicate {

        @Override
        public boolean test(RowView row) {
            if (left.isNull(row) || right.isNull(row)) {
                return false;
            }
            if (left.isFloatingPoint() || right.isFloatingPoint()) {
                return op.matchesDoubles(left.evaluateDouble(row), right.evaluateDouble(row));
            }
            int comparison = Long.compare(left.evaluateLong(row), right.evaluateLong(row));
            return switch (op) {
                case EQ -> comparison == 0;
                case NE -> comparison != 0;
                case LT -> comparison < 0;
                case LE -> comparison <= 0;
                case GT -> comparison > 0;
                case GE -> comparison >= 0;
            };
        }

        @Override
        public String describe() {
            return left.describe() + " " + op.sql() + " " + right.describe();
        }
    }

    record And(List<Predicate> parts) implements Predicate {
        public And {
            parts = List.copyOf(parts);
        }

        /**
         * Indexed rather than enhanced-for.
         *
         * <p>An enhanced-for over a {@code List} allocates an iterator, and this runs once per row.
         * It is invisible in a unit test and shows up in a throughput benchmark as GC noise -- which
         * is how it was found. The hot path allocates nothing, and "nothing" has to include the
         * things the language does on your behalf.
         */
        @Override
        public boolean test(RowView row) {
            for (int i = 0; i < parts.size(); i++) {
                if (!parts.get(i).test(row)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String describe() {
            return "("
                    + String.join(
                            " AND ", parts.stream().map(Predicate::describe).toList()) + ")";
        }
    }

    record Or(List<Predicate> parts) implements Predicate {
        public Or {
            parts = List.copyOf(parts);
        }

        /** Indexed, for the same reason {@link And} is: an iterator per row is an allocation per row. */
        @Override
        public boolean test(RowView row) {
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).test(row)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return "("
                    + String.join(
                            " OR ", parts.stream().map(Predicate::describe).toList()) + ")";
        }
    }

    /**
     * Two-valued negation, and a trap worth naming.
     *
     * <p>The SQL compiler never emits this. {@code !inner.test(row)} turns a row that a comparison
     * dropped for being null into a row that passes, which is wrong: SQL says NOT UNKNOWN is
     * UNKNOWN and the row stays dropped. The SQL predicate compiler pushes negation into the
     * comparisons at compile time instead. This node remains for a predicate built directly, where
     * the caller knows its operand is total -- a null check, a boolean column comparison -- and
     * should not be used over anything that can be UNKNOWN.
     */
    record Not(Predicate inner) implements Predicate {
        @Override
        public boolean test(RowView row) {
            return !inner.test(row);
        }

        @Override
        public String describe() {
            return "NOT " + inner.describe();
        }
    }

    /** The always-true singleton, for the common case of a filter with nothing left in it. */
    Predicate ALWAYS_TRUE = new True();
}
