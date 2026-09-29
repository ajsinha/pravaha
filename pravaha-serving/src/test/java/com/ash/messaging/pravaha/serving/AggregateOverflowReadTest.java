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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SUMWRAP-1 on the read path: a {@code SUM} over a view whose total leaves the 64-bit range is
 * refused {@code PRV-3025}, naming the aggregate, rather than answered with the wrapped number --
 * unkeyed ({@code GlobalAggregate}) and grouped ({@code KeyedAggregate}), BIGINT and DECIMAL alike.
 *
 * <p>Before the fix {@code SELECT SUM(n)} over {@code Long.MAX_VALUE} and {@code 1} answered
 * {@code -9223372036854775808}.
 */
class AggregateOverflowReadTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("big")
            .field("id", Types.string())
            .field("region", Types.string())
            .field("n", Types.int64())
            .field("d", Types.decimal(19, 0))
            .build();

    private ViewQuery queries;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("big", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"a", "EMEA", Long.MAX_VALUE, new BigDecimal("5000000000000000000")}, 1, 100);
        view.applyValues(new Object[] {"b", "EMEA", 1L, new BigDecimal("5000000000000000000")}, 1, 100);
        view.applyValues(new Object[] {"c", "APAC", 7L, BigDecimal.ONE}, 1, 100);
        view.commit(100);
        queries = new ViewQuery(new ViewCatalog().register(view));
    }

    @Test
    void anUnkeyedSumPastTheRangeIsRefusedByName() {
        assertThatThrownBy(() -> queries.execute("SELECT SUM(n) FROM big"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(n)");
    }

    @Test
    void aGroupedSumPastTheRangeIsRefusedByName() {
        assertThatThrownBy(() -> queries.execute("SELECT region, SUM(n) FROM big GROUP BY region"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(n)");
    }

    @Test
    void aDecimalSumPastTheRangeOfItsUnscaledValueIsRefusedByName() {
        // Each value fits 64 bits unscaled; their sum, 10^19, does not.
        assertThatThrownBy(() -> queries.execute("SELECT SUM(d) FROM big"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(d)");
    }

    @Test
    void anAverageSumsTooAndIsRefusedTheSameWay() {
        assertThatThrownBy(() -> queries.execute("SELECT AVG(n) FROM big"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("AVG(n)");
    }

    @Test
    void totalsInsideTheRangeAreStillAnsweredExactly() {
        ViewQuery.Result result =
                queries.execute("SELECT region, SUM(n), COUNT(*) FROM big WHERE region = 'APAC' GROUP BY region");

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsExactly("APAC", 7L, 1L);
    }
}
