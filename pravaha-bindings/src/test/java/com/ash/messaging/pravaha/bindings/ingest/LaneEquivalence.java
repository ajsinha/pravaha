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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two engines fed the same source, one hosting every query on shared lanes and one giving each
 * query a lane of its own, driven through the same script and held to the same answers (LANE-2).
 *
 * <p>The answers are not compared only with each other. Both are compared with what the store
 * says, computed here from {@link CountingScanPlugin#STORE}: two engines that double-counted alike
 * would agree with each other and be wrong together. And every read is judged twice, the second a
 * moment later, because a double count is a number that has reached the right answer and is about
 * to pass it.
 *
 * <p>The script keeps the source still while it changes membership -- a pause, a resume, a join, a
 * restart -- because the shared reader hands a late joiner over from its catch-up read to the live
 * one with an overlap that is a duplicate by design (SRC-3's at-least-once gate). With the source
 * still, the overlap is empty and the answer is exact, on a lane of its own and on a shared one; a
 * difference between them is then this change's, not the handover's.
 */
final class LaneEquivalence implements AutoCloseable {

    static final Principal DANA = new Principal("dana", "public", java.util.Set.of("analyst"), Map.of());

    /** A query the script may register, and what its answer is over a list of records. */
    record Query(String name, String sql, boolean projection, Predicate<long[]> where) {

        /** Rows the view should hold, by the store: [n, total] for a count, [id, amount] for a projection. */
        List<List<Object>> expected(List<long[]> records) {
            Map<List<Long>, Long> net = new TreeMap<>(Comparator.comparing(Object::toString));
            long n = 0;
            long total = 0;
            for (long[] record : records) {
                if (!where.test(record)) {
                    continue;
                }
                long weight = record.length > 2 ? record[2] : 1L;
                n += weight;
                total += weight * record[1];
                net.merge(List.of(record[0], record[1]), weight, Long::sum);
            }
            List<List<Object>> rows = new ArrayList<>();
            if (projection) {
                net.forEach((row, weight) -> {
                    if (weight > 0) {
                        rows.add(List.of(row.get(0), row.get(1)));
                    }
                });
            } else if (n != 0) {
                rows.add(List.of(n, total));
            }
            rows.sort(Comparator.comparing(Object::toString));
            return rows;
        }
    }

    private static Query count(String name, String where, Predicate<long[]> test) {
        return new Query(name, "SELECT COUNT(*) AS n, SUM(amount) AS total FROM shared" + where, false, test);
    }

    /**
     * Different questions about one source: different SQL, so different computations. Counts and sums
     * because a duplicate moves them; one projection for the stateless path, whose keyed view would
     * absorb a duplicate and so proves only that nothing is lost.
     */
    static final List<Query> POOL = List.of(
            count("all_n", "", r -> true),
            count("big_n", " WHERE amount > 100", r -> r[1] > 100),
            count("small_n", " WHERE amount <= 100", r -> r[1] <= 100),
            count("mid_n", " WHERE amount > 50 AND amount < 300", r -> r[1] > 50 && r[1] < 300),
            count("user3_n", " WHERE user_id = 'u3'", r -> r[0] % 7 == 3),
            new Query("top_rows", "SELECT id, amount FROM shared WHERE amount > 300", true, r -> r[1] > 300));

    /** One engine: a registry, its feeds and where it checkpoints. */
    static final class Engine implements AutoCloseable {

        final String label;
        final int sharedLanes;
        final Path root;
        QueryRegistry registry;
        PluginSourceFeeds feeds;
        final Map<String, RegisteredQuery> live = new LinkedHashMap<>();

        Engine(String label, int sharedLanes, Path root) {
            this.label = label;
            this.sharedLanes = sharedLanes;
            this.root = root;
            open();
        }

        void open() {
            feeds = new PluginSourceFeeds().bind(new SourceBinding("shared", "counting-scan", Map.of()));
            registry = new QueryRegistry(new ViewCatalog(), CountingScanPlugin.SCHEMA)
                    .feedingFrom(feeds)
                    .checkpointingTo(
                            root,
                            Configuration.builder()
                                    .set("pravaha.checkpoint.interval", "1h")
                                    .set("pravaha.checkpoint.timeout", "10s")
                                    .build());
            if (sharedLanes > 0) {
                registry.multiplexingLanes(sharedLanes, 1_000);
            }
        }

        void register(Query query) {
            live.put(query.name(), registry.register(query.name(), query.sql(), List.of(0), DANA));
        }

        @Override
        public void close() {
            registry.close();
            live.clear();
        }
    }

    final Engine shared;
    final Engine own;
    private final List<Path> roots = new ArrayList<>();

    /** Where each live query stands: absent is running, a number is the store size it paused at. */
    final Map<String, Integer> pausedAt = new LinkedHashMap<>();

    final Map<String, Query> registered = new LinkedHashMap<>();

    LaneEquivalence(int sharedLanes) {
        try {
            Path a = Files.createTempDirectory("lane2-shared");
            Path b = Files.createTempDirectory("lane2-own");
            roots.add(a);
            roots.add(b);
            this.shared = new Engine("shared lanes", sharedLanes, a);
            this.own = new Engine("own lanes", 0, b);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    List<Engine> engines() {
        return List.of(shared, own);
    }

    void register(Query query) {
        registered.put(query.name(), query);
        engines().forEach(engine -> engine.register(query));
        awaitCatchUps();
    }

    /**
     * Waits for every query that joined behind the shared reader to finish reading its history, so
     * the source changes only once membership has stopped changing. A catch-up still open when rows
     * arrive reads them as well as the shared reader does: SRC-3's handover duplicate, the same on a
     * lane of its own as on a shared one, and not what this compares.
     */
    void awaitCatchUps() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (shared.feeds.catchUpsInFlight() + own.feeds.catchUpsInFlight() > 0) {
            assertThat(System.nanoTime())
                    .as("catch-up reads still open after 30s")
                    .isLessThan(deadline);
            sleep(5);
        }
    }

    void pause(String name) {
        settle();
        pausedAt.put(name, CountingScanPlugin.STORE.size());
        engines().forEach(engine -> engine.registry.pause(name));
    }

    void resume(String name) {
        pausedAt.remove(name);
        engines().forEach(engine -> engine.registry.resume(name));
        awaitCatchUps();
    }

    void drop(String name) {
        registered.remove(name);
        pausedAt.remove(name);
        engines().forEach(engine -> {
            engine.registry.drop(name);
            engine.live.remove(name);
        });
    }

    /**
     * Checkpoints every live query where it stands -- a paused one at the row it paused at -- closes
     * both engines, and registers everything again from those checkpoints.
     *
     * <p>Queries that were running come back first and the paused ones last: a query restored behind
     * the shared reader is caught up exactly, and one restored ahead of it would be handed the gap
     * twice, which is the handover SRC-3 accepts and not what this is testing.
     */
    void restart() {
        settle();
        for (Engine engine : engines()) {
            for (RegisteredQuery query : engine.live.values()) {
                checkpointerOf(query).checkpointNow();
            }
            engine.close();
            engine.open();
        }
        List<Query> order = new ArrayList<>(registered.values());
        order.sort(Comparator.comparing(query -> pausedAt.containsKey(query.name())));
        pausedAt.clear();
        for (Query query : order) {
            engines().forEach(engine -> engine.register(query));
        }
        awaitCatchUps();
    }

    /** What the store says {@code name} should answer now. */
    List<List<Object>> expected(String name) {
        List<long[]> store = List.copyOf(CountingScanPlugin.STORE);
        Integer paused = pausedAt.get(name);
        return registered.get(name).expected(paused == null ? store : store.subList(0, paused));
    }

    /**
     * Waits for every query in both engines to reach the store's answer, then judges them all again
     * a moment later, so a count on its way past the answer is caught rather than read in passing.
     */
    void settle() {
        for (String name : registered.keySet()) {
            for (Engine engine : engines()) {
                awaitAnswer(engine, name);
            }
        }
        sleep(150);
        for (String name : registered.keySet()) {
            for (Engine engine : engines()) {
                RegisteredQuery query = engine.live.get(name);
                query.commit();
                assertThat(rows(query))
                        .as("%s on %s, judged again after it had settled", name, engine.label)
                        .isEqualTo(expected(name));
            }
            assertThat(rows(shared.live.get(name)))
                    .as("%s must answer on a shared lane exactly as on a lane of its own", name)
                    .isEqualTo(rows(own.live.get(name)));
        }
    }

    private void awaitAnswer(Engine engine, String name) {
        RegisteredQuery query = engine.live.get(name);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<List<Object>> seen = List.of();
        while (System.nanoTime() < deadline) {
            query.commit();
            seen = rows(query);
            if (seen.equals(expected(name))) {
                return;
            }
            sleep(10);
        }
        assertThat(seen)
                .as("%s on %s never reached the store's answer", name, engine.label)
                .isEqualTo(expected(name));
    }

    /** The view's rows, sorted, as lists; a count of zero is a row the store says is not there. */
    static List<List<Object>> rows(RegisteredQuery query) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : query.view().scan()) {
            // Arrays.asList, not List.of: a SUM over no non-null value is NULL (ALLNULLAGG-1).
            List<Object> values = java.util.Arrays.asList(row);
            // The first column is a count's n, or a projection's id -- which is never zero.
            if (values.get(0) instanceof Long count && count == 0L) {
                continue;
            }
            rows.add(values);
        }
        rows.sort(Comparator.comparing(Object::toString));
        return rows;
    }

    static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static StreamSchema schema() {
        return CountingScanPlugin.SCHEMA;
    }

    @Override
    public void close() {
        engines().forEach(Engine::close);
        for (Path root : roots) {
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            } catch (java.io.IOException e) {
                // A temporary directory left behind is not a test failure.
            }
        }
    }
}
