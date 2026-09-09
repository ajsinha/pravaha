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
            return !row.isNull(ordinal) && op.matches(Double.compare(row.getDouble(ordinal), value));
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

    record And(List<Predicate> parts) implements Predicate {
        public And {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean test(RowView row) {
            for (Predicate part : parts) {
                if (!part.test(row)) {
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

        @Override
        public boolean test(RowView row) {
            for (Predicate part : parts) {
                if (part.test(row)) {
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
