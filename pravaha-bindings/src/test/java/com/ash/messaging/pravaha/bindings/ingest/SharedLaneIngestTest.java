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
package com.ash.messaging.pravaha.bindings.ingest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LANE-2: queries over one source share lanes, and the lane's one ingest of that source, and each
 * answers exactly as it does on a lane of its own.
 *
 * <p>Every test drives {@link LaneEquivalence}: the same script against an engine that hosts every
 * query on shared lanes and one that gives each its own, both held to the answer the store gives.
 * Counts and sums, not projections -- a keyed view absorbs a duplicate upsert without a trace, which
 * is how LANE-1 hid.
 */
@Timeout(300)
class SharedLaneIngestTest {

    private LaneEquivalence engines;

    @BeforeEach
    void reset() {
        CountingScanPlugin.reset();
    }

    @AfterEach
    void close() {
        if (engines != null) {
            engines.close();
        }
    }

    private static void append(int from, int count) {
        for (int i = from; i < from + count; i++) {
            CountingScanPlugin.append(i, (i * 37L) % 400);
        }
    }

    @Test
    void sixQueriesOverOneStreamShareOneLaneAndOneCopyOfEachRow() {
        engines = new LaneEquivalence(1);
        LaneEquivalence.POOL.forEach(engines::register);
        append(1, 40);
        engines.settle();

        QueryRegistry registry = engines.shared.registry;
        assertThat(registry.pipelinesPerSharedLane())
                .as("six queries over one stream on the one shared lane; LANE-1 kept them to one")
                .containsExactly(6);
        assertThat(registry.queriesOnOwnLanes()).isZero();

        // The ingest: one copy per row into the shared lane, where the own-lane engine writes six.
        long[] shared = engines.shared.feeds.sharedRowsReadAndCopiesWritten();
        long[] own = engines.own.feeds.sharedRowsReadAndCopiesWritten();
        System.out.printf(
                "%nLANE-2 copies into lanes for %d rows read by 6 queries: shared lanes %d, own lanes %d%n",
                shared[0], shared[1], own[1]);
        assertThat(shared[1]).as("one copy of each row for the whole lane").isEqualTo(shared[0]);
        assertThat(own[1]).as("a copy per query, on lanes of their own").isEqualTo(own[0] * 6);
    }

    @Test
    void retractionsReachEveryQueryOnTheLaneOnce() {
        engines = new LaneEquivalence(1);
        LaneEquivalence.POOL.forEach(engines::register);
        append(1, 30);
        engines.settle();
        for (int i = 1; i <= 30; i += 3) {
            CountingScanPlugin.retract(i, (i * 37L) % 400);
        }
        append(31, 10);
        engines.settle();
    }

    @Test
    void pausingOneQueryOnASharedIngestDisturbsNoOtherAndResumingMakesItWhole() {
        engines = new LaneEquivalence(1);
        LaneEquivalence.POOL.forEach(engines::register);
        append(1, 20);
        engines.pause("big_n");
        append(21, 20);
        // Settled while paused: the paused query holds its answer from before, the rest move on.
        engines.settle();
        engines.resume("big_n");
        append(41, 5);
        engines.settle();
    }

    @Test
    void droppingOneQueryLeavesTheRestOfTheLaneCounting() {
        engines = new LaneEquivalence(1);
        LaneEquivalence.POOL.forEach(engines::register);
        append(1, 20);
        engines.settle();
        engines.drop("all_n");
        engines.drop("user3_n");
        append(21, 20);
        engines.settle();
        assertThat(engines.shared.registry.pipelinesPerSharedLane()).containsExactly(4);
    }

    @Test
    void aQueryJoiningMidStreamIsCaughtUpOnceAndThenSharesTheCopy() {
        engines = new LaneEquivalence(1);
        engines.register(LaneEquivalence.POOL.get(0));
        engines.register(LaneEquivalence.POOL.get(1));
        append(1, 25);
        engines.settle();
        // Behind the shared reader by 25 rows: attached to the lane's copy first, caught up through
        // its own route, and never handed the others' history twice.
        engines.register(LaneEquivalence.POOL.get(4));
        engines.register(LaneEquivalence.POOL.get(2));
        engines.settle();
        append(26, 15);
        engines.settle();
    }

    @Test
    void aCheckpointOfEveryQueryOnASharedIngestRestoresThemAllConsistently() {
        engines = new LaneEquivalence(2);
        LaneEquivalence.POOL.forEach(engines::register);
        append(1, 30);
        engines.pause("top_rows");
        append(31, 10);
        engines.settle();
        // Each query checkpoints its own offset and state; a paused one records where it paused, not
        // where the reader shared with the others has got to.
        engines.restart();
        engines.settle();
        append(41, 20);
        CountingScanPlugin.retract(2, 74);
        engines.settle();
        assertThat(engines.shared.registry.queriesOnOwnLanes()).isZero();
    }

