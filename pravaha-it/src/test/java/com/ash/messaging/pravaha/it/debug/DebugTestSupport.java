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
package com.ash.messaging.pravaha.it.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A registry with a checkpointed query over a file, arranged so that a fork has rows to step (ADR-048).
 *
 * <p>The arrangement is the fiddly part and is the same for every test here, so it lives once.
 * Getting a debug session something to step through means getting a checkpoint taken at a position
 * the source has <em>not</em> reached the end of, and a file source reads a small file faster than
 * a test can watch. So:
 *
 * <ol>
 *   <li>the file is written with a few rows, and the query consumes them;
 *   <li>the query's feed is <strong>paused</strong>, which stops its offsets moving;
 *   <li>a checkpoint taken after that pause therefore records exactly those rows;
 *   <li>more rows are appended, which the paused query will not read and a fork will -- the
 *       filesystem reader's position is a line number and it tails.
 * </ol>
 *
 * <p>That leaves a live query at a known position, with known rows beyond it, and nothing moving
 * underneath a test that then asks whether a fork disturbed it. No watermark generator is
 * configured, on purpose: event time moves when a step says so and at no other moment, which is
 * one of the four things {@code DebugDeterminismTest} rests on.
 */
final class DebugTestSupport implements AutoCloseable {

    static final long SECOND = 1_000_000_000L;

    static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            // Ten seconds of allowed lateness, which is what makes a fired window inspectable: a
            // window is forgotten once the watermark passes its end plus the lateness, so with the
            // default of none a window's contents are gone in the step that fired them.
            .allowedLateness(Duration.ofSeconds(10))
            .build();

    static final String SCHEMA_SPEC = "user_id:STRING,amount:INT64,event_time:TIMESTAMP";

    /**
     * An unwindowed aggregate over the whole stream, which is what most of these tests step.
     *
     * <p>Unkeyed rather than {@code GROUP BY user_id}: a keyed aggregate over an unwindowed stream
     * is refused with {@code PRV-2050}, because its key space has no bound. The windowed form
     * below is the one with several groups to page through.
     */
    static final String TOTAL = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    /** A tumbling aggregate, so a session has windows to advance time over and groups to page. */
    static final String PER_SECOND = "SELECT window_start, window_end, user_id, SUM(amount) AS total FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    static final Principal OWNER = Principal.of("debug-tests");

    static String row(String user, long amount, long eventTimeNanos) {
        return user + "," + amount + "," + eventTimeNanos;
    }

    private final Path file;
    private final ViewCatalog views = new ViewCatalog();
    private final QueryRegistry registry;
    private final RegisteredQuery query;
    private final long forkCheckpoint;
    private final List<String> beyond;

    static Configuration defaults() {
        return Configuration.builder()
                .set("pravaha.checkpoint.interval", "200ms")
                .set("pravaha.checkpoint.keep", "5")
                .build();
    }

    DebugTestSupport(Path dir, String sql, List<Integer> keys, List<String> before, List<String> after)
            throws Exception {
        this(dir, sql, keys, before, after, defaults());
    }

    DebugTestSupport(
            Path dir,
            String sql,
            List<Integer> keys,
            List<String> before,
            List<String> after,
            Configuration configuration)
            throws Exception {
        this.beyond = List.copyOf(after);
        this.file = dir.resolve("txn.csv");
        Files.writeString(file, String.join("\n", before) + "\n");
        Path checkpoints = Files.createDirectories(dir.resolve("checkpoints"));

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "filesystem",
                        Map.of("path", file.toString(), "schema", SCHEMA_SPEC, "event.time", "event_time")));
        registry = new QueryRegistry(views, TXN)
                .feedingFrom(feeds)
                .checkpointingTo(checkpoints, configuration)
                .configuredWith(configuration);
        query = registry.register("spend", sql, keys, OWNER);

        // Every row before the pause, and only then the pause: a checkpoint taken while rows were
        // still arriving would record a position nobody in this test knows.
        await(() -> query.rowsIn() >= before.size(), "the query to read the file it was given");
        registry.pause("spend");
        query.commit();

        // A checkpoint taken *after* the pause. Not merely "a checkpoint": the first one may have
        // been written half way through the file, and forking from it would leave the test's
        // arithmetic describing rows the fork never saw.
        List<Long> already = registry.debugSessions().checkpointsOf("spend", OWNER);
        await(
                () -> registry.debugSessions().checkpointsOf("spend", OWNER).stream()
                        .anyMatch(id -> !already.contains(id)),
                "a checkpoint to be taken while the feed was paused");
        forkCheckpoint = registry.debugSessions().checkpointsOf("spend", OWNER).stream()
                .filter(id -> !already.contains(id))
                .max(Long::compare)
                .orElseThrow();

        // The rows a fork will find beyond the checkpoint. Appended after it, so the paused query
        // has not read them and its view cannot contain them.
        if (!beyond.isEmpty()) {
            Files.writeString(file, String.join("\n", beyond) + "\n", StandardOpenOption.APPEND);
        }
    }

    QueryRegistry registry() {
        return registry;
    }

    RegisteredQuery query() {
        return query;
    }

    ViewCatalog views() {
        return views;
    }

    /** The checkpoint every test in this file forks from. */
    long checkpoint() {
        return forkCheckpoint;
    }

    List<String> rowsBeyondTheCheckpoint() {
        return beyond;
    }

    static void await(java.util.function.BooleanSupplier until, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (until.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(until.getAsBoolean()).as("waited 30s for %s", what).isTrue();
    }

    @Override
    public void close() throws IOException {
        registry.close();
    }
}
