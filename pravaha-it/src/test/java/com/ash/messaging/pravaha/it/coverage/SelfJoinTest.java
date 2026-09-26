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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stream joined with itself (Nexmark q7).
 *
 * <p>One arrival is an arrival on both sides. The pipeline hands the row to the side registered
 * first and then to the other, which is the bilinear rule applied to a join whose two inputs are the
 * same delta: {@code d(S join S) = dS join S + S' join dS}, where {@code S'} already includes {@code
 * dS}. So a row pairs with every earlier matching row once from each side and with itself once,
 * exactly as the join of the net input with itself does, and a retraction withdraws the same pairs.
 */
class SelfJoinTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema BID = StreamSchema.builder("bid")
            .field("auction", Types.int64())
            .field("bidder", Types.int64())
            .field("price", Types.int64())
            .field("date_time", Types.timestamp())
            .eventTime("date_time")
            .build();

    @Test
    void aRowPairsWithItselfAndWithEveryEarlierMatchOnBothSides() {
        String sql = "SELECT a.bidder, b.bidder FROM bid a JOIN bid b ON a.auction = b.auction";
        Map<List<Object>, Long> answer =
                ZSetHarness.maintained(BID, sql, List.of(bid(1, 7, 10, 1), bid(1, 8, 20, 2)), Long.MIN_VALUE);
        assertThat(answer)
                .containsOnly(
                        Map.entry(List.of(7L, 7L), 1L),
                        Map.entry(List.of(7L, 8L), 1L),
                        Map.entry(List.of(8L, 7L), 1L),
                        Map.entry(List.of(8L, 8L), 1L));
    }

    @Test
    void theIncrementalSelfJoinEqualsTheSelfJoinFromScratchIncludingRetractions() {
        Random random = new Random(20260926L);
        List<ZSetHarness.Change> held = new ArrayList<>();
        List<ZSetHarness.Change> changes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            if (!held.isEmpty() && random.nextInt(3) == 0) {
                changes.add(held.remove(random.nextInt(held.size())).retracted());
            } else {
                ZSetHarness.Change bid = bid(random.nextInt(5), random.nextInt(4), random.nextInt(6), i);
                held.add(bid);
                changes.add(bid);
            }
        }
        String sql = "SELECT a.auction, a.bidder, b.bidder, a.price - b.price AS gap FROM bid a JOIN bid b "
                + "ON a.auction = b.auction WHERE a.price >= b.price";
        Map<List<Object>, Long> maintained = ZSetHarness.maintained(BID, sql, changes, Long.MIN_VALUE);
        assertThat(maintained).isEqualTo(ZSetHarness.fromScratch(BID, sql, changes, Long.MIN_VALUE));
        assertThat(maintained)
                .as("the control: the join produced pairs to compare")
                .isNotEmpty();
    }

    @Test
    void nexmarkQ7FindsTheHighestBidOfEachTenSecondWindow() {
        String q7 = "SELECT B.auction, B.price, B.bidder, B.date_time FROM bid B JOIN (SELECT MAX(price) AS maxprice, "
                + "TUMBLE_END(date_time, INTERVAL '10' SECOND) AS date_time FROM bid GROUP BY "
                + "TUMBLE(date_time, INTERVAL '10' SECOND)) B1 ON B.price = B1.maxprice WHERE B.date_time "
                + "BETWEEN B1.date_time - INTERVAL '10' SECOND AND B1.date_time";
        // Window [0s, 10s): prices 5, 9, 7 -- the highest is 9, bid by 2. Window [10s, 20s): 4 and 6.
        // Window [20s, 30s), closed when the input ends: 9 alone. The bid of 9 at 21s matches only
        // its own window's maximum, not the first window's, whose ten seconds it is outside.
        List<ZSetHarness.Change> changes = List.of(
                bid(1, 1, 5, 1),
                bid(1, 2, 9, 3),
                bid(2, 3, 7, 8),
                bid(2, 4, 4, 12),
                bid(3, 5, 6, 15),
                bid(3, 6, 9, 21));
        Map<List<Object>, Long> answer = ZSetHarness.maintained(BID, q7, changes, 20 * SECOND + 1);
        assertThat(answer)
                .containsOnly(
                        Map.entry(List.of(1L, 9L, 2L, 3 * SECOND), 1L),
                        Map.entry(List.of(3L, 6L, 5L, 15 * SECOND), 1L),
                        Map.entry(List.of(3L, 9L, 6L, 21 * SECOND), 1L));
        assertThat(answer).isEqualTo(ZSetHarness.fromScratch(BID, q7, changes, 20 * SECOND + 1));
    }

    @Test
    void aFilterOnOneSideIsNotPushedToTheSourceBothSidesRead() {
        // The stream is read once for both sides, so a filter pushed to the source for side a would
        // drop the row for side b too.
        com.ash.messaging.pravaha.api.plugin.SourceCapabilities filters =
                new com.ash.messaging.pravaha.api.plugin.SourceCapabilities(
                        true,
                        true,
                        false,
                        false,
                        com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee.AT_LEAST_ONCE,
                        java.util.EnumSet.of(com.ash.messaging.pravaha.api.plugin.PushdownKind.FILTER),
                        null);
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator selfJoin = plan("SELECT a.bidder, b.bidder FROM "
                + "(SELECT * FROM bid WHERE price > 5) a JOIN bid b ON a.auction = b.auction");
        assertThat(com.ash.messaging.pravaha.sql.plan.SourcePushdown.requestFor(selfJoin, "bid", filters))
                .isEqualTo(com.ash.messaging.pravaha.api.plugin.ReadRequest.NOTHING);
        assertThat(com.ash.messaging.pravaha.sql.plan.SourcePushdown.requestFor(
                                plan("SELECT bidder FROM bid WHERE price > 5"), "bid", filters)
                        .filters())
                .as("the control: the same filter over one read of the stream is pushed")
                .isNotEmpty();

        // And the answer: a's filter holds for a only.
        Map<List<Object>, Long> answer = ZSetHarness.maintained(
                BID,
                "SELECT a.bidder, b.bidder FROM (SELECT * FROM bid WHERE price > 5) a JOIN bid b ON a.auction = b.auction",
                List.of(bid(1, 7, 10, 1), bid(1, 8, 2, 2)),
                Long.MIN_VALUE);
        assertThat(answer).containsOnly(Map.entry(List.of(7L, 7L), 1L), Map.entry(List.of(7L, 8L), 1L));
    }

    @Test
    void theStreamIsListedOnceSoOneReaderFeedsBothSides() {
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator selfJoin =
                plan("SELECT a.bidder, b.bidder FROM bid a JOIN bid b ON a.auction = b.auction");
        try (com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline pipeline =
                com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.compile(
                        selfJoin,
                        () -> new com.ash.messaging.pravaha.testkit.CapturingRowWriter(
                                selfJoin.outputSchema(), row -> {}))) {
            assertThat(pipeline.sourceStreams()).containsExactly("bid");
        }
    }

    private static com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan(String sql) {
        return new com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder()
                .build(com.ash.messaging.pravaha.sql.SqlPlanner.withStreams(BID).plan(sql));
    }

    private static ZSetHarness.Change bid(long auction, long bidder, long price, long seconds) {
        return ZSetHarness.Change.insert(seconds * SECOND, auction, bidder, price, seconds * SECOND);
    }
}
