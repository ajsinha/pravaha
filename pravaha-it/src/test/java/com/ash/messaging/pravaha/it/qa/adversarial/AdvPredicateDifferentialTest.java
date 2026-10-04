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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Random;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ash.messaging.pravaha.codegen.FilterProjectStageGenerator;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.GeneratedChains;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QE-037, QE-038, QE-039: random predicates, three-valued logic, against an oracle written here.
 *
 * <p>Each predicate is registered as {@code SELECT id FROM p WHERE <predicate>} on one embedded
 * engine, every row is pushed once, and each view is compared with the rows the oracle says the
 * predicate is TRUE for. Run interpreted, then with the code generator installed.
 *
 * <p>Mismatches are classified: one that vanishes when NaN rows are left out and involves a NOT over
 * a floating-point comparison was QE-014's (NANNOT-1, fixed) and now fails it too.
 */
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvPredicateDifferentialTest {

    static final long SEED = Long.getLong("pravaha.qa.seed", 20261001L);
    static final int PREDICATES = Integer.getInteger("pravaha.qa.predicates", 400);
    static final int ROW_COUNT = 120;

    @AfterEach
    void uninstall() {
        GeneratedChains.install(null);
    }

    // ------------------------------------------------------------------ the oracle

    /** Kleene truth: TRUE, FALSE or UNKNOWN (null). */
    sealed interface P permits Cmp, Not, And, Or, IsNull, Truth, Like, Bare {
        Boolean eval(Object[] row);

        String sql();

        boolean notOverDouble();
    }

    static final String[] COLUMNS = {"id", "n", "m", "d", "t", "f"};

    record Cmp(int col, String op, Object literal) implements P {
        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public Boolean eval(Object[] row) {
            Object v = row[col];
            if (v == null || literal == null) {
                return null;
            }
            int c;
            if (v instanceof Double dv) {
                double l = ((Number) literal).doubleValue();
                return switch (op) {
                    case "=" -> dv == l;
                    case "<>" -> dv != l;
                    case "<" -> dv < l;
                    case "<=" -> dv <= l;
                    case ">" -> dv > l;
                    case ">=" -> dv >= l;
                    default -> throw new IllegalStateException(op);
                };
            } else if (v instanceof String s) {
                boolean eq = s.equals(literal);
                return "=".equals(op) ? eq : !eq;
            } else {
                c = Long.compare(((Number) v).longValue(), ((Number) literal).longValue());
            }
            return switch (op) {
                case "=" -> c == 0;
                case "<>" -> c != 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                default -> throw new IllegalStateException(op);
            };
        }

        @Override
        public String sql() {
            String lit = literal instanceof String s
                    ? "'" + s.replace("'", "''") + "'"
                    : literal instanceof Double dd ? doubleLiteral(dd) : String.valueOf(literal);
            return COLUMNS[col] + " " + op + " " + lit;
        }

        @Override
        public boolean notOverDouble() {
            return false;
        }
    }

    static String doubleLiteral(double d) {
        // An approximate-numeric literal, which SQL types as DOUBLE: the literal set holds no exponents.
        return Double.toString(d) + "E0";
    }

    record Not(P inner) implements P {
        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public Boolean eval(Object[] row) {
            Boolean b = inner.eval(row);
            return b == null ? null : !b;
        }

        @Override
        public String sql() {
            return "NOT (" + inner.sql() + ")";
        }

        @Override
        public boolean notOverDouble() {
            return touchesDouble(inner) || inner.notOverDouble();
        }
    }

    static boolean touchesDouble(P p) {
        return switch (p) {
            case Cmp c -> c.col() == 3;
            case Not n -> touchesDouble(n.inner());
            case And a -> touchesDouble(a.l()) || touchesDouble(a.r());
            case Or o -> touchesDouble(o.l()) || touchesDouble(o.r());
            case Truth t -> touchesDouble(t.inner());
            default -> false;
        };
    }

    record And(P l, P r) implements P {
        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public Boolean eval(Object[] row) {
            Boolean a = l.eval(row);
            Boolean b = r.eval(row);
            if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
                return false;
            }
            return (a == null || b == null) ? null : true;
        }

        @Override
        public String sql() {
            return "(" + l.sql() + " AND " + r.sql() + ")";
        }

        @Override
        public boolean notOverDouble() {
            return l.notOverDouble() || r.notOverDouble();
        }
    }

    record Or(P l, P r) implements P {
        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public Boolean eval(Object[] row) {
            Boolean a = l.eval(row);
            Boolean b = r.eval(row);
            if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
                return true;
            }
            return (a == null || b == null) ? null : false;
        }

        @Override
        public String sql() {
            return "(" + l.sql() + " OR " + r.sql() + ")";
        }

        @Override
        public boolean notOverDouble() {
            return l.notOverDouble() || r.notOverDouble();
        }
    }

    record IsNull(int col, boolean want) implements P {
        @Override
        public Boolean eval(Object[] row) {
            return (row[col] == null) == want;
        }

        @Override
        public String sql() {
            return COLUMNS[col] + (want ? " IS NULL" : " IS NOT NULL");
        }

        @Override
        public boolean notOverDouble() {
            return false;
        }
    }

    /** IS TRUE / IS FALSE / IS NOT TRUE / IS NOT FALSE. */
    record Truth(P inner, String test) implements P {
        @Override
        public Boolean eval(Object[] row) {
            Boolean b = inner.eval(row);
            return switch (test) {
                case "IS TRUE" -> Boolean.TRUE.equals(b);
                case "IS FALSE" -> Boolean.FALSE.equals(b);
                case "IS NOT TRUE" -> !Boolean.TRUE.equals(b);
                case "IS NOT FALSE" -> !Boolean.FALSE.equals(b);
                default -> throw new IllegalStateException(test);
            };
        }

        @Override
        public String sql() {
            return "(" + inner.sql() + ") " + test;
        }

        @Override
        public boolean notOverDouble() {
            return touchesDouble(inner) || inner.notOverDouble();
        }
    }

    record Like(String pattern, boolean negated) implements P {
        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public Boolean eval(Object[] row) {
            Object v = row[4];
            if (v == null) {
                return null;
            }
            StringBuilder regex = new StringBuilder();
            pattern.codePoints().forEach(cp -> {
                if (cp == '%') {
                    regex.append(".*");
                } else if (cp == '_') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(new String(Character.toChars(cp))));
                }
            });
            boolean m = Pattern.compile(regex.toString(), Pattern.DOTALL)
                    .matcher((String) v)
                    .matches();
            return negated != m;
        }

        @Override
        public String sql() {
            return "t " + (negated ? "NOT LIKE '" : "LIKE '") + pattern.replace("'", "''") + "'";
        }

        @Override
        public boolean notOverDouble() {
            return false;
        }
    }

    record Bare(boolean negated) implements P {
        @SuppressWarnings("NullAway") // null is an answer here (SQL NULL, or nothing found)
        @Override
        public Boolean eval(Object[] row) {
            Object v = row[5];
            return v == null ? null : negated != (Boolean) v;
        }

        @Override
        public String sql() {
            return negated ? "NOT f" : "f";
        }

        @Override
        public boolean notOverDouble() {
            return false;
        }
    }

    // ------------------------------------------------------------------ generators

    static final long[] LONGS = {Long.MIN_VALUE, -1_000_000_000_000L, -7, -1, 0, 1, 2, 7, 42, Long.MAX_VALUE};
    static final int[] INTS = {Integer.MIN_VALUE, -7, -1, 0, 1, 7, 42, Integer.MAX_VALUE};
    static final double[] DOUBLES = {
        Double.NaN,
        Double.NEGATIVE_INFINITY,
        -1e300,
        -2.5,
        -0.0,
        0.0,
        1.0,
        2.5,
        1e300,
        Double.POSITIVE_INFINITY,
        Double.MIN_VALUE
    };
    static final String[] TEXTS = {"", "a", "A", "abc", "a%c", "a_c", "😀", "ß", "ss", " a ", "x'y"};
    static final String[] LIKES = {"%", "_", "a%", "%c", "a_c", "%😀%", "__", "", "%a%", "x'%"};

    static List<Object[]> rows(Random random) {
        List<Object[]> rows = new ArrayList<>();
        for (long id = 0; id < ROW_COUNT; id++) {
            rows.add(new Object[] {
                id,
                random.nextInt(6) == 0 ? null : LONGS[random.nextInt(LONGS.length)],
                random.nextInt(6) == 0 ? null : INTS[random.nextInt(INTS.length)],
                random.nextInt(6) == 0 ? null : DOUBLES[random.nextInt(DOUBLES.length)],
                random.nextInt(6) == 0 ? null : TEXTS[random.nextInt(TEXTS.length)],
                random.nextInt(4) == 0 ? null : random.nextBoolean()
            });
        }
        return rows;
    }

    static P predicate(Random random, int depth, boolean generatorShapes) {
        int pick = random.nextInt(depth <= 0 ? 4 : (generatorShapes ? 7 : 9));
        return switch (pick) {
            case 0, 1 -> comparison(random);
            case 2 -> new IsNull(1 + random.nextInt(5), random.nextBoolean());
            case 3 ->
                generatorShapes ? new Bare(false) : new Like(LIKES[random.nextInt(LIKES.length)], random.nextBoolean());
            case 4 ->
                new And(predicate(random, depth - 1, generatorShapes), predicate(random, depth - 1, generatorShapes));
            case 5 ->
                new Or(predicate(random, depth - 1, generatorShapes), predicate(random, depth - 1, generatorShapes));
            case 6 -> new Not(predicate(random, depth - 1, generatorShapes));
            case 7 ->
                new Truth(
                        predicate(random, depth - 1, generatorShapes),
                        new String[] {"IS TRUE", "IS FALSE", "IS NOT TRUE", "IS NOT FALSE"}[random.nextInt(4)]);
            default -> new Bare(random.nextBoolean());
        };
    }

    static final String[] OPS = {"=", "<>", "<", "<=", ">", ">="};

    static P comparison(Random random) {
        int col = 1 + random.nextInt(4);
        return switch (col) {
            case 1 -> new Cmp(1, OPS[random.nextInt(6)], LONGS[1 + random.nextInt(LONGS.length - 2)]);
            case 2 -> new Cmp(2, OPS[random.nextInt(6)], (long) INTS[1 + random.nextInt(INTS.length - 2)]);
            case 3 -> new Cmp(3, OPS[random.nextInt(6)], new double[] {-2.5, -0.0, 0.0, 1.0, 2.5}[random.nextInt(5)]);
            default -> new Cmp(4, random.nextBoolean() ? "=" : "<>", TEXTS[random.nextInt(TEXTS.length)]);
        };
    }

    // ------------------------------------------------------------------ the run

    record Result(int registered, int refused, int generated, List<String> nanNotMismatches, List<String> mismatches) {}

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    static Result differential(long seed, int count, boolean generate) {
        if (generate) {
            FilterProjectStageGenerator.install();
        } else {
            GeneratedChains.install(null);
        }
        Random random = new Random(seed);
        List<Object[]> rows = rows(random);
        Map<String, P> predicates = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            predicates.put("p" + i, predicate(random, 1 + random.nextInt(4), generate));
        }
        int refused = 0;
        int generated = 0;
        List<String> nanNot = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        try (PravahaEngine engine = AdvSupport.engine(
                e -> e.declareStream("p", "id:INT64,n:INT64?,m:INT32?,d:FLOAT64?,t:STRING?,f:BOOLEAN?", null))) {
            List<String> live = new ArrayList<>();
            for (Map.Entry<String, P> each : predicates.entrySet()) {
                String sql = "SELECT id FROM p WHERE " + each.getValue().sql();
                String outcome = AdvSupport.attempt(() -> engine.register(each.getKey(), sql, "id"));
                if ("OK".equals(outcome)) {
                    live.add(each.getKey());
                } else {
                    refused++;
                    System.out.println("NOTE refused " + each.getKey() + " [" + sql + "] "
                            + outcome.lines().findFirst().orElse(""));
                }
            }
            engine.push("p", rows);
            for (String name : live) {
                P p = predicates.get(name);
                RegisteredQuery query = engine.find(name).orElseThrow();
                if (query.executionPaths().stream().anyMatch(path -> path.startsWith("generated:"))) {
                    generated++;
                }
                TreeSet<Long> actual = new TreeSet<>();
                query.view().scan().forEach(row -> actual.add((Long) row[0]));
                TreeSet<Long> expected = new TreeSet<>();
                TreeSet<Long> expectedWithoutNan = new TreeSet<>();
                TreeSet<Long> actualWithoutNan = new TreeSet<>();
                for (Object[] row : rows) {
                    boolean nan = row[3] instanceof Double dv && dv.isNaN();
                    if (Boolean.TRUE.equals(p.eval(row))) {
                        expected.add((Long) row[0]);
                        if (!nan) {
                            expectedWithoutNan.add((Long) row[0]);
                        }
                    }
                    if (!nan && actual.contains((Long) row[0])) {
                        actualWithoutNan.add((Long) row[0]);
                    }
                }
                if (!expected.equals(actual)) {
                    TreeSet<Long> missing = new TreeSet<>(expected);
                    missing.removeAll(actual);
                    TreeSet<Long> extra = new TreeSet<>(actual);
                    extra.removeAll(expected);
                    String line = name + " [" + p.sql() + "] state=" + query.state() + " missing="
                            + describe(missing, rows) + " extra=" + describe(extra, rows);
                    if (expectedWithoutNan.equals(actualWithoutNan) && p.notOverDouble()) {
                        nanNot.add(line);
                    } else {
                        mismatches.add(line);
                    }
                }
            }
            return new Result(live.size(), refused, generated, nanNot, mismatches);
        }
    }

    static String describe(NavigableSet<Long> ids, List<Object[]> rows) {
        List<String> shown = new ArrayList<>();
        for (Long id : ids) {
            if (shown.size() == 3) {
                shown.add("...");
                break;
            }
            shown.add(java.util.Arrays.toString(rows.get(id.intValue())));
        }
        return ids.size() + shown.toString();
    }

    static void report(String label, Result result) {
        System.out.println("NOTE " + label + " registered=" + result.registered() + " refused=" + result.refused()
                + " generated=" + result.generated() + " nanNot="
                + result.nanNotMismatches().size()
                + " other=" + result.mismatches().size());
        result.nanNotMismatches().stream().limit(5).forEach(line -> System.out.println("NOTE nanNot " + line));
        result.mismatches().stream().limit(20).forEach(line -> System.out.println("NOTE mismatch " + line));
    }

    @Test
    void qe038_randomPredicatesInterpretedAgreeWithTheOracle() {
        Result result = differential(SEED, PREDICATES, false);
        report("QE-038 interpreted seed=" + SEED, result);
        assertThat(result.registered()).isGreaterThan(PREDICATES / 2);
        assertThat(result.mismatches()).isEmpty();
        // NANNOT-1, fixed: no NaN row is lost or gained under a NOT over a floating-point comparison.
        assertThat(result.nanNotMismatches()).isEmpty();
    }

    @Test
    void qe037_039_randomPredicatesGeneratedAgreeWithTheOracle() {
        Result result = differential(SEED + 1, PREDICATES, true);
        report("QE-039 generated seed=" + (SEED + 1), result);
        assertThat(result.generated())
                .as("some filters must actually run generated")
                .isPositive();
        assertThat(result.mismatches()).isEmpty();
        // NANNOT-1, fixed: no NaN row is lost or gained under a NOT over a floating-point comparison.
        assertThat(result.nanNotMismatches()).isEmpty();
    }
}
