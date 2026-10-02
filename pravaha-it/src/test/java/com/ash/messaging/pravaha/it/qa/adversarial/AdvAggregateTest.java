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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;

import static org.assertj.core.api.Assertions.assertThat;

/** QE-036, QE-044..QE-065 and QE-163: aggregates, windows, late data, top-N and joins. */
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvAggregateTest {

    static final long BASE = 1_700_000_000L;
    static final String TUMBLE = "FROM TABLE(TUMBLE(TABLE w, DESCRIPTOR(ts), INTERVAL '10' SECOND))";

    static Instant t(long second) {
        return Instant.ofEpochSecond(BASE + second);
    }

    static String w(long startSecond) {
        return (BASE + startSecond) * 1_000_000_000L + "|" + (BASE + startSecond + 10) * 1_000_000_000L;
    }

    static StreamSchema stream(long latenessSeconds) {
        return StreamSchema.builder("w")
                .field("k", Types.string())
                .field("v", Types.int64().withNullable(true))
                .field("d", Types.float64().withNullable(true))
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .allowedLateness(Duration.ofSeconds(latenessSeconds))
                .build();
    }

    static PravahaEngine windowed(long lateness, String name, String select, String... keys) {
        PravahaEngine engine = AdvSupport.engine(e -> e.declareStream(stream(lateness)));
        engine.register(name, select, keys);
        return engine;
    }

    // ------------------------------------------------------------------ all-NULL groups

    @Test
    @Disabled("QE-163: SUM/AVG/MIN/MAX of a window group whose values are all NULL are published as 0, not NULL")
    void qe163_aggregatesOfAnAllNullWindowGroupAreNull() {
        try (PravahaEngine engine = windowed(
                0,
                "agg",
                "SELECT window_start, window_end, k, SUM(v) AS s, AVG(v) AS a, MIN(v) AS mn, MAX(v) AS mx, "
                        + "COUNT(v) AS c " + TUMBLE + " GROUP BY window_start, window_end, k",
                "window_start",
                "window_end",
                "k")) {
            engine.push("w", new Object[] {"a", null, null, t(1)}, new Object[] {"a", null, null, t(2)});
            engine.advanceEventTime("w", t(10));
            assertThat(AdvSupport.rows(engine, "SELECT * FROM agg")).containsExactly(w(0) + "|a|null|null|null|null|0");
        }
    }

    @Test
    void qe163_observed_allNullWindowGroupIsPublishedAsZeros() {
        try (PravahaEngine engine = windowed(
                0,
                "agg",
                "SELECT window_start, window_end, k, SUM(v) AS s, AVG(v) AS a, MIN(v) AS mn, MAX(v) AS mx, "
                        + "COUNT(v) AS c " + TUMBLE + " GROUP BY window_start, window_end, k",
                "window_start",
                "window_end",
                "k")) {
            engine.push("w", new Object[] {"a", null, null, t(1)}, new Object[] {"a", null, null, t(2)});
            engine.advanceEventTime("w", t(10));
            assertThat(AdvSupport.rows(engine, "SELECT * FROM agg")).containsExactly(w(0) + "|a|0|0|0|0|0");
        }
    }

    @Test
    @Disabled("QE-163: the same all-NULL group read from a view (KeyedAggregate) and kept by a query over a view "
            + "answers SUM/AVG/MIN/MAX with 0")
    void qe163_allNullGroupOnAViewReadAndOverAViewIsNull() {
        try (PravahaEngine engine = allNullChain()) {
            assertThat(AdvSupport.rows(
                            engine,
                            "SELECT g, SUM(v) AS s, AVG(v) AS a, MIN(v) AS mn, MAX(v) AS mx " + "FROM up GROUP BY g"))
                    .containsExactly("x|null|null|null|null");
            assertThat(settle(engine, "SELECT * FROM down")).containsExactly("x|null|null|0");
        }
    }

    @Test
    void qe163_observed_allNullGroupOnAViewReadAndOverAViewIsZero() {
        try (PravahaEngine engine = allNullChain()) {
            assertThat(AdvSupport.rows(
                            engine,
                            "SELECT g, SUM(v) AS s, AVG(v) AS a, MIN(v) AS mn, MAX(v) AS mx " + "FROM up GROUP BY g"))
                    .containsExactly("x|0|0|0|0");
            assertThat(settle(engine, "SELECT * FROM down")).containsExactly("x|0|0|0");
        }
    }

    static PravahaEngine allNullChain() {
        PravahaEngine engine = AdvSupport.engine(e -> e.declareStream("src", "id:INT64,g:STRING,v:INT64?", null));
        engine.query("CREATE CONTINUOUS QUERY up KEYED BY (id) AS SELECT id, g, v FROM src");
        engine.query("CREATE CONTINUOUS QUERY down KEYED BY (g) AS SELECT g, SUM(v) AS s, AVG(v) AS a, "
                + "COUNT(v) AS c FROM up GROUP BY g");
        engine.push("src", new Object[] {1L, "x", null}, new Object[] {2L, "x", null});
        return engine;
    }

    static List<String> settle(PravahaEngine engine, String sql) {
        List<String> last = List.of();
        for (int i = 0; i < 20; i++) {
            List<String> now = AdvSupport.rows(engine, sql);
            if (!now.isEmpty() && now.equals(last)) {
                return now;
            }
            last = now;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return last;
    }

    // ------------------------------------------------------------------ averages, MIN under retraction

    @Test
    void qe036_integerAverageTruncatesTowardZeroAfter128BitNetting() {
        try (PravahaEngine engine = windowed(
                0,
                "av",
                "SELECT window_start, window_end, AVG(v) AS a, SUM(v) AS s " + TUMBLE
                        + " GROUP BY window_start, window_end",
                "window_start",
                "window_end")) {
            engine.push(
                    "w",
                    new Object[] {"a", -7L, null, t(1)},
                    new Object[] {"a", 0L, null, t(2)},
                    new Object[] {"b", Long.MAX_VALUE, null, t(3)},
                    new Object[] {"b", Long.MAX_VALUE, null, t(4)},
                    new Object[] {"b", Long.MIN_VALUE, null, t(5)},
                    new Object[] {"b", Long.MIN_VALUE, null, t(5)});
            engine.advanceEventTime("w", t(10));
            assertThat(AdvSupport.rows(engine, "SELECT * FROM av")).containsExactly(w(0) + "|-1|-9");
        }
    }

    @Test
    void qe044_windowedMinGivenARetractionStopsTheQuery() {
        // CQ §13 lists MIN and MAX as supported in windows; a retraction (a delete from a change feed,
        // an op=D line) stops the query at run time with PRV-3020 instead of being refused when the
        // query is registered over a stream that can carry deletes.
        try (PravahaEngine engine = windowed(
                0,
                "mn",
                "SELECT window_start, window_end, k, MIN(v) AS m " + TUMBLE + " GROUP BY window_start, window_end, k",
                "window_start",
                "window_end",
                "k")) {
            engine.push("w", new Object[] {"a", 1L, null, t(1)}, new Object[] {"a", 2L, null, t(2)});
            String outcome = AdvSupport.attempt(() -> engine.retract("w", new Object[] {"a", 1L, null, t(1)}));
            assertThat(outcome).contains("PRV-3020").contains("MIN cannot handle a retraction");
            assertThat(AdvSupport.state(engine, "mn")).startsWith("FAILED");
        }
    }

    // ------------------------------------------------------------------ DOUBLE group keys

    @Test
    @Disabled(
            "QE-045/QE-046: a windowed GROUP BY on a DOUBLE splits -0.0 from 0.0, and splits NaN payloads into groups "
                    + "the view then collapses, so a row's count is lost")
    void qe045_046_windowedGroupsOnADoubleCountEveryRowOnce() {
        try (PravahaEngine engine = windowed(
                0,
                "gd",
                "SELECT window_start, window_end, d, COUNT(*) AS c " + TUMBLE + " GROUP BY window_start, window_end, d",
                "window_start",
                "window_end",
                "d")) {
            pushDoubles(engine);
            engine.advanceEventTime("w", t(10));
            List<String> rows = AdvSupport.rows(engine, "SELECT * FROM gd");
            long counted = rows.stream()
                    .mapToLong(r -> Long.parseLong(r.substring(r.lastIndexOf('|') + 1)))
                    .sum();
            assertThat(counted).as(rows.toString()).isEqualTo(5);
            assertThat(rows).as("-0.0 and 0.0 compare equal (TY-3)").hasSize(3);
        }
    }

    @Test
    void qe045_046_observed() {
        try (PravahaEngine engine = windowed(
                0,
                "gd",
                "SELECT window_start, window_end, d, COUNT(*) AS c " + TUMBLE + " GROUP BY window_start, window_end, d",
                "window_start",
                "window_end",
                "d")) {
            pushDoubles(engine);
            engine.advanceEventTime("w", t(10));
            // Five rows in; four groups out, summing to four: -0.0 and 0.0 apart, two NaN groups
            // (by bit pattern) collapsed into one view row by the view's key, which compares NaN
            // payloads equal -- one row's count gone.
            assertThat(AdvSupport.rows(engine, "SELECT * FROM gd"))
                    .containsExactly(w(0) + "|-0.0|1", w(0) + "|0.0|1", w(0) + "|1.0|1", w(0) + "|NaN|1");
        }
    }

    @Test
    void qe047_048_049_distinctAndViewReadGroupingOfDoubles() {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream(stream(0)))) {
            engine.register(
                    "cd",
                    "SELECT window_start, window_end, COUNT(DISTINCT d) AS c " + TUMBLE
                            + " GROUP BY window_start, window_end",
                    "window_start",
                    "window_end");
            engine.register("raw", "SELECT k, d, ts FROM w", "k", "ts");
            pushDoubles(engine);
            engine.advanceEventTime("w", t(10));
            List<String> distinct = AdvSupport.rows(engine, "SELECT * FROM cd");
            List<String> grouped = AdvSupport.rows(engine, "SELECT d, COUNT(*) AS c FROM raw GROUP BY d");
            List<String> readDistinct = AdvSupport.rows(engine, "SELECT COUNT(DISTINCT d) AS c FROM raw");
            System.out.println("NOTE QE-047 windowed distinct " + distinct + " QE-048 read grouping " + grouped
                    + " QE-049 read distinct " + readDistinct);
            // Recorded as observed: each path's own notion of equality.
            assertThat(grouped).hasSize(4).contains("NaN|2");
            assertThat(readDistinct).containsExactly("4");
            assertThat(distinct).containsExactly(w(0) + "|4");
        }
    }

    static void pushDoubles(PravahaEngine engine) {
        engine.push(
                "w",
                new Object[] {"a", 1L, -0.0, t(1)},
                new Object[] {"b", 1L, 0.0, t(2)},
                new Object[] {"c", 1L, Double.NaN, t(3)},
                new Object[] {"d", 1L, Double.longBitsToDouble(0x7ff8000000000001L), t(4)},
                new Object[] {"e", 1L, 1.0, t(5)});
    }

    // ------------------------------------------------------------------ overflow

    @Test
    void qe050_051_052_windowedSumOverflow() {
        try (PravahaEngine engine = windowed(
                0,
                "s",
                "SELECT window_start, window_end, SUM(v) AS s " + TUMBLE + " GROUP BY window_start, window_end",
                "window_start",
                "window_end")) {
            // QE-051: one batch that passes the range and ends inside it is answered.
            engine.push(
                    "w",
                    new Object[] {"a", Long.MAX_VALUE, null, t(1)},
                    new Object[] {"a", Long.MAX_VALUE, null, t(1)},
                    new Object[] {"a", Long.MIN_VALUE, null, t(1)},
                    new Object[] {"a", Long.MIN_VALUE, null, t(1)},
                    new Object[] {"a", -7L, null, t(1)});
            engine.push("w", new Object[] {"a", Long.MAX_VALUE, null, t(2)});
            assertThat(AdvSupport.state(engine, "s")).isEqualTo("RUNNING");
            // QE-050/052: the next batch ends outside: refused, the query stops.
            String outcome = AdvSupport.attempt(() -> engine.push("w", new Object[] {"a", 100L, null, t(3)}));
            assertThat(outcome).contains("PRV-3025");
            assertThat(AdvSupport.state(engine, "s")).contains("PRV-3025");
        }
    }

    @Test
    void qe061_aViewReadWhoseSumLeavesTheRangeIsRefused() {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream("src", "id:INT64,v:INT64", null))) {
            engine.register("raw", "SELECT id, v FROM src", "id");
            engine.push("src", new Object[] {1L, Long.MAX_VALUE}, new Object[] {2L, 1L}, new Object[] {3L, -1L});
            assertThat(AdvSupport.rows(engine, "SELECT SUM(v) AS s FROM raw")).containsExactly("9223372036854775807");
            engine.push("src", new Object[] {4L, 1L});
            assertThat(AdvSupport.attempt(() -> AdvSupport.rows(engine, "SELECT AVG(v) AS a FROM raw")))
                    .startsWith("PRV-3025");
        }
    }

    // ------------------------------------------------------------------ lateness

    @Test
    void qe053_aRowAtTheEndOfAClosedWindowIsLateWithZeroLateness() {
        try (PravahaEngine engine = windowed(
                0,
                "c",
                "SELECT window_start, window_end, COUNT(*) AS c " + TUMBLE + " GROUP BY window_start, window_end",
                "window_start",
                "window_end")) {
            engine.push("w", new Object[] {"a", 1L, null, t(1)});
            engine.advanceEventTime("w", t(10));
            engine.push("w", new Object[] {"a", 1L, null, t(9)});
            engine.advanceEventTime("w", t(30));
            assertThat(AdvSupport.rows(engine, "SELECT * FROM c")).containsExactly(w(0) + "|1");
        }
    }

    @Test
    void qe054_055_056_correctionsWithinLatenessAndNothingBeyondIt() {
        try (PravahaEngine engine = windowed(
                10,
                "c",
                "SELECT window_start, window_end, COUNT(*) AS c " + TUMBLE + " GROUP BY window_start, window_end",
                "window_start",
                "window_end")) {
            List<List<String>> commits = new CopyOnWriteArrayList<>();
            engine.subscribe(
                    "c",
                    changes -> commits.add(changes.stream()
                            .map(RowChange::weight)
                            .map(String::valueOf)
                            .toList()));
            engine.push("w", new Object[] {"a", 1L, null, t(1)}, new Object[] {"a", 1L, null, t(2)});
            engine.advanceEventTime("w", t(10));
            assertThat(AdvSupport.rows(engine, "SELECT * FROM c")).containsExactly(w(0) + "|2");
            // QE-054: within lateness (end 10 + 10 > watermark 15): a correction.
            engine.advanceEventTime("w", t(15));
            commits.clear();
            engine.push("w", new Object[] {"a", 1L, null, t(3)});
            assertThat(AdvSupport.rows(engine, "SELECT * FROM c")).containsExactly(w(0) + "|3");
            assertThat(commits)
                    .as("the retraction and the corrected row in one commit")
                    .contains(List.of("-1", "1"));
            // QE-056: a retraction once the window is final is ignored like an insert would be.
            engine.advanceEventTime("w", t(20));
            engine.retract("w", new Object[] {"a", 1L, null, t(3)});
            // QE-055: beyond lateness: never applied.
            engine.push("w", new Object[] {"a", 1L, null, t(4)});
            engine.advanceEventTime("w", t(40));
            assertThat(AdvSupport.rows(engine, "SELECT * FROM c")).containsExactly(w(0) + "|3");
        }
    }

    @Test
    void qe062_globalCountNettedToZero() {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream("src", "id:INT64,v:INT64", null))) {
            String registered = AdvSupport.attempt(() -> engine.register("n", "SELECT COUNT(*) AS c FROM src"));
            System.out.println("NOTE QE-062 register " + registered);
            if (!"OK".equals(registered)) {
                return;
            }
            engine.push("src", new Object[] {1L, 1L}, new Object[] {2L, 2L}, new Object[] {3L, 3L});
            engine.retract("src", new Object[] {1L, 1L}, new Object[] {2L, 2L}, new Object[] {3L, 3L});
            List<String> rows = AdvSupport.rows(engine, "SELECT * FROM n");
            System.out.println("NOTE QE-062 after netting " + rows);
            assertThat(rows).isIn(List.of("0"), List.of());
        }
    }

    @Test
    void qe063_064_extremeAndNullEventTimes() {
        try (PravahaEngine engine = windowed(
                0,
                "c",
                "SELECT window_start, window_end, COUNT(*) AS c " + TUMBLE + " GROUP BY window_start, window_end",
                "window_start",
                "window_end")) {
            String early = AdvSupport.attempt(() -> engine.push("w", new Object[] {
                "a", 1L, null, Instant.ofEpochSecond(-9_223_372_036L, -854_775_808 + 1_000_000_000L)
            }));
            String late = AdvSupport.attempt(() ->
                    engine.push("w", new Object[] {"a", 1L, null, Instant.ofEpochSecond(9_223_372_036L, 854_775_807)}));
            String nullTime = AdvSupport.attempt(() -> engine.push("w", new Object[] {"a", 1L, null, null}));
            String tooLate = AdvSupport.attempt(
                    () -> engine.push("w", new Object[] {"a", 1L, null, Instant.ofEpochSecond(9_300_000_000L)}));
            String advance =
                    AdvSupport.attempt(() -> engine.advanceEventTime("w", Instant.ofEpochSecond(9_223_372_000L)));
            System.out.println("NOTE QE-063 early=" + early + " late=" + late + " beyondRange=" + tooLate + " advance="
                    + advance + " QE-064 null=" + nullTime.lines().findFirst().orElse(""));
            System.out.println("NOTE QE-063 state=" + AdvSupport.state(engine, "c") + " rows="
                    + AdvSupport.attempt(() -> {
                        throw new IllegalStateException(
                                AdvSupport.rows(engine, "SELECT * FROM c").toString());
                    }));
            assertThat(AdvSupport.state(engine, "c")).doesNotStartWith("FAILED");
        }
    }

    static final String HOP_25_10 = "SELECT window_start, window_end, COUNT(*) AS c FROM TABLE(HOP(TABLE w, "
            + "DESCRIPTOR(ts), INTERVAL '10' SECOND, INTERVAL '25' SECOND)) GROUP BY window_start, window_end";

    static List<String> hop25At12() {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream(stream(0)))) {
            engine.register("h", HOP_25_10, "window_start", "window_end");
            engine.push("w", new Object[] {"a", 1L, null, t(12)});
            engine.advanceEventTime("w", t(100));
            return AdvSupport.rows(engine, "SELECT * FROM h");
        }
    }

    @Test
    @Disabled("QE-065: HOP(slide 10 s, size 25 s) aligns window ENDS to the slide, so windows start at -5 s and 5 s; "
            + "SQL's HOP (Calcite's HopEnumerator, Flink) aligns window_start to multiples of the slide")
    void qe065_hopWindowsStartOnMultiplesOfTheSlide() {
        // t=12 lies in [-10,15), [0,25) and [10,35).
        assertThat(hop25At12())
                .containsExactly(
                        (BASE - 10) * 1_000_000_000L + "|" + (BASE + 15) * 1_000_000_000L + "|1",
                        (BASE) * 1_000_000_000L + "|" + (BASE + 25) * 1_000_000_000L + "|1",
                        (BASE + 10) * 1_000_000_000L + "|" + (BASE + 35) * 1_000_000_000L + "|1");
    }

    @Test
    void qe065_observed_hopWindowsAreAlignedByTheirEnd() {
        assertThat(hop25At12())
                .containsExactly(
                        (BASE - 5) * 1_000_000_000L + "|" + (BASE + 20) * 1_000_000_000L + "|1",
                        (BASE + 5) * 1_000_000_000L + "|" + (BASE + 30) * 1_000_000_000L + "|1");
    }

    // ------------------------------------------------------------------ top-N

    @Test
    void qe057_topNAgreesWithTheOracle() {
        long first = Long.getLong("pravaha.qa.seed", 500L);
        List<String> failures = new ArrayList<>();
        for (long seed = first; seed < first + 20; seed++) {
            Random random = new Random(seed);
            try (PravahaEngine engine =
                    AdvSupport.engine(e -> e.declareStream("b", "id:INT64,k:STRING,v:INT64", null))) {
                engine.register(
                        "top",
                        "SELECT k, v, id, rn FROM (SELECT k, v, id, ROW_NUMBER() OVER (PARTITION BY k "
                                + "ORDER BY v DESC) AS rn FROM b) WHERE rn <= 3",
                        "k",
                        "rn");
                List<Object[]> live = new ArrayList<>();
                for (int op = 0; op < 150; op++) {
                    if (!live.isEmpty() && random.nextInt(3) == 0) {
                        Object[] gone = live.remove(random.nextInt(live.size()));
                        engine.retract("b", gone);
                    } else {
                        Object[] row = {(long) op, "p" + random.nextInt(3), (long) random.nextInt(10)};
                        live.add(row);
                        engine.push("b", row);
                    }
                    TreeSet<String> expected = new TreeSet<>();
                    Map<String, List<Long>> partitions = new HashMap<>();
                    live.forEach(r -> partitions
                            .computeIfAbsent((String) r[1], x -> new ArrayList<>())
                            .add((Long) r[2]));
                    partitions.forEach((k, values) -> {
                        values.sort((a, b) -> Long.compare(b, a));
                        for (int i = 0; i < Math.min(3, values.size()); i++) {
                            expected.add(k + "|" + (i + 1) + "|" + values.get(i));
                        }
                    });
                    TreeSet<String> actual = new TreeSet<>();
                    for (String row : AdvSupport.rows(engine, "SELECT k, rn, v FROM top")) {
                        actual.add(row);
                    }
                    if (!expected.equals(actual)) {
                        failures.add("seed " + seed + " op " + op + " expected " + expected + " actual " + actual);
                        break;
                    }
                }
            }
        }
        System.out.println("NOTE QE-057 seeds=20 ops=150 failingSeeds=" + failures.size());
        failures.stream().limit(3).forEach(f -> System.out.println("NOTE mismatch " + f));
        assertThat(failures).isEmpty();
    }

    @Test
    void qe058_topNRetractionOfARowItNeverHeld() {
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream("b", "id:INT64,k:STRING,v:INT64", null))) {
            engine.register(
                    "top",
                    "SELECT k, v, id, rn FROM (SELECT k, v, id, ROW_NUMBER() OVER (PARTITION BY k "
                            + "ORDER BY v DESC) AS rn FROM b) WHERE rn <= 3",
                    "k",
                    "rn");
            engine.push("b", new Object[] {1L, "p", 5L});
            String outcome = AdvSupport.attempt(() -> engine.retract("b", new Object[] {2L, "p", 7L}));
            System.out.println(
                    "NOTE QE-058 " + outcome.lines().findFirst().orElse("") + " / " + AdvSupport.state(engine, "top"));
            assertThat(outcome + AdvSupport.state(engine, "top")).contains("PRV-3024");
        }
    }

    // ------------------------------------------------------------------ joins

    @Test
    void qe059_060_intervalJoinAndSelfJoinAgreeWithANestedLoop() {
        long first = Long.getLong("pravaha.qa.seed", 700L);
        List<String> failures = new ArrayList<>();
        for (long seed = first; seed < first + 15; seed++) {
            Random random = new Random(seed);
            try (PravahaEngine engine =
                    AdvSupport.engine(e -> e.declareStream("a", "aid:INT64,k:STRING,t:TIMESTAMP", "t")
                            .declareStream("b", "bid:INT64,k:STRING,t:TIMESTAMP", "t"))) {
                engine.register(
                        "j",
                        "SELECT a.aid, b.bid FROM a JOIN b ON a.k = b.k "
                                + "AND a.t BETWEEN b.t - INTERVAL '5' SECOND AND b.t",
                        "aid",
                        "bid");
                engine.register(
                        "sj",
                        "SELECT x.aid AS l, y.aid AS r FROM a x JOIN a y ON x.k = y.k "
                                + "AND x.t BETWEEN y.t - INTERVAL '5' SECOND AND y.t",
                        "l",
                        "r");
                List<Object[]> as = new ArrayList<>();
                List<Object[]> bs = new ArrayList<>();
                for (int op = 0; op < 120; op++) {
                    boolean left = random.nextBoolean();
                    List<Object[]> side = left ? as : bs;
                    if (!side.isEmpty() && random.nextInt(4) == 0) {
                        Object[] gone = side.remove(random.nextInt(side.size()));
                        engine.retract(left ? "a" : "b", gone);
                    } else {
                        Object[] row = {(long) op, "k" + random.nextInt(3), t(random.nextInt(30))};
                        side.add(row);
                        engine.push(left ? "a" : "b", row);
                    }
                }
                TreeSet<String> join = new TreeSet<>();
                TreeSet<String> self = new TreeSet<>();
                for (Object[] x : as) {
                    for (Object[] y : bs) {
                        if (x[1].equals(y[1]) && within((Instant) x[2], (Instant) y[2])) {
                            join.add(x[0] + "|" + y[0]);
                        }
                    }
                    for (Object[] y : as) {
                        if (x[1].equals(y[1]) && within((Instant) x[2], (Instant) y[2])) {
                            self.add(x[0] + "|" + y[0]);
                        }
                    }
                }
                TreeSet<String> actualJoin = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM j"));
                TreeSet<String> actualSelf = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM sj"));
                if (!join.equals(actualJoin)) {
                    failures.add("join seed " + seed + " expected " + join.size() + " actual " + actualJoin.size()
                            + " missing " + minus(join, actualJoin) + " extra " + minus(actualJoin, join));
                }
                if (!self.equals(actualSelf)) {
                    failures.add("self seed " + seed + " expected " + self.size() + " actual " + actualSelf.size()
                            + " missing " + minus(self, actualSelf) + " extra " + minus(actualSelf, self));
                }
            }
        }
        System.out.println("NOTE QE-059/060 seeds=15 ops=120 failures=" + failures.size());
        failures.stream().limit(4).forEach(f -> System.out.println("NOTE mismatch " + f));
        assertThat(failures).isEmpty();
    }

    static boolean within(Instant at, Instant other) {
        return !at.isBefore(other.minusSeconds(5)) && !at.isAfter(other);
    }

    static TreeSet<String> minus(TreeSet<String> a, TreeSet<String> b) {
        TreeSet<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out.size() > 6 ? new TreeSet<>(out.headSet(out.toArray(new String[0])[6])) : out;
    }
}
