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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TAUTOFILTER-1: a row filter that restricts nothing is recognised over the predicate the engine runs,
 * and a filter that restricts is never called vacuous -- proved against every row of a small exhaustive
 * domain, NULLs and a NaN included.
 */
class FilterVacuityTest {

    /** Nullable {@code s} text, {@code i} integer, {@code b} boolean, {@code d} double; {@code n} text NOT NULL. */
    private static final StreamSchema T = StreamSchema.builder("t")
            .field("s", Types.string().withNullable(true))
            .field("n", Types.string())
            .field("i", Types.int64().withNullable(true))
            .field("b", Types.bool().withNullable(true))
            .field("d", Types.float64().withNullable(true))
            .build();

    private static final Object[] S = {"a", "b", null};
    private static final Object[] N = {"a", "b"};
    private static final Object[] I = {0L, 1L, null};
    private static final Object[] B = {true, false, null};
    private static final Object[] D = {0.0, Double.NaN, null};

    /** Every row of the domain: 3 x 2 x 3 x 3 x 3. */
    private static final List<Object[]> ROWS = rows();

    private static final String[] ATOMS = {
        "s = 'a'",
        "s <> 'a'",
        "s = s",
        "s <> s",
        "s IS NULL",
        "s IS NOT NULL",
        "n = 'a'",
        "n <> 'b'",
        "n = n",
        "i > 0",
        "i <= 0",
        "i = 1",
        "i <> 1",
        "i >= i",
        "i < i",
        "i = i",
        "i + 1 > i",
        "i + 1 = i + 1",
        "b",
        "NOT b",
        "b IS TRUE",
        "b IS NOT TRUE",
        "b IS FALSE",
        "b IS NULL",
        "b IS NOT NULL",
        "LOWER(s) = LOWER(s)",
        "LOWER(s) = 'a'",
        "UPPER(n) = UPPER(n)",
        "1 = 1",
        "1 = 0",
        "TRUE",
        "FALSE",
        "s IS NOT DISTINCT FROM s",
        "i IS NOT DISTINCT FROM 1",
        "COALESCE(s, 'x') = 'x'",
        "COALESCE(s, 'x') = COALESCE(s, 'x')",
        "s LIKE '%'",
        "s LIKE 'a%'",
        "s NOT LIKE '%'",
        "d = d",
        "d <> d",
        "d > 0",
        "d <= 0",
        "d < d",
        "d >= d",
        "s = n",
        "s <> n",
        "CHAR_LENGTH(s) > 0",
        "i IS NULL",
        "i IS NOT NULL",
        "d IS NULL",
        "(CASE WHEN i > 0 THEN s ELSE NULL END) IS NULL",
    };

    @Test
    void theListedTautologiesAreCaught() {
        StreamSchema r = StreamSchema.builder("r")
                .field("region", Types.string().withNullable(true))
                .field("x", Types.string().withNullable(true))
                .field("a", Types.int64().withNullable(true))
                .field("k", Types.int64())
                .field("flag", Types.bool().withNullable(true))
                .build();
        verdict(r, "TRUE", FilterVacuity.Verdict.ALWAYS_TRUE);
        verdict(r, "1 = 1 OR region = 'x'", FilterVacuity.Verdict.ALWAYS_TRUE);
        verdict(r, "x IS NULL OR x IS NOT NULL", FilterVacuity.Verdict.ALWAYS_TRUE);
        verdict(r, "region IS NOT DISTINCT FROM region", FilterVacuity.Verdict.ALWAYS_TRUE);
        verdict(r, "k = k", FilterVacuity.Verdict.ALWAYS_TRUE);
        verdict(r, "NOT (k <> k)", FilterVacuity.Verdict.ALWAYS_TRUE);
        verdict(r, "flag IS TRUE OR flag IS NOT TRUE", FilterVacuity.Verdict.ALWAYS_TRUE);
        verdict(r, "a < 5 OR a >= 5 OR a IS NULL", FilterVacuity.Verdict.ALWAYS_TRUE);
        // On a nullable column each of these is IS NOT NULL written as a comparison: refused as such.
        verdict(r, "region = region", FilterVacuity.Verdict.NULLS_ONLY);
        verdict(r, "NOT (a <> a)", FilterVacuity.Verdict.NULLS_ONLY);
        verdict(r, "a >= a", FilterVacuity.Verdict.NULLS_ONLY);
        verdict(r, "a <= a", FilterVacuity.Verdict.NULLS_ONLY);
        verdict(r, "LOWER(region) = LOWER(region)", FilterVacuity.Verdict.NULLS_ONLY);
        verdict(r, "flag OR NOT flag", FilterVacuity.Verdict.NULLS_ONLY);
        verdict(r, "region = 'x' OR region <> 'x'", FilterVacuity.Verdict.NULLS_ONLY);
        verdict(r, "a = a OR region = 'x'", FilterVacuity.Verdict.NULLS_ONLY);
        // Keeps nothing.
        verdict(r, "a <> a", FilterVacuity.Verdict.ALWAYS_FALSE);
        verdict(r, "a > a", FilterVacuity.Verdict.ALWAYS_FALSE);
        verdict(r, "region = 'x' AND 1 = 0", FilterVacuity.Verdict.ALWAYS_FALSE);
        verdict(r, "FALSE", FilterVacuity.Verdict.ALWAYS_FALSE);
        // Genuine restrictions, including an explicit null test and a reflexive comparison beside one.
        verdict(r, "region = 'EU'", FilterVacuity.Verdict.RESTRICTS);
        verdict(r, "region IS NOT NULL", FilterVacuity.Verdict.RESTRICTS);
        verdict(r, "region = region AND a > 1", FilterVacuity.Verdict.RESTRICTS);
        verdict(r, "region = 'EU' OR region = 'US'", FilterVacuity.Verdict.RESTRICTS);
        verdict(r, "region LIKE 'E%'", FilterVacuity.Verdict.RESTRICTS);
    }

