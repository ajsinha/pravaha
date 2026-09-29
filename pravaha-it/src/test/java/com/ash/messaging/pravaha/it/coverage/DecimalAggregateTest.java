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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DECSUM-1 on the continuous side: {@code SUM}, {@code MIN} and {@code MAX} of a {@code DECIMAL}
 * column in a global and a windowed aggregate answer exactly at the column's scale, a retraction
 * included; {@code AVG} is refused {@code PRV-2021}. The read side is {@code
 * DecimalAggregateReadTest} in pravaha-serving.
 */
class DecimalAggregateTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("region", Types.string())
            .field("amount", Types.decimal(12, 2))
            .field("placed_at", Types.timestamp())
            .eventTime("placed_at")
            .build();

    @Test
    void aGlobalDecimalSumIsExactUnderRetraction() {
        String sql = "SELECT SUM(amount) FROM orders";
        ZSetHarness.Change a = order("EMEA", "0.10", 1);
        ZSetHarness.Change b = order("EMEA", "0.20", 2);
        ZSetHarness.Change c = order("APAC", "-1.05", 3);
        List<ZSetHarness.Change> changes = List.of(a, b, c, b.retracted());

        Map<List<Object>, Long> maintained = ZSetHarness.maintained(ORDERS, sql, changes, Long.MIN_VALUE);

        // 0.10 + -1.05 = -0.95, where a double sum of 0.1 and 0.2 is already 0.30000000000000004.
        assertThat(maintained).containsOnly(Map.entry(List.of(new BigDecimal("-0.95")), 1L));
        assertThat(maintained).isEqualTo(ZSetHarness.fromScratch(ORDERS, sql, changes, Long.MIN_VALUE));
    }

    @Test
    void aWindowedDecimalSumMinAndMaxAreExactPerWindow() {
        String sql = "SELECT window_start, region, SUM(amount) AS total, MIN(amount) AS low, MAX(amount) AS high "
                + "FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(placed_at), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, region";
        List<ZSetHarness.Change> changes = List.of(
                order("EMEA", "0.10", 1),
                order("EMEA", "0.20", 2),
                order("EMEA", "99999999.99", 12),
                order("EMEA", "0.01", 13));

        Map<List<Object>, Long> answer = ZSetHarness.maintained(ORDERS, sql, changes, 30 * SECOND);

        assertThat(answer)
                .containsOnly(
                        Map.entry(
                                List.of(
                                        0L,
                                        "EMEA",
                                        new BigDecimal("0.30"),
                                        new BigDecimal("0.10"),
                                        new BigDecimal("0.20")),
                                1L),
                        // Past the column's own ten digits: the sum is DECIMAL(38, 2).
                        Map.entry(
                                List.of(
                                        10 * SECOND,
                                        "EMEA",
                                        new BigDecimal("100000000.00"),
                                        new BigDecimal("0.01"),
                                        new BigDecimal("99999999.99")),
                                1L));
    }

    @Test
    void aDecimalAverageIsRefusedOnTheContinuousSideToo() {
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(ORDERS).plan("SELECT AVG(amount) FROM orders")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("AVG(amount)");
    }

    private static ZSetHarness.Change order(String region, String amount, long seconds) {
        return ZSetHarness.Change.insert(seconds * SECOND, region, new BigDecimal(amount), seconds * SECOND);
    }
}
