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
package com.ash.messaging.pravaha.embedded;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.Subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The embedded engine doing real work: declare, register, feed, read, subscribe, write out, restart.
 *
 * <p>Until this existed the embedded engine could start and stop and do nothing in between -- it
 * could not register or read a query, which made "embeddable" a property of the build rather than of
 * the product. Every test here runs the whole loop in one process with no network.
 */
class EmbeddedLoopTest {

    private static final String TXN = "user_id:STRING,amount:INT64";
    /**
     * An unwindowed aggregate keyed by its own count: every row changes the answer, so every commit
     * withdraws the old row and adds the new one. (A per-key unwindowed GROUP BY is refused by the
     * planner as unbounded state, PRV-2050, which is right and not this test's business.)
     */
    private static final String SPEND = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    private static final String BIG = "SELECT user_id, amount FROM txn WHERE amount > 100";

    record Spend(long n, long total) {}

    record Big(String userId, long amount) {}

    @Test
    void aPushedRowIsInTheAnswerWhenPushReturns() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN);
            engine.start();
            engine.register("spend", SPEND, "n");
            engine.register("big_txn", BIG, "user_id");

            assertThat(engine.push(
                            "txn", new Object[] {"u1", 300L}, new Object[] {"u2", 50L}, new Object[] {"u3", 200L}))
                    .as("two computations read txn")
                    .isEqualTo(2);

            assertThat(engine.query(Spend.class, "SELECT n, total FROM spend")).containsExactly(new Spend(3, 550L));
            assertThat(engine.query("SELECT amount FROM big_txn WHERE user_id = ?", "u3")
                            .rows()
                            .get(0)[0])
                    .isEqualTo(200L);
            assertThat(engine.query(Big.class, "SELECT * FROM big_txn"))
                    .containsExactlyInAnyOrder(new Big("u1", 300L), new Big("u3", 200L));
            assertThat(engine.queries()).containsExactly("spend", "big_txn");
            assertThat(engine.streams()).extracting(StreamSchema::name).containsExactly("txn");
        }
    }

    @Test
    void aSubscriberSeesTheRetractionBeforeTheReplacement() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN);
            engine.start();
            engine.register("spend", SPEND, "n");
            List<RowChange> seen = new CopyOnWriteArrayList<>();
            try (Subscription subscription = engine.subscribe("spend", seen::addAll)) {
                engine.push("txn", Map.of("user_id", "u1", "amount", 300L));
                engine.push("txn", Map.of("user_id", "u1", "amount", 200L));

                await(() -> seen.size() >= 3, "three changes");
                assertThat(seen.get(0).isRetraction()).isFalse();
                assertThat(seen.get(0).get("total")).isEqualTo(300L);
                // An update is a withdrawal of the old row and then the new one, in that order.
                assertThat(seen.get(1).isRetraction()).isTrue();
                assertThat(seen.get(1).weight()).isEqualTo(-1L);
                assertThat(seen.get(1).as(Spend.class)).isEqualTo(new Spend(1, 300L));
                assertThat(seen.get(2).isRetraction()).isFalse();
                assertThat(seen.get(2).values()).containsEntry("n", 2L).containsEntry("total", 500L);
                assertThat(subscription.delivered()).isEqualTo(3);
            }
        }
    }

    @Test
    void aBadRowRefusesTheWholeBatchAndAnUnknownStreamIsNamed() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN);
            engine.start();
            engine.register("big_txn", BIG, "user_id");

            assertThatThrownBy(() -> engine.push("txn", new Object[] {"u1", 300L}, new Object[] {"u2", "lots"}))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8102")
                    .hasMessageContaining("'amount'");
            assertThat(engine.query("SELECT * FROM big_txn").rows())
                    .as("the good row before the bad one must not have been delivered")
                    .isEmpty();

            assertThatThrownBy(() -> engine.push("orders", new Object[] {"x"}))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8101")
                    .hasMessageContaining("txn");
            assertThatThrownBy(() -> engine.push("txn", new Object[] {"u1"})).hasMessageContaining("1 values");
            assertThatThrownBy(() -> engine.register("spend", SPEND, "nope"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[n, total]");
        }
    }

    @Test
    void declarationsAreFixedAtStartAndCallsNeedARunningEngine() {
        PravahaEngine engine = PravahaEngine.createDefault();
        assertThatThrownBy(() -> engine.query("SELECT 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATED");
        engine.declareStream("txn", TXN);
        assertThatThrownBy(() -> engine.declareStream("txn", TXN)).hasMessageContaining("declared twice");
        engine.start();
        assertThatThrownBy(() -> engine.declareStream("later", TXN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed at start");
        engine.close();
        assertThat(engine.state()).isEqualTo(EngineState.STOPPED);
        assertThatThrownBy(engine::queries).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pauseDropsPushedRowsAndDropReleasesTheView() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN);
            engine.start();
            engine.register("spend", SPEND, "n");
            engine.push("txn", new Object[] {"u1", 300L});

            engine.pause("spend");
            assertThat(engine.find("spend").orElseThrow().state()).isEqualTo(QueryState.PAUSED);
            assertThat(engine.push("txn", new Object[] {"u1", 1L}))
                    .as("a paused query is not fed")
                    .isZero();
            assertThat(engine.query("SELECT total FROM spend").rows().get(0)[0])
                    .as("and keeps answering at the frontier it reached")
                    .isEqualTo(300L);

            engine.resume("spend");
            engine.push("txn", new Object[] {"u1", 5L});
            assertThat(engine.query("SELECT total FROM spend").rows().get(0)[0]).isEqualTo(305L);

            engine.drop("spend");
            assertThat(engine.queries()).isEmpty();
            assertThat(engine.push("txn", new Object[] {"u1", 5L})).isZero();
        }
    }

    @Test
    void aWindowOverPushedRowsClosesWhenEventTimeIsAdvanced() {
        StreamSchema clicks = StreamSchema.builder("clicks")
                .field("page", Types.string())
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build();
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream(clicks);
            engine.start();
            engine.register(
                    "page_views",
                    "SELECT window_start, page, COUNT(*) AS views "
                            + "FROM TABLE(TUMBLE(TABLE clicks, DESCRIPTOR(ts), INTERVAL '10' SECOND)) "
                            + "GROUP BY window_start, window_end, page",
                    "window_start",
                    "page");
            Instant base = Instant.parse("2026-09-19T10:00:00Z");
            engine.push(
                    "clicks", new Object[] {"home", base.plusSeconds(1)}, new Object[] {"home", base.plusSeconds(2)});
            assertThat(engine.query("SELECT * FROM page_views").rows())
                    .as("the window is still open")
                    .isEmpty();

            engine.advanceEventTime("clicks", base.plusSeconds(30));

            List<Object[]> rows =
                    engine.query("SELECT page, views FROM page_views").rows();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsExactly("home", 2L);
        }
    }

    @Test
    void aBoundSourceFeedsAQueryAndABoundSinkReceivesItsOutput(@TempDir Path dir) throws Exception {
        Path incoming = dir.resolve("txn.csv");
        Files.writeString(incoming, "u1,300\nu2,50\nu3,700\n");
        Path out = dir.resolve("out/big_txn.csv");
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN)
                    .bindSource("txn", "filesystem", Map.of("path", incoming.toString(), "schema", TXN))
                    .bindSink("large_txn", "filesystem", Map.of("path", out.toString(), "schema", TXN));
            engine.start();
            engine.register(ContinuousQuery.named("big_txn")
                    .sql("SELECT user_id, amount FROM txn WHERE amount > 100")
                    .keyedBy("user_id")
                    .writingTo("large_txn")
                    .build());

            await(() -> engine.query("SELECT * FROM big_txn").size() == 2, "the file's rows in the view");
            await(() -> lines(out).size() == 2, "the file's rows in the sink");
            assertThat(lines(out)).containsExactlyInAnyOrder("u1,300", "u3,700");
            assertThat(engine.registry().sinkOf("big_txn")).contains("large_txn");

            // Pushed rows go the same way as read ones.
            engine.push("txn", new Object[] {"u4", 900L});
            await(() -> lines(out).size() == 3, "the pushed row in the sink");
        }
    }

    @Test
    void aSinkThatCannotTakeTheChangelogIsRefusedByName(@TempDir Path dir) {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN)
                    .bindSink(
                            "totals",
                            "filesystem",
                            Map.of("path", dir.resolve("t.csv").toString(), "schema", "n:INT64,total:INT64"));
            engine.start();
            assertThatThrownBy(() -> engine.register(ContinuousQuery.named("spend")
                            .sql(SPEND)
                            .keyedBy("n")
                            .writingTo("totals")
                            .build()))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("totals");
        }
    }

    @Test
    void theConfigurationDescribesTheSameEngineTheCodeDoes(@TempDir Path dir) {
        Configuration configuration = Configuration.builder()
                .set("pravaha.node.id", "configured")
                .set("pravaha.streams.txn.schema", TXN)
                .set("pravaha.queries.spend.sql", SPEND)
                .set("pravaha.queries.spend.keys", "n")
                .set("pravaha.queries.recent.sql", "SELECT user_id, amount FROM txn")
                .set("pravaha.queries.recent.keys", "user_id")
                .set("pravaha.queries.recent.retention", "1h")
                .build();
        try (PravahaEngine engine = PravahaEngine.create(configuration)) {
            engine.start();
            assertThat(engine.instanceId()).isEqualTo("configured");
            assertThat(engine.queries()).containsExactlyInAnyOrder("spend", "recent");
            engine.push("txn", new Object[] {"u1", 10L});
            assertThat(engine.query("SELECT total FROM spend").rows().get(0)[0]).isEqualTo(10L);
        }

        Configuration twice =
                Configuration.builder().set("pravaha.streams.txn.schema", TXN).build();
        PravahaEngine clash = PravahaEngine.create(twice);
        clash.declareStream("txn", TXN);
        assertThatThrownBy(clash::start).hasMessageContaining("both in code and under pravaha.streams");
        assertThat(clash.state()).isEqualTo(EngineState.FAILED);
        clash.close();
    }

    @Test
    void aRestartedEngineComesBackWithItsQueriesAndTheirState(@TempDir Path dir) throws Exception {
        Configuration persistent = Configuration.builder()
                .set("pravaha.streams.txn.schema", TXN)
                .set(
                        "pravaha.registry.journal",
                        dir.resolve("registry/journal.log").toString())
                .set("pravaha.checkpoint.directory", dir.resolve("checkpoints").toString())
                .set("pravaha.checkpoint.interval", "100ms")
                .build();

        try (PravahaEngine first = PravahaEngine.create(persistent)) {
            first.start();
            first.register("big_txn", BIG, "user_id");
            first.push("txn", new Object[] {"u1", 300L}, new Object[] {"u2", 50L}, new Object[] {"u3", 200L});
            // Checkpoints every 100ms; waiting several intervals past the push guarantees the newest
            // one was cut after the rows were applied.
            Path checkpoints = dir.resolve("checkpoints");
            await(() -> checkpointsUnder(checkpoints) > 0, "a checkpoint");
            Thread.sleep(400);
        }

        try (PravahaEngine second = PravahaEngine.create(persistent)) {
            second.start();
            assertThat(second.queries()).as("recovered from the journal").containsExactly("big_txn");
            // Pushed rows cannot be replayed from anywhere, so their presence is the checkpoint's doing.
            assertThat(second.query(Big.class, "SELECT * FROM big_txn"))
                    .as("restored from the checkpoint, not recomputed from nothing")
                    .containsExactlyInAnyOrder(new Big("u1", 300L), new Big("u3", 200L));
            assertThat(second.push("txn", new Object[] {"u4", 400L})).isOne();
            assertThat(second.query(Big.class, "SELECT * FROM big_txn"))
                    .containsExactlyInAnyOrder(new Big("u1", 300L), new Big("u3", 200L), new Big("u4", 400L));
        }
    }

    private static long checkpointsUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .count();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private static List<String> lines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (java.io.IOException e) {
            return new ArrayList<>();
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what, e);
            }
        }
    }
}
