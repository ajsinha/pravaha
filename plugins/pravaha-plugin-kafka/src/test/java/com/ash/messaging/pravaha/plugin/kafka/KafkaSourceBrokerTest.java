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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code kafka} source against a real broker: Kafka transactions, exact resumption across a
 * restart on several partitions, the monitoring group, retention, and health.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class KafkaSourceBrokerTest {

    private final List<KafkaSourcePlugin> plugins = new ArrayList<>();

    @AfterEach
    void closeEverything() {
        plugins.forEach(KafkaSourcePlugin::close);
    }

    @Test
    void anAbortedTransactionIsNeverDeliveredAndResumingAcrossItsMarkersIsExact() {
        String topic = KafkaBroker.topic("aborts", 1, false);
        try (KafkaProducer<byte[], byte[]> producer = KafkaBroker.producer("aborts-" + topic)) {
            producer.beginTransaction();
            KafkaBroker.send(producer, topic, 0, "k", row("committed-1", 1));
            KafkaBroker.send(producer, topic, 0, "k", row("committed-2", 2));
            producer.commitTransaction();
            producer.beginTransaction();
            KafkaBroker.send(producer, topic, 0, "k", row("aborted-1", 100));
            KafkaBroker.send(producer, topic, 0, "k", row("aborted-2", 100));
            producer.abortTransaction();
            producer.beginTransaction();
            KafkaBroker.send(producer, topic, 0, "k", row("committed-3", 3));
            producer.commitTransaction();
        }
        // Offsets: 0-1 committed, 2 marker, 3-4 aborted, 5 marker, 6 committed, 7 marker.
        KafkaSourcePlugin plugin = open(topic, Map.of());

        SourceOffset afterTwo;
        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 3);
            assertThat(rows.described())
                    .containsExactly("[committed-1, 1, @1]", "[committed-2, 2, @1]", "[committed-3, 3, @1]");
            awaitPosition(reader, rows, topic + "/0@8");
        }
        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 1);
            reader.poll(rows, 1);
            afterTwo = reader.position();
        }
        assertThat(afterTwo.token()).isEqualTo(topic + "/0@2");

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader resumed = plugin.createReader(partition(0), afterTwo)) {
            awaitRows(resumed, rows, 1);
            sleep(500);
            resumed.poll(rows, 64);
            assertThat(rows.described())
                    .as("from the marker: the aborted records skipped again, the next committed one once")
                    .containsExactly("[committed-3, 3, @1]");
        }

        KafkaSourcePlugin uncommitted = open(topic, Map.of("isolation.level", "read_uncommitted"));
        try (Collected rows = new Collected(uncommitted.schema());
                PartitionReader reader = uncommitted.createReader(partition(0), null)) {
            awaitRows(reader, rows, 5);
            assertThat(rows.described())
                    .as("read_uncommitted sees what was aborted")
                    .hasSize(5);
        }
    }

    @Test
    void readersOnEveryPartitionResumeFromCheckpointedPositionsWithNothingLostOrRepeated() {
        String topic = KafkaBroker.topic("restart", 3, false);
        for (int i = 0; i < 30; i++) {
            KafkaBroker.send(topic, i % 3, "u" + i, row("u" + i, i));
        }
        KafkaSourcePlugin first = open(topic, Map.of());
        List<SourcePartition> partitions = first.partitions("txn");
        assertThat(partitions).extracting(SourcePartition::index).containsExactly(0, 1, 2);

        Map<Integer, SourceOffset> checkpoint = new TreeMap<>();
        List<String> seen = new ArrayList<>();
        for (SourcePartition partition : partitions) {
            try (Collected rows = new Collected(first.schema());
                    PartitionReader reader = first.createReader(partition, SourceOffset.BEGINNING)) {
                awaitRows(reader, rows, 4);
                // The "checkpoint" is taken at 4 rows; the reader then goes on to deliver 2 more that
                // die with it -- a crash after delivery and before the next checkpoint.
                checkpoint.put(partition.index(), reader.position());
                seen.addAll(rows.described());
                try (Collected lost = new Collected(first.schema())) {
                    reader.poll(lost, 2);
                }
            }
        }
        first.close();
        for (int i = 30; i < 45; i++) {
            KafkaBroker.send(topic, i % 3, "u" + i, row("u" + i, i));
        }

        KafkaSourcePlugin second = open(topic, Map.of());
        for (SourcePartition partition : second.partitions("txn")) {
            try (Collected rows = new Collected(second.schema());
                    PartitionReader reader = second.createReader(partition, checkpoint.get(partition.index()))) {
                awaitRows(reader, rows, 11);
                sleep(300);
                reader.poll(rows, 64);
                seen.addAll(rows.described());
            }
        }
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            expected.add("[u" + i + ", " + i + ", @1]");
        }
        assertThat(seen)
                .as("each record exactly once across the restart")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void onlyADurableCheckpointsOffsetReachesTheMonitoringGroup() throws Exception {
        String topic = KafkaBroker.topic("monitored", 1, false);
        for (int i = 0; i < 5; i++) {
            KafkaBroker.send(topic, 0, "k", row("u" + i, i));
        }
        String group = "pravaha-monitor-" + topic;
        KafkaSourcePlugin plugin = open(topic, Map.of("monitoring.group", group));
        TopicPartition p0 = new TopicPartition(topic, 0);

        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KafkaBroker.bootstrap()));
                Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 5);
            sleep(1_000);
            assertThat(committed(admin, group, p0))
                    .as("delivered is not durable")
                    .isNull();

            reader.checkpointed(new SourceOffset(topic + "/0@3"));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (committed(admin, group, p0) == null && System.nanoTime() < deadline) {
                sleep(100);
            }
            assertThat(committed(admin, group, p0).offset()).isEqualTo(3);
        }

        KafkaSourcePlugin restarted = open(topic, Map.of("monitoring.group", group));
        try (Collected rows = new Collected(restarted.schema());
                PartitionReader reader = restarted.createReader(partition(0), SourceOffset.BEGINNING)) {
            awaitRows(reader, rows, 5);
            assertThat(rows.described())
                    .as("with no checkpoint the group's committed offset 3 is not where it starts: start.from is")
                    .hasSize(5);
        }
    }

    @Test
    void recordsRetentionDeletedBeforeTheCheckpointReadThemAreRefusedNotSkipped() throws Exception {
        String topic = KafkaBroker.topic("retention", 1, false);
        for (int i = 0; i < 6; i++) {
            KafkaBroker.send(topic, 0, "k", row("u" + i, i));
        }
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KafkaBroker.bootstrap()))) {
            admin.deleteRecords(Map.of(new TopicPartition(topic, 0), RecordsToDelete.beforeOffset(4)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        }
        KafkaSourcePlugin plugin = open(topic, Map.of());

        assertThatThrownBy(() -> plugin.createReader(partition(0), new SourceOffset(topic + "/0@2")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5106")
                .hasMessageContaining("retention deleted 2 records");
        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 2);
            assertThat(rows.described())
                    .as("earliest is the log's start now")
                    .containsExactly("[u4, 4, @1]", "[u5, 5, @1]");
        }
    }

    @Test
    void aTopicDeletedUnderARunningReaderStopsItRatherThanWaiting() throws Exception {
        // TOPICGONE-1, against a real broker: after the delete the consumer only logs "unknown topic
        // or partition", and the query stayed RUNNING with health UP until the topic came back.
        String topic = KafkaBroker.topic("gone", 1, false);
        KafkaBroker.send(topic, 0, "k", row("u0", 0));
        KafkaSourcePlugin plugin = open(topic, Map.of("topic.missing.timeout", "2s"));
        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 1);
            try (Admin admin = Admin.create(Map.of("bootstrap.servers", KafkaBroker.bootstrap()))) {
                admin.deleteTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS);
            }
            assertThatThrownBy(() -> {
                        long deadline =
                                System.nanoTime() + Duration.ofSeconds(60).toNanos();
                        while (System.nanoTime() < deadline) {
                            reader.poll(rows, 64);
                            sleep(20);
                        }
                    })
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5130")
                    .hasMessageContaining(topic);
            assertThat(plugin.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);
        }
    }

    @Test
    void healthIsLagAgainstTheBrokersEndOffsets() {
        String topic = KafkaBroker.topic("health", 2, false);
        for (int i = 0; i < 4; i++) {
            KafkaBroker.send(topic, 1, "k", row("u" + i, i));
        }
        KafkaSourcePlugin plugin = open(topic, Map.of("lag.warn.records", "3"));
        assertThat(plugin.health().state()).isEqualTo(HealthStatus.State.HEALTHY);

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader idle = plugin.createReader(partition(0), null);
                PartitionReader behind = plugin.createReader(partition(1), null)) {
            HealthStatus lagging = plugin.health();
            assertThat(lagging.state()).isEqualTo(HealthStatus.State.DEGRADED);
            assertThat(lagging.detail()).contains("p0 0").contains("p1 4");
            awaitRows(behind, rows, 4);
            idle.poll(rows, 1);
        }
    }

    @Test
    void aTopicThatDoesNotExistIsRefusedAndIsNotCreated() {
        KafkaSourcePlugin plugin = new KafkaSourcePlugin();
        plugin.configure(new KafkaSourcePluginTest.Ctx("txn", options("never-made-" + System.nanoTime(), Map.of())));

        assertThatThrownBy(plugin::open).hasMessageContaining("PRV-5101").hasMessageContaining("does not exist");
    }

    // ---------------------------------------------------------------------------------------

    private KafkaSourcePlugin open(String topic, Map<String, String> overrides) {
        KafkaSourcePlugin plugin = new KafkaSourcePlugin();
        plugin.configure(new KafkaSourcePluginTest.Ctx("txn", options(topic, overrides)));
        plugin.open();
        plugins.add(plugin);
        return plugin;
    }

    private static Map<String, String> options(String topic, Map<String, String> overrides) {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", KafkaBroker.bootstrap());
        options.put("topic", topic);
        options.put("schema", "user_id:STRING,amount:INT64");
        options.put("start.timeout", "20s");
        options.putAll(overrides);
        return options;
    }

    private static String row(String user, long amount) {
        return "{\"user_id\":\"" + user + "\",\"amount\":" + amount + "}";
    }

    private static SourcePartition partition(int index) {
        return new SourcePartition("txn", index, Map.of());
    }

    private static OffsetAndMetadata committed(Admin admin, String group, TopicPartition partition) throws Exception {
        return admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata()
                .get(30, TimeUnit.SECONDS)
                .get(partition);
    }

    private static void awaitRows(PartitionReader reader, Collected rows, int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (rows.rows().size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("only " + rows.described() + " arrived");
            }
            reader.poll(rows, count - rows.rows().size());
            sleep(10);
        }
    }

    private static void awaitPosition(PartitionReader reader, Collected rows, String token) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!reader.position().token().equals(token)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the position stayed at " + reader.position() + ", not " + token);
            }
            reader.poll(rows, 64);
            sleep(10);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
