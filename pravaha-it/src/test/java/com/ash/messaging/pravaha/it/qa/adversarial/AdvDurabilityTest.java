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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.embedded.ContinuousQuery;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

import static org.assertj.core.api.Assertions.assertThat;

/** QE-012, QE-076..QE-095, QE-164: restarts, crashes, corrupt and unwritable state. */
@EnabledIfSystemProperty(named = AdvSupport.SWITCH, matches = "true")
class AdvDurabilityTest {

    static final long BASE = 1_700_000_000L;

    // ------------------------------------------------------------------ the workload and its oracle

    /** A CSV workload of chunks; chunk c's event times lie in [10c, 10c + 9]. */
    static final class Workload {
        final List<List<String>> chunks = new ArrayList<>();
        final Map<Long, String[]> live = new TreeMap<>(); // id -> g, v, ts-second
        final Map<String, long[]> windows = new TreeMap<>(); // start|end|g -> sum, count

        Workload(long seed, int chunkCount, int linesPerChunk) {
            Random random = new Random(seed);
            long nextId = 1;
            for (int c = 0; c < chunkCount; c++) {
                List<String> lines = new ArrayList<>();
                List<Long> chunkIds = new ArrayList<>();
                for (int i = 0; i < linesPerChunk; i++) {
                    if (!chunkIds.isEmpty() && random.nextInt(4) == 0) {
                        Long gone = chunkIds.remove(random.nextInt(chunkIds.size()));
                        String[] row = live.remove(gone);
                        lines.add(line("D", gone, row[0], Long.parseLong(row[1]), Long.parseLong(row[2])));
                        window(row[0], Long.parseLong(row[1]), Long.parseLong(row[2]), -1);
                    } else {
                        long id = nextId++;
                        String g = "g" + random.nextInt(3);
                        long v = random.nextInt(100);
                        long second = 10L * c + random.nextInt(10);
                        live.put(id, new String[] {g, Long.toString(v), Long.toString(second)});
                        chunkIds.add(id);
                        lines.add(line("I", id, g, v, second));
                        window(g, v, second, 1);
                    }
                }
                chunks.add(lines);
            }
            // A sentinel far ahead closes every window before it.
            long second = 10L * (chunkCount + 1000);
            List<String> sentinel = List.of(line("I", nextId, "zz", 0, second));
            live.put(nextId, new String[] {"zz", "0", Long.toString(second)});
            chunks.add(sentinel);
        }

        static String line(String op, long id, String g, long v, long second) {
            return op + "," + id + "," + g + "," + v + "," + (BASE + second) * 1_000_000_000L;
        }

        void window(String g, long v, long second, long weight) {
            long start = Math.floorDiv(second, 10) * 10;
            String key = (BASE + start) * 1_000_000_000L + "|" + (BASE + start + 10) * 1_000_000_000L + "|" + g;
            long[] acc = windows.computeIfAbsent(key, k -> new long[2]);
            acc[0] += weight * v;
            acc[1] += weight;
        }

        TreeSet<String> proj() {
            TreeSet<String> out = new TreeSet<>();
            live.forEach((id, row) -> out.add(id + "|" + row[0] + "|" + row[1]));
            return out;
        }

        TreeSet<String> win() {
            TreeSet<String> out = new TreeSet<>();
            windows.forEach((key, acc) -> {
                if (acc[1] != 0) {
                    out.add(key + "|" + acc[0] + "|" + acc[1]);
                }
            });
            return out;
        }

        TreeSet<String> agg() {
            Map<String, long[]> groups = new TreeMap<>();
            live.values().forEach(row -> {
                long[] acc = groups.computeIfAbsent(row[0], g -> new long[2]);
                acc[0] += Long.parseLong(row[1]);
                acc[1]++;
            });
            TreeSet<String> out = new TreeSet<>();
            groups.forEach((g, acc) -> out.add(g + "|" + acc[0] + "|" + acc[1]));
            return out;
        }
    }

