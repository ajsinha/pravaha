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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What LANE-2 saves, measured: a thousand different queries over one source, on eight shared lanes
 * and on a lane each.
 *
 * <p>Before LANE-2 the thousand could not share at all -- a shared lane carried one query per stream,
 * so the other 992 went to lanes of their own whatever multiplexing was set to. The numbers printed
 * are the ones the README and OPERATIONS quote: lanes, the off-heap bytes those lanes hold, and how
 * many copies of each source row were written into lane inboxes.
 *
 * <p>The lanes are configured small -- a 64 KiB inbox and 64 KiB arena slabs -- so that a thousand
 * lanes of their own fit in a test JVM. The ratio is what the configuration does not change; at the
 * defaults (a 1 MiB inbox and a 4 MiB first arena slab per active lane) the own-lane side is about
 * five gigabytes.
 */
@Timeout(600)
class SharedLaneDensityTest {

    private static final int QUERIES = 1_000;
    private static final int SHARED_LANES = 8;
    private static final int ROWS = 400;

    private static final LaneConfig SMALL = LaneConfig.defaults()
            .withWaitStrategy(com.ash.messaging.pravaha.common.queue.WaitStrategy.Kind.BACKOFF_PARK)
            .withThreads("pravaha-query", true)
            .withInbox(256, 256)
            .withArena(64 * 1024, 64);

    @BeforeEach
    void reset() {
        CountingScanPlugin.reset();
    }

    private record Engine(QueryRegistry registry, PluginSourceFeeds feeds, List<RegisteredQuery> queries) {}

    private static Engine engine(int sharedLanes) {
        PluginSourceFeeds feeds = new PluginSourceFeeds().bind(new SourceBinding("shared", "counting-scan", Map.of()));
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), CountingScanPlugin.SCHEMA)
                .executingWith(SMALL, MemoryAccess.best())
                .feedingFrom(feeds);
        if (sharedLanes > 0) {
            registry.multiplexingLanes(sharedLanes, QUERIES / sharedLanes);
        }
        List<RegisteredQuery> queries = new ArrayList<>(QUERIES);
        long started = System.nanoTime();
        for (int i = 0; i < QUERIES; i++) {
            queries.add(registry.register(
                    "q" + i,
                    "SELECT COUNT(*) AS n, SUM(amount) AS total FROM shared WHERE amount > " + i,
                    List.of(0),
                    LaneEquivalence.DANA));
        }
        System.out.printf(
                "%d registrations on %s: %d ms%n",
                QUERIES, sharedLanes > 0 ? "shared lanes" : "own lanes", (System.nanoTime() - started) / 1_000_000);
        return new Engine(registry, feeds, queries);
    }

    /** Waits for every query to count what the store says, and fails on any that does not. */
    private static void awaitEveryAnswer(Engine engine) {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        for (int i = 0; i < QUERIES; i++) {
            RegisteredQuery query = engine.queries().get(i);
            int threshold = i;
            List<List<Object>> expected = new LaneEquivalence.Query("q" + i, "", false, r -> r[1] > threshold)
                    .expected(CountingScanPlugin.STORE);
            List<List<Object>> seen = LaneEquivalence.rows(query);
            while (!seen.equals(expected) && System.nanoTime() < deadline) {
                LaneEquivalence.sleep(5);
                query.commit();
                seen = LaneEquivalence.rows(query);
            }
            assertThat(seen).as("q%d", i).isEqualTo(expected);
        }
    }

    private static long bytesOf(Engine engine, boolean shared) {
        long total = shared ? engine.registry().sharedLaneBytes() : 0;
        for (RegisteredQuery query : engine.queries()) {
            Map<String, Long> parts = query.offHeapBytes();
            if (shared) {
                // A hosted query reports its lane's inbox and arena as its own; the lanes are counted
                // once above, so only what is the query's alone is added here.
                total += parts.getOrDefault("pipeline-arena", 0L);
            } else {
                total += parts.values().stream().mapToLong(Long::longValue).sum();
            }
        }
        return total;
    }

    @Test
    void aThousandQueriesOverOneSourceShareEightLanesAndOneCopyOfEachRowPerLane() {
        Engine shared = engine(SHARED_LANES);
        Engine own = engine(0);
        try {
            // Every late joiner's catch-up read finished first: one still open when rows arrive reads
            // them as well as the shared reader does, which is SRC-3's handover duplicate on a lane
            // of its own as much as on a shared one, and not what this measures.
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (shared.feeds().catchUpsInFlight() + own.feeds().catchUpsInFlight() > 0) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                LaneEquivalence.sleep(10);
            }
            for (int id = 1; id <= ROWS; id++) {
                CountingScanPlugin.append(id, (id * 37L) % 1_000);
            }
            awaitEveryAnswer(shared);
            awaitEveryAnswer(own);

            List<Integer> perLane = shared.registry().pipelinesPerSharedLane();
            long sharedBytes = bytesOf(shared, true);
            long ownBytes = bytesOf(own, false);
            long ownLaneBytes = bytesOf(own, false) - pipelineArenas(own);
            long[] sharedCopies = shared.feeds().sharedRowsReadAndCopiesWritten();
            long[] ownCopies = own.feeds().sharedRowsReadAndCopiesWritten();

            System.out.printf(
                    "%nLANE-2, %d queries over one source, %d rows:%n"
                            + "  shared lanes : %d lanes %s, %,d B off-heap in lanes, %,d B with pipeline arenas, "
                            + "%,d copies into inboxes%n"
                            + "  own lanes    : %d lanes, %,d B off-heap in lanes, %,d B with pipeline arenas, "
                            + "%,d copies into inboxes%n",
                    QUERIES,
                    ROWS,
                    perLane.size(),
                    perLane,
                    shared.registry().sharedLaneBytes(),
                    sharedBytes,
                    sharedCopies[1],
                    own.registry().queriesOnOwnLanes(),
                    ownLaneBytes,
                    ownBytes,
                    ownCopies[1]);

            assertThat(shared.registry().queriesOnOwnLanes())
                    .as("every query hosted: before LANE-2, 992 of these went to lanes of their own")
                    .isZero();
            assertThat(perLane).hasSize(SHARED_LANES).allMatch(count -> count == QUERIES / SHARED_LANES);
            assertThat(own.registry().queriesOnOwnLanes()).isEqualTo(QUERIES);
            assertThat(shared.registry().sharedLaneBytes())
                    .as("eight lanes' inboxes and arenas against a thousand")
                    .isLessThan(ownLaneBytes / 50);
            assertThat(sharedCopies[1])
                    .as("one copy per row per shared lane, not one per query")
                    .isEqualTo(sharedCopies[0] * SHARED_LANES);
            assertThat(ownCopies[1]).isEqualTo(ownCopies[0] * QUERIES);
        } finally {
            shared.registry().close();
            own.registry().close();
        }
    }

    private static long pipelineArenas(Engine engine) {
        return engine.queries().stream()
                .mapToLong(query -> query.offHeapBytes().getOrDefault("pipeline-arena", 0L))
                .sum();
    }
}
