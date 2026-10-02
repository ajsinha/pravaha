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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

import static org.assertj.core.api.Assertions.assertThat;

/** QE-139..QE-161: pathological SQL, state that tries to grow without bound, and limits. */
@Timeout(900)
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvResourceTest {

    static final long BASE = 1_700_000_000L;

    static StreamSchema w() {
        return StreamSchema.builder("w")
                .field("k", Types.string())
                .field("v", Types.int64())
                .field("t", Types.string())
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
    }

    static PravahaEngine engine() {
        return AdvSupport.engine(Map.of("pravaha.embedded.push-timeout", "20s"), e -> e.declareStream(w()));
    }

    /** Registers {@code sql} keyed by {@code keys} and reports the outcome and how long it took. */
    static String timed(PravahaEngine engine, String name, String sql, String... keys) {
        long start = System.nanoTime();
        String outcome = AdvSupport.attempt(() -> engine.register(name, sql, keys));
        long millis = (System.nanoTime() - start) / 1_000_000;
        return outcome.lines().findFirst().orElse("").replaceAll("(.{0,220}).*", "$1") + " (" + millis + " ms)";
    }

    @Test
    void qe139_140_141_142_157_158_160_pathologicalShapes() {
        try (PravahaEngine engine = engine()) {
            StringBuilder parens = new StringBuilder("SELECT k, v, ts FROM w WHERE ");
            parens.append("(".repeat(3000)).append("v > 1").append(")".repeat(3000));
            StringBuilder cases = new StringBuilder("SELECT k, ts, ");
            for (int i = 0; i < 300; i++) {
                cases.append("CASE WHEN v > ").append(i).append(" THEN ");
            }
            cases.append("1").append(" ELSE 0 END".repeat(300)).append(" AS c FROM w");
            StringBuilder in = new StringBuilder("SELECT k, v, ts FROM w WHERE v IN (");
            for (int i = 0; i < 20_000; i++) {
                in.append(i == 0 ? "" : ",").append(i);
            }
            in.append(")");
            StringBuilder derived = new StringBuilder("SELECT k, v, ts FROM w");
            for (int i = 0; i < 200; i++) {
                derived.insert(0, "SELECT k, v, ts FROM (").append(") d").append(i);
            }
            StringBuilder ors = new StringBuilder("SELECT k, v, ts FROM w WHERE ");
            for (int i = 0; i < 60_000; i++) {
                ors.append(i == 0 ? "" : " OR ").append("t = 'value-").append(i).append("'");
            }
            List<String> seen = new ArrayList<>();
            seen.add("QE-139 3000 parentheses: " + timed(engine, "p1", parens.toString(), "k", "ts"));
            seen.add("QE-139 300 nested CASE: " + timed(engine, "p2", cases.toString(), "k", "ts"));
            seen.add("QE-140 IN of 20000: " + timed(engine, "p3", in.toString(), "k", "ts"));
            seen.add("QE-141 200 derived tables: " + timed(engine, "p4", derived.toString(), "k", "ts"));
            seen.add("QE-152 " + ors.length() / 1024 + " KiB of OR: " + timed(engine, "p5", ors.toString(), "k", "ts"));
            seen.add("QE-142 cross join: " + timed(engine, "p6", "SELECT a.k, b.v, a.ts FROM w a, w b", "k", "ts"));
            seen.add("QE-157 unwindowed group: "
                    + timed(engine, "p7", "SELECT k, COUNT(*) AS c FROM w GROUP BY k", "k"));
            seen.add("QE-158 left join, no bound: "
                    + timed(engine, "p8", "SELECT a.k, b.v FROM w a LEFT JOIN w b ON a.k = b.k", "k"));
            seen.add("QE-160 zero window: "
                    + timed(
                            engine,
                            "p9",
                            "SELECT window_start, window_end, COUNT(*) AS c FROM "
                                    + "TABLE(TUMBLE(TABLE w, DESCRIPTOR(ts), INTERVAL '0' SECOND)) GROUP BY window_start, window_end",
                            "window_start",
                            "window_end"));
            seen.add("QE-160 negative hop: "
                    + timed(
                            engine,
                            "p10",
                            "SELECT window_start, window_end, COUNT(*) AS c FROM "
                                    + "TABLE(HOP(TABLE w, DESCRIPTOR(ts), INTERVAL '-1' SECOND, INTERVAL '10' SECOND)) "
                                    + "GROUP BY window_start, window_end",
                            "window_start",
                            "window_end"));
            seen.add("QE-151 long name: " + timed(engine, "n".repeat(10_000), "SELECT k, v, ts FROM w", "k", "ts"));
            seen.forEach(s -> System.out.println("NOTE " + s));
            String push = AdvSupport.attempt(
                    () -> engine.push("w", new Object[] {"a", 5L, "value-7", Instant.ofEpochSecond(BASE)}));
            System.out.println("NOTE QE-139..152 push after them: "
                    + push.lines().findFirst().orElse(""));
            for (String s : seen) {
                String outcome = s.substring(s.indexOf(": ") + 2);
                assertThat(outcome).as(s).matches("(OK|PRV-\\d{4}).*");
            }
        }
    }

    @Test
    void qe148_sixtyFiveColumns() {
        StringBuilder spec = new StringBuilder("id:INT64");
        StringBuilder select = new StringBuilder("SELECT id");
        for (int i = 1; i <= 64; i++) {
            spec.append(",c").append(i).append(":INT64");
            select.append(", c").append(i);
        }
        String declared =
                AdvSupport.attempt(() -> AdvSupport.engine(e -> e.declareStream("wide", spec.toString(), null))
                        .close());
        System.out.println("NOTE QE-148 declaring a 65-column stream: "
                + declared.lines().findFirst().orElse(""));
        if (!"OK".equals(declared)) {
            assertThat(declared).startsWith("PRV-3030");
            return;
        }
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream("wide", spec.toString(), null))) {
            String registered = AdvSupport.attempt(() -> engine.register("wide65", select + " FROM wide", "id"));
            Object[] row = new Object[65];
            for (int i = 0; i < 65; i++) {
                row[i] = (long) i;
            }
            String pushed =
                    "OK".equals(registered) ? AdvSupport.attempt(() -> engine.push("wide", new Object[][] {row})) : "-";
            String state = "OK".equals(registered) ? AdvSupport.state(engine, "wide65") : "-";
            System.out.println(
                    "NOTE QE-148 register=" + registered.lines().findFirst().orElse("") + " push="
                            + pushed.lines().findFirst().orElse("") + " state="
                            + state.lines().findFirst().orElse(""));
            assertThat(registered + pushed + state).contains("PRV-3030");
        }
    }

    @Test
    void qe149_aCatastrophicRegexOverOneRow() throws Exception {
        try (PravahaEngine engine = engine()) {
            engine.register("rx", "SELECT k, REGEXP_EXTRACT(t, '(a+)+$') AS m, ts FROM w", "k", "ts");
            engine.register("other", "SELECT k, v, ts FROM w", "k", "ts");
            List<String> timings = new ArrayList<>();
            for (int n = 16; n <= 24; n += 2) {
                String text = "a".repeat(n) + "b";
                long start = System.nanoTime();
                String outcome = AdvSupport.attempt(() ->
                        engine.push("w", new Object[] {"k" + text.length(), 1L, text, Instant.ofEpochSecond(BASE)}));
                timings.add(n + " a's: " + (System.nanoTime() - start) / 1_000_000 + " ms "
                        + outcome.lines().findFirst().orElse(""));
            }
            // One row of 40 a's: 2^40 steps. Pushed on another thread, so the test can watch.
            String evil = "a".repeat(40) + "b";
            CompletableFuture<String> stuck = CompletableFuture.supplyAsync(() -> AdvSupport.attempt(
                    () -> engine.push("w", new Object[] {"evil", 1L, evil, Instant.ofEpochSecond(BASE)})));
            String outcome;
            try {
                outcome = stuck.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                outcome = "still running after 30 s";
            }
            String afterwards = AdvSupport.attempt(
                    () -> engine.push("w", new Object[] {"z", 2L, "x", Instant.ofEpochSecond(BASE)}));
            boolean otherHasIt = AdvSupport.rows(engine, "SELECT k FROM other").contains("z");
            System.out.println("NOTE QE-149 " + timings);
            System.out.println(
                    "NOTE QE-149 40 a's: " + outcome.lines().findFirst().orElse("") + " | rx state="
                            + AdvSupport.state(engine, "rx").lines().findFirst().orElse("") + " | next push: "
                            + afterwards.lines().findFirst().orElse("") + " | the other query got it: " + otherHasIt);
        }
    }

    @Test
    void qe155_aSubscriberThatNeverKeepsUpWithFailOverflow() throws Exception {
        try (PravahaEngine engine = engine()) {
            engine.register("sub", "SELECT k, v, ts FROM w", "k", "ts");
            java.util.concurrent.atomic.AtomicInteger delivered = new java.util.concurrent.atomic.AtomicInteger();
            var subscription = engine.subscribe(
                    "sub",
                    com.ash.messaging.pravaha.registry.SubscriptionOptions.of(
                            10, com.ash.messaging.pravaha.registry.SubscriptionOptions.Overflow.FAIL),
                    changes -> {
                        delivered.addAndGet(changes.size());
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            long start = System.nanoTime();
            String pushes = "OK";
            for (int i = 0; i < 200 && "OK".equals(pushes); i++) {
                int n = i;
                pushes = AdvSupport.attempt(() ->
                        engine.push("w", new Object[] {"k" + n, 1L, "", Instant.ofEpochSecond(BASE + n)}));
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            Thread.sleep(500);
            String ended = AdvSupport.attempt(() -> {
                throw new IllegalStateException(String.valueOf(subscription));
            });
            System.out.println("NOTE QE-155 200 pushes took " + millis + " ms, last="
                    + pushes.lines().findFirst().orElse("") + ", delivered=" + delivered.get() + ", subscription="
                    + ended.lines().findFirst().orElse(""));
            assertThat(millis)
                    .as("a slow subscriber must not hold up the engine")
                    .isLessThan(20_000);
        }
    }

    /** A row of {@code size} characters pushed with the inbox cell raised to 64 KiB; the query's state after it. */
    static String wideRow(int size) {
        try (PravahaEngine engine = AdvSupport.engine(
                Map.of("pravaha.lane.inbox.cell-bytes", "65536"),
                e -> e.declareStream("s", "id:INT64,t:STRING", null))) {
            engine.register("q", "SELECT id, t FROM s", "id");
            String outcome = AdvSupport.attempt(() -> engine.push("s", new Object[] {1L, "x".repeat(size)}));
            return AdvSupport.state(engine, "q").lines().findFirst().orElse("") + " | push "
                    + outcome.lines().findFirst().orElse("");
        }
    }

    @Test
    void qe167_aRowWithinTheConfiguredCellIsAccepted() {
        // CELLBYTES-1, fixed: the embedded engine reads pravaha.lane.* as a server does.
        assertThat(wideRow(4_000)).isEqualTo("RUNNING | push OK");
    }

    @Test
    void qe167_aRowWiderThanTheCellIsRefusedForThePushAndTheQueryKeepsRunning() {
        // Past even the raised cell: refused for that push, by size, naming the setting; the query
        // keeps running. It used to stop every query on the stream for good (FAILED PRV-8004).
        String wide = wideRow(70_000);
        assertThat(wide).startsWith("RUNNING | push PRV-8102").contains("at most 65536 bytes");
    }

    @Test
    void qe150_aTenMegabyteString() {
        try (PravahaEngine engine = engine()) {
            engine.register("big", "SELECT k, t, ts FROM w", "k", "ts");
            String huge = "x".repeat(10 * 1024 * 1024);
            String outcome = AdvSupport.attempt(
                    () -> engine.push("w", new Object[] {"a", 1L, huge, Instant.ofEpochSecond(BASE)}));
            int length = engine.find("big").orElseThrow().view().scan().stream()
                    .mapToInt(r -> ((String) r[1]).length())
                    .max()
                    .orElse(-1);
            System.out.println("NOTE QE-150 push=" + outcome.lines().findFirst().orElse("") + " stored length=" + length
                    + " state=" + AdvSupport.state(engine, "big"));
            assertThat(outcome).matches("OK|PRV-\\d{4}.*");
        }
    }

    @Test
    void qe144_145_146_aMillionGroupsKeysAndDistinctValues() {
        int keys = Integer.getInteger("pravaha.qa.keys", 1_000_050);
        try (PravahaEngine engine = engine()) {
            engine.register(
                    "grp",
                    "SELECT window_start, window_end, k, COUNT(*) AS c FROM TABLE(TUMBLE(TABLE w, "
                            + "DESCRIPTOR(ts), INTERVAL '10' SECOND)) GROUP BY window_start, window_end, k",
                    "window_start",
                    "window_end",
                    "k");
            engine.register(
                    "dist",
                    "SELECT window_start, window_end, COUNT(DISTINCT v) AS c FROM TABLE(TUMBLE("
                            + "TABLE w, DESCRIPTOR(ts), INTERVAL '10' SECOND)) GROUP BY window_start, window_end",
                    "window_start",
                    "window_end");
            engine.register("keys", "SELECT k, v, ts FROM w", "k", "ts");
            long start = System.nanoTime();
            String failure = "OK";
            int pushed = 0;
            Instant ts = Instant.ofEpochSecond(BASE + 1);
            for (int at = 0; at < keys && "OK".equals(failure); at += 10_000) {
                List<Object[]> batch = new ArrayList<>(10_000);
                for (int i = at; i < Math.min(keys, at + 10_000); i++) {
                    batch.add(new Object[] {"k" + i, (long) i, "", ts});
                }
                failure = AdvSupport.attempt(() -> engine.push("w", batch));
                pushed += batch.size();
            }
            String advance = AdvSupport.attempt(() -> engine.advanceEventTime("w", Instant.ofEpochSecond(BASE + 20)));
            Runtime runtime = Runtime.getRuntime();
            long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            System.out.println("NOTE QE-144..146 pushed=" + pushed + " in " + (System.nanoTime() - start) / 1_000_000
                    + " ms, push=" + failure.lines().findFirst().orElse("") + " advance="
                    + advance.lines().findFirst().orElse("")
                    + " heapUsedMb=" + usedMb + " maxMb=" + runtime.maxMemory() / (1024 * 1024));
            for (String name : List.of("grp", "dist", "keys")) {
                System.out.println("NOTE QE-144..146 " + name + " state="
                        + AdvSupport.state(engine, name).lines().findFirst().orElse("") + " viewSize="
                        + engine.find(name).orElseThrow().view().size());
            }
            System.out.println("NOTE QE-145 distinct="
                    + AdvSupport.attempt(() -> {
                                throw new IllegalStateException(AdvSupport.rows(engine, "SELECT c FROM dist")
                                        .toString());
                            })
                            .lines()
                            .findFirst()
                            .orElse(""));
        }
    }

    @Test
    void qe147_topNPastAMillionHeldRows() {
        int rows = Integer.getInteger("pravaha.qa.topn", 1_000_100);
        try (PravahaEngine engine = AdvSupport.engine(
                Map.of("pravaha.embedded.push-timeout", "20s"),
                e -> e.declareStream("b", "id:INT64,k:STRING,v:INT64", null))) {
            engine.register(
                    "top",
                    "SELECT k, v, id, rn FROM (SELECT k, v, id, ROW_NUMBER() OVER (PARTITION BY k "
                            + "ORDER BY v DESC) AS rn FROM b) WHERE rn <= 3",
                    "k",
                    "rn");
            String failure = "OK";
            int pushed = 0;
            for (int at = 0; at < rows && "OK".equals(failure); at += 10_000) {
                List<Object[]> batch = new ArrayList<>(10_000);
                for (int i = at; i < Math.min(rows, at + 10_000); i++) {
                    batch.add(new Object[] {(long) i, "p" + (i % 10), (long) i});
                }
                failure = AdvSupport.attempt(() -> engine.push("b", batch));
                pushed += batch.size();
            }
            System.out.println("NOTE QE-147 pushed=" + pushed + " push="
                    + failure.lines().findFirst().orElse("") + " state="
                    + AdvSupport.state(engine, "top").lines().findFirst().orElse(""));
            assertThat(failure + AdvSupport.state(engine, "top")).contains("PRV-4001");
        }
    }

    /** One row into HOP(1 ms, 1 day) beside an ordinary query on the same stream; then a minute of watermark. */
    static List<String> fineHop(String size) throws Exception {
        List<String> seen = new ArrayList<>();
        try (PravahaEngine engine = engine()) {
            String registered = AdvSupport.attempt(() -> engine.register(
                    "hop",
                    "SELECT window_start, window_end, "
                            + "COUNT(*) AS c FROM TABLE(HOP(TABLE w, DESCRIPTOR(ts), INTERVAL '0.001' SECOND, INTERVAL "
                            + size + ")) "
                            + "GROUP BY window_start, window_end",
                    "window_start",
                    "window_end"));
            seen.add("register " + registered.lines().findFirst().orElse(""));
            if (!"OK".equals(registered)) {
                return seen;
            }
            engine.register("plain", "SELECT k, v, ts FROM w", "k", "ts");
            engine.push("w", new Object[] {"a", 1L, "", Instant.ofEpochSecond(BASE)});
            CompletableFuture<String> advance = CompletableFuture.supplyAsync(
                    () -> AdvSupport.attempt(() -> engine.advanceEventTime("w", Instant.ofEpochSecond(BASE + 60))));
            String outcome;
            try {
                outcome = advance.get(45, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                outcome = "still running after 45 s";
            }
            seen.add("advance " + outcome.lines().findFirst().orElse(""));
            long start = System.nanoTime();
            String next = AdvSupport.attempt(() ->
                    engine.push("w", new Object[] {"b", 2L, "", Instant.ofEpochSecond(BASE + 61)}));
            seen.add("next push " + next.lines().findFirst().orElse("") + " after "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            seen.add("plain has b: "
                    + AdvSupport.rows(engine, "SELECT k FROM plain").contains("b"));
            seen.add("hop rows " + engine.find("hop").orElseThrow().view().size()
                    + " (60000 windows have closed, each holding the row) state "
                    + AdvSupport.state(engine, "hop").lines().findFirst().orElse(""));
            seen.add("heapUsedMb "
                    + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024));
        }
        return seen;
    }

    @Test
    void qe159_aHopWithMillionsOfWindowsPerRowIsRefusedOrBounded() throws Exception {
        // FINEHOP-1, fixed: refused at registration (PRV-3026), so it never reaches a lane -- the
        // day variant no longer needs the 8 GB heap it took to reproduce.
        List<String> seen = fineHop("'1' DAY");
        assertThat(seen.get(0))
                .satisfiesAnyOf(
                        r -> assertThat(r).startsWith("register PRV-"),
                        r -> assertThat(seen).contains("advance OK", "plain has b: true"));
        assertThat(seen).containsExactly(seen.get(0));
        assertThat(seen.get(0))
                .startsWith("register PRV-3026")
                .contains("86400000 windows")
                .contains("pravaha.lane.max-windows-per-row");
    }

    @Test
    void qe159_anHourOfMillisecondHopsIsRefusedToo() throws Exception {
        // The hour the observation used (3.6 million windows a row, 1.3 GB of heap) is refused alike.
        assertThat(fineHop("'1' HOUR")).singleElement().asString().startsWith("register PRV-3026");
    }

    @Test
    void qe159_161_windowsThatMultiplyWork() throws Exception {
        try (PravahaEngine engine = engine()) {
            String hop = timed(
                    engine,
                    "hop",
                    "SELECT window_start, window_end, COUNT(*) AS c FROM TABLE(HOP(TABLE w, "
                            + "DESCRIPTOR(ts), INTERVAL '0.001' SECOND, INTERVAL '1' DAY)) GROUP BY window_start, window_end",
                    "window_start",
                    "window_end");
            String hopPush = "-";
            if (hop.startsWith("OK")) {
                CompletableFuture<String> push = CompletableFuture.supplyAsync(() -> AdvSupport.attempt(
                        () -> engine.push("w", new Object[] {"a", 1L, "", Instant.ofEpochSecond(BASE)})));
                try {
                    hopPush = push.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    hopPush = "still running after 30 s";
                }
            }
            System.out.println("NOTE QE-159 register=" + hop + " push="
                    + hopPush.lines().findFirst().orElse("") + " state="
                    + (engine.find("hop").isPresent()
                            ? AdvSupport.state(engine, "hop")
                                    .lines()
                                    .findFirst()
                                    .orElse("")
                            : "-"));
        }
        try (PravahaEngine engine = engine()) {
            engine.register(
                    "ms",
                    "SELECT window_start, window_end, COUNT(*) AS c FROM TABLE(TUMBLE(TABLE w, "
                            + "DESCRIPTOR(ts), INTERVAL '0.001' SECOND)) GROUP BY window_start, window_end",
                    "window_start",
                    "window_end");
            engine.push("w", new Object[] {"a", 1L, "", Instant.ofEpochSecond(BASE)});
            long start = System.nanoTime();
            CompletableFuture<String> advance = CompletableFuture.supplyAsync(() -> AdvSupport.attempt(
                    () -> engine.advanceEventTime("w", Instant.ofEpochSecond(BASE + 365L * 24 * 3600))));
            String outcome;
            try {
                outcome = advance.get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                outcome = "still running after 60 s";
            }
            Thread.sleep(500);
            System.out.println("NOTE QE-161 a year of 1 ms windows: "
                    + outcome.lines().findFirst().orElse("") + " in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms; rows="
                    + engine.find("ms").orElseThrow().view().size()
                    + " state="
                    + AdvSupport.state(engine, "ms").lines().findFirst().orElse(""));
            assertThat(outcome).doesNotContain("still running");
        }
        try (PravahaEngine engine = engine()) {
            engine.register(
                    "ms",
                    "SELECT window_start, window_end, COUNT(*) AS c FROM TABLE(TUMBLE(TABLE w, "
                            + "DESCRIPTOR(ts), INTERVAL '0.001' SECOND)) GROUP BY window_start, window_end",
                    "window_start",
                    "window_end");
            engine.push("w", new Object[] {"a", 1L, "", Instant.ofEpochSecond(BASE)});
            engine.advanceEventTime("w", Instant.ofEpochSecond(BASE + 1));
            Thread.sleep(300);
            System.out.println("NOTE QE-161 control, one second of 1 ms windows: rows="
                    + engine.find("ms").orElseThrow().view().size() + " state=" + AdvSupport.state(engine, "ms"));
        }
    }
}