    @Test
    void aPausedQueryCheckpointedAndRestoredMissesNothingItWasPausedThrough() {
        engines = new LaneEquivalence(1);
        engines.register(LaneEquivalence.POOL.get(0));
        engines.register(LaneEquivalence.POOL.get(3));
        append(1, 10);
        engines.pause("mid_n");
        append(11, 30);
        // The shared reader has read 30 rows past where mid_n paused. Its checkpoint must say so.
        engines.restart();
        engines.settle();
    }

    // ---------------------------------------------------------------- joins on a shared lane

    private static final StreamSchema PROFILES = StreamSchema.builder("profiles")
            .field("user_id", Types.string())
            .field("tier", Types.string())
            .build();

    /** A count over a join: a row handed to either side twice would move it. */
    private static final String JOIN = "SELECT COUNT(*) AS n, SUM(s.amount) AS total "
            + "FROM shared s JOIN profiles p ON s.user_id = p.user_id WHERE p.tier = 'gold'";

    @Test
    void aJoinIsHostedOnASharedLaneAndAnswersAsOnItsOwn() throws Exception {
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                QueryRegistry sharedLanes = joinRegistry(1);
                QueryRegistry ownLanes = joinRegistry(0)) {
            RegisteredQuery onShared = sharedLanes.register("tiers", JOIN, List.of(0), LaneEquivalence.DANA);
            RegisteredQuery plainOnShared =
                    sharedLanes.register("all_n", LaneEquivalence.POOL.get(0).sql(), List.of(0), LaneEquivalence.DANA);
            RegisteredQuery onOwn = ownLanes.register("tiers", JOIN, List.of(0), LaneEquivalence.DANA);

            // Hosted: LANE-1 gave every join a lane of its own, because the shared lane dispatched by
            // stream and a join's second side had no way to arrive.
            assertThat(sharedLanes.sharedLaneOf("tiers")).contains(0);
            assertThat(sharedLanes.pipelinesPerSharedLane()).containsExactly(2);

            for (int user = 0; user < 7; user++) {
                String tier = user % 2 == 0 ? "gold" : "silver";
                profile(arena, onShared, "u" + user, tier);
                profile(arena, onOwn, "u" + user, tier);
            }
            append(1, 35);

            long n = 0;
            long total = 0;
            for (long id = 1; id <= 35; id++) {
                if ((id % 7) % 2 == 0) {
                    n++;
                    total += (id * 37L) % 400;
                }
            }
            List<List<Object>> expected = List.of(List.of(n, total));
            assertThat(awaitRows(onOwn, expected))
                    .as("the join on a lane of its own")
                    .isEqualTo(expected);
            assertThat(awaitRows(onShared, expected))
                    .as("the join on a shared lane")
                    .isEqualTo(expected);
            assertThat(awaitRows(plainOnShared, LaneEquivalence.POOL.get(0).expected(CountingScanPlugin.STORE)))
                    .as("the plain query on the join's lane counts each row once")
                    .isEqualTo(LaneEquivalence.POOL.get(0).expected(CountingScanPlugin.STORE));
        }
    }

    private static QueryRegistry joinRegistry(int sharedLanes) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), CountingScanPlugin.SCHEMA, PROFILES)
                .feedingFrom(new PluginSourceFeeds().bind(new SourceBinding("shared", "counting-scan", Map.of())));
        if (sharedLanes > 0) {
            registry.multiplexingLanes(sharedLanes, 100);
        }
        return registry;
    }

    private static void profile(RowArena arena, RegisteredQuery query, String user, String tier) {
        RowLayout layout = RowLayout.of(PROFILES);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setString(1, tier);
        // Now, as the counting source stamps its rows: a join matches within an hour of event time.
        writer.weight(1L)
                .eventTimestampNanos(System.currentTimeMillis() * 1_000_000L)
                .sequence(0)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(
                        "profiles", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private static List<List<Object>> awaitRows(RegisteredQuery query, List<List<Object>> expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<List<Object>> seen = List.of();
        while (System.nanoTime() < deadline) {
            query.commit();
            seen = LaneEquivalence.rows(query);
            if (seen.equals(expected)) {
                LaneEquivalence.sleep(150);
                query.commit();
                return LaneEquivalence.rows(query);
            }
            LaneEquivalence.sleep(10);
        }
        return seen;
    }
}