    @Test
    void aVacuousFilterIsRefusedWhereItIsBoundAndAnEmptyOneOnlyWhenItIsTheSameForEverybody() {
        for (String vacuous : List.of("s = s", "1 = 1 OR s = 'x'", "i >= i", "LOWER(s) = LOWER(s)")) {
            assertThatThrownBy(() -> NarrowingPlan.compile(T, filter(vacuous)))
                    .as(vacuous)
                    .isInstanceOfSatisfying(PravahaException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(SecurityErrors.FILTER_NOT_ENFORCEABLE);
                        assertThat(e.getMessage()).contains("TAUTOFILTER-1").contains(vacuous);
                    });
        }
        assertThatThrownBy(() -> NarrowingPlan.compile(T, filter("s = s"))).hasMessageContaining("IS NOT NULL");
        assertThatCode(() -> NarrowingPlan.compile(T, filter("s <> s"))).doesNotThrowAnyException();
        assertThatThrownBy(
                        () -> NarrowingPlan.compile(T, filter("s <> s"), NarrowingPlan.Judgement.SESSION_FREE_POLICY))
                .hasMessageContaining("false for every row");
        assertThatCode(() ->
                        NarrowingPlan.compile(T, filter("1 = 1 OR s = 'x'"), NarrowingPlan.Judgement.SESSION_POLICY))
                .as("under the probe's stand-in values a session policy's verdict describes nobody")
                .doesNotThrowAnyException();
        assertThatCode(() -> NarrowingPlan.compile(T, filter("s = 'a' AND i > 0")))
                .doesNotThrowAnyException();
    }

    /**
     * The property. For random predicates over the schema: a filter called {@code ALWAYS_TRUE} keeps every
     * row, {@code ALWAYS_FALSE} keeps none, and {@code NULLS_ONLY} keeps every row with no NULL in it --
     * so no filter that drops a row by its values is ever refused. And the analysis finds enough of the
     * real tautologies to matter.
     */
    @Test
    void neverCallsARestrictingFilterVacuous() {
        Random random = new Random(20260928L);
        int planned = 0;
        int vacuous = 0;
        int caught = 0;
        for (int round = 0; round < 4000; round++) {
            String sql = predicate(random, 3);
            Predicate predicate;
            try {
                predicate = predicateOf(T, sql);
            } catch (PravahaException unsupported) {
                continue;
            }
            planned++;
            FilterVacuity.Verdict verdict = FilterVacuity.of(predicate, T);
            boolean keepsAll = true;
            boolean keepsNone = true;
            boolean keepsAllPresent = true;
            for (Object[] row : ROWS) {
                boolean kept = predicate == null || predicate.test(view(row));
                keepsAll &= kept;
                keepsNone &= !kept;
                if (!hasNull(row)) {
                    keepsAllPresent &= kept;
                }
            }
            switch (verdict) {
                case ALWAYS_TRUE -> assertThat(keepsAll).as(sql).isTrue();
                case ALWAYS_FALSE -> assertThat(keepsNone).as(sql).isTrue();
                case NULLS_ONLY -> assertThat(keepsAllPresent).as(sql).isTrue();
                case RESTRICTS -> {
                    // assumed to restrict: the analysis is sound, not complete
                }
            }
            if (keepsAll) {
                vacuous++;
                if (verdict == FilterVacuity.Verdict.ALWAYS_TRUE) {
                    caught++;
                }
            }
        }
        assertThat(planned).isGreaterThan(2000);
        assertThat(vacuous).isGreaterThan(50);
        assertThat(caught)
                .as("tautologies over this domain recognised: %d of %d", caught, vacuous)
                .isGreaterThan(vacuous * 3 / 4);
    }

    @Test
    void aFilterTooLargeToDecideIsAssumedToRestrictAndAWideOneIsDecidedWithinTheBudget() {
        Predicate vacuous = predicateOf(T, "(s = 'a' OR s <> 'a' OR s IS NULL) AND (i > 0 OR i <= 0 OR i IS NULL)");
        assertThat(FilterVacuity.of(vacuous, T)).isEqualTo(FilterVacuity.Verdict.ALWAYS_TRUE);
        assertThat(FilterVacuity.of(vacuous, T, 1))
                .as("out of budget: assumed to restrict, never refused for want of an answer")
                .isEqualTo(FilterVacuity.Verdict.RESTRICTS);
        StringBuilder wide = new StringBuilder("(s = 'v0' OR i = 0)");
        for (int k = 1; k < 40; k++) {
            wide.append(" AND (s = 'v").append(k).append("' OR i = ").append(k).append(')');
        }
        wide.append(" OR (s = 'v0' OR s <> 'v0' OR s IS NULL)");
        assertThat(FilterVacuity.of(predicateOf(T, wide.toString()), T)).isEqualTo(FilterVacuity.Verdict.ALWAYS_TRUE);
    }

    // ---------------------------------------------------------------------------- helpers

    private static void verdict(StreamSchema schema, String sql, FilterVacuity.Verdict expected) {
        assertThat(FilterVacuity.of(predicateOf(schema, sql), schema)).as(sql).isEqualTo(expected);
    }

    private static Narrowing filter(String sql) {
        return new Narrowing(Optional.of(sql), Map.of(), List.of());
    }

    private static Predicate predicateOf(StreamSchema schema, String sql) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .overBoundedInput()
                .build(SqlPlanner.withStreams(schema).plan("SELECT * FROM " + schema.name() + " WHERE " + sql));
        return find(plan);
    }

    private static Predicate find(PhysicalOperator plan) {
        if (plan instanceof FilterOperator filter) {
            return filter.predicate();
        }
        for (PhysicalOperator input : plan.inputs()) {
            Predicate found = find(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String predicate(Random random, int depth) {
        if (depth == 0 || random.nextInt(3) == 0) {
            return ATOMS[random.nextInt(ATOMS.length)];
        }
        return switch (random.nextInt(3)) {
            case 0 -> "NOT (" + predicate(random, depth - 1) + ")";
            case 1 -> "(" + predicate(random, depth - 1) + " AND " + predicate(random, depth - 1) + ")";
            default -> "(" + predicate(random, depth - 1) + " OR " + predicate(random, depth - 1) + ")";
        };
    }

    private static List<Object[]> rows() {
        List<Object[]> out = new ArrayList<>();
        for (Object s : S) {
            for (Object n : N) {
                for (Object i : I) {
                    for (Object b : B) {
                        for (Object d : D) {
                            out.add(new Object[] {s, n, i, b, d});
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean hasNull(Object[] row) {
        for (Object value : row) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    private static RowView view(Object[] row) {
        return (RowView) Proxy.newProxyInstance(
                FilterVacuityTest.class.getClassLoader(), new Class<?>[] {RowView.class}, (proxy, method, args) -> {
                    int at = args == null || args.length == 0 ? -1 : (Integer) args[0];
                    return switch (method.getName()) {
                        case "isNull" -> row[at] == null;
                        case "getString" -> (String) row[at];
                        case "getLong" -> (Long) row[at];
                        case "getInt" -> ((Long) row[at]).intValue();
                        case "getBoolean" -> (Boolean) row[at];
                        case "getDouble" -> (Double) row[at];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
