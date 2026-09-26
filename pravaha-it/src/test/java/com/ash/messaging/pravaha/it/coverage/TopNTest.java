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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)} filtered to a top-N (Nexmark q18, q19).
 *
 * <p>The property is INCR.md's: the top-N maintained over a stream of insertions and retractions
 * equals the top-N computed from scratch over the net input, number column included. The small
 * cases show the three moves a Z-set top-N makes -- a new row entering and displacing the last, a
 * retraction promoting the row below, and every row between changing its number -- and the
 * randomised case runs a thousand changes of each kind against the from-scratch answer.
 */
class TopNTest {

    private static final StreamSchema BID = StreamSchema.builder("bid")
            .field("auction", Types.int64())
            .field("bidder", Types.int64())
            .field("price", Types.int64().withNullable(true))
            .field("date_time", Types.timestamp())
            .build();

    /** Nexmark q19, with N = 2 so that displacement is visible in three rows. */
    private static final String TOP_TWO = "SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction "
            + "ORDER BY price DESC) AS rank_number FROM bid) WHERE rank_number <= 2";

    @Test
    void aNewRowEntersTheTopAndDisplacesTheLast() {
        ZSetHarness.Change p10 = bid(1, 1, 10L, 1);
        ZSetHarness.Change p30 = bid(1, 2, 30L, 2);
        ZSetHarness.Change p20 = bid(1, 3, 20L, 3);
        assertThat(run(TOP_TWO, p10, p30, p20))
                .containsOnly(
                        Map.entry(List.of(1L, 2L, 30L, sec(2), 1L), 1L),
                        Map.entry(List.of(1L, 3L, 20L, sec(3), 2L), 1L));
    }

    @Test
    void aRetractionOfTheTopRowPromotesTheRowsBelow() {
        ZSetHarness.Change p10 = bid(1, 1, 10L, 1);
        ZSetHarness.Change p30 = bid(1, 2, 30L, 2);
        ZSetHarness.Change p20 = bid(1, 3, 20L, 3);
        assertThat(run(TOP_TWO, p10, p30, p20, p30.retracted()))
                .containsOnly(
                        Map.entry(List.of(1L, 3L, 20L, sec(3), 1L), 1L),
                        Map.entry(List.of(1L, 1L, 10L, sec(1), 2L), 1L));
    }

    @Test
    void partitionsAreNumberedIndependently() {
        assertThat(run(
                        "SELECT auction, price, rn FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY "
                                + "price DESC) AS rn FROM bid) WHERE rn = 1",
                        bid(1, 1, 10L, 1),
                        bid(2, 1, 5L, 2),
                        bid(1, 2, 12L, 3),
                        bid(2, 2, 4L, 4)))
                .containsOnly(Map.entry(List.of(1L, 12L, 1L), 1L), Map.entry(List.of(2L, 5L, 1L), 1L));
    }

    @Test
    void nexmarkQ18KeepsTheLastBidOfEachBidderOnEachAuction() {
        String q18 = "SELECT auction, bidder, price, date_time FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY "
                + "bidder, auction ORDER BY date_time DESC) AS rank_number FROM bid) WHERE rank_number <= 1";
        assertThat(run(q18, bid(1, 7, 10L, 1), bid(1, 7, 11L, 5), bid(1, 8, 3L, 2), bid(1, 7, 9L, 3)))
                .containsOnly(Map.entry(List.of(1L, 7L, 11L, sec(5)), 1L), Map.entry(List.of(1L, 8L, 3L, sec(2)), 1L));
    }

    @Test
    void nullsSortFirstWhenDescendingAsSqlDefaultsAndLastWhenAskedTo() {
        ZSetHarness.Change none = bid(1, 1, null, 1);
        ZSetHarness.Change some = bid(1, 2, 5L, 2);
        assertThat(run(
                        "SELECT bidder FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY price DESC) "
                                + "AS rn FROM bid) WHERE rn <= 1",
                        none,
                        some))
                .containsOnlyKeys(List.of(1L));
        assertThat(run(
                        "SELECT bidder FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY price DESC "
                                + "NULLS LAST) AS rn FROM bid) WHERE rn <= 1",
                        none,
                        some))
                .containsOnlyKeys(List.of(2L));
    }

    @Test
    void aConditionBesideTheBoundFiltersTheTopRowsAfterTheyAreNumbered() {
        // The number is assigned first and the price filter applied to the numbered rows, as SQL
        // evaluates the outer WHERE over the inner query's output: rank 2 is 10 and is dropped.
        String sql = "SELECT bidder, rn FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY price DESC) "
                + "AS rn FROM bid) WHERE rn <= 2 AND price > 15";
        assertThat(run(sql, bid(1, 1, 10L, 1), bid(1, 2, 30L, 2), bid(1, 3, 5L, 3)))
                .containsOnly(Map.entry(List.of(2L, 1L), 1L));
    }

