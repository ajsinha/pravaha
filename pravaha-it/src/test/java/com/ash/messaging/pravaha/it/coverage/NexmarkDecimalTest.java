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
package com.ash.messaging.pravaha.it.coverage;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DECIMAL arithmetic, exact at the precision and scale SQL gives it (Nexmark q1: {@code 0.908 *
 * price}).
 *
 * <p>Every expected value below is written as the decimal it must equal, scale included: {@code
 * 0.908 * 7} is {@code 6.356} at scale 3, not {@code 6.356000000000001} and not {@code 6.36}. The
 * refusals are the other half: a quotient, a result whose scale SQL had to shrink, and a row whose
 * answer has too many digits are each refused rather than rounded.
 */
class NexmarkDecimalTest {

    private static final StreamSchema BID = StreamSchema.builder("bid")
            .field("auction", Types.int64())
            .field("price", Types.int64())
            .field("fee", Types.decimal(10, 2).withNullable(true))
            .field("huge", Types.decimal(38, 0))
            .field("fine", Types.decimal(38, 20))
            .build();

    @Test
    void nexmarkQ1MultipliesByADecimalLiteralExactly() {
        String sql = "SELECT auction, 0.908 * price AS price FROM bid";
        assertThat(rows(sql, row(1L, 1000L), row(2L, 7L), row(3L, -3L), row(4L, 9_007_199_254_740_993L)))
                .containsOnlyKeys(
                        List.of(1L, new BigDecimal("908.000")),
                        List.of(2L, new BigDecimal("6.356")),
                        List.of(3L, new BigDecimal("-2.724")),
                        // Past 2^53, where a double cannot hold the price, let alone the product.
                        List.of(4L, new BigDecimal("8178536923304821.644")));
    }

    @Test
    void theResultTypeIsTheOneSqlDerives() {
        // DECIMAL(4, 3) times BIGINT, which is DECIMAL(19, 0): precision 4 + 19, scale 3 + 0.
        DecimalType type = (DecimalType)
                plan("SELECT 0.908 * price FROM bid").outputSchema().field(0).type();
        assertThat(type.precision()).isEqualTo(23);
        assertThat(type.scale()).isEqualTo(3);
    }

    @Test
    void aSumKeepsTheLargerScaleAndADifferenceToo() {
        assertThat(one("SELECT fee + 0.005 FROM bid", row(1L, 1L, new BigDecimal("2.50"))))
                .isEqualTo(new BigDecimal("2.505"));
        assertThat(one("SELECT fee - price FROM bid", row(1L, 3L, new BigDecimal("2.50"))))
                .isEqualTo(new BigDecimal("-0.50"));
        assertThat(one("SELECT -fee FROM bid", row(1L, 3L, new BigDecimal("2.50"))))
                .isEqualTo(new BigDecimal("-2.50"));
    }

    @Test
    void nullInIsNullOut() {
        assertThat(one("SELECT fee * 2 FROM bid", row(1L, 1L, null))).isNull();
    }

    @Test
    void aDecimalIsComparedExactlyInAPredicate() {
        // 0.1 * 3 is exactly 0.3 in decimal and 0.30000000000000004 in double, so the double
        // evaluation this replaces would drop the row.
        assertThat(rows("SELECT auction FROM bid WHERE fee * 3 = 0.30", row(1L, 1L, new BigDecimal("0.10"))))
                .containsOnlyKeys(List.of(1L));
    }

    @Test
    void castToDoubleIsTheApproximationByName() {
        assertThat(one("SELECT CAST(fee AS DOUBLE) FROM bid", row(1L, 1L, new BigDecimal("2.25"))))
                .isEqualTo(2.25);
    }

    @Test
    void aCastEveryValueSurvivesIsCompiledAndANarrowingOneIsRefused() {
        assertThat(one("SELECT CAST(price AS DECIMAL(21, 2)) FROM bid", row(1L, 42L)))
                .isEqualTo(new BigDecimal("42.00"));
        // BIGINT has 19 digits and DECIMAL(10, 2) holds 8 before the point: 10^9 would have no answer.
        assertThatThrownBy(() -> plan("SELECT CAST(price AS DECIMAL(10, 2)) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("narrows");
        assertThatThrownBy(() -> plan("SELECT CAST(fee AS DECIMAL(10, 1)) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("needs 2 decimal places");
    }

    @Test
    void divisionIsRefusedBecauseAQuotientIsRarelyExact() {
        assertThatThrownBy(() -> plan("SELECT fee / 3 FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("DECIMAL division");
    }

    @Test
    void aProductWhoseScaleSqlHadToShrinkIsRefusedRatherThanRounded() {
        // Scale 20 + 20 = 40 is past the cap of 38, so SQL would hold the product at scale 38 and
        // round every row that uses the last two places.
        assertThatThrownBy(() -> plan("SELECT fine * fine FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("needs 40 decimal places");
    }

    @Test
    void aRowWhoseAnswerHasTooManyDigitsIsRefusedRatherThanWrapped() {
        // 10^37 * 10 has 39 digits and DECIMAL(38, 0) holds 38.
        BigDecimal tenToThe37 = BigDecimal.TEN.pow(37);
        assertThat(one("SELECT huge * 1 FROM bid", row(1L, 1L, null, tenToThe37)))
                .isEqualTo(tenToThe37);
        assertThatThrownBy(() -> one("SELECT huge * 10 FROM bid", row(1L, 1L, null, tenToThe37)))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("does not fit DECIMAL(38, 0)");
    }

    @Test
    void theIncrementalAnswerEqualsTheAnswerFromScratchIncludingRetractions() {
        String sql = "SELECT auction, 0.908 * price AS converted, fee * price AS charged FROM bid WHERE fee > 1.00";
        ZSetHarness.Change a = row(1L, 1000L, new BigDecimal("1.25"));
        ZSetHarness.Change b = row(2L, 7L, new BigDecimal("0.50"));
        ZSetHarness.Change c = row(3L, 3L, new BigDecimal("9.99"));
        List<ZSetHarness.Change> changes = List.of(a, b, c, c, a.retracted(), c.retracted(), b.retracted(), a);

        Map<List<Object>, Long> maintained = ZSetHarness.maintained(BID, sql, changes, Long.MIN_VALUE);
        assertThat(maintained).isEqualTo(ZSetHarness.fromScratch(BID, sql, changes, Long.MIN_VALUE));
        assertThat(maintained)
                .as("the control: a once and c once survive; b never passes the filter")
                .containsOnly(
                        Map.entry(List.of(1L, new BigDecimal("908.000"), new BigDecimal("1250.00")), 1L),
                        Map.entry(List.of(3L, new BigDecimal("2.724"), new BigDecimal("29.97")), 1L));
    }

    // ---------------------------------------------------------------- helpers

    private static ZSetHarness.Change row(Object... values) {
        Object[] full = Arrays.copyOf(values, 5);
        if (full[3] == null) {
            full[3] = BigDecimal.ZERO;
        }
        if (full[4] == null) {
            full[4] = BigDecimal.ZERO;
        }
        return ZSetHarness.Change.insert(0, full);
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(BID).plan(sql));
    }

    private static Map<List<Object>, Long> rows(String sql, ZSetHarness.Change... input) {
        return ZSetHarness.maintained(BID, sql, List.of(input), Long.MIN_VALUE);
    }

    private static Object one(String sql, ZSetHarness.Change input) {
        Map<List<Object>, Long> out = rows(sql, input);
        assertThat(out).hasSize(1);
        return out.keySet().iterator().next().get(0);
    }
}
