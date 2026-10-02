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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ash.messaging.pravaha.embedded.PravahaEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QE-001..QE-035: expressions and types over the embedded engine's interpreted path. Every case
 * projects or filters stream {@code s}, keyed by {@code id}, over a fixed set of edge rows.
 *
 * <p>A case that holds is an ordinary assertion; a case the engine fails is kept {@code @Disabled}
 * with its QE id, asserting what the documentation promises.
 */
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvExpressionTest {

    static final String SCHEMA = "id:INT64,i:INT32?,a:INT64?,b:INT64?,sm:INT16?,ty:INT8?,d:FLOAT64?,t:STRING?,"
            + "amt:DECIMAL(18,4)?,ts:TIMESTAMP";

    private static final Instant TS = Instant.parse("2026-01-01T00:00:00Z");

    /** id, i, a, b, sm, ty, d, t, amt. */
    static final Object[][] ROWS = {
        {
            1L,
            2_000_000_000,
            Long.MIN_VALUE,
            -1L,
            (short) 30000,
            (byte) 100,
            Double.NaN,
            "a.b",
            new BigDecimal("99999999999999.9999")
        },
        {2L, Integer.MIN_VALUE, Long.MAX_VALUE, 2L, (short) -32768, (byte) -128, -0.0, "ß", new BigDecimal("-1")},
        {3L, 5, 7L, -2L, (short) 1, (byte) 1, 0.0, null, null},
        {4L, null, null, null, null, null, 1e300, "😀", null},
        {5L, -7, -7L, 2L, (short) -1, (byte) -1, Double.POSITIVE_INFINITY, "abc", new BigDecimal("0.0001")},
        {6L, 1, 1L, 1L, (short) 1, (byte) 1, -5.5, "  tab\t", null},
        {7L, 1, 1L, 1L, (short) 1, (byte) 1, 2.5, "x%", null},
    };

    /** What one projection did: refused at registration, failed while running, or its rows. */
    record Outcome(String refused, String state, List<String> rows) {
        boolean published(String row) {
            return rows.contains(row);
        }
    }

    static Outcome run(String sql, Object[]... rows) {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream("s", SCHEMA, "ts"))) {
            String registered = AdvSupport.attempt(() -> engine.register("q", sql, "id"));
            if (!"OK".equals(registered)) {
                return new Outcome(registered, "", List.of());
            }
            for (Object[] row : rows) {
                Object[] withTime = java.util.Arrays.copyOf(row, row.length + 1);
                withTime[row.length] = TS;
                AdvSupport.attempt(() -> engine.push("s", new Object[][] {withTime}));
            }
            String state = AdvSupport.state(engine, "q");
            List<String> read = new ArrayList<>();
            // A failed query still holds the rows it published; read them through the registry.
            engine.find("q").orElseThrow().view().scan().forEach(r -> read.add(AdvSupport.render(r)));
            read.sort(null);
            return new Outcome(null, state, read);
        }
    }

    static Outcome run(String sql) {
        return run(sql, ROWS);
    }

    // ------------------------------------------------------------------ narrow-integer overflow

    @Test
    void qe001_intProductPastTheRangeIsNeverPublishedWrapped() {
        // NARROWINT-1, fixed: an INT result outside INT's range is an overflow, routed as a BIGINT
        // one is (the query stops PRV-8003), never the wrapped -294967296.
        Outcome outcome = run("SELECT id, i * 2 AS x FROM s");
        assertThat(outcome.published("1|-294967296")).as(outcome.toString()).isFalse();
        assertThat(outcome.state()).startsWith("FAILED PRV-8003").contains("INT overflow");
    }

    @Test
    void qe002_intSumAndSquarePastTheRangeAreNeverPublishedWrapped() {
        assertThat(run("SELECT id, i + i AS x FROM s").published("1|-294967296"))
                .isFalse();
        assertThat(run("SELECT id, i * i AS x FROM s").published("1|-1651507200"))
                .isFalse();
    }

    @Test
    void qe003_negatingIntMinIsNeverPublishedAsItself() {
        assertThat(run("SELECT id, -i AS x FROM s").published("2|-2147483648")).isFalse();
    }

    @Test
    void qe004_absOfIntMinIsNeverNegative() {
        assertThat(run("SELECT id, ABS(i) AS x FROM s").published("2|-2147483648"))
                .isFalse();
    }

    @Test
    void qe005_smallintArithmeticIsNeverPublishedWrapped() {
        assertThat(run("SELECT id, sm + sm AS x FROM s").published("1|-5536")).isFalse();
        assertThat(run("SELECT id, sm * sm AS x FROM s").published("1|-5888")).isFalse();
    }

    @Test
    void qe006_tinyintArithmeticIsNeverPublishedWrapped() {
        Outcome outcome = run("SELECT id, ty + ty AS x FROM s");
        assertThat(outcome.published("1|-56")).as(outcome.toString()).isFalse();
    }

    @Test
    void qe007_narrowingCastOutOfRangeIsNeverTruncated() {
        // NARROWINT-1 range-checks an integer narrowing CAST: Long.MIN/MAX are an INT overflow.
        Outcome outcome = run("SELECT id, CAST(a AS INT) AS x FROM s");
        assertThat(outcome.published("1|0")).as(outcome.toString()).isFalse();
        assertThat(outcome.published("2|-1")).as(outcome.toString()).isFalse();
        assertThat(outcome.state()).startsWith("FAILED PRV-8003").contains("INT overflow");
    }

    @Test
    void qe008_narrowingCastToSmallintIsNeverTruncated() {
        Outcome outcome = run("SELECT id, CAST(i AS SMALLINT) AS x FROM s");
        assertThat(outcome.published("1|-27648")).as(outcome.toString()).isFalse();
        assertThat(outcome.state()).startsWith("FAILED PRV-8003").contains("SMALLINT overflow");
    }

    @Test
    void qe009_castOfNanOrInfinityToBigintIsNeverAnInteger() {
        // NARROWCAST-1, fixed: NaN, the infinities and 1e300 have no BIGINT value.
        Outcome outcome = run("SELECT id, CAST(d AS BIGINT) AS x FROM s");
        assertThat(outcome.published("1|0")).as("NaN -> 0: " + outcome).isFalse();
        assertThat(outcome.published("5|9223372036854775807"))
                .as("+Inf -> MAX: " + outcome)
                .isFalse();
        assertThat(outcome.state()).startsWith("FAILED PRV-8003").contains("BIGINT overflow");
        for (Object[] row : new Object[][] {ROWS[3], ROWS[4]}) {
            Outcome one = run("SELECT id, CAST(d AS BIGINT) AS x FROM s", row);
            assertThat(one.state()).as(one.toString()).startsWith("FAILED PRV-8003");
            assertThat(one.rows()).as(one.toString()).isEmpty();
        }
        // A filter on the cast meets the same refusal: NaN is not kept by `= 0`.
        Outcome filtered = run("SELECT id FROM s WHERE CAST(d AS BIGINT) = 0", ROWS[0]);
        assertThat(filtered.rows()).as(filtered.toString()).isEmpty();
        assertThat(filtered.state()).as(filtered.toString()).startsWith("FAILED PRV-8003");
        // In range, the cast truncates towards zero as before.
        Outcome inRange = run("SELECT id, CAST(d AS BIGINT) AS x FROM s", ROWS[5], ROWS[6]);
        assertThat(inRange.state()).isEqualTo("RUNNING");
        assertThat(inRange.rows()).containsExactly("6|-5", "7|2");
    }

    @Test
    void qe009_aBigintLiteralPastTheRangeIsRefusedAtRegistration() {
        Outcome outcome = run("SELECT id, CAST(9223372036854775808 AS BIGINT) AS x FROM s");
        assertThat(outcome.published("1|-9223372036854775808"))
                .as(outcome.toString())
                .isFalse();
        assertThat(outcome.refused() + " " + outcome.state()).contains("PRV-");
    }

    @Test
    void qe010_longMinDividedByMinusOneIsNeverPublished() {
        // DIVMIN-1, fixed: Long.MIN_VALUE / -1 is a BIGINT overflow like + - * past 64 bits.
        Outcome outcome = run("SELECT id, a / b AS x FROM s", ROWS[0]);
        assertThat(outcome.published("1|-9223372036854775808"))
                .as(outcome.toString())
                .isFalse();
        assertThat(outcome.state()).startsWith("FAILED PRV-8003").contains("BIGINT overflow");
        // The remainder has an answer.
        Outcome modulo = run("SELECT id, a % b AS x FROM s", ROWS[0]);
        assertThat(modulo.state()).as(modulo.toString()).isEqualTo("RUNNING");
        assertThat(modulo.rows()).containsExactly("1|0");
    }

    @Test
    void qe011_bigintSubtractionAtMinStopsTheQueryWithACode() {
        Outcome outcome = run("SELECT id, a - 1 AS x FROM s", ROWS[0]);
        assertThat(outcome.state()).startsWith("FAILED PRV-8003").contains("ArithmeticException");
        assertThat(outcome.rows()).isEmpty();
    }

    // ------------------------------------------------------------------ filters

    @Test
    void qe013_aFilterOnAnIntProductAgreesWithItsOwnProjection() {
        // NARROWINT-1, fixed: the filter evaluated 2e9 * 2 in 64 bits (4e9, not < 0) while the
        // projection of the same expression published -294967296. Both now meet the same INT
        // overflow, on the same row, and neither answers with a value the other contradicts.
        Outcome filtered = run("SELECT id FROM s WHERE i * 2 < 0");
        assertThat(filtered.state())
                .as(filtered.toString())
                .startsWith("FAILED PRV-8003")
                .contains("INT overflow");
        Outcome projected = run("SELECT id, i * 2 AS x FROM s WHERE i * 2 > 0");
        assertThat(projected.state())
                .as(projected.toString())
                .startsWith("FAILED PRV-8003")
                .contains("INT overflow");
        assertThat(projected.rows()).noneMatch(row -> row.startsWith("1|"));
        // Within range the two agree as before: 5 * 2 and 1 * 2 pass a filter > 0 and project 10, 2.
        Outcome inRange = run("SELECT id, i * 2 AS x FROM s WHERE i * 2 > 0", ROWS[2], ROWS[5]);
        assertThat(inRange.state()).isEqualTo("RUNNING");
        assertThat(inRange.rows()).containsExactly("3|10", "6|2");
    }

    @Test
    void qe014_notOfAComparisonKeepsANanRowAsIeeeSays() {
        // NANNOT-1, fixed: NaN > 5 is FALSE, so NOT (NaN > 5) is TRUE and the NaN row (1) is kept.
        assertThat(run("SELECT id FROM s WHERE NOT (d > 5)").rows()).contains("1");
        assertThat(run("SELECT id FROM s WHERE NOT (d < 5)").rows()).contains("1");
        assertThat(run("SELECT id FROM s WHERE d > 5").rows()).containsExactly("4", "5");
        assertThat(run("SELECT id FROM s WHERE NOT (d > 5)").rows()).containsExactly("1", "2", "3", "6", "7");
    }

    @Test
    void qe016_017_nanAndSignedZeroCompareAsIeee() {
        assertThat(run("SELECT id FROM s WHERE d <> d").rows()).containsExactly("1");
        assertThat(run("SELECT id FROM s WHERE d = 0").rows()).containsExactly("2", "3");
    }

    @Test
    void qe018_anIntColumnAgainstAnOutOfRangeLiteral() {
        assertThat(run("SELECT id FROM s WHERE i < 3000000000").rows()).containsExactly("1", "2", "3", "5", "6", "7");
        assertThat(run("SELECT id FROM s WHERE i > -3000000000").rows()).containsExactly("1", "2", "3", "5", "6", "7");
    }

    @Test
    void qe019_inAndNotInWithNull() {
        assertThat(run("SELECT id FROM s WHERE a NOT IN (7, NULL)").rows()).isEmpty();
        assertThat(run("SELECT id FROM s WHERE a IN (7, NULL)").rows()).containsExactly("3");
    }

    @Test
    void qe020_reversedAndNegatedBetween() {
        assertThat(run("SELECT id FROM s WHERE a BETWEEN 7 AND -7").rows()).isEmpty();
        assertThat(run("SELECT id FROM s WHERE NOT (a BETWEEN -7 AND 7)").rows())
                .containsExactly("1", "2");
    }

    @Test
    void qe021_022_likeIsNotARegexAndUnderscoreIsOneCodePoint() {
        assertThat(run("SELECT id FROM s WHERE t LIKE 'a.%'").rows()).containsExactly("1");
        assertThat(run("SELECT id FROM s WHERE t LIKE '_'").rows()).containsExactly("2", "4");
    }

    @Test
    void qe023_splitIndexDelimitersAreLiteral() {
        assertThat(run("SELECT id, SPLIT_INDEX(t, '.', 1) AS x FROM s").rows()).contains("1|b", "5|null");
        assertThat(run("SELECT id, SPLIT_INDEX(t, '|', 0) AS x FROM s").rows()).contains("1|a.b");
    }

    @Test
    void qe024_substringEdges() {
        assertThat(run("SELECT id, SUBSTRING(t FROM 0 FOR 2) AS x FROM s").rows())
                .contains("1|a", "4|😀");
        assertThat(run("SELECT id, SUBSTRING(t FROM -1) AS x FROM s").rows()).contains("5|abc");
        // SQL raises "negative substring length"; this engine answers ''. Recorded as a NOTE.
        assertThat(run("SELECT id, SUBSTRING(t FROM 2 FOR -1) AS x FROM s").rows())
                .contains("5|");
    }

    @Test
    void qe025_026_027_029_030_stringFunctionsAsDocumented() {
        assertThat(run("SELECT id, UPPER(t) AS x FROM s").rows()).contains("2|SS");
        assertThat(run("SELECT id, TRIM(t) AS x FROM s").rows()).contains("6|tab\t");
        assertThat(run("SELECT id, REGEXP_EXTRACT(t, '(a)(x)?', 2) AS x FROM s").rows())
                .contains("5|null");
        assertThat(run("SELECT id, t || 'x' AS x FROM s").rows()).contains("3|null", "5|abcx");
        assertThat(run("SELECT id, CASE WHEN i > 0 THEN 1 END AS x FROM s").rows())
                .contains("5|null", "3|1");
    }

    // ------------------------------------------------------------------ decimals and doubles

    @Test
    void qe031_aDecimalProductPast38DigitsStopsTheQueryRatherThanRounding() {
        // SQL types amt*amt*amt as DECIMAL(38, 12) -- the scale survives the cap, so registration has
        // nothing to refuse. The one row whose value has 42 integer digits stops the lane (no DLQ is
        // reachable from push; see QE-012), and nothing rounded is published.
        Outcome outcome = run("SELECT id, amt * amt * amt AS x FROM s");
        assertThat(outcome.refused()).isNull();
        assertThat(outcome.state()).startsWith("FAILED PRV-8003").contains("does not fit DECIMAL(38, 12)");
        assertThat(outcome.rows()).isEmpty();
    }

    @Test
    void qe032_aDecimalLiteralWithMoreScaleThanTheColumnIsComparedExactly() {
        assertThat(run("SELECT id FROM s WHERE amt > 99999999999999.99985").rows())
                .containsExactly("1");
        assertThat(run("SELECT id FROM s WHERE amt > 99999999999999.99995").rows())
                .isEmpty();
    }

    @Test
    void qe033_034_wideDecimalResultsAreExact() {
        assertThat(run("SELECT id, CAST(a AS DECIMAL(19, 0)) AS x FROM s").rows())
                .contains("1|-9223372036854775808", "2|9223372036854775807");
        assertThat(run("SELECT id, a * 1.5 AS x FROM s").rows())
                .contains("1|-13835058055282163712.0", "2|13835058055282163710.5");
    }

    @Test
    void qe035_doubleDivisionByZeroIsIeee() {
        assertThat(run("SELECT id, d / 0 AS x FROM s").rows()).contains("1|NaN", "3|NaN", "5|Infinity", "6|-Infinity");
    }
}