    @Test
    void aNumberingWithoutABoundIsRefusedWithTheReason() {
        assertThatThrownBy(() -> plan("SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY price) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("without a bound")
                .hasMessageContaining("rn <= 10");
        assertThatThrownBy(() -> plan("SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY "
                        + "price) AS rn FROM bid) WHERE rn > 2"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("rn <= N");
    }

    @Test
    void aNumberingWithNoOrderAndAWindowAggregateAreRefusedByName() {
        assertThatThrownBy(() -> plan("SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction) AS rn "
                        + "FROM bid) WHERE rn <= 2"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("no ORDER BY");
        assertThatThrownBy(() -> plan("SELECT auction, AVG(price) OVER (PARTITION BY auction ORDER BY date_time ROWS "
                        + "BETWEEN 10 PRECEDING AND CURRENT ROW) FROM bid"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("window aggregate");
    }

    @Test
    void theIncrementalAnswerEqualsTheAnswerFromScratchOverAThousandRandomChanges() {
        Random random = new Random(20260926L);
        List<ZSetHarness.Change> held = new ArrayList<>();
        List<ZSetHarness.Change> changes = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            if (!held.isEmpty() && random.nextInt(3) == 0) {
                ZSetHarness.Change gone = held.remove(random.nextInt(held.size()));
                changes.add(gone.retracted());
            } else {
                // Few prices, so ties are common and the tie-break is exercised; a few nulls.
                Long price = random.nextInt(10) == 0 ? null : (long) random.nextInt(8);
                ZSetHarness.Change bid = bid(random.nextInt(4), random.nextInt(5), price, random.nextInt(6));
                held.add(bid);
                changes.add(bid);
            }
        }
        String sql = "SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY auction ORDER BY price DESC, "
                + "date_time) AS rn FROM bid) WHERE rn <= 3";
        Map<List<Object>, Long> maintained = ZSetHarness.maintained(BID, sql, changes, Long.MIN_VALUE);
        assertThat(maintained).isEqualTo(ZSetHarness.fromScratch(BID, sql, changes, Long.MIN_VALUE));
        assertThat(maintained.values())
                .as("a top-N holds each numbered row once")
                .containsOnly(1L);
        assertThat(maintained).as("four auctions, three numbers each, at most").hasSizeLessThanOrEqualTo(12);
    }

    @Test
    void aTopNRestoredFromACheckpointAnswersAsIfItHadNeverStopped() {
        // A top-N that resumed empty after a restart would number the next row 1 in a partition
        // whose first rows it had already emitted -- a wrong answer with nothing to show for it.
        List<ZSetHarness.Change> changes = List.of(
                bid(1, 1, 10L, 1),
                bid(1, 2, 30L, 2),
                bid(2, 3, 7L, 3),
                bid(1, 3, 20L, 4),
                bid(1, 2, 30L, 2).retracted(),
                bid(1, 4, 25L, 5));
        Map<List<Object>, Long> uninterrupted = ZSetHarness.maintained(BID, TOP_TWO, changes, Long.MIN_VALUE);
        for (int split = 0; split <= changes.size(); split++) {
            assertThat(ZSetHarness.maintained(BID, TOP_TWO, changes, Long.MIN_VALUE, split))
                    .as("checkpointed after %d changes", split)
                    .isEqualTo(uninterrupted);
        }
        assertThat(uninterrupted).hasSize(3);
    }

    // ---------------------------------------------------------------- helpers

    private static long sec(long seconds) {
        return seconds * 1_000_000_000L;
    }

    private static ZSetHarness.Change bid(long auction, long bidder, Long price, long seconds) {
        return ZSetHarness.Change.insert(sec(seconds), auction, bidder, price, sec(seconds));
    }

    private static Map<List<Object>, Long> run(String sql, ZSetHarness.Change... changes) {
        List<ZSetHarness.Change> input = List.of(changes);
        Map<List<Object>, Long> maintained = ZSetHarness.maintained(BID, sql, input, Long.MIN_VALUE);
        assertThat(maintained)
                .as("maintained equals from scratch for this input too")
                .isEqualTo(ZSetHarness.fromScratch(BID, sql, input, Long.MIN_VALUE));
        return maintained;
    }

    private static com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(BID).plan(sql));
    }
}
