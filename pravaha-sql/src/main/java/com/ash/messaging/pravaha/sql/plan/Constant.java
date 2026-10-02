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

import org.apache.calcite.rex.RexLiteral;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * A constant in a predicate: written in the SQL, or bound to a {@code ?} (ADR-032).
 *
 * <p>The two are deliberately indistinguishable past this point. {@code WHERE user_id = ?} and
 * {@code WHERE user_id = 'u1'} build the same {@link com.ash.messaging.pravaha.runtime.plan.Predicate},
 * so the parameterised form cannot be slower, cannot take a different code path, and cannot behave
 * subtly differently from the form people test with. A separate "parameter predicate" would have
 * been the obvious design and would have doubled the number of shapes every operator and every
 * pushdown negotiation has to understand.
 */
sealed interface Constant {

    boolean isNull();

    long asLong();

    double asDouble();

    String asString();

    boolean asBoolean();

    static Constant of(RexLiteral literal) {
        return new OfLiteral(literal);
    }

    static Constant of(Object value, int parameterIndex) {
        return new OfBinding(value, parameterIndex);
    }

    /** A constant written into the SQL text. */
    record OfLiteral(RexLiteral literal) implements Constant {

        private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
        private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

        @Override
        public boolean isNull() {
            return literal.isNull();
        }

        @Override
        public long asLong() {
            // Temporal first, and never through BigDecimal. Calcite's getValueAs raises a
            // java.lang.AssertionError for a TIMESTAMP asked for as a BigDecimal -- an Error, which
            // escapes every catch (Exception) on the way out and which round 2 recorded killing a
            // Flight worker thread rather than failing the query that caused it.
            //
            // The units are the engine's, not Calcite's: nanoseconds for a timestamp and a time of
            // day, days for a date. Comparing a millisecond literal against a nanosecond column is
            // wrong by six orders of magnitude and still looks like a timestamp.
            Long temporal = temporalNanos();
            if (temporal != null) {
                return temporal;
            }
            BigDecimal decimal = literal.getValueAs(BigDecimal.class);
            if (decimal != null) {
                // NARROWCAST-1: longValue keeps the low 64 bits of a literal past BIGINT, so
                // 9223372036854775808 compared as Long.MIN_VALUE. Refused instead.
                if (decimal.compareTo(LONG_MIN) < 0 || decimal.compareTo(LONG_MAX) > 0) {
                    throw new PravahaException(
                            SqlErrors.UNSUPPORTED_EXPRESSION,
                            "literal " + literal + " is outside BIGINT's range [" + Long.MIN_VALUE + ", "
                                    + Long.MAX_VALUE + "]; refused rather than compared as its low 64 bits");
                }
                return decimal.longValue();
            }
            Long value = literal.getValueAs(Long.class);
            if (value == null) {
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION, "cannot read literal " + literal + " as a number");
            }
            return value;
        }

        @Override
        public double asDouble() {
            Long temporal = temporalNanos();
            if (temporal != null) {
                return temporal;
            }
            // TY-13. Asking for the BigDecimal and converting loses the largest finite double:
            // `WHERE f64 = 1.7976931348623157E308` compiled to *Infinity*, so every comparison
            // against it was false and the query returned zero rows under exit 0. Both `=` and
            // `>=` failing is what gives it away -- if the literal were Double.MAX_VALUE, `>=`
            // would match.
            //
            // Calcite holds a decimal whose value is above Double.MAX_VALUE, and BigDecimal's
            // correctly-rounded doubleValue() then saturates to infinity. Asking for the Double
            // directly skips the decimal entirely, which is what an IEEE-754 literal wanted in the
            // first place. Only this one value is affected, because it is the only place where
            // losing the last digit of the mantissa crosses the representable edge.
            Double exact = literal.getValueAs(Double.class);
            if (exact != null && !exact.isInfinite()) {
                return exact;
            }
            BigDecimal decimal = literal.getValueAs(BigDecimal.class);
            return decimal == null ? 0d : decimal.doubleValue();
        }

        /** This literal in the engine's own units, or null when it is not a temporal one. */
        private Long temporalNanos() {
            return switch (literal.getType().getSqlTypeName()) {
                case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> literal.getValueAs(Long.class) * 1_000_000L;
                case TIME -> (long) literal.getValueAs(Integer.class) * 1_000_000L;
                case DATE -> (long) literal.getValueAs(Integer.class);
                default -> null;
            };
        }

        @Override
        public String asString() {
            return String.valueOf(literal.getValueAs(String.class));
        }

        @Override
        public boolean asBoolean() {
            return Boolean.TRUE.equals(literal.getValueAs(Boolean.class));
        }
    }

    /** A value bound to a placeholder. */
    record OfBinding(Object value, int parameterIndex) implements Constant {

        @Override
        public boolean isNull() {
            return value == null;
        }

        @Override
        public long asLong() {
            if (value instanceof Number number) {
                return number.longValue();
            }
            throw wrongType("a whole number");
        }

        @Override
        public double asDouble() {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            throw wrongType("a number");
        }

        @Override
        public String asString() {
            if (value instanceof CharSequence text) {
                return text.toString();
            }
            throw wrongType("text");
        }

        @Override
        public boolean asBoolean() {
            if (value instanceof Boolean flag) {
                return flag;
            }
            throw wrongType("a boolean");
        }

        private PravahaException wrongType(String wanted) {
            return new PravahaException(
                    SqlErrors.PARAMETER_TYPE,
                    "?" + (parameterIndex + 1) + " is compared against a column that needs " + wanted + ", but a "
                            + value.getClass().getSimpleName() + " was bound");
        }
    }
}
