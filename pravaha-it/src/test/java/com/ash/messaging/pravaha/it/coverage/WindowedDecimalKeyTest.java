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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WINDECKEY-1: a windowed {@code GROUP BY} on a {@code DECIMAL} column, and {@code COUNT(DISTINCT)}
 * of one, tell values apart by their whole unscaled value.
 *
 * <p>Before the fix both read the key with {@code getLong} -- the high half of the 128-bit slot,
 * zero for every value of eighteen digits or fewer -- so {@code 1.50} and {@code 2.75} were one
 * group, reported under whichever arrived first, and counted as one distinct value.
 */
class WindowedDecimalKeyTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema TRADES = StreamSchema.builder("trades")
            .field("price", Types.decimal(12, 2))
            .field("qty", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final String BY_PRICE = "SELECT window_start, price, SUM(qty) AS volume "
            + "FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(ts), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, price";

    private static final List<ZSetHarness.Change> CHANGES =
            List.of(trade("1.50", 10, 1), trade("2.75", 20, 2), trade("1.50", 5, 3), trade("-2.75", 1, 4));

    @Test
    void aWindowedGroupByOnADecimalColumnKeepsDifferentValuesApart() {
        Map<List<Object>, Long> answer = ZSetHarness.maintained(TRADES, BY_PRICE, CHANGES, 30 * SECOND);

        assertThat(answer)
                .containsOnly(
                        Map.entry(List.of(0L, new BigDecimal("1.50"), 15L), 1L),
                        Map.entry(List.of(0L, new BigDecimal("2.75"), 20L), 1L),
                        Map.entry(List.of(0L, new BigDecimal("-2.75"), 1L), 1L));
    }

    @Test
    void theDecimalKeysSurviveACheckpointAndRestore() {
        Map<List<Object>, Long> answer = ZSetHarness.maintained(TRADES, BY_PRICE, CHANGES, 30 * SECOND, 2);

        assertThat(answer).isEqualTo(ZSetHarness.maintained(TRADES, BY_PRICE, CHANGES, 30 * SECOND));
    }

    @Test
    void aWindowedCountDistinctOfADecimalColumnCountsEachValue() {
        String sql = "SELECT window_start, COUNT(DISTINCT price) AS prices "
                + "FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(ts), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end";

        Map<List<Object>, Long> answer = ZSetHarness.maintained(TRADES, sql, CHANGES, 30 * SECOND);

        assertThat(answer).containsOnly(Map.entry(List.of(0L, 3L), 1L));
        assertThat(ZSetHarness.maintained(TRADES, sql, CHANGES, 30 * SECOND, 3)).isEqualTo(answer);
    }

    private static ZSetHarness.Change trade(String price, long qty, long seconds) {
        return ZSetHarness.Change.insert(seconds * SECOND, new BigDecimal(price), qty, seconds * SECOND);
    }
}
