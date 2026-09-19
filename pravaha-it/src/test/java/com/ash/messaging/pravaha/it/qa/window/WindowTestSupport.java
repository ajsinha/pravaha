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
package com.ash.messaging.pravaha.it.qa.window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixtures and harnesses for {@code docs/qa/cases/WIN.md}'s executable form.
 *
 * <p>Split out of the test classes themselves so that neither exceeds the project's 1500-line
 * source file limit -- {@link WindowAnswerTest} covers sections 1-8 of WIN.md and
 * {@link WindowClosingAnswerTest} covers sections 9-14, and both extend this class for its
 * datasets, its two harnesses (configured, the deployment path; embedded, direct on the window
 * classes) and its small utilities, so a case reads the same whichever file it lives in.
 */
abstract class WindowTestSupport {

    protected static final long MS = 1_000_000L;
    protected static final long SECOND = 1_000_000_000L;
    protected static final long MINUTE = 60 * SECOND;
    protected static final long HOUR = 60 * MINUTE;
    protected static final long DAY = 24 * HOUR;

    protected static final String SPEC = "txn_id:INT64,user_id:INT64,amount:INT64,event_time:TIMESTAMP";
    protected static final String NULLABLE_SPEC = "txn_id:INT64,user_id:INT64,amount:INT64?,event_time:TIMESTAMP";

