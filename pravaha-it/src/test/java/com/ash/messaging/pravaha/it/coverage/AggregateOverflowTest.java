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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SUMWRAP-1 on the continuous side: a maintained {@code SUM} -- unwindowed and windowed, BIGINT and
 * DECIMAL, by insertion and by retraction -- whose total leaves the 64-bit range stops with {@code
 * PRV-3025} naming the aggregate. It used to wrap and publish {@code -9223372036854775808} as the
 * total. The read side is {@code AggregateOverflowReadTest} in pravaha-serving.
 */
class AggregateOverflowTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("price", Types.decimal(19, 0))
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    @Test
    void anUnwindowedSumPastTheRangeStopsByNameRatherThanWrapping() {
        List<ZSetHarness.Change> changes = List.of(txn("u1", Long.MAX_VALUE, "0", 1), txn("u1", 1, "0", 2));

        assertThatThrownBy(() -> ZSetHarness.maintained(TXN, "SELECT SUM(amount) FROM txn", changes, Long.MIN_VALUE))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(amount)");
    }

    @Test
    void aRetractionThatCarriesTheTotalPastTheRangeIsRefusedToo() {
        ZSetHarness.Change minusOne = txn("u1", -1, "0", 2);
        List<ZSetHarness.Change> changes = List.of(txn("u1", Long.MAX_VALUE, "0", 1), minusOne.retracted());

        assertThatThrownBy(() -> ZSetHarness.maintained(TXN, "SELECT SUM(amount) FROM txn", changes, Long.MIN_VALUE))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025");
    }

    @Test
    void aDecimalSumPastTheRangeOfItsUnscaledValueStopsByName() {
        List<ZSetHarness.Change> changes =
                List.of(txn("u1", 0, "5000000000000000000", 1), txn("u1", 0, "5000000000000000000", 2));

        assertThatThrownBy(() -> ZSetHarness.maintained(TXN, "SELECT SUM(price) FROM txn", changes, Long.MIN_VALUE))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(price)");
    }

    @Test
    void aWindowedSumPastTheRangeStopsByName() {
        String sql = "SELECT window_start, user_id, SUM(amount) AS total "
                + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(ts), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, user_id";
        List<ZSetHarness.Change> changes = List.of(txn("u1", Long.MAX_VALUE, "0", 1), txn("u1", 1, "0", 2));

        assertThatThrownBy(() -> ZSetHarness.maintained(TXN, sql, changes, 30 * SECOND))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(amount)");
    }

    @Test
    void aHoppingWindowWhoseSlicesFitButWhoseTotalDoesNotStopsWhenItFires() {
        String sql = "SELECT window_start, user_id, SUM(amount) AS total "
                + "FROM TABLE(HOP(TABLE txn, DESCRIPTOR(ts), INTERVAL '10' SECOND, INTERVAL '20' SECOND)) "
                + "GROUP BY window_start, window_end, user_id";
        long half = Long.MAX_VALUE / 2 + 1;
        List<ZSetHarness.Change> changes = List.of(txn("u1", half, "0", 1), txn("u1", half, "0", 11));

        assertThatThrownBy(() -> ZSetHarness.maintained(TXN, sql, changes, 60 * SECOND))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3025")
                .hasMessageContaining("SUM(amount)");
    }

    @Test
    void aTotalThatReachesTheEdgeOfTheRangeExactlyIsAnswered() {
        List<ZSetHarness.Change> changes = List.of(txn("u1", Long.MAX_VALUE - 1, "0", 1), txn("u1", 1, "0", 2));

        Map<List<Object>, Long> answer =
                ZSetHarness.maintained(TXN, "SELECT SUM(amount) FROM txn", changes, Long.MIN_VALUE);

        assertThat(answer).containsOnly(Map.entry(List.of(Long.MAX_VALUE), 1L));
    }

    private static ZSetHarness.Change txn(String user, long amount, String price, long seconds) {
        return ZSetHarness.Change.insert(seconds * SECOND, user, amount, new BigDecimal(price), seconds * SECOND);
    }
}
