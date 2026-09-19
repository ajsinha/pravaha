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
package com.ash.messaging.pravaha.plugin.kafka;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.SinkFactory;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A continuous query's revising answer, written to a real Kafka topic by the engine's own delivery.
 *
 * <p>Nothing here calls the sink directly. A {@link QueryRegistry} is given a {@link SinkFactory}
 * over {@link KafkaSinkPlugin}, queries are registered against it, rows are pushed in and checkpoints
 * taken by hand -- so what a {@code read_committed} consumer reads is what the engine's delivery,
 * checkpoint cut and restore made of it.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class KafkaSinkRegistrationTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    /** A global aggregate: every change retracts the previous answer and writes the new one. */
    private static final String SPEND = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    private static final String LATEST_SQL = "SELECT user_id, amount FROM txn";

    @TempDir
    Path root;

    private RowArena arena;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
        arena.close();
    }

    @Test
    void aRevisingAggregateReachesTheTopicOneWholeCheckpointAtATime() throws Exception {
        String topic = KafkaBroker.topic("spend", 1, true);
        QueryRegistry registry = checkpointed(new KafkaSinks().bind("spend_topic", spend(topic)));
        RegisteredQuery query = registry.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_topic");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        commitUntil(query, 2L);
        assertThat(KafkaBroker.readCommitted(topic))
                .as("staged, not visible, until the checkpoint recording it is durable")
                .isEmpty();

        checkpointerOf(query).checkpointNow();
        assertThat(table(KafkaBroker.readCommitted(topic))).containsExactly(Map.entry("{\"n\":2}", "350"));

        feed(query, "u1", 75L);
        commitUntil(query, 3L);
        assertThat(table(KafkaBroker.readCommitted(topic))).containsExactly(Map.entry("{\"n\":2}", "350"));
        checkpointerOf(query).checkpointNow();

        List<KafkaBroker.Seen> log = KafkaBroker.readCommitted(topic);
        assertThat(log)
                .as("every answer the query gave, and the retraction of each it replaced, once")
                .extracting(Object::toString)
                .containsExactly(
                        "{\"n\":2}={\"n\":2,\"total\":350}", "{\"n\":2}=null", "{\"n\":3}={\"n\":3,\"total\":425}");
        assertThat(table(log))
                .as("read as a table -- as compaction leaves it -- the one current answer")
                .containsExactly(Map.entry("{\"n\":3}", "425"));
        assertThat(registry.sinkGuarantee("spend_so_far"))
                .hasValueSatisfying(text -> assertThat(text).startsWith("exactly-once"));
    }

    /**
     * The crash exactly-once exists for, end to end: the checkpoint is durable and the process dies
     * before its commit reaches Kafka. The restart restores the checkpoint, commits the handle it
     * recorded from the staging topic, and the replay writes what came after it.
     */
    @Test
    void aCrashAfterTheCheckpointBeforeTheCommitLosesNothingAndRepeatsNothing() throws Exception {
        String topic = KafkaBroker.topic("latest", 1, true);
        Map<String, String> binding = latest(topic);
        KafkaSinks before = new KafkaSinks().bind("latest_topic", binding);
        QueryRegistry first = checkpointed(before);
        RegisteredQuery query = first.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_topic");

        feed(query, "u1", 300L);
        feed(query, "u2", 50L);
        query.commit();
        before.dieInsteadOfCommitting();
        checkpointerOf(query).checkpointNow();
        assertThat(KafkaBroker.readCommitted(topic))
                .as("the process died before the commit arrived")
                .isEmpty();

        // Work after the checkpoint, into a process that is already dead.
        feed(query, "u1", 375L);
        query.commit();
        first.close();
        registries.remove(first);

        QueryRegistry second = checkpointed(new KafkaSinks().bind("latest_topic", binding));
        RegisteredQuery restarted =
                second.registerWritingTo("latest_amount", LATEST_SQL, List.of(0), DANA, "latest_topic");
        assertThat(KafkaBroker.readCommitted(topic))
                .as("the restore committed what the checkpoint recorded, and nothing after it")
                .extracting(Object::toString)
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}",
                        "{\"user_id\":\"u2\"}={\"user_id\":\"u2\",\"amount\":50}");

        // The source rewinds to the checkpoint's offsets and delivers what came after it.
        feed(restarted, "u1", 375L);
        restarted.commit();
        checkpointerOf(restarted).checkpointNow();

        List<KafkaBroker.Seen> log = KafkaBroker.readCommitted(topic);
        assertThat(table(log))
                .containsOnly(Map.entry("{\"user_id\":\"u1\"}", "375"), Map.entry("{\"user_id\":\"u2\"}", "50"));
        assertThat(log)
                .as("each change once, in the order the view made them -- a keyed view replaces u1 in place, "
                        + "so its new value is an upsert over the old key, not a tombstone and an insert")
                .extracting(Object::toString)
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}",
                        "{\"user_id\":\"u2\"}={\"user_id\":\"u2\",\"amount\":50}",
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":375}");
        assertThat(log.stream()
                        .filter(seen -> seen.value() != null && seen.value().contains("\"amount\":300")))
                .as("the change the crash interrupted, written once")
                .hasSize(1);
        assertThat(log.stream()
                        .filter(seen -> seen.value() != null && seen.value().contains("\"amount\":375")))
                .as("the change after the checkpoint, written once -- by the replay, not by the dead process")
                .hasSize(1);
    }

    @Test
    void withoutCheckpointsEachViewCommitIsItsOwnTransaction() throws Exception {
        String topic = KafkaBroker.topic("unchecked", 1, true);
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(new KafkaSinks().bind("spend_topic", spend(topic)))
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50));
        registries.add(registry);
        RegisteredQuery query = registry.registerWritingTo("spend_so_far", SPEND, List.of(0), DANA, "spend_topic");

        feed(query, "u1", 300L);
        commitUntil(query, 1L);
        feed(query, "u1", 1L);
        commitUntil(query, 2L);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline
                && !table(KafkaBroker.readCommitted(topic)).containsKey("{\"n\":2}")) {
            query.commit();
        }
        assertThat(table(KafkaBroker.readCommitted(topic))).containsExactly(Map.entry("{\"n\":2}", "301"));
        assertThat(registry.sinkGuarantee("spend_so_far"))
                .hasValueSatisfying(text -> assertThat(text).startsWith("at-least-once"));
    }

    @Test
    void theRegistryRefusesAQueryWhoseShapeOrKeyDiffersFromTheSinksDeclaration() {
        String topic = KafkaBroker.topic("refusals", 1, true);
        Map<String, String> byTotal = new HashMap<>(spend(topic));
        byTotal.put("key.columns", "total");
        QueryRegistry registry =
                checkpointed(new KafkaSinks().bind("spend_topic", spend(topic)).bind("by_total", byTotal));

        assertThatThrownBy(() -> registry.registerWritingTo(
                        "wrong_shape", "SELECT user_id, amount FROM txn", List.of(0), DANA, "spend_topic"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is configured for rows");
        assertThatThrownBy(() -> registry.registerWritingTo("wrong_key", SPEND, List.of(0), DANA, "by_total"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("keys its records by [total]");
    }

    // ---------------------------------------------------------------------------------------

    private static Map<String, String> spend(String topic) {
        return Map.of(
                "bootstrap.servers",
                KafkaBroker.bootstrap(),
                "topic",
                topic,
                "schema",
                "n:INT64,total:INT64",
                "key.columns",
                "n",
                "transactional.id",
                "spend-" + topic);
    }

    private static Map<String, String> latest(String topic) {
        return Map.of(
                "bootstrap.servers",
                KafkaBroker.bootstrap(),
                "topic",
                topic,
                "schema",
                "user_id:STRING,amount:INT64",
                "key.columns",
                "user_id",
                "transactional.id",
                "latest-" + topic);
    }

    /**
     * The log read as a compacted topic leaves it: the last value per key, a tombstone removing it.
     * The value is shown as its last column, which is the only one these tests vary.
     */
    private static Map<String, String> table(List<KafkaBroker.Seen> log) {
        Map<String, String> table = new LinkedHashMap<>();
        for (KafkaBroker.Seen seen : log) {
            table.remove(seen.key());
            if (seen.value() != null) {
                String value = seen.value();
                String last = value.substring(value.lastIndexOf(':') + 1, value.length() - 1);
                table.put(seen.key(), last);
            }
        }
        return table;
    }

    private QueryRegistry checkpointed(SinkFactory sinks) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(sinks)
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50))
                .checkpointingTo(
                        root,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "30s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void feed(RegisteredQuery query, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    /** Commits until the view's answer has counted {@code rows} rows: the aggregate emits on a tick. */
    private static void commitUntil(RegisteredQuery query, long rows) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            query.commit();
            if (query.view().scan().stream().anyMatch(row -> row[0] instanceof Number n && n.longValue() == rows)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the view never counted " + rows + " rows: "
                + query.view().scan().stream().map(java.util.Arrays::toString).toList());
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** Binds sink names to Kafka sinks, as the server's binding would. */
    private static final class KafkaSinks implements SinkFactory {
        private final Map<String, Map<String, String>> bindings = new HashMap<>();
        private volatile boolean dieInsteadOfCommitting;

        KafkaSinks bind(String name, Map<String, String> config) {
            bindings.put(name, config);
            return this;
        }

        void dieInsteadOfCommitting() {
            dieInsteadOfCommitting = true;
        }

        private KafkaSinkPlugin configured(String name) {
            Map<String, String> config = bindings.get(name);
            if (config == null) {
                throw new IllegalArgumentException("no sink named '" + name + "' is bound");
            }
            KafkaSinkPlugin plugin = new KafkaSinkPlugin();
            plugin.configure(new Ctx(name, config));
            return plugin;
        }

        @Override
        public SinkCapabilities capabilitiesOf(String sinkName) {
            return configured(sinkName).capabilities();
        }

        @Override
        public Description describe(String sinkName) {
            KafkaSinkPlugin plugin = configured(sinkName);
            return new Description(plugin.capabilities(), plugin.schema(), plugin.keyColumns());
        }

        @Override
        public StreamSinkPlugin open(String sinkName) {
            KafkaSinkPlugin plugin = configured(sinkName);
            plugin.open();
            return new Dying(plugin);
        }

        /**
         * The sink as one process holds it: once told to, the process dies at the commit -- its
         * clients close, and nothing it would have sent afterwards arrives.
         */
        private final class Dying implements StreamSinkPlugin {
            private final KafkaSinkPlugin delegate;
            private boolean dead;

            Dying(KafkaSinkPlugin delegate) {
                this.delegate = delegate;
            }

            @Override
            public SinkCapabilities capabilities() {
                return delegate.capabilities();
            }

            @Override
            public java.util.Optional<StreamSchema> schema() {
                return delegate.schema();
            }

            @Override
            public List<String> keyColumns() {
                return delegate.keyColumns();
            }

            @Override
            public int write(List<RowView> batch) {
                return dead ? batch.size() : delegate.write(batch);
            }

            @Override
            public void flush() {
                if (!dead) {
                    delegate.flush();
                }
            }

            @Override
            public void beginTransaction(long checkpointId) {
                if (!dead) {
                    delegate.beginTransaction(checkpointId);
                }
            }

            @Override
            public String prepare(long checkpointId) {
                return dead ? "" : delegate.prepare(checkpointId);
            }

            @Override
            public void commit(String handle) {
                if (dieInsteadOfCommitting) {
                    dead = true;
                    delegate.close();
                }
                if (!dead) {
                    delegate.commit(handle);
                }
            }

            @Override
            public void abort(String handle) {
                if (!dead) {
                    delegate.abort(handle);
                }
            }

            @Override
            public void abortAfter(long checkpointId) {
                if (!dead) {
                    delegate.abortAfter(checkpointId);
                }
            }

            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public Version version() {
                return delegate.version();
            }

            @Override
            public void configure(PluginContext context) {}

            @Override
            public void open() {}

            @Override
            public HealthStatus health() {
                return delegate.health();
            }

            @Override
            public void close() {
                delegate.close();
            }
        }
    }
}
