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
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QE-041, QE-042, QE-043: windowed aggregates over out-of-order inserts, retractions and late rows,
 * compared after every watermark advance with an oracle that decides each (row, window) pair on its
 * own: applied iff {@code window_end + allowedLateness > watermark} when it arrives (CQ §5, §6;
 * {@code WindowedAggregate.process}). A window is visible once {@code window_end <= watermark}.
 */
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvWindowDifferentialTest {

    static final long BASE = 1_700_000_000L;
    static final long NANOS = 1_000_000_000L;

    /**
     * QE-163: the engine answers SUM, AVG, MIN and MAX of a group whose values are all NULL with 0
     * (reproduced on its own in {@code AdvAggregateTest}). The sweep's oracle says the same unless
     * {@code -Dpravaha.qa.strictNulls=true}, so the sweep looks past that one defect for others.
     */
    static final boolean ALL_NULL_AS_ZERO = !Boolean.getBoolean("pravaha.qa.strictNulls");

    record Row(String k, Long v, long second) {}

    /** One window's groups: k -> each value's multiplicity (null values counted under a null key). */
    static final class Oracle {
        final long size;
        final long slide;
        final long lateness;
        final TreeMap<Long, Map<String, Map<Long, Long>>> windows = new TreeMap<>();
        long watermark = Long.MIN_VALUE;

        Oracle(long size, long slide, long lateness) {
            this.size = size;
            this.slide = slide;
            this.lateness = lateness;
        }

        List<Long> startsOf(long second) {
            List<Long> starts = new ArrayList<>();
            long last = Math.floorDiv(second, slide) * slide;
            for (long start = last; start > second - size; start -= slide) {
                starts.add(start);
            }
            return starts;
        }

        void apply(Row row, long weight) {
            for (long start : startsOf(row.second())) {
                long end = start + size;
                if (watermark != Long.MIN_VALUE && end + lateness <= watermark) {
                    continue;
                }
                windows.computeIfAbsent(start, s -> new HashMap<>())
                        .computeIfAbsent(row.k(), k -> new HashMap<>())
                        .merge(row.v() == null ? Long.MIN_VALUE : row.v(), weight, Long::sum);
            }
        }

        /** {@code start|end|k|sum|count|countv|avg|distinct} for every visible group, as the view renders it. */
        TreeSet<String> visible(boolean minMax) {
            TreeSet<String> rows = new TreeSet<>();
            windows.forEach((start, groups) -> {
                long end = start + size;
                if (end > watermark) {
                    return;
                }
                groups.forEach((k, values) -> {
                    long count = 0;
                    long countV = 0;
                    long sum = 0;
                    Long min = null;
                    Long max = null;
                    int distinct = 0;
                    for (Map.Entry<Long, Long> each : values.entrySet()) {
                        long m = each.getValue();
                        if (m < 0) {
                            throw new IllegalStateException("negative multiplicity " + each);
                        }
                        if (m == 0) {
                            continue;
                        }
                        count += m;
                        if (each.getKey() != Long.MIN_VALUE) {
                            countV += m;
                            sum += m * each.getKey();
                            distinct++;
                            min = min == null ? each.getKey() : Math.min(min, each.getKey());
                            max = max == null ? each.getKey() : Math.max(max, each.getKey());
                        }
                    }
                    if (count == 0) {
                        return;
                    }
                    String prefix = (BASE + start) * NANOS + "|" + (BASE + end) * NANOS + "|" + k + "|";
                    String none = ALL_NULL_AS_ZERO ? "0" : "null";
                    if (minMax) {
                        rows.add(prefix + (min == null ? none : min) + "|" + (max == null ? none : max));
                    } else {
                        rows.add(prefix + (countV == 0 ? none : sum) + "|" + count + "|" + countV + "|"
                                + (countV == 0 ? none : sum / countV) + "|" + distinct);
                    }
                });
            });
            return rows;
        }
    }

    record Run(int comparisons, List<String> mismatches, String state) {}

    static Run run(long seed, int rows, long size, long slide, long lateness, boolean retractions, boolean minMax) {
        Random random = new Random(seed);
        StreamSchema schema = StreamSchema.builder("w")
                .field("k", Types.string())
                .field("v", Types.int64().withNullable(true))
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .allowedLateness(Duration.ofSeconds(lateness))
                .build();
        String window = size == slide
                ? "TABLE(TUMBLE(TABLE w, DESCRIPTOR(ts), INTERVAL '" + size + "' SECOND))"
                : "TABLE(HOP(TABLE w, DESCRIPTOR(ts), INTERVAL '" + slide + "' SECOND, INTERVAL '" + size
                        + "' SECOND))";
        String select = minMax
                ? "SELECT window_start, window_end, k, MIN(v) AS mn, MAX(v) AS mx FROM " + window
                : "SELECT window_start, window_end, k, SUM(v) AS s, COUNT(*) AS c, COUNT(v) AS cv, AVG(v) AS av, "
                        + "COUNT(DISTINCT v) AS cd FROM " + window;
        String sql = select + " GROUP BY window_start, window_end, k";
        Oracle oracle = new Oracle(size, slide, lateness);
        List<String> mismatches = new ArrayList<>();
        int comparisons = 0;
        try (PravahaEngine engine = AdvSupport.engine(e -> e.declareStream(schema))) {
            engine.register("agg", sql, "window_start", "window_end", "k");
            List<Row> live = new ArrayList<>();
            long horizon = 0;
            int pushed = 0;
            while (pushed < rows) {
                int batch = 1 + random.nextInt(6);
                List<Object[]> inserts = new ArrayList<>();
                List<Row> insertRows = new ArrayList<>();
                for (int i = 0; i < batch && pushed < rows; i++, pushed++) {
                    // Mostly near the horizon, sometimes far behind it: late or very late.
                    long second = Math.max(0, horizon + random.nextInt(25) - (random.nextInt(8) == 0 ? 60 : 12));
                    Row row = new Row(
                            "k" + random.nextInt(3),
                            random.nextInt(7) == 0 ? null : (long) (random.nextInt(21) - 10),
                            second);
                    insertRows.add(row);
                    inserts.add(new Object[] {row.k(), row.v(), Instant.ofEpochSecond(BASE + second)});
                }
                engine.push("w", inserts);
                for (Row row : insertRows) {
                    oracle.apply(row, 1);
                    live.add(row);
                }
                if (retractions && !live.isEmpty() && random.nextInt(3) == 0) {
                    Row gone = live.remove(random.nextInt(live.size()));
                    engine.retract("w", new Object[] {gone.k(), gone.v(), Instant.ofEpochSecond(BASE + gone.second())});
                    oracle.apply(gone, -1);
                }
                if (random.nextInt(3) == 0) {
                    horizon += 1 + random.nextInt(12);
                    long watermark = horizon - random.nextInt(5);
                    if (watermark > oracle.watermark || oracle.watermark == Long.MIN_VALUE) {
                        engine.advanceEventTime("w", Instant.ofEpochSecond(BASE + watermark));
                        oracle.watermark = watermark;
                        comparisons++;
                        compare(engine, oracle, minMax, "seed " + seed + " watermark " + watermark, mismatches);
                    }
                }
                if (!mismatches.isEmpty()) {
                    break;
                }
            }
            long end = horizon + size + lateness + 100;
            engine.advanceEventTime("w", Instant.ofEpochSecond(BASE + end));
            oracle.watermark = end;
            comparisons++;
            if (mismatches.isEmpty()) {
                compare(engine, oracle, minMax, "seed " + seed + " final", mismatches);
            }
            return new Run(comparisons, mismatches, AdvSupport.state(engine, "agg"));
        }
    }

    static void compare(PravahaEngine engine, Oracle oracle, boolean minMax, String where, List<String> mismatches) {
        TreeSet<String> actual = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM agg"));
        TreeSet<String> expected = oracle.visible(minMax);
        if (!actual.equals(expected)) {
            TreeSet<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            TreeSet<String> extra = new TreeSet<>(actual);
            extra.removeAll(expected);
            mismatches.add(where + " missing=" + missing + " extra=" + extra);
        }
    }

    static void sweep(
            String label,
            int seeds,
            int rows,
            long size,
            long slide,
            long lateness,
            boolean retractions,
            boolean minMax) {
        long first = Long.getLong("pravaha.qa.seed", 100L);
        int total = 0;
        List<String> failures = new ArrayList<>();
        for (long seed = first; seed < first + seeds; seed++) {
            Run run = run(seed, rows, size, slide, lateness, retractions, minMax);
            total += run.comparisons();
            if (!run.mismatches().isEmpty()) {
                failures.add(label + " " + run.mismatches().get(0) + " state=" + run.state());
            }
        }
        System.out.println("NOTE " + label + " seeds=" + seeds + " rows=" + rows + " comparisons=" + total
                + " failingSeeds=" + failures.size());
        failures.stream().limit(5).forEach(f -> System.out.println("NOTE mismatch " + f));
        assertThat(failures).isEmpty();
    }

    @Test
    void qe041_tumblingWithRetractionsAndNoLateness() {
        sweep("QE-041 tumble L=0", 25, 300, 10, 10, 0, true, false);
    }

    @Test
    void qe041_tumblingWithRetractionsAndLateness() {
        sweep("QE-041 tumble L=10", 25, 300, 10, 10, 10, true, false);
    }

    @Test
    void qe042_hoppingWithRetractionsAndLateness() {
        sweep("QE-042 hop 30/10 L=10", 15, 200, 30, 10, 10, true, false);
    }

    @Test
    void qe042_hoppingWithoutLateness() {
        sweep("QE-042 hop 30/10 L=0", 15, 200, 30, 10, 0, true, false);
    }

    @Test
    void qe043_minAndMaxInsertsOnly() {
        sweep("QE-043 tumble min/max L=10", 15, 200, 10, 10, 10, false, true);
        sweep("QE-043 hop min/max L=0", 10, 200, 30, 10, 0, false, true);
    }
}
