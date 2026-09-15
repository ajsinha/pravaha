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
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
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

    /** Fewer, because each is fed a row and the point is the per-query cost, not the count. */
    private static final int ACTIVE_QUERIES = 50;

    /**
     * Retained for the report line only; the assertion is an absolute bound on threads.
     *
     * <p>Platform threads per query that this build is allowed to cost.
     *
     * <p>A ratchet, and it may only fall. Measured at exactly 1.00 when written -- the lane -- with
     * the feed already virtual (W9-2). This fixture now turns watermarks on, so it also covers the
     * clock that W9-3 moved to a shared timer; what it still does not cover is checkpointing, which
     * needs a directory.
     *
     * <p>Set at the measured value rather than a hoped-for one. A ceiling nobody meets is a ceiling
     * that gets raised.
     */
    private static final double PLATFORM_THREADS_PER_QUERY = 1.0;

    @Test
    void whatOneRegisteredQueryCosts() {
        long threadsBefore = platformThreads();
        long heapBefore = usedHeapAfterGc();
        long directBefore = directMemory();

        ViewCatalog views = new ViewCatalog();
        List<String> names = new ArrayList<>();
        // Watermarks on, because that is what a server does and it is where the second per-query
        // thread used to be. Measuring without them measured the easy case (W9-3).
        try (QueryRegistry registry =
                new QueryRegistry(views, TXN).generatingWatermarks(Duration.ofSeconds(30), Duration.ofSeconds(1))) {
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
            long directDuring = directMemory();
            long directEachKb = (directDuring - directBefore) / QUERIES / 1024;
            double threadsEach = (threadsDuring - threadsBefore) / (double) QUERIES;
            long heapEachKb = (heapDuring - heapBefore) / QUERIES / 1024;

            // Reported, not asserted, except for the thread ratchet. These numbers are the point of
            // the test: whoever is asked "how many queries does one node hold" should find an
            // answer here rather than an opinion.
            System.out.printf(
                    "NODE SCALE: %d distinct continuous queries registered in %d ms%n"
                            + "  platform threads: %d -> %d (%.2f per query)%n"
                            + "  heap after gc:    %d KiB -> %d KiB (%d KiB per query)%n"
                            + "  off-heap:         %d KiB -> %d KiB (%d KiB per query)%n"
                            + "  the arena's first slab is allocated by the first row, not at registration,%n"
                            + "  so an idle query holds its inbox and no arena at all%n",
                    QUERIES,
                    registerMillis,
                    threadsBefore,
                    threadsDuring,
                    threadsEach,
                    heapBefore / 1024,
                    heapDuring / 1024,
                    heapEachKb,
                    directBefore / 1024,
                    directDuring / 1024,
                    directEachKb);

            // The wall ADR-036 names. Measured rather than computed from configuration, because the
            // configuration says 5 MiB a query and what a query actually holds is the question.
            assertThat(directEachKb)
                    .as(
                            "%d queries hold %d KiB off-heap each. The arena's first slab is allocated by "
                                    + "the first row now, so an idle query should hold its inbox and nothing else "
                                    + "-- a 4 MiB eager slab each is what put a thousand queries at ~5 GB",
                            QUERIES, directEachKb)
                    .isLessThan(4 * 1024);

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

    @Test
    void whatOneQueryCostsOnceRowsArrive() {
        // The number that matters for the stated workload. An Aerospike-backed continuous query
        // scans once a second, so it is not idle -- it receives rows, and a query that has received
        // a row has an arena. W9-6's lazy slab does nothing for it; what it holds is decided by how
        // that slab is sized.
        //
        // 1,328 KiB, and fully attributed: 1,024 inbox, 284 pipeline arena, 20 the feeder this test
        // uses. The lane's own arena is zero -- a projection never allocates one, because its output
        // goes to the view rather than through the lane's scratch.
        //
        // It was 2,068 until the attribution existed. Half of it was a *second* arena, the
        // pipeline's own, hardcoded to a flat megabyte for every plan and invisible to the lane's
        // accounting -- which is why the first attempt at sizing the lane's arena moved the number
        // not at all, and why that attempt was reverted rather than shipped (W9-7).
        long directBefore = directMemory();

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN);
                RowArena feeder = new RowArena(MemoryAccess.best(), 1 << 20, 4)) {
            for (int i = 0; i < ACTIVE_QUERIES; i++) {
                registry.register("a" + i, "SELECT user_id, amount FROM txn WHERE amount > " + i, List.of(0), DANA);
            }
            for (int i = 0; i < ACTIVE_QUERIES; i++) {
                feedOneRow(registry, feeder, "a" + i);
            }

            long directAfter = directMemory();
            long eachKb = (directAfter - directBefore) / ACTIVE_QUERIES / 1024;

            // The same number with names in it. A pool total says how much; only the engine can say
            // which part of it holds what, and not being able to ask is what left W9-7 with a
            // thousand unexplained kilobytes.
            java.util.Map<String, Long> attributed = new java.util.LinkedHashMap<>();
            for (int i = 0; i < ACTIVE_QUERIES; i++) {
                registry.require("a" + i)
                        .offHeapBytes()
                        .forEach((part, bytes) -> attributed.merge(part, bytes, Long::sum));
            }
            long attributedTotal =
                    attributed.values().stream().mapToLong(Long::longValue).sum();

            System.out.printf(
                    "ACTIVE SCALE: %d queries, one row each%n"
                            + "  buffer pool: %d KiB -> %d KiB (%d KiB per query)%n"
                            + "  the engine's own attribution, per query:%n",
                    ACTIVE_QUERIES, directBefore / 1024, directAfter / 1024, eachKb);
            attributed.forEach(
                    (part, bytes) -> System.out.printf("    %-10s %6d KiB%n", part, bytes / ACTIVE_QUERIES / 1024));
            System.out.printf(
                    "    %-10s %6d KiB   (pool total minus what the engine claims)%n",
                    "unattributed", (directAfter - directBefore - attributedTotal) / ACTIVE_QUERIES / 1024);

            assertThat(eachKb)
                    .as(
                            "%d queries that have each received a row hold %d KiB each, so a thousand of them "
                                    + "is about %d GB -- the number ADR-036's target has to live within",
                            ACTIVE_QUERIES, eachKb, eachKb * 1000 / (1024 * 1024))
                    .isLessThanOrEqualTo(1400);
        }
    }

    @Test
    void whatAWindowedAggregateCostsOnceRowsArrive() {
        // The shape the owner's workload actually is: GROUP BY over an Aerospike scan, not a
        // projection. It matters here because the two use memory differently -- a projection's
        // output goes straight to the view and never touches the lane's arena, which is why that
        // arena measured zero and why sizing it moved nothing (W9-7).
        long directBefore = directMemory();

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN);
                RowArena feeder = new RowArena(MemoryAccess.best(), 1 << 20, 4)) {
            for (int i = 0; i < ACTIVE_QUERIES; i++) {
                registry.register(
                        "w" + i,
                        "SELECT user_id, SUM(amount) AS total FROM txn WHERE amount > " + i
                                + " GROUP BY user_id, TUMBLE(ts, INTERVAL '1' SECOND)",
                        List.of(0),
                        DANA);
            }
            for (int i = 0; i < ACTIVE_QUERIES; i++) {
                feedOneRow(registry, feeder, "w" + i);
            }

            long eachKb = (directMemory() - directBefore) / ACTIVE_QUERIES / 1024;
            java.util.Map<String, Long> attributed = new java.util.LinkedHashMap<>();
            for (int i = 0; i < ACTIVE_QUERIES; i++) {
                registry.require("w" + i)
                        .offHeapBytes()
                        .forEach((part, bytes) -> attributed.merge(part, bytes, Long::sum));
            }

            System.out.printf(
                    "WINDOWED SCALE: %d aggregates, one row each -- %d KiB per query%n", ACTIVE_QUERIES, eachKb);
            attributed.forEach(
                    (part, bytes) -> System.out.printf("    %-16s %6d KiB%n", part, bytes / ACTIVE_QUERIES / 1024));

            assertThat(eachKb)
                    .as(
                            "a windowed aggregate holds %d KiB once rows arrive, against a projection's 1,328. "
                                    + "A thousand of them is about %d GB",
                            eachKb, eachKb * 1000 / (1024 * 1024))
                    .isLessThanOrEqualTo(2048);
        }
    }

    /** Pushes one row into {@code name} and waits for the lane to have applied it. */
    private static void feedOneRow(QueryRegistry registry, RowArena feeder, String name) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = feeder.allocate(layout.rowSize(64));
        writer.begin(feeder.regionOf(handle), feeder.offsetOf(handle));
        writer.setString(0, "u1").setLong(1, 1_000_000L).setLong(2, 1_000L);
        writer.weight(1L).eventTimestampNanos(1_000L).sequence(1L).commit();
        feeder.trimTo(handle, writer.sizeSoFar());
        registry.require(name).accept("txn", view.wrap(feeder.regionOf(handle), feeder.offsetOf(handle)));
        registry.require(name).awaitApplied(Duration.ofSeconds(10));
        feeder.resetTo(feeder.mark());
    }

    private static long platformThreads() {
        return Thread.getAllStackTraces().keySet().size();
    }

    /** Off-heap this JVM holds, from the buffer pool the arena and the inbox allocate from. */
    private static long directMemory() {
        return java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)
                .stream()
                .filter(pool -> "direct".equals(pool.getName()))
                .mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed)
                .sum();
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