    /** Waits until all three views equal the workload's answer; null if they do, else the difference. */
    static String converge(PravahaEngine engine, Workload workload, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            TreeSet<String> proj = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM proj"));
            TreeSet<String> win = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM win"));
            TreeSet<String> agg = new TreeSet<>(AdvSupport.rows(engine, "SELECT * FROM agg"));
            if (proj.equals(workload.proj()) && win.equals(workload.win()) && agg.equals(workload.agg())) {
                return null;
            }
            last = "proj " + diff(workload.proj(), proj) + "; win " + diff(workload.win(), win) + "; agg "
                    + diff(workload.agg(), agg);
            Thread.sleep(200);
        }
        return last;
    }

    static String diff(TreeSet<String> expected, TreeSet<String> actual) {
        TreeSet<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        TreeSet<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        return missing.isEmpty() && extra.isEmpty() ? "ok" : "missing " + head(missing) + " extra " + head(extra);
    }

    static String head(TreeSet<String> rows) {
        return rows.size() + rows.stream().limit(4).toList().toString();
    }

    static void append(Path csv, List<String> lines) throws Exception {
        Files.writeString(
                csv,
                String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    // ------------------------------------------------------------------ QE-077 / QE-078

    record Child(Process process, List<String> output) {}

    static Child startChild(Path dir, Path csv) throws Exception {
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                AdvCrashChildMain.class.getName(),
                dir.toString(),
                csv.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> output = new ArrayList<>();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null) {
                    output.add(line);
                    if (line.startsWith("READY")) {
                        break;
                    }
                }
            } else if (!process.isAlive()) {
                break;
            } else {
                Thread.sleep(20);
            }
        }
        // Keep draining so the child never blocks on a full pipe.
        Thread drain = new Thread(() -> {
            try {
                while (reader.readLine() != null) {
                    // discard
                }
            } catch (Exception ignored) {
                // the child was killed
            }
        });
        drain.setDaemon(true);
        drain.start();
        return new Child(process, output);
    }

    static void kill(Child child) throws Exception {
        child.process().destroyForcibly();
        child.process().waitFor(30, TimeUnit.SECONDS);
    }

    @Test
    void qe077_078_sigkillAtRandomMomentsLosesAndDoublesNothing(@TempDir Path dir) throws Exception {
        long seed = Long.getLong("pravaha.qa.seed", 77L);
        int kills = Integer.getInteger("pravaha.qa.kills", 5);
        Workload workload = new Workload(seed, 20, 30);
        Path csv = dir.resolve("f.csv");
        Files.writeString(csv, "");
        Path state = dir.resolve("state");
        Random random = new Random(seed);
        int next = 0;
        List<String> log = new ArrayList<>();
        for (int k = 0; k < kills && next < workload.chunks.size(); k++) {
            Child child = startChild(state, csv);
            assertThat(child.output())
                    .as("child started: " + child.output())
                    .anySatisfy(l -> assertThat(l).startsWith("READY"));
            int burst = 1 + random.nextInt(5);
            for (int b = 0; b < burst && next < workload.chunks.size() - 1; b++) {
                append(csv, workload.chunks.get(next++));
                Thread.sleep(random.nextInt(150));
            }
            long wait = random.nextInt(4) == 0 ? 0 : 50 + random.nextInt(900);
            Thread.sleep(wait);
            kill(child);
            log.add("kill " + k + " after chunk " + next + " (+" + wait + " ms)");
        }
        while (next < workload.chunks.size()) {
            append(csv, workload.chunks.get(next++));
        }
        System.out.println("NOTE QE-077 seed=" + seed + " " + log);
        try (PravahaEngine engine = AdvCrashChildMain.engine(state, csv)) {
            String difference = converge(engine, workload, Duration.ofSeconds(60));
            System.out.println("NOTE QE-077 final: " + (difference == null ? "equal to the oracle" : difference));
            assertThat(difference).isNull();
        }
    }

    // ------------------------------------------------------------------ in-process restarts over a file

    /** A non-followed CSV holding the whole workload, read once through. */
    static PravahaEngine fileEngine(Path state, Path csv, Map<String, String> extra) {
        Map<String, String> settings = new LinkedHashMap<>(AdvSupport.durable(state));
        settings.putAll(extra);
        Map<String, String> source = new LinkedHashMap<>();
        source.put("path", csv.toString());
        source.put("schema", AdvCrashChildMain.SCHEMA);
        source.put("op.column", "op");
        source.put("event.time", "ts");
        PravahaEngine engine = AdvSupport.engine(
                settings,
                e -> e.declareStream("f", AdvCrashChildMain.SCHEMA, "ts")
                        .bindSource("f", "filesystem", source)
                        .declareQuery(ContinuousQuery.named("proj")
                                .sql("SELECT id, g, v FROM f")
                                .keyedBy("id")
                                .build())
                        .declareQuery(ContinuousQuery.named("win")
                                .sql(AdvCrashChildMain.WINDOW)
                                .keyedBy("window_start", "window_end", "g")
                                .build()));
        if (engine.find("agg").isEmpty()) {
            engine.query(
                    "CREATE CONTINUOUS QUERY agg KEYED BY (g) AS SELECT g, SUM(v) AS s, COUNT(*) AS c FROM proj GROUP BY g");
        }
        return engine;
    }

    static Path writeWorkload(Path dir, Workload workload) throws Exception {
        Path csv = dir.resolve("all.csv");
        List<String> lines = new ArrayList<>();
        workload.chunks.forEach(lines::addAll);
        Files.write(csv, lines);
        return csv;
    }

    static List<Path> checkpointFiles(Path state) throws Exception {
        try (Stream<Path> files = Files.walk(state.resolve("checkpoints"))) {
            return files.filter(p -> p.getFileName().toString().matches("checkpoint-\\d+\\.bin"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    static Path newest(List<Path> files, String query) {
        return files.stream()
                .filter(p -> p.getParent().getFileName().toString().equals(query))
                .max(Comparator.comparingLong(
                        p -> Long.parseLong(p.getFileName().toString().replaceAll("\\D", ""))))
                .orElseThrow();
    }

    @Test
    void qe079_080_095_aCorruptNewestCheckpointNeverChangesTheAnswer(@TempDir Path dir) throws Exception {
        Workload workload = new Workload(79, 10, 20);
        Path csv = writeWorkload(dir, workload);
        List<String> outcomes = new ArrayList<>();
        for (String damage : List.of("truncate", "bitflip", "only-truncate")) {
            Path state = dir.resolve("state-" + damage);
            Map<String, String> extra = damage.startsWith("only") ? Map.of("pravaha.checkpoint.keep", "1") : Map.of();
            try (PravahaEngine engine = fileEngine(state, csv, extra)) {
                assertThat(converge(engine, workload, Duration.ofSeconds(30))).isNull();
                Thread.sleep(1200);
            }
            List<Path> files = checkpointFiles(state);
            for (String query : List.of("proj", "win", "agg")) {
                Path target = newest(files, query);
                byte[] bytes = Files.readAllBytes(target);
                if (damage.endsWith("truncate")) {
                    Files.write(target, java.util.Arrays.copyOf(bytes, bytes.length / 2));
                } else {
                    bytes[bytes.length / 2] ^= 0x5A;
                    Files.write(target, bytes);
                }
            }
            String started = AdvSupport.attempt(() -> {
                try (PravahaEngine engine = fileEngine(state, csv, extra)) {
                    String difference = converge(engine, workload, Duration.ofSeconds(30));
                    String states = AdvSupport.state(engine, "proj") + " / " + AdvSupport.state(engine, "win") + " / "
                            + AdvSupport.state(engine, "agg");
                    throw new IllegalStateException(
                            (difference == null ? "EQUAL" : "DIFFERENT " + difference) + " states " + states);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            outcomes.add(damage + ": " + started.replace("UNCODED java.lang.IllegalStateException: ", ""));
        }
        outcomes.forEach(o -> System.out.println(
                "NOTE QE-079/080/095 " + o.lines().findFirst().orElse("")));
        // Never a different answer: equal, or a refusal with a code.
        assertThat(outcomes)
                .allSatisfy(o -> assertThat(o).doesNotContain("DIFFERENT").doesNotContain("UNCODED"));
    }

    @Test
    @Disabled("QE-080: checkpoints carry no checksum; a single flipped bit in a window checkpoint is restored as "
            + "state and the view publishes a corrupted window boundary for ever")
    void qe080_aFlippedBitInACheckpointIsNeverRestoredAsAnAnswer(@TempDir Path dir) throws Exception {
        assertThat(bitFlips(dir)).isEmpty();
    }

    @Test
    void qe080_observed(@TempDir Path dir) throws Exception {
        assertThat(bitFlips(dir)).isNotEmpty();
    }

    static List<String> bitFlips(Path dir) throws Exception {
        Workload workload = new Workload(80, 10, 20);
        Path csv = writeWorkload(dir, workload);
        Path pristine = dir.resolve("pristine");
        try (PravahaEngine engine = fileEngine(pristine, csv, Map.of())) {
            assertThat(converge(engine, workload, Duration.ofSeconds(30))).isNull();
            Thread.sleep(1200);
        }
        Path relative = pristine.relativize(newest(checkpointFiles(pristine), "win"));
        int length = (int) Files.size(pristine.resolve(relative));
        int positions = Integer.getInteger("pravaha.qa.flips", 16);
        Map<String, Integer> verdicts = new TreeMap<>();
        List<String> different = new ArrayList<>();
        for (int i = 0; i < positions; i++) {
            int at = 24 + (int) ((long) (length - 24 - 12) * i / positions);
            Path copy = dir.resolve("flip-" + i);
            copyTree(pristine, copy);
            Path target = copy.resolve(relative);
            byte[] bytes = Files.readAllBytes(target);
            bytes[at] ^= 0x01;
            Files.write(target, bytes);
            String outcome = AdvSupport.attempt(() -> {
                        try (PravahaEngine engine = fileEngine(copy, csv, Map.of())) {
                            String difference = converge(engine, workload, Duration.ofSeconds(8));
                            throw new IllegalStateException(difference == null ? "EQUAL" : "DIFFERENT " + difference);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .replace("UNCODED java.lang.IllegalStateException: ", "");
            String verdict = outcome.startsWith("EQUAL")
                    ? "equal"
                    : outcome.startsWith("DIFFERENT")
                            ? "DIFFERENT"
                            : outcome.startsWith("PRV-") ? "refused " + outcome.substring(0, 8) : "uncoded";
            verdicts.merge(verdict, 1, Integer::sum);
            if (!verdict.equals("equal") && !verdict.startsWith("refused")) {
                different.add("byte " + at + "/" + length + ": "
                        + outcome.lines().findFirst().orElse(""));
            }
        }
        System.out.println(
                "NOTE QE-080 flips=" + positions + " in " + relative + " (" + length + " bytes): " + verdicts);
        different.forEach(d -> System.out.println("NOTE QE-080 " + d));
        return different;
    }

    static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : all.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    @Test
    void qe090_aRestartWithAReplacementInFlight(@TempDir Path dir) throws Exception {
        Workload workload = new Workload(90, 30, 40);
        Path csv = writeWorkload(dir, workload);
        Path state = dir.resolve("state");
        String replaced;
        try (PravahaEngine engine = fileEngine(state, csv, Map.of())) {
            assertThat(converge(engine, workload, Duration.ofSeconds(30))).isNull();
            replaced = AdvSupport.attempt(() -> engine.query("CREATE OR REPLACE CONTINUOUS QUERY win KEYED BY "
                    + "(window_start, window_end, g) WITH (backfill = 'history', cutover = 'manual', "
                    + "backfill.rate.limit = 50) AS " + AdvCrashChildMain.WINDOW + " HAVING COUNT(*) > 0"));
            Thread.sleep(300);
        }
        String after = AdvSupport.attempt(() -> {
                    try (PravahaEngine engine = fileEngine(state, csv, Map.of())) {
                        String difference = converge(engine, workload, Duration.ofSeconds(30));
                        throw new IllegalStateException((difference == null ? "EQUAL" : "DIFFERENT " + difference)
                                + " | "
                                + AdvSupport.rows(engine, "SHOW CONTINUOUS QUERIES").stream()
                                        .map(r -> r.split("\\|")[0] + "=" + r.split("\\|")[1])
                                        .toList());
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
                .replace("UNCODED java.lang.IllegalStateException: ", "");
        System.out.println("NOTE QE-090 replace=" + replaced.lines().findFirst().orElse("") + " | after restart: "
                + after.lines().findFirst().orElse(""));
        assertThat(after).doesNotContain("DIFFERENT").doesNotStartWith("UNCODED");
    }

    // ------------------------------------------------------------------ QE-076, QE-089, QE-091, QE-092

    static final StreamSchema W = StreamSchema.builder("w")
            .field("k", Types.string())
            .field("v", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    @Test
    void qe076_089_091_092_restartsKeepStateNamesAndShares(@TempDir Path dir) throws Exception {
        Map<String, String> settings = AdvSupport.durable(dir);
        String count = "SELECT window_start, window_end, k, COUNT(*) AS c, SUM(v) AS s FROM TABLE(TUMBLE(TABLE w, "
                + "DESCRIPTOR(ts), INTERVAL '10' SECOND)) GROUP BY window_start, window_end, k";
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            engine.register("open_windows", count, "window_start", "window_end", "k");
            engine.register("alias_a", "SELECT k, v, ts FROM w WHERE v > 0", "k", "ts");
            engine.register("alias_b", "SELECT k, v, ts FROM w WHERE v > 0", "k", "ts");
            engine.register("dropped", "SELECT k, ts FROM w", "k", "ts");
            for (int i = 0; i < 70; i++) {
                engine.register("many_" + i, "SELECT k, v, ts FROM w WHERE v > " + (i - 100), "k", "ts");
            }
            engine.push("w", new Object[] {"a", 1L, Instant.ofEpochSecond(BASE + 1)}, new Object[] {
                "a", 2L, Instant.ofEpochSecond(BASE + 2)
            });
            engine.drop("dropped");
            engine.drop("alias_a");
            Thread.sleep(1500);
        }
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            assertThat(engine.queries())
                    .doesNotContain("dropped", "alias_a")
                    .contains("alias_b", "open_windows", "many_69");
            engine.push("w", new Object[] {"a", 3L, Instant.ofEpochSecond(BASE + 3)});
            engine.advanceEventTime("w", Instant.ofEpochSecond(BASE + 20));
            String w0 = (BASE * 1_000_000_000L) + "|" + ((BASE + 10) * 1_000_000_000L);
            assertThat(AdvSupport.rows(engine, "SELECT * FROM open_windows")).containsExactly(w0 + "|a|3|6");
            assertThat(AdvSupport.rows(engine, "SELECT k, v FROM many_69")).containsExactly("a|1", "a|2", "a|3");
        }
    }

    /** Two names on one computation; {@code drop} (or nobody) dropped; what the survivor holds after a restart. */
    static List<String> sharedAfterRestart(Path dir, String drop) throws Exception {
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            engine.register("alias_a", "SELECT k, v, ts FROM w WHERE v > 0", "k", "ts");
            engine.register("alias_b", "SELECT k, v, ts FROM w WHERE v > 0", "k", "ts");
            engine.push("w", new Object[] {"a", 1L, Instant.ofEpochSecond(BASE + 1)}, new Object[] {
                "a", 2L, Instant.ofEpochSecond(BASE + 2)
            });
            if (drop != null) {
                engine.drop(drop);
            }
            Thread.sleep(1500);
        }
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            String survivor = "alias_a".equals(drop) ? "alias_b" : "alias_a";
            return AdvSupport.rows(engine, "SELECT k, v FROM " + survivor);
        }
    }

    @Test
    @Disabled("QE-091: when the first name of a shared computation is dropped, the surviving name comes back from a "
            + "restart with none of the state the computation had")
    void qe091_theSurvivorOfASharedComputationKeepsItsStateAcrossARestart(@TempDir Path dir) throws Exception {
        assertThat(sharedAfterRestart(dir, "alias_a")).containsExactly("a|1", "a|2");
    }

    @Test
    void qe091_observed(@TempDir Path dir) throws Exception {
        List<String> none = sharedAfterRestart(dir.resolve("none"), null);
        List<String> second = sharedAfterRestart(dir.resolve("second"), "alias_b");
        List<String> first = sharedAfterRestart(dir.resolve("first"), "alias_a");
        System.out.println("NOTE QE-091 nothing dropped: " + none + " | second name dropped: " + second
                + " | first name dropped: " + first);
        assertThat(none).containsExactly("a|1", "a|2");
        assertThat(second).containsExactly("a|1", "a|2");
        assertThat(first).isEmpty();
    }

    // ------------------------------------------------------------------ journal damage

    @Test
    void qe081_082_aDamagedJournal(@TempDir Path dir) throws Exception {
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            engine.register("first", "SELECT k, v, ts FROM w", "k", "ts");
            engine.register("latter", "SELECT k, v, ts FROM w WHERE v > 1", "k", "ts");
        }
        Path journal = dir.resolve("registry.journal");
        byte[] whole = Files.readAllBytes(journal);
        // QE-081: the last record half written.
        Files.write(journal, java.util.Arrays.copyOf(whole, whole.length - 7));
        String truncated = AdvSupport.attempt(() -> {
            try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
                throw new IllegalStateException("queries " + engine.queries());
            }
        });
        assertThat(truncated).contains("queries [first]");
    }

    /** Three registrations; the middle record's length prefix damaged; what a restart recovers. */
    static List<String> damagedMiddleRecord(Path dir) throws Exception {
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            engine.register("alpha", "SELECT k, v, ts FROM w", "k", "ts");
            engine.register("beta", "SELECT k, v, ts FROM w WHERE v > 1", "k", "ts");
            engine.register("gamma", "SELECT k, v, ts FROM w WHERE v > 2", "k", "ts");
        }
        Path journal = dir.resolve("registry.journal");
        byte[] bytes = Files.readAllBytes(journal);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        int second = 4 + buffer.getInt(0);
        buffer.putInt(second, 0x7fffff00); // one damaged length prefix, in the middle of the file
        Files.write(journal, bytes);
        List<String> seen = new ArrayList<>();
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            seen.add("after damage: " + engine.queries());
            engine.register("delta", "SELECT k, v, ts FROM w WHERE v > 3", "k", "ts");
        }
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            seen.add("after registering delta and restarting: " + engine.queries());
        }
        return seen;
    }

    @Test
    void qe082_aDamagedRecordInTheMiddleOfTheJournalRefusesTheStart(@TempDir Path dir) {
        // JOURNALMID-1, fixed: the start is refused PRV-8005 naming the offset, not replayed as [alpha].
        String outcome = AdvSupport.attempt(() -> {
            try {
                damagedMiddleRecord(dir);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(outcome).startsWith("PRV-8005").contains("byte offset").contains("a complete record follows");
    }

    @Test
    void qe082_aRegistrationAfterATornTailSurvivesTheNextStart(@TempDir Path dir) throws Exception {
        // The other half of JOURNALMID-1: a registration appended after a genuinely torn final record
        // used to land behind the torn bytes and be lost at the next start. The tail is cut first now.
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            engine.register("alpha", "SELECT k, v, ts FROM w", "k", "ts");
            engine.register("beta", "SELECT k, v, ts FROM w WHERE v > 1", "k", "ts");
        }
        Path journal = dir.resolve("registry.journal");
        byte[] whole = Files.readAllBytes(journal);
        Files.write(journal, java.util.Arrays.copyOf(whole, whole.length - 7));
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            assertThat(engine.queries().toString()).isEqualTo("[alpha]");
            engine.register("gamma", "SELECT k, v, ts FROM w WHERE v > 3", "k", "ts");
        }
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            assertThat(engine.queries().toString()).isEqualTo("[alpha, gamma]");
        }
    }

    // ------------------------------------------------------------------ ownership, permissions, full disk

    @Test
    @Disabled("QE-083: a second engine in the same JVM with the same node id (the default, pravaha-embedded) claims a "
            + "running engine's state directory, and closing it deletes the first engine's ownership marker")
    void qe083_aSecondEngineOnARunningEnginesDirectoryIsRefused(@TempDir Path dir) {
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine first = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            String same = AdvSupport.attempt(
                    () -> AdvSupport.engine(settings, e -> e.declareStream(W)).close());
            assertThat(same).startsWith("PRV-4003");
        }
    }

    @Test
    void qe083_observed(@TempDir Path dir) {
        Map<String, String> settings = AdvSupport.durable(dir);
        Map<String, String> other = new LinkedHashMap<>(settings);
        other.put("pravaha.node.id", "intruder");
        try (PravahaEngine first = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            first.register("q", "SELECT k, v, ts FROM w", "k", "ts");
            String intruderBefore = AdvSupport.attempt(
                    () -> AdvSupport.engine(other, e -> e.declareStream(W)).close());
            String same = AdvSupport.attempt(() -> {
                try (PravahaEngine second = AdvSupport.engine(settings, e -> e.declareStream(W))) {
                    // Both engines now journal and checkpoint into one directory.
                    second.register("q_second", "SELECT k, ts FROM w", "k", "ts");
                }
            });
            boolean markerAfter = Files.exists(dir.resolve(".pravaha-owner"))
                    && Files.exists(dir.resolve("checkpoints").resolve(".pravaha-owner"));
            String intruderAfter = AdvSupport.attempt(
                    () -> AdvSupport.engine(other, e -> e.declareStream(W)).close());
            System.out.println("NOTE QE-083 intruder while first runs: "
                    + intruderBefore.lines().findFirst().orElse("")
                    + " | same id, same JVM: " + same + " | markers after the second closed: " + markerAfter
                    + " | intruder after: " + intruderAfter.lines().findFirst().orElse(""));
            assertThat(intruderBefore).startsWith("PRV-4003");
            assertThat(same).isEqualTo("OK");
            assertThat(markerAfter).isFalse();
            assertThat(intruderAfter).isEqualTo("OK");
        }
    }

    @Test
    void qe084_085_unwritableCheckpointsAndJournal(@TempDir Path dir) throws Exception {
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
            engine.register("q", "SELECT k, v, ts FROM w", "k", "ts");
            Thread.sleep(500);
            Path checkpoints = dir.resolve("checkpoints");
            try (Stream<Path> all = Files.walk(checkpoints)) {
                for (Path p : all.filter(Files::isDirectory).toList()) {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("r-x------"));
                }
            }
            engine.push("w", new Object[] {"a", 1L, Instant.ofEpochSecond(BASE + 1)});
            Thread.sleep(1000);
            var query = engine.find("q").orElseThrow();
            String checkpointState = "failures=" + query.checkpointFailures() + " last="
                    + query.lastCheckpointFailure()
                            .map(s -> s.lines().findFirst().orElse(""))
                            .orElse("none");
            List<String> rows = AdvSupport.rows(engine, "SELECT k, v FROM q");
            Files.setPosixFilePermissions(
                    dir.resolve("registry.journal"), PosixFilePermissions.fromString("r--------"));
            String register = AdvSupport.attempt(() -> engine.register("q2", "SELECT k, ts FROM w", "k", "ts"));
            System.out.println("NOTE QE-084 " + checkpointState + " rows=" + rows + " | QE-085 "
                    + register.lines().findFirst().orElse("") + " present="
                    + engine.find("q2").isPresent());
            try (Stream<Path> all = Files.walk(checkpoints)) {
                for (Path p : all.filter(Files::isDirectory).toList()) {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
                }
            }
            assertThat(query.checkpointFailures()).isPositive();
            assertThat(rows).containsExactly("a|1");
            assertThat(register).isNotEqualTo("OK");
            assertThat(engine.find("q2")).isEmpty();
        }
    }

    @Test
    void qe086_087_aFullDiskAndACheckpointDirectoryThatIsAFile(@TempDir Path dir) throws Exception {
        Map<String, String> settings = new LinkedHashMap<>(AdvSupport.durable(dir));
        Path journal = dir.resolve("registry.journal");
        Files.createSymbolicLink(journal, Path.of("/dev/full"));
        String full = AdvSupport.attempt(() -> {
            try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream(W))) {
                String outcome = AdvSupport.attempt(() -> engine.register("q", "SELECT k, v, ts FROM w", "k", "ts"));
                throw new IllegalStateException(
                        "started; register=" + outcome.lines().findFirst().orElse("") + " present="
                                + engine.find("q").isPresent());
            }
        });
        Path dir2 = dir.resolve("two");
        Files.createDirectories(dir2);
        Files.writeString(dir2.resolve("checkpoints"), "not a directory");
        String file = AdvSupport.attempt(() -> AdvSupport.engine(AdvSupport.durable(dir2), e -> e.declareStream(W))
                .close());
        System.out.println("NOTE QE-086 " + full.lines().findFirst().orElse("") + " | QE-087 "
                + file.lines().findFirst().orElse(""));
        assertThat(full).doesNotContain("present=true");
        assertThat(file).startsWith("PRV-");
    }

    /** A view of (id INT64, v INT64), restarted over a stream whose v is now STRING. */
    static List<String> retyped(Path dir) throws Exception {
        Map<String, String> settings = AdvSupport.durable(dir);
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream("s", "id:INT64,v:INT64", null))) {
            engine.register("q", "SELECT id, v FROM s", "id");
            engine.push("s", new Object[] {1L, 10L});
            Thread.sleep(1000);
        }
        try (PravahaEngine engine = AdvSupport.engine(settings, e -> e.declareStream("s", "id:INT64,v:STRING", null))) {
            engine.push("s", new Object[] {2L, "abc"});
            List<String> seen = new ArrayList<>();
            seen.add("schema " + engine.find("q").orElseThrow().outputSchema());
            engine.find("q")
                    .orElseThrow()
                    .view()
                    .scan()
                    .forEach(r -> seen.add(r[0] + "=" + r[1].getClass().getSimpleName()));
            return seen;
        }
    }

    @Test
    @Disabled("QE-088: a checkpoint taken when column v was BIGINT is restored into the view after v became STRING; "
            + "the view's VARCHAR column then holds a Long beside Strings")
    void qe088_aCheckpointOfAnotherOutputSchemaIsNotRestored(@TempDir Path dir) throws Exception {
        assertThat(retyped(dir)).doesNotContain("1=Long");
    }

    @Test
    void qe088_observed(@TempDir Path dir) throws Exception {
        List<String> seen = retyped(dir);
        System.out.println("NOTE QE-088 " + seen);
        assertThat(seen).contains("1=Long", "2=String");
        assertThat(seen.get(0)).contains("v VARCHAR");
    }

    // ------------------------------------------------------------------ QE-012, QE-094, QE-164

    @Test
    @Disabled("QE-012: with pravaha.dlq.directory set, a row that divides by zero stops the query (PRV-8003) instead "
            + "of going to the dead-letter queue as CQ §11 and the PRV-3010 message say")
    void qe012_aRowThatDividesByZeroGoesToTheDeadLetterQueue(@TempDir Path dir) throws Exception {
        try (PravahaEngine engine = divisionEngine(dir)) {
            Thread.sleep(2000);
            assertThat(AdvSupport.state(engine, "q")).isEqualTo("RUNNING");
            assertThat(engine.deadLetters()
                            .counts(engine.find("q").orElseThrow().name())
                            .entries())
                    .isEqualTo(1);
            assertThat(AdvSupport.rows(engine, "SELECT * FROM q")).containsExactly("1|5", "3|2");
        }
    }

    @Test
    void qe012_observed(@TempDir Path dir) throws Exception {
        try (PravahaEngine engine = divisionEngine(dir)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (AdvSupport.state(engine, "q").equals("RUNNING") && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            String state = AdvSupport.state(engine, "q");
            long entries = engine.deadLetters().counts("q").entries();
            System.out.println("NOTE QE-012 state=" + state.lines().findFirst().orElse("") + " dlqEntries=" + entries);
            assertThat(state).startsWith("FAILED PRV-8003").contains("division by zero");
            assertThat(entries).isZero();
        }
    }

    static PravahaEngine divisionEngine(Path dir) throws Exception {
        Path csv = dir.resolve("z.csv");
        Files.writeString(csv, "1,10,2\n2,10,0\n3,10,5\n");
        Map<String, String> source = Map.of("path", csv.toString(), "schema", "id:INT64,a:INT64,b:INT64");
        PravahaEngine engine = AdvSupport.engine(
                AdvSupport.durable(dir.resolve("state")),
                e -> e.declareStream("z", "id:INT64,a:INT64,b:INT64", null).bindSource("z", "filesystem", source));
        engine.register("q", "SELECT id, a / b AS r FROM z", "id");
        return engine;
    }

    @Test
    void qe094_aCorruptDeadLetterFileAtRestart(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("bad.csv");
        Files.writeString(csv, "1,10\nnot-a-number,5\n2,20\n");
        Map<String, String> source = Map.of("path", csv.toString(), "schema", "id:INT64,v:INT64");
        Path state = dir.resolve("state");
        try (PravahaEngine engine = AdvSupport.engine(
                AdvSupport.durable(state),
                e -> e.declareStream("b", "id:INT64,v:INT64", null).bindSource("b", "filesystem", source))) {
            engine.register("q", "SELECT id, v FROM b", "id");
            Thread.sleep(1500);
            System.out.println("NOTE QE-094 before: rows=" + AdvSupport.rows(engine, "SELECT * FROM q") + " dlq="
                    + engine.deadLetters().counts("q"));
        }
        List<Path> dlqFiles;
        try (Stream<Path> files = Files.walk(state.resolve("dlq"))) {
            dlqFiles = files.filter(Files::isRegularFile).toList();
        }
        for (Path f : dlqFiles) {
            Files.writeString(f, "{\"truncated\": \u0000garbage\n", StandardOpenOption.APPEND);
        }
        String after = AdvSupport.attempt(() -> {
            try (PravahaEngine engine = AdvSupport.engine(
                    AdvSupport.durable(state),
                    e -> e.declareStream("b", "id:INT64,v:INT64", null).bindSource("b", "filesystem", source))) {
                String counts = AdvSupport.attempt(() -> {
                    throw new IllegalStateException(
                            String.valueOf(engine.deadLetters().counts("q")));
                });
                String page = AdvSupport.attempt(() -> {
                    throw new IllegalStateException(
                            String.valueOf(engine.deadLetters().page("q", 0, 10)));
                });
                throw new IllegalStateException("started state=" + AdvSupport.state(engine, "q") + " counts=" + counts
                        + " page=" + page.lines().findFirst().orElse(""));
            }
        });
        System.out.println("NOTE QE-094 files=" + dlqFiles.size() + " after: "
                + after.lines().findFirst().orElse(""));
        assertThat(after).contains("started state=RUNNING");
    }

    @Test
    @Disabled("QE-164: a CSV TIMESTAMP after 2262-04-11 overflows nanoseconds silently and the row is stamped in 1677")
    void qe164_aTimestampPastTheNanosecondRangeIsRefusedNotWrapped(@TempDir Path dir) throws Exception {
        List<String> rows = farFuture(dir);
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r).doesNotContain("|-"));
    }

    @Test
    void qe164_observed(@TempDir Path dir) throws Exception {
        List<String> rows = farFuture(dir);
        System.out.println("NOTE QE-164 " + rows);
        assertThat(rows).anySatisfy(r -> assertThat(r).contains("|-"));
    }

    static List<String> farFuture(Path dir) throws Exception {
        Path csv = dir.resolve("t.csv");
        Files.writeString(csv, "1,3000-01-01T00:00:00Z\n");
        Map<String, String> source = Map.of("path", csv.toString(), "schema", "id:INT64,ts:TIMESTAMP");
        try (PravahaEngine engine = AdvSupport.engine(
                AdvSupport.durable(dir.resolve("state")),
                e -> e.declareStream("t", "id:INT64,ts:TIMESTAMP", null).bindSource("t", "filesystem", source))) {
            engine.register("q", "SELECT id, ts FROM t", "id");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            List<String> rows = List.of();
            while (rows.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
                rows = AdvSupport.rows(engine, "SELECT * FROM q");
            }
            List<String> raw = new ArrayList<>();
            engine.find("q")
                    .orElseThrow()
                    .view()
                    .scan()
                    .forEach(r -> raw.add(r[0] + "|"
                            + (r[1] instanceof Instant i ? i.getEpochSecond() * 1_000_000_000L + i.getNano() : r[1])));
            return raw;
        }
    }
}
