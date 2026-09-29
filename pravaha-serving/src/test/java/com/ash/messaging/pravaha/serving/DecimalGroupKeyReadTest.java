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
package com.ash.messaging.pravaha.serving;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DECKEYGROUP-1: a read that groups by a {@code DECIMAL} column, or counts its distinct values, tells
 * values apart by their whole unscaled value -- as windowed aggregates have since WINDECKEY-1.
 *
 * <p>Before the fix both were refused with {@code PRV-3020} ("cannot group by a column of type
 * DECIMAL yet", "COUNT(DISTINCT ...) over a DECIMAL column is not supported").
 */
class DecimalGroupKeyReadTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("fills")
            .field("id", Types.int64())
            .field("price", Types.decimal(30, 2))
            .field("qty", Types.int64())
            .build();

    private ViewQuery queries;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("fills", SCHEMA, List.of(0), 10_000);
        // 1.50 and 2.75 have the same high half (zero); 1e25 needs both halves.
        view.applyValues(new Object[] {1L, new BigDecimal("1.50"), 10L}, 1, 100);
        view.applyValues(new Object[] {2L, new BigDecimal("2.75"), 20L}, 1, 100);
        view.applyValues(new Object[] {3L, new BigDecimal("1.50"), 5L}, 1, 100);
        view.applyValues(new Object[] {4L, new BigDecimal("10000000000000000000000000.00"), 1L}, 1, 100);
        view.applyValues(new Object[] {5L, new BigDecimal("-2.75"), 2L}, 1, 100);
        view.commit(100);
        queries = new ViewQuery(new ViewCatalog().register(view));
    }

    @Test
    void aGroupByOnADecimalColumnKeepsEveryValueApart() {
        ViewQuery.Result result = queries.execute("SELECT price, SUM(qty) FROM fills GROUP BY price");

        assertThat(result.rows().stream()
                        .map(row -> ((BigDecimal) row[0]).toPlainString() + "=" + row[1])
                        .sorted()
                        .toList())
                .containsExactly("-2.75=2", "1.50=15", "10000000000000000000000000.00=1", "2.75=20");
        assertThat(((DecimalType) result.schema().field(0).type()).scale()).isEqualTo(2);
    }

    @Test
    void countDistinctOfADecimalColumnCountsEachValue() {
        ViewQuery.Result result = queries.execute("SELECT COUNT(DISTINCT price) FROM fills");

        assertThat(((Number) result.rows().get(0)[0]).longValue()).isEqualTo(4L);
    }

    @Test
    void countDistinctOfADecimalPerGroupCountsEachValue() {
        ViewQuery.Result result = queries.execute("SELECT qty, COUNT(DISTINCT price) FROM fills GROUP BY qty");

        assertThat(result.rows().stream().map(row -> row[0] + "=" + row[1]).toList())
                .containsExactlyInAnyOrder("1=1", "10=1", "2=1", "20=1", "5=1");
    }
}
