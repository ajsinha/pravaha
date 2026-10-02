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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A topic scaled out under a running query, against a real broker: the partition added while the
 * query runs reaches the view from its first record, its offset is in the next checkpoint, and a
 * restart -- with another partition added while the node was down -- counts every record once.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class KafkaPartitionGrowthBrokerTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String TOTALS = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    @TempDir
    Path root;

    private final List<QueryRegistry> registries = new ArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    @Test
    void aPartitionAddedWhileTheQueryRunsIsReadAndCheckpointedAndSurvivesARestart() throws Exception {
        String topic = KafkaBroker.topic("scaled", 2, false);
        write(topic, 0, 1, 5);
        write(topic, 1, 6, 10);

        QueryRegistry first = registry(topic);
        RegisteredQuery query = first.register("totals", TOTALS, List.of(0), Principal.ANONYMOUS);
        awaitTotals(query, 10, total(1, 10));

        grow(topic, 3);
        write(topic, 2, 11, 20);
        awaitTotals(query, 20, total(1, 20));
        assertThat(query.feed().describe()).contains("txn gained 1 partition while running [2]");

        Checkpoint checkpoint = checkpointerOf(query).checkpointNow();
        assertThat(checkpoint.offsets())
                .as("the new partition's offset is in the checkpoint, with the partition it belongs to")
                .containsEntry("partition-2", topic + "/2@10")
                .containsEntry("source-of-partition-2", "2/txn");
        first.close();
        registries.remove(first);

        // While nothing is reading: more records in the partition the checkpoint knows, and a
        // partition it has never heard of.
        write(topic, 2, 21, 25);
        grow(topic, 4);
        write(topic, 3, 26, 30);

        QueryRegistry second = registry(topic);
        RegisteredQuery restarted = second.register("totals", TOTALS, List.of(0), Principal.ANONYMOUS);
        awaitTotals(restarted, 30, total(1, 30));
        Thread.sleep(2_000);
        assertThat(totals(restarted))
                .as("restored at 20; partition 2 resumed at its offset, partition 3 read from its first record")
                .isEqualTo(List.of(30L, total(1, 30)));
    }

    // ---------------------------------------------------------------------------------------

    /**
     * ADR-054 against a real broker: two different questions about one topic share one reader, and the
     * one registered late, while records keep arriving, still counts each record exactly once.
     */
    @Test
    void aLateQueryOnTheSameTopicSharesItsReaderAndCountsEachRecordOnce() throws Exception {
        String topic = KafkaBroker.topic("shared", 2, false);
        write(topic, 0, 1, 500);
        write(topic, 1, 501, 1000);
        QueryRegistry registry = registry(topic);
        RegisteredQuery first = registry.register("totals", TOTALS, List.of(0), Principal.ANONYMOUS);
        awaitTotals(first, 1000, total(1, 1000));

        RegisteredQuery late = registry.register(
                "big_totals",
                "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE amount > 0",
                List.of(0),
                Principal.ANONYMOUS);
        write(topic, 0, 1001, 1500);
        write(topic, 1, 1501, 2000);
        awaitTotals(first, 2000, total(1, 2000));
        awaitTotals(late, 2000, total(1, 2000));
        Thread.sleep(2_000);
        assertThat(totals(late)).as("nothing twice across the seam").isEqualTo(List.of(2000L, total(1, 2000)));
        assertThat(late.feed().describe()).contains("shared").contains("one reader shared with 1 other query");
    }

    private QueryRegistry registry(String topic) {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", KafkaBroker.bootstrap());
        options.put("topic", topic);
        options.put("schema", "user_id:STRING,amount:INT64");
        options.put("partitions.refresh", "1s");
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .feedingFrom(new PluginSourceFeeds().bind(new SourceBinding("txn", "kafka", options)))
                .checkpointingTo(
                        root.resolve("checkpoints"),
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "30s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private static void grow(String topic, int partitions) throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KafkaBroker.bootstrap()))) {
            admin.createPartitions(Map.of(topic, NewPartitions.increaseTo(partitions)))
                    .all()
                    .get(60, TimeUnit.SECONDS);
        }
    }

    private static void write(String topic, int partition, int from, int to) {
        try (var producer = KafkaBroker.producer(null)) {
            for (int i = from; i <= to; i++) {
                KafkaBroker.send(
                        producer, topic, partition, "u" + i, "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
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
                throw new AssertionError("expected " + wanted + ", the view holds " + totals(query) + " (feed: "
                        + query.feed().describe() + ")");
            }
            Thread.sleep(50);
        }
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
