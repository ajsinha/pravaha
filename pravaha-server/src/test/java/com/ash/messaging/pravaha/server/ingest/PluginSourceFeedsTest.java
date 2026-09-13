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
package com.ash.messaging.pravaha.server.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gap that made "server" a misnomer.
 *
 * <p>Registration built an execution with lanes, arenas and watermarks, and nothing ever handed it a
 * row: the only production code in the engine that drove a pump was the CLI's one-shot {@code run}.
 * A query registered on a server reached RUNNING and stayed at zero rows for as long as it lived,
 * and every other feature was untestable behind that.
 */
class PluginSourceFeedsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SCHEMA_SPEC = "id:INT64,user_id:STRING,amount:INT64";

    @Test
    void aRegisteredQueryReceivesRowsFromItsBoundSource(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("txn.csv");
        Files.writeString(data, "1,ann,100\n2,bob,250\n3,ann,50\n");

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", data.toString(), "schema", SCHEMA_SPEC)));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            RegisteredQuery query =
                    registry.register("by_user", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);

            // Rows arrive without anybody pushing.
            awaitRows(query, 3);
            assertThat(query.feed().describe()).contains("txn").contains("1 partition");

            // And they are visible. This is the assertion the blocker actually comes down to, and
            // the first version of this test did not make it -- it checked rowsIn and stopped, so
            // it passed while an end-to-end run reported five thousand rows in and zero rows out.
            // A served view shows its committed frontier, and nothing on the ingest path moved it.
            awaitView(views, "SELECT user_id, amount FROM by_user", 2);
        }
    }

    @Test
    void aStreamWithNoBindingRegistersAndSaysSoRatherThanFailing(@TempDir Path dir) {
        // An embedder pushing its own rows through accept() is a supported way to run, so a server
        // that refused to register a query it could not find a file for would break all of them.
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(new PluginSourceFeeds())) {
            RegisteredQuery query =
                    registry.register("unbound", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);

            assertThat(query.feed()).isSameAs(SourceFeed.NONE);
            // The difference between a silent query an operator can diagnose and one they cannot.
            assertThat(query.feed().describe()).contains("no source is bound");
        }
    }

    @Test
    void pauseStopsTheRowsRatherThanOnlyTheAcceptPath(@TempDir Path dir) throws Exception {
        // A feed writes into the lane's inbox directly and never passes through accept(), so a pause
        // that stopped only at accept() would leave rows arriving and the view moving -- a pause in
        // name only, which is worse than not offering one.
        Path data = dir.resolve("txn.csv");
        StringBuilder many = new StringBuilder();
        // Big enough that the feed cannot drain it inside the pause window. At twenty thousand the
        // source ran dry during the settle, so the equality assertion below held whether or not
        // pause did anything -- it passed for the wrong reason, which a mutation run exposed.
        for (int i = 0; i < 600_000; i++) {
            many.append(i)
                    .append(",user_id")
                    .append(i % 7)
                    .append(',')
                    .append(i)
                    .append('\n');
        }
        Files.writeString(data, many);

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", data.toString(), "schema", SCHEMA_SPEC)));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            registry.register("paused", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);
            RegisteredQuery query = registry.require("paused");

            awaitRows(query, 1);
            registry.pause("paused");

            // Rows already in flight are not recalled, so settle first and then compare.
            Thread.sleep(200);
            long afterPause = query.rowsIn();
            Thread.sleep(500);
            assertThat(query.rowsIn())
                    .as("a paused query must stop receiving rows, not merely stop accepting them")
                    .isEqualTo(afterPause);

            registry.resume("paused");
            awaitRows(query, afterPause + 1);
        }
    }

    @Test
    void aBindingNamingAPluginThatIsNotThereIsRefusedWithWhatIsAvailable() {
        PluginSourceFeeds feeds = new PluginSourceFeeds().bind(new SourceBinding("txn", "kafka", Map.of("topic", "t")));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            assertThatThrownBy(() -> registry.register(
                            "bad", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5090")
                    .hasMessageContaining("kafka")
                    // Naming what *is* there turns "no such plugin" into a one-line fix.
                    .hasMessageContaining("filesystem");
        }
    }

    @Test
    void aFailedBindingDoesNotLeaveTheQueryHalfStarted(@TempDir Path dir) {
        // Without the unwind, a registration that failed here left a lane thread and an arena alive
        // for the lifetime of the process, holding memory nothing could reach.
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "filesystem",
                        Map.of("path", dir.resolve("missing.csv").toString(), "schema", SCHEMA_SPEC)));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            assertThatThrownBy(() -> registry.register(
                            "broken", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS))
                    .isInstanceOf(PravahaException.class);
            assertThat(registry.names()).doesNotContain("broken");
        }
    }

    @Test
    void aRegisteredQueryIsCheckpointedWhenADirectoryIsConfigured(@TempDir Path dir) throws Exception {
        // PeriodicCheckpointer and FileCheckpointStore were both built and tested, and neither was
        // ever constructed outside a test -- so a registered query kept no checkpoints at all. The
        // journal brought definitions back after a restart and their accumulated state came back
        // empty. The ingestion gap hid it: with no rows arriving there was no state to lose.
        Path data = dir.resolve("txn.csv");
        Files.writeString(data, "1,ann,100\n2,bob,250\n3,ann,50\n");
        Path checkpoints = dir.resolve("checkpoints");

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", data.toString(), "schema", SCHEMA_SPEC)));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN)
                .feedingFrom(feeds)
                .checkpointingTo(
                        checkpoints,
                        com.ash.messaging.pravaha.common.config.Configuration.builder()
                                // A second, not the one-minute default: this test is about whether
                                // anything checkpoints at all, and waiting a minute to find out
                                // would make it a test nobody runs.
                                .set("pravaha.checkpoint.interval", "1s")
                                .build())) {
            RegisteredQuery query = registry.register(
                    "checkpointed", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);
            awaitRows(query, 3);

            Path own = checkpoints.resolve("checkpointed");
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && !(Files.isDirectory(own) && !isEmpty(own))) {
                Thread.sleep(50);
            }
            assertThat(own).as("each query checkpoints into its own directory").isDirectory();
            assertThat(isEmpty(own))
                    .as("a running query must actually write checkpoints, not merely be configured to")
                    .isFalse();
        }
    }

    @Test
    void aLargeFeedIsNotLostToARaceBetweenTheLaneTheFeedAndAReader(@TempDir Path dir) throws Exception {
        // Three threads reach ServedView's maps and none of them knew about the others: the lane
        // applies rows, this feed commits on its own timer, and readers scan. Nothing committed
        // before ingestion worked, so the collision was unreachable; the moment it worked, commit
        // iterated the overlay on the feed thread while the lane wrote to it and the feed died of a
        // ConcurrentModificationException -- silently, after 181,248 of 200,000 rows, with the query
        // still reporting RUNNING and describe() still saying "reading txn (1 partition)".
        //
        // The key is txn_id, unique per row, so the view's size must equal the row count exactly.
        // The first version of this check keyed on user_id with 500 distinct values and 200,000 rows
        // collapsed to a correct-looking 500 -- it would have passed against the bug.
        int rows = 60_000;
        Path data = dir.resolve("many.csv");
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            csv.append(i).append(",user").append(i % 50).append(',').append(i).append('\n');
        }
        Files.writeString(data, csv);

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("txn", "filesystem", Map.of("path", data.toString(), "schema", SCHEMA_SPEC)));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            RegisteredQuery query =
                    registry.register("wide", "SELECT id, amount FROM txn", List.of(0), Principal.ANONYMOUS);

            // A reader hammering the view throughout, because a reader is the third thread and the
            // one a deployment actually has. Without it this test only covers two of the three.
            java.util.concurrent.atomic.AtomicReference<Throwable> readerFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean(true);
            com.ash.messaging.pravaha.serving.ViewQuery reader = new com.ash.messaging.pravaha.serving.ViewQuery(views);
            Thread scans = new Thread(
                    () -> {
                        while (reading.get()) {
                            try {
                                reader.execute("SELECT id FROM wide").size();
                            } catch (Throwable t) {
                                readerFailure.set(t);
                                return;
                            }
                        }
                    },
                    "test-reader");
            scans.setDaemon(true);
            scans.start();

            try {
                awaitRows(query, rows);
                awaitView(views, "SELECT id, amount FROM wide", rows);
            } finally {
                reading.set(false);
                scans.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
            }

            assertThat(readerFailure.get())
                    .as("a reader scanning while the lane applies and the feed commits must not see a broken map")
                    .isNull();
            assertThat(query.rowsIn())
                    .as("every row must arrive; a feed that dies mid-stream stops counting and says nothing")
                    .isEqualTo(rows);
            assertThat(query.feed().describe())
                    .as("a feed that died records why rather than going on describing itself as healthy")
                    .doesNotContain("stopped");
        }
    }

    @Test
    void aSourceCanRetractWhatItInserted(@TempDir Path dir) throws Exception {
        // The blocker under every Z-set defect. Four of five plugins hard-coded weight +1 and the
        // schema grammar had no operation column, so no configured source could deliver a negative
        // weight -- and the whole retraction model was unreachable from a real deployment. Every
        // defect in it survived because nobody could get a retraction in to find one.
        Path data = dir.resolve("ops.csv");
        Files.writeString(data, "1,ann,100,I\n2,bob,250,I\n3,cat,50,I\n2,bob,250,D\n");

        StreamSchema opSchema = StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("op", Types.string())
                .build();

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "filesystem",
                        Map.of(
                                "path", data.toString(),
                                "schema", "id:INT64,user_id:STRING,amount:INT64,op:STRING",
                                "op.column", "op")));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, opSchema).feedingFrom(feeds)) {
            RegisteredQuery query =
                    registry.register("live", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);

            awaitRows(query, 4);
            // Three inserted, one retracted, so two survive: ann and cat. bob's insert and his
            // retraction cancel, which is the whole point.
            awaitView(views, "SELECT user_id, amount FROM live", 2);

            List<Object[]> rows = new com.ash.messaging.pravaha.serving.ViewQuery(views)
                    .execute("SELECT user_id FROM live")
                    .rows();
            assertThat(rows).hasSize(2);
            assertThat(rows.stream().map(r -> r[0]).toList())
                    .as("bob inserted then retracted must not be in the view")
                    .containsExactlyInAnyOrder("ann", "cat");
        }
    }

    /** Waits for a view to hold {@code expected} rows, or fails saying what it held. */
    private static void awaitView(ViewCatalog views, String sql, int expected) throws InterruptedException {
        com.ash.messaging.pravaha.serving.ViewQuery reader = new com.ash.messaging.pravaha.serving.ViewQuery(views);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int size = -1;
        while (System.nanoTime() < deadline) {
            size = reader.execute(sql).size();
            if (size >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(size)
                .as(
                        "the view held %d rows after fifteen seconds; %d were expected. Rows reaching the "
                                + "engine is not the same as rows being readable",
                        size, expected)
                .isGreaterThanOrEqualTo(expected);
    }

    private static boolean isEmpty(Path directory) throws java.io.IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Waits for the feed to deliver, or fails saying what it managed.
     *
     * <p>A poll rather than a sleep: the feed naps a millisecond between empty reads, so any fixed
     * wait is either flaky on a loaded machine or slow on an idle one.
     */
    private static void awaitRows(RegisteredQuery query, long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (query.rowsIn() >= atLeast) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(query.rowsIn())
                .as("the feed delivered %d rows in ten seconds; %d were expected", query.rowsIn(), atLeast)
                .isGreaterThanOrEqualTo(atLeast);
    }
}
