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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A continuous query reading a real Kafka topic through the path a node runs: the {@code kafka}
 * binding discovered by name through {@link PluginSourceFeeds}, one reader per partition, the pump,
 * and the registry's checkpoints.
 *
 * <p>Two things only the whole path can show. A restart restores the view from its checkpoint and
 * resumes every partition at the checkpoint's offsets, so a record delivered after the checkpoint
 * is counted once more and never twice, and a record written while the node was down is counted
 * once. And a {@code kafka-sink} topic read back by a {@code kafka} source: one query's revising
 * answer, retractions and all, becomes another query's input with its weights intact.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class KafkaSourceRegistrationTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String TOTALS = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

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
    void aRestartResumesEveryPartitionFromTheCheckpointWithNothingLostAndNothingCountedTwice() throws Exception {
        String topic = KafkaBroker.topic("totals", 3, false);
        write(topic, 1, 30);

        QueryRegistry first = sourcedFrom(topic, root.resolve("checkpoints"), null);
        RegisteredQuery query = first.register("totals", TOTALS, List.of(0), Principal.ANONYMOUS);
        awaitTotals(query, 30, total(1, 30));

        Checkpoint checkpoint = checkpointerOf(query).checkpointNow();
        assertThat(checkpoint.offsets())
                .as("one position per partition, each a Kafka offset")
                .containsKeys("partition-0", "partition-1", "partition-2");
        assertThat(checkpoint.offsets().get("partition-1")).isEqualTo(topic + "/1@10");

        // After the checkpoint: counted by this process, and not in the checkpoint.
        write(topic, 31, 40);
        awaitTotals(query, 40, total(1, 40));
        first.close();
        registries.remove(first);

        // While nothing is reading.
        write(topic, 41, 50);

        QueryRegistry second = sourcedFrom(topic, root.resolve("checkpoints"), null);
        RegisteredQuery restarted = second.register("totals", TOTALS, List.of(0), Principal.ANONYMOUS);
        awaitTotals(restarted, 50, total(1, 50));
        Thread.sleep(1_500);
        assertThat(totals(restarted))
                .as("restored at 30 and replayed from the checkpoint's offsets: 31-40 once more, 41-50 once, "
                        + "and nothing before the checkpoint twice")
                .isEqualTo(List.of(50L, total(1, 50)));
    }

    @Test
    void anUndecodableRecordGoesToTheDeadLetterQueueAndTheQueryReadsOn() throws Exception {
        String topic = KafkaBroker.topic("dlq", 1, false);
        KafkaBroker.send(topic, 0, "k", "{\"user_id\":\"u1\",\"amount\":1}");
        KafkaBroker.send(topic, 0, "k", "{\"user_id\":\"u2\",\"amount\":\"two\"}");
        KafkaBroker.send(topic, 0, "k", "{\"user_id\":\"u3\",\"amount\":3}");
        Path deadLetters = root.resolve("dlq");

        QueryRegistry registry = sourcedFrom(topic, root.resolve("checkpoints"), deadLetters);
        RegisteredQuery query = registry.register("totals", TOTALS, List.of(0), Principal.ANONYMOUS);

        awaitTotals(query, 2, 4L);
        String dead = Files.readString(deadLetters.resolve("totals.dlq"));
        assertThat(dead).contains(topic + "/0@1").contains("'amount' is INT64");
    }

    /**
     * {@code kafka-sink} in changelog mode, read back by the {@code kafka} source in changelog form.
     *
     * <p>The first query's answer is revised twice, so the topic holds each answer and the retraction
     * of each it replaced. The second query sums what it reads: with the weights applied the sum is
     * the first query's current answer, and a source that read every record as an insertion would
     * have summed all three answers instead.
     */
    @Test
    void aKafkaSinkTopicReadsBackThroughTheKafkaSourceWithItsRetractions() throws Exception {
        String topic = KafkaBroker.topic("spend-changes", 1, false);
        Map<String, String> sink = new HashMap<>();
        sink.put("bootstrap.servers", KafkaBroker.bootstrap());
        sink.put("topic", topic);
        sink.put("schema", "n:INT64,total:INT64");
        sink.put("mode", "changelog");
        sink.put("transactional.id", "spend-" + topic);
        QueryRegistry writer = new QueryRegistry(new ViewCatalog(), TXN)
                .writingTo(new PluginSinks().bind(new SinkBinding("spend_topic", "kafka-sink", sink)))
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50))
                .checkpointingTo(root.resolve("writer"), checkpointing());
        registries.add(writer);
        RegisteredQuery spend =
                writer.registerWritingTo("spend", TOTALS, List.of(0), Principal.ANONYMOUS, "spend_topic");

        feed(spend, "u1", 300L);
        feed(spend, "u2", 50L);
        commitUntil(spend, 2L);
        checkpointerOf(spend).checkpointNow();
        feed(spend, "u1", 75L);
        commitUntil(spend, 3L);
        checkpointerOf(spend).checkpointNow();
        assertThat(KafkaBroker.readCommitted(topic))
                .extracting(KafkaBroker.Seen::value)
                .as("what the sink wrote: each answer, and the retraction of the one it replaced")
                .containsExactly(
                        "{\"op\":\"insert\",\"weight\":1,\"row\":{\"n\":2,\"total\":350}}",
                        "{\"op\":\"delete\",\"weight\":-1,\"row\":{\"n\":2,\"total\":350}}",
                        "{\"op\":\"insert\",\"weight\":1,\"row\":{\"n\":3,\"total\":425}}");

        StreamSchema changes = StreamSchema.builder("spend_changes")
                .field("n", Types.int64())
                .field("total", Types.int64())
                .build();
        Map<String, String> source = new HashMap<>();
        source.put("bootstrap.servers", KafkaBroker.bootstrap());
        source.put("topic", topic);
        source.put("schema", "n:INT64,total:INT64");
        source.put("format", "changelog");
        QueryRegistry reader = new QueryRegistry(new ViewCatalog(), changes)
                .feedingFrom(new PluginSourceFeeds().bind(new SourceBinding("spend_changes", "kafka", source)))
                .checkpointingTo(root.resolve("reader"), checkpointing());
        registries.add(reader);
        RegisteredQuery current = reader.register(
                "current_spend",
                "SELECT SUM(n) AS n, SUM(total) AS total FROM spend_changes",
                List.of(0),
                Principal.ANONYMOUS);

        awaitTotals(current, 3, 425L);
        Thread.sleep(1_000);
        assertThat(totals(current))
                .as("the retraction arrived as a retraction: 2 + 2 - 2 + 3 is not 7")
                .isEqualTo(List.of(3L, 425L));
    }

    // ---------------------------------------------------------------------------------------

    private QueryRegistry sourcedFrom(String topic, Path checkpoints, @Nullable Path deadLetters) {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", KafkaBroker.bootstrap());
        options.put("topic", topic);
        options.put("schema", "user_id:STRING,amount:INT64");
        PluginSourceFeeds feeds = new PluginSourceFeeds().bind(new SourceBinding("txn", "kafka", options));
        if (deadLetters != null) {
            feeds.deadLetteringTo(deadLetters);
        }
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .feedingFrom(feeds)
                .checkpointingTo(checkpoints, checkpointing());
        registries.add(registry);
        return registry;
    }

    private static Configuration checkpointing() {
        return Configuration.builder()
                .set("pravaha.checkpoint.interval", "1h")
                .set("pravaha.checkpoint.timeout", "30s")
                .build();
    }

    /** Records {@code from..to}, amount equal to the number, spread over three partitions. */
    private static void write(String topic, int from, int to) {
        try (var producer = KafkaBroker.producer(null)) {
            for (int i = from; i <= to; i++) {
                KafkaBroker.send(producer, topic, i % 3, "u" + i, "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
            }
        }
    }

    private static long total(int from, int to) {
        long sum = 0;
        for (int i = from; i <= to; i++) {
            sum += i;
        }
        return sum;
    }

    private static List<Long> totals(RegisteredQuery query) {
        for (Object[] row : query.view().scan()) {
            return List.of(
                    row[0] == null ? 0L : ((Number) row[0]).longValue(),
                    row[1] == null ? 0L : ((Number) row[1]).longValue());
        }
        return List.of(0L, 0L);
    }

    private static void awaitTotals(RegisteredQuery query, long n, long total) throws InterruptedException {
        List<Long> wanted = List.of(n, total);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!totals(query).equals(wanted)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "expected " + wanted + ", the view holds " + totals(query) + " (query " + query.state() + ")");
            }
            Thread.sleep(50);
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
        throw new AssertionError("the view never counted " + rows + " rows");
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }
}
