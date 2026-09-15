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
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The target, run rather than extrapolated to.
 *
 * <p>ADR-036 asks for thousands of continuous queries on one instance and says the wave is done
 * "when it can register thousands rather than hundreds inside a test JVM -- which is the same
 * statement as the target, made falsifiable". Every figure reported until now was measured at fifty
 * to two hundred queries and multiplied.
 *
 * <p>Multiplying is what this exists to stop. A thousand is where the things that do not scale
 * linearly show themselves: registration is milliseconds each and a thousand of them is a wait; a
 * thousand live views is a different heap; a runner's round-robin assignment is even by count and
 * says nothing about cost. None of that appears at two hundred.
 *
 * <p>Default sizing on purpose. An operator who has read `OPERATIONS.md` would size the inbox down
 * for this workload, and the point here is what happens to somebody who does not.
 */
@Timeout(900)
final class ThousandQueryTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final int QUERIES = 1_000;

    @Test
    void aThousandContinuousQueriesOnOneNode() {
        long threadsBefore = Thread.getAllStackTraces().keySet().size();
        long directBefore = directMemory();
        long heapBefore = usedHeapAfterGc();

        ViewCatalog views = new ViewCatalog();
        List<String> names = new ArrayList<>(QUERIES);
        try (QueryRegistry registry = new QueryRegistry(views, TXN);
                RowArena feeder = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {

            long start = System.nanoTime();
            for (int i = 0; i < QUERIES; i++) {
                String name = "q" + i;
                // Distinct SQL, so each is its own computation. A thousand copies of one query
                // shares one and would measure nothing at all.
                registry.register(
                        name,
                        "SELECT user_id, SUM(amount) AS total FROM txn WHERE amount > " + i
                                + " GROUP BY user_id, TUMBLE(ts, INTERVAL '1' SECOND)",
                        List.of(0),
                        DANA);
                names.add(name);
            }
            long registerMillis = (System.nanoTime() - start) / 1_000_000L;

            long threadsAfter = Thread.getAllStackTraces().keySet().size();
            long directAfter = directMemory();
            long heapAfter = usedHeapAfterGc();

            Map<String, Long> attributed = new java.util.LinkedHashMap<>();
            for (String name : names) {
                registry.require(name)
                        .offHeapBytes()
                        .forEach((part, bytes) -> attributed.merge(part, bytes, Long::sum));
            }

            System.out.printf(
                    "THOUSAND QUERIES on one node, default sizing:%n"
                            + "  registration: %d ms total, %.1f ms each%n"
                            + "  platform threads: %d -> %d (+%d) on %d cores%n"
                            + "  off-heap: %d MiB -> %d MiB (%d KiB per query)%n"
                            + "  heap after gc: %d MiB -> %d MiB (%d KiB per query)%n"
                            + "  attributed off-heap, per query:%n",
                    registerMillis,
                    registerMillis / (double) QUERIES,
                    threadsBefore,
                    threadsAfter,
                    threadsAfter - threadsBefore,
                    Runtime.getRuntime().availableProcessors(),
                    directBefore / (1024 * 1024),
                    directAfter / (1024 * 1024),
                    (directAfter - directBefore) / QUERIES / 1024,
                    heapBefore / (1024 * 1024),
                    heapAfter / (1024 * 1024),
                    (heapAfter - heapBefore) / QUERIES / 1024);
            attributed.forEach((part, bytes) -> System.out.printf("    %-16s %6d KiB%n", part, bytes / QUERIES / 1024));

            // Every one of them is a real, answerable query -- not a thousand registrations that
            // happened to be accepted.
            assertThat(registry.size())
                    .as("a thousand distinct questions are a thousand computations")
                    .isEqualTo(QUERIES);
            for (String name : List.of(names.get(0), names.get(QUERIES / 2), names.get(QUERIES - 1))) {
                RegisteredQuery query = registry.require(name);
                assertThat(query.state().isTerminal()).as("%s is alive", name).isFalse();
            }

            // And they answer. One row to the first and last, which is what distinguishes a
            // registered query from a running one.
            feedOneRow(registry, feeder, names.get(0));
            feedOneRow(registry, feeder, names.get(QUERIES - 1));
            assertThat(registry.require(names.get(0)).rowsIn()).isPositive();
            assertThat(registry.require(names.get(QUERIES - 1)).rowsIn()).isPositive();

            // The claim the wave rests on: threads follow cores, not registrations. Before ADR-027's
            // multiplexing this was one per query, so a thousand queries were a thousand threads.
            long cores = Runtime.getRuntime().availableProcessors();
            assertThat(threadsAfter - threadsBefore)
                    .as(
                            "a thousand queries added %d platform threads on a %d-core machine",
                            threadsAfter - threadsBefore, cores)
                    .isLessThanOrEqualTo(2 * cores + 8);

            // And memory. 1,328 KiB each was measured at fifty; this is the same number at a
            // thousand, which is the only way to know it was a rate and not a coincidence.
            assertThat((directAfter - directBefore) / QUERIES / 1024)
                    .as("off-heap per query at a thousand, against 1,328 KiB measured at fifty")
                    .isLessThanOrEqualTo(1500);
        }
    }

    @Test
    void aThousandQueriesSizedTheWayOperationsSaysTo() {
        // The other half, and the one an operator acts on. OPERATIONS.md tells a node holding many
        // narrow queries to size the inbox down, because 2048 cells of 512 bytes is burst capacity
        // a query fed by a once-a-second scan will never use. This is that advice, run.
        //
        // A recommendation nobody checked is how a default becomes folklore, and this project has
        // already found two of those.
        long directBefore = directMemory();

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN)
                .executingWith(
                        com.ash.messaging.pravaha.runtime.lane.LaneConfig.defaults()
                                .withInbox(256, 256)
                                .withWaitStrategy(com.ash.messaging.pravaha.common.queue.WaitStrategy.Kind.BACKOFF_PARK)
                                .withThreads("pravaha-query", true),
                        MemoryAccess.best())) {

            for (int i = 0; i < QUERIES; i++) {
                registry.register(
                        "s" + i,
                        "SELECT user_id, SUM(amount) AS total FROM txn WHERE amount > " + i
                                + " GROUP BY user_id, TUMBLE(ts, INTERVAL '1' SECOND)",
                        List.of(0),
                        DANA);
            }

            long eachKb = (directMemory() - directBefore) / QUERIES / 1024;
            System.out.printf(
                    "THOUSAND QUERIES sized for many: %d KiB per query, %d MiB for the thousand%n",
                    eachKb, (directMemory() - directBefore) / (1024 * 1024));

            assertThat(registry.size()).isEqualTo(QUERIES);
            assertThat(eachKb)
                    .as("a 256 x 256B inbox is 64 KiB, so a thousand queries is tens of megabytes rather "
                            + "than a gigabyte -- which is what OPERATIONS.md tells an operator and what "
                            + "nothing had checked")
                    .isLessThanOrEqualTo(128);
        }
    }

    private static void feedOneRow(QueryRegistry registry, RowArena feeder, String name) {
        RowLayout layout = RowLayout.of(registry.streams()[0]);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = feeder.allocate(layout.rowSize(64));
        writer.begin(feeder.regionOf(handle), feeder.offsetOf(handle));
        writer.setString(0, "u1").setLong(1, 5_000_000L).setLong(2, 500_000_000L);
        writer.weight(1L).eventTimestampNanos(500_000_000L).sequence(1L).commit();
        feeder.trimTo(handle, writer.sizeSoFar());
        registry.require(name).accept("txn", view.wrap(feeder.regionOf(handle), feeder.offsetOf(handle)));
        registry.require(name).awaitApplied(Duration.ofSeconds(30));
    }

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
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
