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
package com.ash.messaging.pravaha.it.qa.state;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A query's state is visible while it grows, not only in the message that says it died.
 *
 * <p>ADR-037. `SlicedAggregateState` has always counted its accumulators and always known its
 * ceiling; nothing carried either anywhere. So `PRV-4001 STATE_TOO_LARGE` was the first anyone heard
 * of a query's state, and the query at nine tenths of its ceiling -- the one still worth acting on --
 * could not be seen at all. For the failure mode that arrives as a surprise, that is the wrong way
 * round.
 */
@Timeout(120)
final class StateVisibilityTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    @Test
    void stateIsReportedAsItGrowsRatherThanOnlyWhenItIsRefused() {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN);
                RowArena feeder = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            registry.register(
                    "g",
                    "SELECT user_id, SUM(amount) AS total FROM txn "
                            + "GROUP BY user_id, TUMBLE(ts, INTERVAL '1' SECOND)",
                    List.of(0),
                    DANA);
            RegisteredQuery query = registry.require("g");

            assertThat(query.stateUsage().held())
                    .as("nothing fed yet, so nothing held")
                    .isZero();
            assertThat(query.stateUsage().ceiling())
                    .as("and the ceiling is knowable before anything is near it, which is the point")
                    .isPositive();

            // Distinct keys, so each is its own accumulator: this is what a cardinality surprise
            // looks like from the inside, arriving one row at a time.
            for (int i = 0; i < 50; i++) {
                feed(feeder, query, "user-" + i, 100L + i);
            }

            assertThat(query.stateUsage().held())
                    .as("fifty distinct keys in one window are fifty accumulators, and an operator can "
                            + "see them without waiting for the query to die")
                    .isGreaterThanOrEqualTo(50);
            assertThat(query.stateUsage().fraction())
                    .as("and how full it is, which is the number worth alerting on")
                    .isGreaterThan(0.0)
                    .isLessThan(1.0);
        }
    }

    private static void feed(RowArena feeder, RegisteredQuery query, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = feeder.allocate(layout.rowSize(64));
        writer.begin(feeder.regionOf(handle), feeder.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount).setLong(2, 500_000_000L);
        writer.weight(1L).eventTimestampNanos(500_000_000L).sequence(amount).commit();
        feeder.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", view.wrap(feeder.regionOf(handle), feeder.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}
