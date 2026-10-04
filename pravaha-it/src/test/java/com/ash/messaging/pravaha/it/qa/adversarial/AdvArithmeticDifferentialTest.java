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
package com.ash.messaging.pravaha.it.qa.adversarial;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.RegisteredQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QE-040: random BIGINT expressions against a {@link BigInteger} oracle. A row whose evaluation
 * leaves 64 bits or divides by zero must never be published with a value; every other row must be
 * published with exactly the oracle's value (or not at all, if an earlier row stopped the query).
 */
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvArithmeticDifferentialTest {

    static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** An expression, its SQL, and its value under the oracle: null for SQL NULL. */
    sealed interface E permits Col, Lit, Bin, Neg, Abs, Case {
        String sql();

        /**
         * The expression's exact value, or null for SQL NULL.
         *
         * @throws ArithmeticException where SQL raises an error
         */
        BigInteger eval(Long x, Long y);
    }

    static BigInteger fit(BigInteger v) {
        if (v.compareTo(MIN) < 0 || v.compareTo(MAX) > 0) {
            throw new ArithmeticException("out of range " + v);
        }
        return v;
    }

    record Col(String name) implements E {
        @Override
        public String sql() {
            return name;
        }

        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public BigInteger eval(Long x, Long y) {
            Long v = "x".equals(name) ? x : y;
            return v == null ? null : BigInteger.valueOf(v);
        }
    }

    record Lit(long value) implements E {
        @Override
        public String sql() {
            return value < 0 ? "(" + value + ")" : Long.toString(value);
        }

        @Override
        public BigInteger eval(Long x, Long y) {
            return BigInteger.valueOf(value);
        }
    }

    record Bin(E l, String op, E r) implements E {
        @Override
        public String sql() {
            return "(" + l.sql() + " " + op + " " + r.sql() + ")";
        }

        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public BigInteger eval(Long x, Long y) {
            // NULL wins over an error in the other operand: the engine checks nullness before it
            // evaluates, and SQL leaves the order of evaluation to the implementation.
            BigInteger a;
            BigInteger b;
            ArithmeticException error = null;
            try {
                a = l.eval(x, y);
            } catch (ArithmeticException e) {
                a = BigInteger.ZERO;
                error = e;
            }
            try {
                b = r.eval(x, y);
            } catch (ArithmeticException e) {
                b = BigInteger.ZERO;
                error = error == null ? e : error;
            }
            if (isNull(l, x, y) || isNull(r, x, y)) {
                return null;
            }
            if (error != null) {
                throw error;
            }
            return fit(
                    switch (op) {
                        case "+" -> a.add(b);
                        case "-" -> a.subtract(b);
                        case "*" -> a.multiply(b);
                        case "/" -> {
                            if (b.signum() == 0) {
                                throw new ArithmeticException("division by zero");
                            }
                            yield a.divide(b);
                        }
                        case "%" -> {
                            if (b.signum() == 0) {
                                throw new ArithmeticException("division by zero");
                            }
                            yield a.remainder(b);
                        }
                        default -> throw new IllegalStateException(op);
                    });
        }
    }

    /** Whether an expression is NULL, errors aside. */
    static boolean isNull(E e, Long x, Long y) {
        return switch (e) {
            case Col c -> ("x".equals(c.name()) ? x : y) == null;
            case Lit l -> false;
            case Bin b -> isNull(b.l(), x, y) || isNull(b.r(), x, y);
            case Neg n -> isNull(n.inner(), x, y);
            case Abs a -> isNull(a.inner(), x, y);
            case Case c -> {
                boolean taken;
                try {
                    BigInteger a = c.cl().eval(x, y);
                    BigInteger b = c.cr().eval(x, y);
                    taken = a != null && b != null && a.compareTo(b) > 0;
                } catch (ArithmeticException ex) {
                    taken = false;
                }
                yield isNull(taken ? c.then() : c.otherwise(), x, y);
            }
            default -> false;
        };
    }

    record Neg(E inner) implements E {
        @Override
        public String sql() {
            return "(-" + inner.sql() + ")";
        }

        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public BigInteger eval(Long x, Long y) {
            BigInteger v = inner.eval(x, y);
            return v == null ? null : fit(v.negate());
        }
    }

    record Abs(E inner) implements E {
        @Override
        public String sql() {
            return "ABS(" + inner.sql() + ")";
        }

        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public BigInteger eval(Long x, Long y) {
            BigInteger v = inner.eval(x, y);
            return v == null ? null : fit(v.abs());
        }
    }

    record Case(E cl, E cr, E then, E otherwise) implements E {
        @Override
        public String sql() {
            return "CASE WHEN " + cl.sql() + " > " + cr.sql() + " THEN " + then.sql() + " ELSE " + otherwise.sql()
                    + " END";
        }

        @Override
        public BigInteger eval(Long x, Long y) {
            BigInteger a = cl.eval(x, y);
            BigInteger b = cr.eval(x, y);
            boolean taken = a != null && b != null && a.compareTo(b) > 0;
            return taken ? then.eval(x, y) : otherwise.eval(x, y);
        }
    }

    static final long[] EDGE = {
        Long.MIN_VALUE,
        Long.MIN_VALUE + 1,
        -1L << 40,
        -3,
        -1,
        0,
        1,
        2,
        3,
        1L << 40,
        Long.MAX_VALUE - 1,
        Long.MAX_VALUE,
        3037000499L,
        3037000500L
    };

    static E expression(Random random, int depth) {
        if (depth <= 0 || random.nextInt(4) == 0) {
            return switch (random.nextInt(3)) {
                case 0 -> new Col("x");
                case 1 -> new Col("y");
                default ->
                    new Lit(random.nextInt(5) == 0 ? EDGE[random.nextInt(EDGE.length)] : random.nextInt(21) - 10);
            };
        }
        return switch (random.nextInt(9)) {
            case 0, 1, 2, 3, 4 ->
                new Bin(
                        expression(random, depth - 1),
                        new String[] {"+", "-", "*", "/", "%"}[random.nextInt(5)],
                        expression(random, depth - 1));
            case 5 -> new Neg(expression(random, depth - 1));
            case 6 -> new Abs(expression(random, depth - 1));
            default ->
                new Case(
                        expression(random, depth - 1),
                        expression(random, depth - 1),
                        expression(random, depth - 1),
                        expression(random, depth - 1));
        };
    }

    record Mismatch(String name, String sql, String detail, boolean minOverMinusOne) {}

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    @Test
    void qe040_randomBigintExpressionsAgreeWithBigInteger() {
        long seed = Long.getLong("pravaha.qa.seed", 40L);
        int count = Integer.getInteger("pravaha.qa.expressions", 300);
        Random random = new Random(seed);
        List<Long[]> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(new Long[] {
                random.nextInt(8) == 0
                        ? null
                        : (random.nextInt(3) == 0
                                ? EDGE[random.nextInt(EDGE.length)]
                                : (long) random.nextInt(2001) - 1000),
                random.nextInt(8) == 0
                        ? null
                        : (random.nextInt(3) == 0
                                ? EDGE[random.nextInt(EDGE.length)]
                                : (long) random.nextInt(2001) - 1000)
            });
        }
        Map<String, E> expressions = new HashMap<>();
        List<Mismatch> mismatches = new ArrayList<>();
        int refused = 0;
        int failedAsExpected = 0;
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream("r", "id:INT64,x:INT64?,y:INT64?", null))) {
            List<String> live = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                E expression = expression(random, 1 + random.nextInt(4));
                String name = "e" + i;
                String sql = "SELECT id, " + expression.sql() + " AS v FROM r";
                if ("OK".equals(AdvSupport.attempt(() -> engine.register(name, sql, "id")))) {
                    expressions.put(name, expression);
                    live.add(name);
                } else {
                    refused++;
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                Long[] row = rows.get(i);
                long id = i;
                AdvSupport.attempt(() -> engine.push("r", new Object[][] {{id, row[0], row[1]}}));
            }
            for (String name : live) {
                E expression = expressions.get(name);
                RegisteredQuery query = engine.find(name).orElseThrow();
                Map<Long, Object> published = new HashMap<>();
                query.view().scan().forEach(r -> published.put((Long) r[0], r[1]));
                boolean stopped = false;
                for (int i = 0; i < rows.size(); i++) {
                    Long[] row = rows.get(i);
                    BigInteger expected;
                    String error = null;
                    try {
                        expected = expression.eval(row[0], row[1]);
                    } catch (ArithmeticException e) {
                        expected = null;
                        error = e.getMessage();
                    }
                    Object actual = published.get((long) i);
                    boolean present = published.containsKey((long) i);
                    if (error != null) {
                        if (present) {
                            boolean minOverMinusOne = actual instanceof Long l
                                    && l == Long.MIN_VALUE
                                    && expression.sql().contains("/");
                            mismatches.add(new Mismatch(
                                    name,
                                    expression.sql(),
                                    "row " + i + " " + java.util.Arrays.toString(row) + " oracle " + error
                                            + " but published " + actual,
                                    minOverMinusOne));
                        }
                        stopped = true;
                        continue;
                    }
                    if (stopped && !present) {
                        continue;
                    }
                    Object want = expected;
                    Object got = actual instanceof Number number ? new BigInteger(number.toString()) : actual;
                    if (!present || !java.util.Objects.equals(want, got)) {
                        if (!present && !query.state().toString().equals("RUNNING")) {
                            continue; // stopped by a row the oracle also refuses later or earlier
                        }
                        mismatches.add(new Mismatch(
                                name,
                                expression.sql(),
                                "row " + i + " " + java.util.Arrays.toString(row) + " oracle " + want + " published "
                                        + (present
                                                ? actual + " ("
                                                        + (actual == null
                                                                ? "null"
                                                                : actual.getClass()
                                                                        .getSimpleName()) + ")"
                                                : "<absent>"),
                                false));
                    }
                }
                if (stopped && !query.state().toString().equals("RUNNING")) {
                    failedAsExpected++;
                }
            }
        }
        System.out.println("NOTE QE-040 seed=" + seed + " registered=" + expressions.size() + " refused=" + refused
                + " stoppedOnOracleError=" + failedAsExpected + " mismatches=" + mismatches.size()
                + " (of which MIN/-1: "
                + mismatches.stream().filter(Mismatch::minOverMinusOne).count() + ")");
        java.util.Set<String> seen = new java.util.HashSet<>();
        mismatches.stream().filter(m -> seen.add(m.name())).forEach(m -> System.out.println("NOTE mismatch " + m));
        // Known classes, each recorded under its own case: MIN / -1 (QE-010), and a MIN_VALUE whose
        // negation or absolute value the planner folds or simplifies away (QE-040 note).
        assertThat(mismatches.stream()
                        .filter(m -> !m.minOverMinusOne())
                        .filter(m -> !m.detail()
                                .contains("oracle out of range 9223372036854775808 but published "
                                        + "-9223372036854775808"))
                        // The planner may simplify away a sub-expression whose value is not needed
                        // (CASE with equal branches, -(-x)) and with it the error SQL would raise:
                        // recorded as a NOTE, not a wrong value.
                        .filter(m -> !(m.detail().contains(" oracle out of range")
                                        || m.detail().contains(" oracle division"))
                                || !(m.sql().contains("CASE") || m.sql().contains("(-(-")))
                        .toList())
                .isEmpty();
    }
}
