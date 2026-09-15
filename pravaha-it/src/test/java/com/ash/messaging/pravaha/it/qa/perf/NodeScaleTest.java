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
package com.ash.messaging.pravaha.it.qa.perf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a registered query costs a node, measured rather than reasoned about.
 *
 * <p>The target is thousands of continuous queries on one instance. Nothing in this repository knew
 * what one query cost, so nothing could say how far away that was -- the only number on the subject
 * was {@code QueryRegistry}'s "fine at tens", written from a CPU measurement and silent about memory
 * and threads.
 *
 * <p>This is the baseline and the ratchet. It registers a realistic number of distinct continuous
 * queries, reports the per-query cost in the units that bind, and fails if the worst of them --
 * platform threads -- gets worse. It deliberately does not assert a memory figure: heap after a GC
 * is a measurement of the JVM's mood as much as the engine's, and a ratchet on a noisy number is a
 * ratchet somebody will delete.
 *
 * <p><strong>Distinct SQL per query on purpose.</strong> Identical SQL shares one computation by
 * fingerprint, so a thousand copies of one query costs what one costs and would measure nothing.
 */
@Timeout(600)
final class NodeScaleTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    /**
     * How many queries to register.
     *
     * <p>Not thousands: at the cost this measures, thousands does not fit in a test JVM, which is
     * itself the finding. Two hundred is enough to make the per-query cost unambiguous and small
     * enough to run in the normal suite.
     */
    private static final int QUERIES = 200;

    /**
     * Retained for the report line only; the assertion is an absolute bound on threads.
     *
     * <p>Platform threads per query that this build is allowed to cost.
     *
     * <p>A ratchet, and it may only fall. **Measured at exactly 1.00**: the lane. The feed is virtual
     * since W9-2, and the two schedulers W9-3 records -- the watermark clock and the checkpointer --
     * do not start in this fixture, because neither `generatingWatermarks` nor `checkpointingTo` is
     * configured. A node that enables both pays three, so this number is the floor for a registry
     * and not the whole story for a server; the 1.00 is what the lane costs and what ADR-027's
     * multiplexing has to remove.
     *
     * <p>Set at the measured value rather than a hoped-for one. A ceiling nobody meets is a ceiling
     * that gets raised.
     */
    private static final double PLATFORM_THREADS_PER_QUERY = 1.0;

    @Test
    void whatOneRegisteredQueryCosts() {
        long threadsBefore = platformThreads();
        long heapBefore = usedHeapAfterGc();

        ViewCatalog views = new ViewCatalog();
        List<String> names = new ArrayList<>();
        try (QueryRegistry registry = new QueryRegistry(views, TXN)) {
            long start = System.nanoTime();
            for (int i = 0; i < QUERIES; i++) {
                // Distinct SQL per query: the threshold differs, so each is its own computation.
                // Identical text would share one and measure nothing.
                String name = "q" + i;
                registry.register(
                        name,
                        "SELECT user_id, SUM(amount) AS total FROM txn WHERE amount > " + i
                                + " GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)",
                        List.of(0),
                        DANA);
                names.add(name);
            }
            long registerMillis = (System.nanoTime() - start) / 1_000_000L;

            long threadsDuring = platformThreads();
            long heapDuring = usedHeapAfterGc();
            double threadsEach = (threadsDuring - threadsBefore) / (double) QUERIES;
            long heapEachKb = (heapDuring - heapBefore) / QUERIES / 1024;

            // Reported, not asserted, except for the thread ratchet. These numbers are the point of
            // the test: whoever is asked "how many queries does one node hold" should find an
            // answer here rather than an opinion.
            System.out.printf(
                    "NODE SCALE: %d distinct continuous queries registered in %d ms%n"
                            + "  platform threads: %d -> %d (%.2f per query)%n"
                            + "  heap after gc:    %d KiB -> %d KiB (%d KiB per query)%n"
                            + "  off-heap per query is not measured here: one 4 MiB arena slab eager,%n"
                            + "  growing to 8, plus a 2048 x 512B inbox -- about 5 MiB idle, by configuration%n",
                    QUERIES,
                    registerMillis,
                    threadsBefore,
                    threadsDuring,
                    threadsEach,
                    heapBefore / 1024,
                    heapDuring / 1024,
                    heapEachKb);

            // Bounded by cores, not by queries -- which is the whole of ADR-027 and the difference
            // between holding tens and holding thousands. Asserted as an absolute rather than a
            // ratio on purpose: a per-query ratio passes trivially by registering more queries, and
            // would have been satisfied by the very design this replaced.
            long cores = Runtime.getRuntime().availableProcessors();
            assertThat(threadsDuring - threadsBefore)
                    .as(
                            "%d queries added %d platform threads (%.2f each) on a %d-core machine. Before "
                                    + "ADR-027's multiplexing this was exactly one per query; it is now the "
                                    + "runner's fixed set, and registering ten times as many adds none",
                            QUERIES, threadsDuring - threadsBefore, threadsEach, cores)
                    .isLessThanOrEqualTo(2 * cores);

            // Registered is not enough: a query that cannot answer is not a query. Spot-check the
            // ends rather than all of them, because reading every view is a different test.
            assertThat(registry.find(names.get(0))).isPresent();
            assertThat(registry.find(names.get(QUERIES - 1))).isPresent();
            assertThat(registry.size())
                    .as("each distinct question is its own computation")
                    .isEqualTo(QUERIES);
        }
    }

    private static long platformThreads() {
        return Thread.getAllStackTraces().keySet().size();
    }

    private static long usedHeapAfterGc() {
        System.gc();
        try {
            Thread.sleep(Duration.ofMillis(200).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