    /** Q_T(S) of WIN.md section 0.2: tumbling, size {@code seconds}. */
    protected static String tumble(String stream, String interval) {
        return "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                + "TABLE(TUMBLE(TABLE " + stream + ", DESCRIPTOR(event_time), INTERVAL '" + interval + ")) "
                + "GROUP BY window_start, window_end, user_id";
    }

    /**
     * Q_H(D,S) of section 0.2. The first interval in the SQL text is the <em>slide</em> and the
     * second is the size -- Calcite's order, which {@code PhysicalPlanBuilder} reverses.
     */
    protected static String hop(String stream, String slide, String size) {
        return "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                + "TABLE(HOP(TABLE " + stream + ", DESCRIPTOR(event_time), INTERVAL '" + slide + ", INTERVAL '"
                + size + ")) GROUP BY window_start, window_end, user_id";
    }

    // ------------------------------------------------------------------ datasets

    /** Dataset A of section 0.3, six rows, two users. */
    protected static final String A = csv(
            row(1, 100, 10, 1 * SECOND),
            row(2, 100, 20, 5 * SECOND),
            row(3, 200, 30, 7 * SECOND),
            row(4, 100, 40, 11 * SECOND),
            row(5, 200, 50, 19 * SECOND),
            row(6, 100, 60, 25 * SECOND));

    /** A plus the pusher row of section 0.3: user 999, amount 0, at 40.000. */
    protected static final String A_PLUS = A + row(7, 999, 0, 40 * SECOND);

    /** Dataset B: boundaries, one user, amounts are powers of two. */
    protected static final String B = csv(
            row(1, 100, 1, 0L),
            row(2, 100, 2, 10 * SECOND - 1),
            row(3, 100, 4, 10 * SECOND),
            row(4, 100, 8, 20 * SECOND - 1),
            row(5, 100, 16, 20 * SECOND),
            row(6, 100, 32, 20 * SECOND),
            row(7, 100, 64, 30 * SECOND - 1),
            row(8, 100, 128, 30 * SECOND));

    protected static final String B_PLUS = B + row(9, 999, 0, 50 * SECOND);

    /** Dataset C: amounts 10, 20, 30 at 5.000, 15.000, 25.000. */
    protected static final String C =
            csv(row(1, 100, 10, 5 * SECOND), row(2, 100, 20, 15 * SECOND), row(3, 100, 30, 25 * SECOND));

    protected static final String C_PLUS = C + row(4, 999, 0, 60 * SECOND);

    /** Dataset D: amounts 1 and 2 at 0.000 and 2.000, for the non-dividing hop. */
    protected static final String D_PLUS =
            csv(row(1, 100, 1, 0L), row(2, 100, 2, 2 * SECOND)) + row(3, 999, 0, 30 * SECOND);

    protected static String row(long id, long user, long amount, long eventTime) {
        return id + "," + user + "," + amount + "," + eventTime + "\n";
    }

    protected static String csv(String... rows) {
        return String.join("", rows);
    }

    // ------------------------------------------------------------------ helpers

    /** Dataset O of section 7: 120 rows one per second, amount 1, plus the pusher at 2000.000. */
    protected static String open() {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            csv.append(row(i, 100, 1, i * SECOND));
        }
        return csv + row(121, 999, 0, 2000 * SECOND);
    }

    /** Dataset S(U) of section 5, and its three expected windows, at the given unit. */
    protected static List<String> sizeSweep(Path dir, long unit, String interval) throws Exception {
        return sizeSweep(dir, unit, interval, 3);
    }

    protected static List<String> sizeSweep(Path dir, long unit, String interval, int expected) throws Exception {
        String data = csv(
                        row(1, 100, 1, 0L),
                        row(2, 100, 2, unit / 2),
                        row(3, 100, 4, unit),
                        row(4, 100, 8, 2 * unit - 1),
                        row(5, 100, 16, 2 * unit))
                + row(6, 999, 0, 10 * unit);
        return configured(dir, data, SPEC, Duration.ZERO, tumble("s0", interval), List.of(0, 1, 2), 6, expected);
    }

    /** agg.csv of section 14, under one aggregate, keyed on (window_start, window_end, user_id). */
    protected static List<String> aggregate(Path dir, String expression) throws Exception {
        String data = csv(
                        row(1, 100, 5, SECOND),
                        row(2, 100, 5, 2 * SECOND),
                        row(3, 100, 7, 3 * SECOND),
                        "4,100,,4000000000\n", // the NULL amount
                        row(5, 200, 11, 5 * SECOND))
                + row(6, 999, 0, 40 * SECOND);
        return configured(
                dir.resolve(expression.replaceAll("[^A-Za-z]", "")),
                data,
                NULLABLE_SPEC,
                Duration.ZERO,
                "SELECT window_start, window_end, user_id, " + expression + " FROM "
                        + "TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                List.of(0, 1, 2),
                6,
                2);
    }

    /**
     * The configured path: a binding, a registry feeding from it, a watermark clock, and the view.
     *
     * <p>Waits for every row to arrive <em>before</em> looking at the view, because a windowing
     * assertion over a source that has not finished reading is an assertion about nothing -- which
     * is the second of WIN.md section 0.5's three vacuity modes.
     */
    protected static List<String> configured(
            Path dir,
            String csv,
            String schemaSpec,
            Duration outOfOrderness,
            String sql,
            List<Integer> keys,
            long expectRowsIn,
            int expectViewRows)
            throws Exception {
        Files.createDirectories(dir);
        Path data = dir.resolve("data.csv");
        Files.writeString(data, csv);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = registry(views, dir, data, schemaSpec, outOfOrderness)) {
            RegisteredQuery query = registry.register("v", sql, keys, Principal.ANONYMOUS);
            awaitRowsIn(query, expectRowsIn);
            awaitView(views, "SELECT * FROM v", expectViewRows);
            // Settle, then read once more: a view that overshoots the expected count is as much a
            // failure as one that undershoots, and only a second read after quiet can see it.
            Thread.sleep(250);
            List<Object[]> rows =
                    new ViewQuery(views).execute("SELECT * FROM v").rows();
            assertThat(rows)
                    .as("the view settled on %d rows; %d were expected", rows.size(), expectViewRows)
                    .hasSize(expectViewRows);
            return render(rows);
        }
    }

    protected static QueryRegistry registry(
            ViewCatalog views, Path dir, Path data, String schemaSpec, Duration outOfOrderness) {
        StreamSchema.Builder builder = StreamSchema.builder("s0");
        for (String column : schemaSpec.split(",")) {
            String[] parts = column.split(":");
            builder.field(parts[0], typeOf(parts[1]));
        }
        StreamSchema s0 =
                builder.eventTime("event_time").outOfOrderness(outOfOrderness).build();

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "s0",
                        "filesystem",
                        Map.of("path", data.toString(), "schema", schemaSpec, "event.time", "event_time")));
        return new QueryRegistry(views, s0)
                .feedingFrom(feeds)
                // The enforced minimum idle timeout, and a tick fine enough that a test waits
                // milliseconds rather than seconds for a window to close.
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50));
    }

    protected static com.ash.messaging.pravaha.api.data.PravahaType typeOf(String name) {
        return switch (name) {
            case "INT64" -> Types.int64();
            case "INT64?" -> Types.int64().withNullable(true);
            case "TIMESTAMP" -> Types.timestamp();
            default -> throw new IllegalArgumentException(name);
        };
    }

    protected static com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan(String sql) {
        StreamSchema s0 = StreamSchema.builder("s0")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(s0).plan(sql));
    }

    /**
     * The first descendant of {@code operator} whose label starts with {@code prefix}.
     *
     * <p>The window assigner sits under a {@code Project} that resolves the boundary columns'
     * names, so "the assigner is the immediate input" does not hold once a projection is involved.
     */
    protected static com.ash.messaging.pravaha.runtime.plan.PhysicalOperator findByLabel(
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator operator, String prefix) {
        if (operator.label().startsWith(prefix)) {
            return operator;
        }
        for (com.ash.messaging.pravaha.runtime.plan.PhysicalOperator input : operator.inputs()) {
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator found = findByLabel(input, prefix);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The repository root, found by walking up from the working directory to a file it owns. */
    protected static java.nio.file.Path repoRoot() {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.exists(root.resolve("docs/CONTINUOUS_QUERIES.md")) && root.getParent() != null) {
            root = root.getParent();
        }
        return root;
    }

    /** File names under any module's {@code src/main} whose content matches {@code regex}. */
    protected static String grepMainSources(String regex) throws java.io.IOException {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        StringBuilder matches = new StringBuilder();
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(repoRoot())) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    // Not a nested checkout. Agents work in git worktrees under .claude/, so a walk
                    // of the repository root finds one copy of every source file per running agent
                    // -- and this assertion, which counts the files a call site appears in, saw
                    // three QueryExecution.java and failed for a reason with nothing to do with the
                    // engine.
                    .filter(p -> !p.startsWith(nestedCheckouts()))
                    .forEach(p -> {
                        try {
                            if (pattern.matcher(java.nio.file.Files.readString(p))
                                    .find()) {
                                matches.append(p.getFileName()).append('\n');
                            }
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        }
        return matches.toString();
    }

    /**
     * H1 over a windowed plan with a two-column key (`key`, `amount`) on `event_time`, used by the
     * lateness cases (WIN-170 to WIN-174) where the correction path needs {@code allowedLateness}
     * above zero -- reachable end to end since {@code StreamSchema.Builder.allowedLateness}, through
     * exactly the planner path {@code PhysicalPlanBuilder.allowedLatenessOf} reads, rather than by
     * reconstructing the operator by hand.
     */
    protected static final class H1 implements AutoCloseable {
        private final com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline pipeline;
        private final com.ash.messaging.pravaha.common.arena.RowArena arena;
        private final com.ash.messaging.pravaha.common.row.BinaryRowWriter writer;
        private final com.ash.messaging.pravaha.common.row.BinaryRowView reader;
        private final RowLayout layout;
        private final com.ash.messaging.pravaha.serving.ServedView view;
        private final com.ash.messaging.pravaha.serving.ViewSink sink;
        private final List<com.ash.messaging.pravaha.serving.ViewChange> lastBatch = new ArrayList<>();
        private long sequence;

        H1(String sql, long allowedLatenessNanos, List<Integer> viewKeys) {
            StreamSchema txn = StreamSchema.builder("txn")
                    .field("key", Types.int64())
                    .field("amount", Types.int64())
                    .field("event_time", Types.timestamp())
                    .eventTime("event_time")
                    .allowedLateness(Duration.ofNanos(allowedLatenessNanos))
                    .build();
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan =
                    new PhysicalPlanBuilder().build(SqlPlanner.withStreams(txn).plan(sql));
            view = new com.ash.messaging.pravaha.serving.ServedView("v", plan.outputSchema(), viewKeys, 100_000);
            sink = new com.ash.messaging.pravaha.serving.ViewSink(view, plan.outputSchema());
            sink.onCommit((batch, frontier) -> {
                lastBatch.clear();
                lastBatch.addAll(batch);
            });
            layout = RowLayout.of(txn);
            arena = new com.ash.messaging.pravaha.common.arena.RowArena(
                    com.ash.messaging.pravaha.common.memory.MemoryAccess.best(), 1 << 20, 8);
            pipeline = com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.compile(
                    plan, (com.ash.messaging.pravaha.runtime.exec.RowOutput) sink::begin);
            writer = new com.ash.messaging.pravaha.common.row.BinaryRowWriter(layout);
            reader = new com.ash.messaging.pravaha.common.row.BinaryRowView(layout);
        }

        void feed(long key, long amount, long eventTime) {
            long handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setLong(0, key);
            writer.setLong(1, amount);
            writer.setLong(2, eventTime)
                    .weight(1)
                    .eventTimestampNanos(eventTime)
                    .sequence(++sequence)
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }

        /** Advances the watermark and returns the batch of changes this advance alone committed. */
        List<com.ash.messaging.pravaha.serving.ViewChange> advance(long watermark) {
            pipeline.advanceWatermark(watermark);
            lastBatch.clear();
            sink.commit(sink.appliedFrontier());
            return List.copyOf(lastBatch);
        }

        long lateRecords() {
            return pipeline.lateRecords();
        }

        long corrections() {
            return pipeline.corrections();
        }

        void lateOutput(java.util.function.Consumer<com.ash.messaging.pravaha.api.data.RowView> sink) {
            pipeline.lateOutput(sink);
        }

        com.ash.messaging.pravaha.serving.ServedView view() {
            return view;
        }

        @Override
        public void close() {
            pipeline.close();
            arena.close();
        }
    }

    /**
     * H1 over a windowed plan built on a fixed single-key {@code txn} schema: feed every row, then
     * advance the watermark once per entry of {@code watermarks}, returning how many rows the view
     * received on each advance (WIN-156, WIN-160).
     */
    protected static List<Integer> advanceSequenceAndCountEmissions(
            String sql, long allowedLatenessNanos, List<long[]> rows, List<Long> watermarks) throws Exception {
        StreamSchema txn = StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .allowedLateness(Duration.ofNanos(allowedLatenessNanos))
                .build();
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(txn).plan(sql));
        com.ash.messaging.pravaha.serving.ServedView view =
                new com.ash.messaging.pravaha.serving.ServedView("v", plan.outputSchema(), List.of(2), 100_000);
        com.ash.messaging.pravaha.serving.ViewSink sink =
                new com.ash.messaging.pravaha.serving.ViewSink(view, plan.outputSchema());
        RowLayout layout = RowLayout.of(txn);
        List<Integer> results = new ArrayList<>();
        try (com.ash.messaging.pravaha.common.arena.RowArena arena =
                        new com.ash.messaging.pravaha.common.arena.RowArena(
                                com.ash.messaging.pravaha.common.memory.MemoryAccess.best(), 1 << 20, 8);
                com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline pipeline =
                        com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.compile(
                                plan, (com.ash.messaging.pravaha.runtime.exec.RowOutput) sink::begin)) {
            com.ash.messaging.pravaha.common.row.BinaryRowWriter writer =
                    new com.ash.messaging.pravaha.common.row.BinaryRowWriter(layout);
            com.ash.messaging.pravaha.common.row.BinaryRowView reader =
                    new com.ash.messaging.pravaha.common.row.BinaryRowView(layout);
            long sequence = 0;
            for (long[] row : rows) {
                long handle = arena.allocate(layout.rowSize(256));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setLong(0, 1L); // one fixed key
                writer.setLong(1, row[1]); // amount
                writer.setLong(2, row[0]) // event_time
                        .weight(1)
                        .eventTimestampNanos(row[0])
                        .sequence(++sequence)
                        .commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            for (long watermark : watermarks) {
                long before = sink.rowsApplied();
                pipeline.advanceWatermark(watermark);
                results.add((int) (sink.rowsApplied() - before));
            }
        }
        return results;
    }

    /**
     * As {@link #advanceSequenceAndCountEmissions}, but a single watermark advance followed by
     * {@code finish()} (WIN-167): returns {@code [afterAdvance, afterFinish]}.
     */
    protected static List<Integer> advanceSequenceAndCountEmissionsThenFinish(
            String sql, List<long[]> rows, long watermark) throws Exception {
        StreamSchema txn = StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(txn).plan(sql));
        com.ash.messaging.pravaha.serving.ServedView view =
                new com.ash.messaging.pravaha.serving.ServedView("v", plan.outputSchema(), List.of(2), 100_000);
        com.ash.messaging.pravaha.serving.ViewSink sink =
                new com.ash.messaging.pravaha.serving.ViewSink(view, plan.outputSchema());
        RowLayout layout = RowLayout.of(txn);
        List<Integer> results = new ArrayList<>();
        try (com.ash.messaging.pravaha.common.arena.RowArena arena =
                        new com.ash.messaging.pravaha.common.arena.RowArena(
                                com.ash.messaging.pravaha.common.memory.MemoryAccess.best(), 1 << 20, 8);
                com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline pipeline =
                        com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.compile(
                                plan, (com.ash.messaging.pravaha.runtime.exec.RowOutput) sink::begin)) {
            com.ash.messaging.pravaha.common.row.BinaryRowWriter writer =
                    new com.ash.messaging.pravaha.common.row.BinaryRowWriter(layout);
            com.ash.messaging.pravaha.common.row.BinaryRowView reader =
                    new com.ash.messaging.pravaha.common.row.BinaryRowView(layout);
            long sequence = 0;
            for (long[] row : rows) {
                long handle = arena.allocate(layout.rowSize(256));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setLong(0, 1L);
                writer.setLong(1, row[1]);
                writer.setLong(2, row[0])
                        .weight(1)
                        .eventTimestampNanos(row[0])
                        .sequence(++sequence)
                        .commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            long before = sink.rowsApplied();
            pipeline.advanceWatermark(watermark);
            results.add((int) (sink.rowsApplied() - before));
            before = sink.rowsApplied();
            pipeline.finish();
            results.add((int) (sink.rowsApplied() - before));
        }
        return results;
    }

    protected static List<String> render(List<Object[]> rows) {
        List<String> rendered = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    text.append('|');
                }
                text.append(row[i]);
            }
            rendered.add(text.toString());
        }
        return rendered;
    }

    protected static long sumOf(List<String> rows, int ordinal) {
        long total = 0;
        for (String row : rows) {
            total += Long.parseLong(row.split("\\|")[ordinal]);
        }
        return total;
    }

    /** The n column summed over every row whose window starts at {@code windowStart}. */
    protected static long countOf(List<String> rows, long windowStart) {
        long total = 0;
        for (String row : rows) {
            String[] parts = row.split("\\|");
            if (Long.parseLong(parts[0]) == windowStart) {
                total += Long.parseLong(parts[3]);
            }
        }
        return total;
    }

    protected static void awaitRowsIn(RegisteredQuery query, long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && query.rowsIn() < atLeast) {
            Thread.sleep(10);
        }
        assertThat(query.rowsIn())
                .as("the feed delivered %d rows; %d are in the file. A windowing assertion over a "
                        + "source that has not finished reading is an assertion about nothing")
                .isGreaterThanOrEqualTo(atLeast);
        assertThat(query.failure()).as("the lane must not have died").isEmpty();
    }

    protected static void awaitView(ViewCatalog views, String sql, int expected) throws InterruptedException {
        ViewQuery reader = new ViewQuery(views);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int size = -1;
        while (System.nanoTime() < deadline) {
            size = reader.execute(sql).size();
            if (size >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(size)
                .as("the view held %d rows after twenty seconds; %d were expected", size, expected)
                .isGreaterThanOrEqualTo(expected);
    }

    /**
     * The directory QA agents keep their git worktrees in, under the tree being walked.
     *
     * <p>Those worktrees are full copies of this repository, so a walk of the root sees one copy of
     * every source file per running agent -- and a check that counts the files a call site appears
     * in reports three where it expects one. It presents as a product change and is not one.
     *
     * <p>Compared against the root actually being walked, not as a substring. A test run from
     * inside one of those worktrees has a root whose own path contains {@code /.claude/}, and a
     * substring test would discard the entire tree and pass on nothing at all.
     */
    private static Path nestedCheckouts() {
        return repoRoot().resolve(".claude");
    }
}
