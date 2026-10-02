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
package com.ash.messaging.pravaha.it.qa.adversarial;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

import static org.assertj.core.api.Assertions.assertThat;

/** QE-066..QE-075 (queries on queries) and QE-162 (a push one query cannot apply). */
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvChainTest {

    static final String UP = "CREATE CONTINUOUS QUERY up KEYED BY (id) AS SELECT id, g, v FROM src WHERE v > 0";
    static final String MID = "CREATE CONTINUOUS QUERY mid KEYED BY (g) AS SELECT g, SUM(v) AS s, COUNT(*) AS c, "
            + "AVG(v) AS a FROM up GROUP BY g";
    static final String TOP = "CREATE CONTINUOUS QUERY top KEYED BY (g) AS SELECT g, s FROM mid WHERE s > 50";

    /** The keyed view's documented rule: each key shows the present row that most recently gained weight. */
    static final class KeyedOracle {
        final Map<Long, LinkedHashMap<List<Object>, Long>> keys = new TreeMap<>();

        void apply(Object[] row, long weight) {
            if ((Long) row[2] <= 0) {
                return; // the WHERE v > 0 upstream of the view
            }
            List<Object> values = Arrays.asList(row);
            LinkedHashMap<List<Object>, Long> rows = keys.computeIfAbsent((Long) row[0], k -> new LinkedHashMap<>());
            Long had = rows.remove(values);
            long now = (had == null ? 0 : had) + weight;
            if (weight > 0) {
                rows.put(values, now);
            } else if (now > 0) {
                // A retraction does not make a row the shown one: put it back where it was.
                LinkedHashMap<List<Object>, Long> rebuilt = new LinkedHashMap<>();
                rows.forEach(rebuilt::put);
                rows.clear();
                rows.put(values, now);
                rows.putAll(rebuilt);
            }
            if (rows.isEmpty()) {
                keys.remove((Long) row[0]);
            }
        }

        TreeSet<String> up() {
            TreeSet<String> out = new TreeSet<>();
            keys.forEach((id, rows) -> {
                List<Object> shown = rows.lastEntry().getKey();
                out.add(shown.get(0) + "|" + shown.get(1) + "|" + shown.get(2));
            });
            return out;
        }

        TreeSet<String> mid() {
            Map<String, long[]> groups = new TreeMap<>();
            keys.values().forEach(rows -> {
                List<Object> shown = rows.lastEntry().getKey();
                long[] acc = groups.computeIfAbsent((String) shown.get(1), g -> new long[2]);
                acc[0] += (Long) shown.get(2);
                acc[1]++;
            });
            TreeSet<String> out = new TreeSet<>();
            groups.forEach((g, acc) -> out.add(g + "|" + acc[0] + "|" + acc[1] + "|" + acc[0] / acc[1]));
            return out;
        }

        TreeSet<String> top() {
            TreeSet<String> out = new TreeSet<>();
            for (String row : mid()) {
                String[] cells = row.split("\\|");
                if (Long.parseLong(cells[1]) > 50) {
                    out.add(cells[0] + "|" + cells[1]);
                }
            }
            return out;
        }
    }

    /** Polls until each view equals the oracle's, or gives up and returns the difference. */
    static String await(PravahaEngine engine, KeyedOracle oracle) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            TreeSet<String> up = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM up"));
            TreeSet<String> mid = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM mid"));
            TreeSet<String> top = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM top"));
            if (up.equals(oracle.up()) && mid.equals(oracle.mid()) && top.equals(oracle.top())) {
                return null;
            }
            last = "up " + up + " vs " + oracle.up() + "; mid " + mid + " vs " + oracle.mid() + "; top " + top + " vs "
                    + oracle.top();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }
        return last;
    }

    static PravahaEngine chain(Map<String, String> settings) {
        PravahaEngine engine =
                AdvSupport.engine(settings, e -> e.declareStream("src", "id:INT64,g:STRING,v:INT64", null));
        if (engine.find("up").isEmpty()) {
            engine.query(UP);
            engine.query(MID);
            engine.query(TOP);
        }
        return engine;
    }

    /** One random step, applied to the engine and the oracle alike. */
    static String step(Random random, PravahaEngine engine, KeyedOracle oracle, List<Object[]> live, int op) {
        int kind = random.nextInt(4);
        if (kind == 0 && !live.isEmpty()) {
            Object[] gone = live.remove(random.nextInt(live.size()));
            engine.retract("src", gone);
            oracle.apply(gone, -1);
            return "retract " + Arrays.toString(gone);
        }
        long id = kind == 1 || live.isEmpty() ? 1000 + op : (Long) live.get(random.nextInt(live.size()))[0];
        Object[] row = {id, "g" + random.nextInt(3), (long) (random.nextInt(41) - 10)};
        if (kind == 2 && !live.isEmpty()) {
            // A proper update: the old row withdrawn, then the new one.
            for (Object[] old : new ArrayList<>(live)) {
                if (old[0].equals(id)) {
                    live.remove(old);
                    engine.retract("src", old);
                    oracle.apply(old, -1);
                }
            }
        }
        live.add(row);
        engine.push("src", row);
        oracle.apply(row, 1);
        return (kind == 2 ? "update " : kind == 3 ? "blind upsert " : "insert ") + Arrays.toString(row);
    }

    @Test
    void qe066_aRandomChainFollowsItsUpstreamsAnswer() {
        long first = Long.getLong("pravaha.qa.seed", 900L);
        int seeds = Integer.getInteger("pravaha.qa.seeds", 10);
        List<String> failures = new ArrayList<>();
        int compared = 0;
        for (long seed = first; seed < first + seeds; seed++) {
            Random random = new Random(seed);
            KeyedOracle oracle = new KeyedOracle();
            List<Object[]> live = new ArrayList<>();
            try (PravahaEngine engine = chain(Map.of())) {
                for (int op = 0; op < 150; op++) {
                    String did = step(random, engine, oracle, live, op);
                    if (op % 10 == 9 || op == 149) {
                        compared++;
                        String difference = await(engine, oracle);
                        if (difference != null) {
                            failures.add("seed " + seed + " op " + op + " (" + did + "): " + difference);
                            break;
                        }
                    }
                }
            }
        }
        System.out.println(
                "NOTE QE-066 seeds=" + seeds + " ops=150 comparisons=" + compared + " failures=" + failures.size());
        failures.stream().limit(3).forEach(f -> System.out.println("NOTE mismatch " + f));
        assertThat(failures).isEmpty();
    }

    @Test
    void qe067_aChainSurvivesARestart(@TempDir Path dir) throws Exception {
        Random random = new Random(67);
        KeyedOracle oracle = new KeyedOracle();
        List<Object[]> live = new ArrayList<>();
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine engine = chain(settings)) {
            for (int op = 0; op < 120; op++) {
                step(random, engine, oracle, live, op);
            }
            assertThat(await(engine, oracle)).isNull();
            Thread.sleep(1500); // several checkpoint intervals after the last change
        }
        try (PravahaEngine restarted = chain(settings)) {
            String difference = await(restarted, oracle);
            System.out.println("NOTE QE-067 after restart: " + (difference == null ? "equal" : difference));
            assertThat(difference).isNull();
            // And it keeps following.
            for (int op = 120; op < 160; op++) {
                step(random, restarted, oracle, live, op);
            }
            assertThat(await(restarted, oracle)).isNull();
        }
    }

    @Test
    void qe068_theEmbeddedRegisterCallCannotBuildOnAView() {
        try (PravahaEngine engine = chain(Map.of())) {
            String api = AdvSupport.attempt(
                    () -> engine.register("down_api", "SELECT g, SUM(v) AS s FROM up GROUP BY g", "g"));
            String sql = AdvSupport.attempt(() -> engine.query(
                    "CREATE CONTINUOUS QUERY down_sql KEYED BY (g) AS SELECT g, SUM(v) AS s FROM up GROUP BY g"));
            System.out.println(
                    "NOTE QE-068 register()=" + api.lines().findFirst().orElse("") + " CREATE=" + sql);
            assertThat(sql).isEqualTo("OK");
            assertThat(api).startsWith("PRV-2002").contains("Object 'up' not found");
        }
    }

    @Test
    void qe069_to_073_chainRefusals() {
        try (PravahaEngine engine = chain(Map.of())) {
            String previous = "top";
            String deep = "OK";
            int refusedAt = -1;
            for (int level = 4; level <= 14 && "OK".equals(deep); level++) {
                refusedAt = level;
                String name = "l" + level;
                String from = previous;
                deep = AdvSupport.attempt(() ->
                        engine.query("CREATE CONTINUOUS QUERY " + name + " KEYED BY (g) AS SELECT g, s FROM " + from));
                previous = name;
            }
            String cycle = AdvSupport.attempt(
                    () -> engine.query("CREATE OR REPLACE CONTINUOUS QUERY up KEYED BY (g) AS SELECT g, s FROM top"));
            String drop = AdvSupport.attempt(() -> engine.query("DROP CONTINUOUS QUERY up"));
            String min = AdvSupport.attempt(() ->
                    engine.query("CREATE CONTINUOUS QUERY m KEYED BY (g) AS SELECT g, MIN(v) AS m FROM up GROUP BY g"));
            String retain = AdvSupport.attempt(() ->
                    engine.query("CREATE CONTINUOUS QUERY r KEYED BY (id) RETAIN FOR PT1H AS SELECT id, v FROM up"));
            System.out.println("NOTE QE-069 refused at query #" + refusedAt + " (up=1, mid=2, top=3): "
                    + deep.lines().findFirst().orElse("") + " | QE-070 "
                    + cycle.lines().findFirst().orElse("")
                    + " | QE-071 " + drop.lines().findFirst().orElse("") + " | QE-072 "
                    + min.lines().findFirst().orElse("")
                    + " | QE-073 " + retain.lines().findFirst().orElse(""));
            assertThat(deep).startsWith("PRV-8027");
            assertThat(cycle).matches("PRV-80(25|26).*");
            assertThat(drop).startsWith("PRV-8024").contains("mid");
            assertThat(min).startsWith("PRV-2075");
            assertThat(retain).startsWith("PRV-8026");
        }
    }

    @Test
    void qe074_aDownstreamOfAFailedUpstreamStaysAtItsFrontier() {
        try (PravahaEngine engine = chain(Map.of())) {
            engine.push("src", new Object[] {1L, "g0", 30L}, new Object[] {2L, "g0", 40L});
            KeyedOracle oracle = new KeyedOracle();
            oracle.apply(new Object[] {1L, "g0", 30L}, 1);
            oracle.apply(new Object[] {2L, "g0", 40L}, 1);
            assertThat(await(engine, oracle)).isNull();
            // mid's SUM leaves the range: mid fails; top must stay RUNNING at what it had.
            String outcome = AdvSupport.attempt(() -> engine.push("src", new Object[] {3L, "g0", Long.MAX_VALUE}));
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String mid = AdvSupport.state(engine, "mid");
            String top = AdvSupport.state(engine, "top");
            String topRead = AdvSupport.attempt(
                    () -> AdvSupport.rows(engine, "SELECT * FROM top").forEach(r -> {
                        throw new IllegalStateException(r);
                    }));
            System.out.println("NOTE QE-074 push=" + outcome.lines().findFirst().orElse("") + " mid="
                    + mid.lines().findFirst().orElse("") + " top=" + top + " topRead="
                    + topRead.lines().findFirst().orElse(""));
            assertThat(mid).startsWith("FAILED");
            assertThat(top).startsWith("RUNNING");
            assertThat(topRead).contains("g0|70");
        }
    }

    @Test
    void qe075_pauseAndResumeInAChainCatchUp() {
        Random random = new Random(75);
        KeyedOracle oracle = new KeyedOracle();
        List<Object[]> live = new ArrayList<>();
        try (PravahaEngine engine = chain(Map.of())) {
            for (int op = 0; op < 30; op++) {
                step(random, engine, oracle, live, op);
            }
            assertThat(await(engine, oracle)).isNull();
            engine.pause("mid");
            for (int op = 30; op < 130; op++) {
                step(random, engine, oracle, live, op);
            }
            engine.resume("mid");
            String difference = await(engine, oracle);
            System.out.println("NOTE QE-075 after resume: " + (difference == null ? "equal" : difference));
            assertThat(difference).isNull();
        }
    }

    // ------------------------------------------------------------------ QE-162

    static final StreamSchema W = StreamSchema.builder("w")
            .field("id", Types.int64())
            .field("x", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    static final String COUNT = "SELECT window_start, window_end, COUNT(*) AS c FROM TABLE(TUMBLE(TABLE w, "
            + "DESCRIPTOR(ts), INTERVAL '10' SECOND)) GROUP BY window_start, window_end";

    @Test
    @Disabled("QE-162: a push that one query cannot apply throws after the other queries applied it but before they "
            + "committed; their views hide the row until some later push, and the caller's retry counts it twice")
    void qe162_aPushOneQueryRefusesIsAllOrNothingForTheOthers() {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream(W))) {
            engine.register("bad", "SELECT id, x - 1 AS y FROM w", "id"); // fails on Long.MIN_VALUE
            engine.register("good", "SELECT id, x FROM w", "id");
            engine.register("cnt", COUNT, "window_start", "window_end");
            Instant ts = Instant.ofEpochSecond(1_700_000_001L);
            String first = AdvSupport.attempt(() -> engine.push("w", new Object[] {1L, Long.MIN_VALUE, ts}));
            assertThat(first).as("the push reports a failure").isNotEqualTo("OK");
            // Either the row reached nobody (and a retry is right) or it reached the healthy queries
            // and they show it (and a retry is wrong). Not: invisible now, and doubled after a retry.
            boolean visible = !AdvSupport.rows(engine, "SELECT * FROM good").isEmpty();
            engine.push("w", new Object[] {1L, Long.MIN_VALUE, ts}); // the caller's retry, after the failure
            engine.advanceEventTime("w", Instant.ofEpochSecond(1_700_000_100L));
            List<String> counted = AdvSupport.rows(engine, "SELECT c FROM cnt");
            assertThat(visible || counted.equals(List.of("1")))
                    .as("visible after the failed push=" + visible + ", count after one retry=" + counted)
                    .isTrue();
        }
    }

    @Test
    void qe162_observed() {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream(W))) {
            engine.register("bad", "SELECT id, x - 1 AS y FROM w", "id");
            engine.register("good", "SELECT id, x FROM w", "id");
            engine.register("cnt", COUNT, "window_start", "window_end");
            Instant ts = Instant.ofEpochSecond(1_700_000_001L);
            String first = AdvSupport.attempt(() -> engine.push("w", new Object[] {1L, Long.MIN_VALUE, ts}));
            // "long overflow" is not asserted: once the JIT compiles Math.subtractExact the JVM may throw it without a
            // message.
            assertThat(first).startsWith("PRV-3010").contains("ArithmeticException");
            assertThat(AdvSupport.rows(engine, "SELECT * FROM good"))
                    .as("applied, never committed")
                    .isEmpty();
            engine.push("w", new Object[] {1L, Long.MIN_VALUE, ts});
            engine.advanceEventTime("w", Instant.ofEpochSecond(1_700_000_100L));
            assertThat(AdvSupport.rows(engine, "SELECT c FROM cnt"))
                    .as("one row, counted twice")
                    .containsExactly("2");
        }
    }

    static Map<String, String> none() {
        return new HashMap<>();
    }
}
