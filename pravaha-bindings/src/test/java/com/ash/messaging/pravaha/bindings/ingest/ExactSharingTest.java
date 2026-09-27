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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-054: a source promising exactly-once and order is shared, and each query still receives each
 * record once, in order. {@code rowsIn} is the measure rather than the view, because a view keyed by id
 * absorbs a duplicate that ingest counted.
 */
@Timeout(120)
class ExactSharingTest {

    private static final Principal DANA = new Principal("dana", "acme", java.util.Set.of("analyst"), Map.of());

    private static final String ASKS_ONE = "SELECT id, amount FROM log WHERE amount > 0";

    private static final String ASKS_ANOTHER = "SELECT id, amount FROM log WHERE amount > 1";

    @BeforeEach
    void reset() {
        OrderedLogPlugin.reset();
    }

    private static PluginSourceFeeds feeds(Map<String, String> options) {
        return new PluginSourceFeeds().bind(new SourceBinding("log", "ordered-log", options));
    }

    private static void appendRange(long fromId, long toId) {
        for (long id = fromId; id <= toId; id++) {
            OrderedLogPlugin.append(id, 10);
        }
    }

    @Test
    void aLateJoinerCatchesUpToTheSeamAndThenSharesWithNothingTwice() throws Exception {
        appendRange(1, 500);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, OrderedLogPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            awaitRowsIn(first, 500);

            // Joins behind a reader that has read everything: a catch-up from the beginning, bounded to
            // where the shared reader stands, then the fan-out. The catch-up is slow and the log grows
            // while it runs, so the seam moves under it -- the case where attaching before the seam
            // would hand this query the new records twice, once from each reader.
            OrderedLogPlugin.slowAfterFirst = 20;
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            appendRange(501, 800);
            awaitRowsIn(first, 800);
            awaitRowsIn(second, 800);
            Thread.sleep(200);

            assertThat(first.rowsIn())
                    .as("each record once, to the query that was first")
                    .isEqualTo(800);
            assertThat(second.rowsIn())
                    .as("each record once, to the query that caught up: the seam is exact, not an overlap")
                    .isEqualTo(800);
            assertThat(OrderedLogPlugin.OPEN.get())
                    .as("one reader once the catch-up has met the shared one")
                    .isEqualTo(1);

            // From the seam on, one read serves both. (Before it, the joiner's catch-up read up to wherever
            // the seam had moved to; that part is a private read of the gap, and is what joining late costs.)
            long before = OrderedLogPlugin.RECORDS_READ.get();
            appendRange(801, 1000);
            awaitRowsIn(first, 1000);
            awaitRowsIn(second, 1000);
            Thread.sleep(200);
            assertThat(OrderedLogPlugin.RECORDS_READ.get() - before)
                    .as("200 new records read once for two queries, not twice")
                    .isEqualTo(200);
            assertThat(first.rowsIn()).isEqualTo(1000);
            assertThat(second.rowsIn()).isEqualTo(1000);
        }
    }

    @Test
    void aPausedQueryResumesThroughAnExactCatchUpWhileTheOtherKeepsReading() throws Exception {
        appendRange(1, 100);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, OrderedLogPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRowsIn(first, 100);
            awaitRowsIn(second, 100);

            registry.pause("asks_one");
            Thread.sleep(100);
            appendRange(101, 300);
            awaitRowsIn(second, 300);
            assertThat(first.rowsIn()).as("paused means paused").isEqualTo(100);

            // A slow catch-up, and records arriving during it: the seam moves while the resumed query
            // chases it, which is where attaching early would hand it the new records twice.
            OrderedLogPlugin.slowAfterFirst = 20;
            registry.resume("asks_one");
            appendRange(301, 400);
            awaitRowsIn(first, 400);
            awaitRowsIn(second, 400);
            Thread.sleep(200);
            assertThat(first.rowsIn()).isEqualTo(400);
            assertThat(second.rowsIn()).isEqualTo(400);
            assertThat(OrderedLogPlugin.OPEN.get()).isEqualTo(1);
        }
    }

    /** Counts and sums, because a duplicate or a gap moves them; a keyed projection would absorb either. */
    private static final String COUNT_ONE = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM log WHERE amount > 0";

    private static final String COUNT_ANOTHER = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM log WHERE amount > 1";

    private static QueryRegistry checkpointing(ViewCatalog views, Path root) {
        return new QueryRegistry(views, OrderedLogPlugin.SCHEMA)
                .feedingFrom(feeds(Map.of()))
                .checkpointingTo(
                        root,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
    }

    @Test
    void aQueryRestoredAheadOfTheSharedReaderWaitsForItAndIsHandedNothingTwice(@TempDir Path root) throws Exception {
        appendRange(1, 100);
        try (QueryRegistry before = checkpointing(new ViewCatalog(), root)) {
            RegisteredQuery older = before.register("older", COUNT_ONE, List.of(0), DANA);
            RegisteredQuery newer = before.register("newer", COUNT_ANOTHER, List.of(0), DANA);
            awaitRowsIn(older, 100);
            awaitRowsIn(newer, 100);
            before.pause("older");
            Thread.sleep(100);
            appendRange(101, 300);
            awaitRowsIn(newer, 300);
            // Two checkpoints of one source at two positions: 100 and 300.
            LaneEquivalence.checkpointerOf(older).checkpointNow();
            LaneEquivalence.checkpointerOf(newer).checkpointNow();
        }

        OrderedLogPlugin.OPEN.set(0);
        // Slow, so the shared reader is still short of 300 when the newer query registers: without it the
        // reader crosses the gap in a millisecond and the newer query joins at its position, not ahead.
        OrderedLogPlugin.slowEvery = 10;
        try (QueryRegistry after = checkpointing(new ViewCatalog(), root)) {
            // The older first, so the shared reader starts at 100 and the newer is restored ahead of it.
            RegisteredQuery older = after.register("older", COUNT_ONE, List.of(0), DANA);
            RegisteredQuery newer = after.register("newer", COUNT_ANOTHER, List.of(0), DANA);
            appendRange(301, 400);
            awaitAnswer(older, 400);
            awaitAnswer(newer, 400);
            Thread.sleep(200);
            assertThat(answer(older))
                    .as("the older query: 100 restored, 200 of gap, 100 new")
                    .isEqualTo(400);
            assertThat(answer(newer))
                    .as("the newer query: 300 restored and the 100 new, and none of the gap it had already counted")
                    .isEqualTo(400);
            assertThat(OrderedLogPlugin.OPEN.get()).as("one reader for both").isEqualTo(1);
            // Proof the restart restored rather than re-read: what each ingested since.
            assertThat(older.rowsIn())
                    .as("restored at 100: the 200 of gap and 100 new")
                    .isEqualTo(300);
            assertThat(newer.rowsIn()).as("restored at 300: the 100 new only").isEqualTo(100);
        }
    }

    private static long answer(RegisteredQuery query) {
        query.commit();
        for (Object[] row : query.view().scan()) {
            return (Long) row[0];
        }
        return 0;
    }

    private static void awaitAnswer(RegisteredQuery query, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (answer(query) < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(query.name() + " counted " + answer(query) + ", expected " + expected);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void theSameSourceWithoutDeclaringAnOrderKeepsAReaderPerQuery() throws Exception {
        appendRange(1, 50);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, OrderedLogPlugin.SCHEMA)
                .feedingFrom(feeds(Map.of("declare.order", "false")))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRowsIn(first, 50);
            awaitRowsIn(second, 50);
            assertThat(OrderedLogPlugin.OPEN.get())
                    .as("exactly-once without an order cannot be shared (SRC-3's gate)")
                    .isEqualTo(2);
        }
    }

    private static void awaitRowsIn(RegisteredQuery query, long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (query.rowsIn() < atLeast) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(query.name() + " ingested " + query.rowsIn() + " rows, expected " + atLeast);
            }
            Thread.sleep(10);
        }
    }
}
