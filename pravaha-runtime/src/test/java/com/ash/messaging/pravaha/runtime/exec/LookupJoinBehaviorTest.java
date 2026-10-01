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
package com.ash.messaging.pravaha.runtime.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.LookupJoinOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code LookupJoin}'s concurrency, caching and staleness behaviour, exercised directly (harness
 * HJ5's counterpart for the lookup operator) rather than through the registry or a live plugin.
 *
 * <p>Covers the cases in {@code docs/project/qa/cases/JOIN.md} §4 that need to watch the operator's own
 * counters -- {@code peakInFlight()}, {@code coalescedCount()}, {@code lookupCount()},
 * {@code cacheHitCount()} -- which only a test inside {@code com.ash.messaging.pravaha.runtime.exec}
 * can read.
 */
class LookupJoinBehaviorTest {

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    private final List<Object[]> out = new ArrayList<>();

    @AfterEach
    void tearDown() {
        arena.close();
    }

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.int64())
                .build();
    }

    private static StreamSchema dim() {
        return StreamSchema.builder("dim")
                .field("user_id", Types.int64())
                .field("tier", Types.string())
                .build();
    }

    private static StreamSchema mergedOutput() {
        return StreamSchema.builder("out")
                .field("order_id", Types.int64())
                .field("user_id", Types.int64())
                .field("d_user_id", Types.int64().withNullable(true))
                .field("tier", Types.string().withNullable(true))
                .build();
    }

    private static LookupJoinOperator plan(boolean leftOuter) {
        return new LookupJoinOperator(
                ScanOperator.of("orders", orders()), "dim", List.of(1), dim(), mergedOutput(), leftOuter);
    }

    private void feed(LookupJoin join, long orderId, long userId, long at) {
        RowLayout layout = RowLayout.of(orders());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, orderId).setLong(1, userId);
        writer.weight(1L).eventTimestampNanos(at).sequence(orderId).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        join.process(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    /**
     * {@code LookupJoin} is package-private and not {@code AutoCloseable} (only {@code close()},
     * called by its owning pipeline) -- so a plain build-run-close, rather than try-with-resources,
     * is what this harness uses.
     */
    private LookupJoin newJoin(LookupJoinOperator plan, LookupSourcePlugin source, int maxCacheEntries) {
        return new LookupJoin(plan, source, arena, collector(), maxCacheEntries);
    }

    private RowProcessor collector() {
        return row -> {
            Object[] values = new Object[4];
            values[0] = row.getLong(0);
            values[1] = row.getLong(1);
            values[2] = row.isNull(2) ? null : row.getLong(2);
            values[3] = row.isNull(3) ? null : row.getString(3);
            out.add(values);
        };
    }

    // ======================= JOIN-049: the lookup is as of *now*, not the event's time =======================

    /** A dimension whose answer for one key changes on the second and later calls. */
    private static final class ChangingLookup implements LookupSourcePlugin {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public StreamSchema schema() {
            return dim();
        }

        @Override
        public List<String> keyColumns() {
            return List.of("user_id");
        }

        @Override
        public int lookup(Object[] key, PartitionReader.RecordSink sink) {
            int n = calls.incrementAndGet();
            sink.beginRow()
                    .setLong(0, ((Number) key[0]).longValue())
                    .setString(1, n == 1 ? "gold" : "platinum")
                    .commit();
            return 1;
        }

        @Override
        public Duration cacheFor() {
            return Duration.ZERO;
        }

        @Override
        public String name() {
            return "changing";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}
    }

    @Test
    void thePeriodTheSyntaxNamesIsNotHonoured() {
        // Three orders for the same key, fed in an order where the third's event time (2s) falls
        // between the first's (1s) and second's (3s) -- and still gets the later value, because the
        // lookup answers by wall-clock arrival order, not by the event time the syntax names.
        ChangingLookup dim = new ChangingLookup();
        LookupJoin join = newJoin(plan(false), dim, 64);
        try {
            // Each feed is drained before the next: the plugin's calls are dispatched to a virtual
            // thread and complete asynchronously, so without forcing each one to finish first their
            // execution order (and so which one sees calls==1) is a race, not the arrival order the
            // case is about.
            feed(join, 1L, 1L, 1_000_000_000L); // event time 1s -> "gold" (first call)
            join.drain();
            feed(join, 3L, 1L, 3_000_000_000L); // event time 3s -> "platinum" (second call)
            join.drain();
            feed(join, 5L, 1L, 2_000_000_000L); // event time 2s, between the two -- still "platinum"
            join.drain();
        } finally {
            join.close();
        }
        assertThat(out.stream().map(row -> row[0] + ":" + row[3]).toList())
                .containsExactly("1:gold", "3:platinum", "5:platinum");
        assertThat(dim.calls)
                .as("three separate lookups, not one cached answer")
                .hasValue(3);
    }

    // ======================= JOIN-051: in-flight lookups are bounded by maxConcurrency =======================

    /** A dimension whose lookups are slow and whose concurrency the operator must respect. */
    private static final class SlowBoundedLookup implements LookupSourcePlugin {
        private final int maxConcurrency;
        private final long sleepMillis;
        private final AtomicInteger current = new AtomicInteger();
        private final AtomicInteger observedPeak = new AtomicInteger();

        SlowBoundedLookup(int maxConcurrency, long sleepMillis) {
            this.maxConcurrency = maxConcurrency;
            this.sleepMillis = sleepMillis;
        }

        @Override
        public StreamSchema schema() {
            return dim();
        }

        @Override
        public List<String> keyColumns() {
            return List.of("user_id");
        }

        @Override
        public int lookup(Object[] key, PartitionReader.RecordSink sink) {
            int now = current.incrementAndGet();
            observedPeak.updateAndGet(peak -> Math.max(peak, now));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                current.decrementAndGet();
            }
            sink.beginRow()
                    .setLong(0, ((Number) key[0]).longValue())
                    .setString(1, "seg")
                    .commit();
            return 1;
        }

        @Override
        public int maxConcurrency() {
            return maxConcurrency;
        }

        @Override
        public String name() {
            return "slow-bounded";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}
    }

    @Test
    void inFlightLookupsAreBoundedByTheSourcesMaxConcurrency() {
        SlowBoundedLookup dim = new SlowBoundedLookup(4, 50);
        LookupJoin join = newJoin(plan(false), dim, 200);
        long start = System.nanoTime();
        try {
            for (int i = 0; i < 100; i++) {
                feed(join, i, i, i); // 100 distinct keys, so nothing is a cache hit or a coalesce
            }
        } finally {
            join.close();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(out).hasSize(100);
        assertThat(dim.observedPeak.get())
                .as("the source's own concurrency counter, measured independently of the operator's")
                .isLessThanOrEqualTo(4);
        // 100 lookups at 4 concurrent x 50ms is ~1.25s; serialised it would be 5s. A generous bound
        // catches a regression to serial execution without being timing-flaky.
        assertThat(elapsed)
                .as("100 lookups took %s; serialised at 50ms each would be 5s", elapsed)
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aZeroOrNegativeMaxConcurrencyFloorsAtOne() {
        SlowBoundedLookup dim = new SlowBoundedLookup(0, 10);
        LookupJoin join = newJoin(plan(false), dim, 20);
        try {
            for (int i = 0; i < 5; i++) {
                feed(join, i, i, i);
            }
        } finally {
            join.close();
        }
        assertThat(out).hasSize(5);
        assertThat(dim.observedPeak.get()).as("maxInFlight = max(1, 0) = 1").isEqualTo(1);
    }

    // ======================= JOIN-052: cacheFor() == ZERO disables both caching and coalescing =======================

    /** A dimension whose calls are counted per key, with a chosen sleep so concurrent arrivals overlap. */
    private static final class CountingLookup implements LookupSourcePlugin {
        private final Duration cacheFor;
        private final long sleepMillis;
        final Map<Long, AtomicLong> callsPerKey = new ConcurrentHashMap<>();
        final AtomicInteger totalCalls = new AtomicInteger();

        CountingLookup(Duration cacheFor, long sleepMillis) {
            this.cacheFor = cacheFor;
            this.sleepMillis = sleepMillis;
        }

        @Override
        public StreamSchema schema() {
            return dim();
        }

        @Override
        public List<String> keyColumns() {
            return List.of("user_id");
        }

        @Override
        public int lookup(Object[] key, PartitionReader.RecordSink sink) {
            long k = ((Number) key[0]).longValue();
            callsPerKey.computeIfAbsent(k, ignored -> new AtomicLong()).incrementAndGet();
            totalCalls.incrementAndGet();
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            sink.beginRow().setLong(0, k).setString(1, "seg").commit();
            return 1;
        }

        @Override
        public Duration cacheFor() {
            return cacheFor;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}
    }

    @Test
    void aCacheableSourceCoalescesConcurrentRequestsForOneKey() {
        CountingLookup dim = new CountingLookup(Duration.ofSeconds(60), 100);
        LookupJoin join = newJoin(plan(false), dim, 64);
        try {
            for (int i = 0; i < 5; i++) {
                feed(join, i, 1L, i); // five records, all keyed u1, fed faster than the 100ms lookup
            }
        } finally {
            join.close();
        }
        assertThat(out).hasSize(5);
        assertThat(dim.totalCalls)
                .as("one call to the store for five records on the same key")
                .hasValue(1);
        assertThat(join.lookupCount()).isEqualTo(1);
        assertThat(join.coalescedCount())
                .as("the other four joined the lookup already running rather than starting their own")
                .isEqualTo(4);
    }

    @Test
    void aSourceRefusingCachingIsAskedOncePerRecordEvenForOneKey() {
        CountingLookup dim = new CountingLookup(Duration.ZERO, 100);
        LookupJoin join = newJoin(plan(false), dim, 64);
        try {
            for (int i = 0; i < 5; i++) {
                feed(join, i, 1L, i);
            }
        } finally {
            join.close();
        }
        assertThat(out).hasSize(5);
        assertThat(dim.totalCalls)
                .as("cacheFor() == ZERO means no answer is shared between concurrent records either")
                .hasValue(5);
        assertThat(join.lookupCount()).isEqualTo(5);
        assertThat(join.coalescedCount()).isZero();
        assertThat(join.cacheHitCount()).isZero();
    }

    // ======================= JOIN-053: the lookup cache is bounded and access-ordered =======================

    @Test
    void aHotKeyKeptWarmByInterleavedAccessSurvivesAFloodOfColdKeys() {
        // maxCacheEntries = 10. u1, then 20 cold keys with u1 revisited every third one, then u1
        // again: the interleaving keeps u1 at the front of the access order, so it survives.
        CountingLookup dim = new CountingLookup(Duration.ofMinutes(1), 0);
        LookupJoin join = newJoin(plan(false), dim, 10);
        try {
            feed(join, 0, 1L, 0);
            join.drain(); // u1's first lookup is cached before anything races it
            for (int i = 0; i < 20; i++) {
                feed(join, i + 1, 100L + i, i + 1);
                if (i % 3 == 0) {
                    feed(join, 1000 + i, 1L, 1000 + i);
                }
            }
            feed(join, 9999, 1L, 9999);
        } finally {
            join.close();
        }
        assertThat(dim.callsPerKey.get(1L))
                .as("u1 was asked once and every later reference was a cache hit, because interleaving "
                        + "kept it at the front of the access-ordered cache")
                .hasValue(1);
    }

    @Test
    void aHotKeyNotRevisitedIsEvictedByTwentyColdKeysPastABoundOfTen() {
        CountingLookup dim = new CountingLookup(Duration.ofMinutes(1), 0);
        LookupJoin join = newJoin(plan(false), dim, 10);
        try {
            feed(join, 0, 1L, 0);
            join.drain(); // u1's first lookup is cached before anything races it
            for (int i = 0; i < 20; i++) {
                feed(join, i + 1, 100L + i, i + 1);
            }
            feed(join, 9999, 1L, 9999);
        } finally {
            join.close();
        }
        assertThat(dim.callsPerKey.get(1L))
                .as("20 cold keys past a cache of 10 pushed u1 out, so the last reference is a second miss")
                .hasValue(2);
    }
}
