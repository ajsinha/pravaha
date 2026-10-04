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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compressed topics against a real broker (ADR-053): the source reads snappy and zstd batches,
 * whether the producer or the broker compressed them; {@code kafka-sink} writes them; and an lz4
 * topic stops the source with a failure that names lz4 and the ADR, not a dead fetch thread.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class KafkaCompressedBrokerTest {

    private static final StreamSchema LATEST = StreamSchema.builder("latest")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private final List<KafkaSourcePlugin> sources = new ArrayList<>();
    private final List<KafkaSinkPlugin> sinks = new ArrayList<>();
    private final TestRows rows = new TestRows();

    @AfterEach
    void closeEverything() {
        sources.forEach(KafkaSourcePlugin::close);
        sinks.forEach(KafkaSinkPlugin::close);
        rows.close();
    }

    @SuppressWarnings(
            "FutureReturnValueIgnored") // the task reports its own outcome (a callback, or a catch-all in the task)
    @ParameterizedTest
    @ValueSource(strings = {"snappy", "zstd"})
    void theSourceReadsBatchesAProducerCompressed(String codec) {
        String topic = KafkaBroker.topic("produced-" + codec, 1, false);
        try (KafkaProducer<byte[], byte[]> producer = producer(codec)) {
            for (int i = 0; i < 20; i++) {
                producer.send(new ProducerRecord<>(topic, 0, bytes("u" + i), bytes(row("u" + i, i))));
            }
            producer.flush();
        }

        assertThat(readAll(topic, 20)).hasSize(20).contains("[u0, 0, @1]", "[u19, 19, @1]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"snappy", "zstd"})
    void theSourceReadsATopicTheBrokerCompresses(String codec) {
        String topic = topicCompressedBy(codec);
        for (int i = 0; i < 5; i++) {
            KafkaBroker.send(topic, 0, "u" + i, row("u" + i, i));
        }

        assertThat(readAll(topic, 5))
                .containsExactly("[u0, 0, @1]", "[u1, 1, @1]", "[u2, 2, @1]", "[u3, 3, @1]", "[u4, 4, @1]");
    }

    /**
     * Before ADR-053 this topic's first poll threw a NoClassDefFoundError that killed the fetch
     * thread and left the reader silent and "healthy". lz4 is still not read, and now says so.
     */
    @Test
    void anLz4TopicStopsTheSourceNamingTheCodecAndTheAdr() {
        String topic = topicCompressedBy("lz4");
        KafkaBroker.send(topic, 0, "u0", row("u0", 0));
        KafkaSourcePlugin plugin = source(topic);

        assertThatThrownBy(() -> {
                    try (Collected collected = new Collected(plugin.schema());
                            PartitionReader reader = plugin.createReader(partition(), null)) {
                        long deadline =
                                System.nanoTime() + Duration.ofSeconds(30).toNanos();
                        while (System.nanoTime() < deadline) {
                            reader.poll(collected, 64);
                            Thread.sleep(20);
                        }
                    }
                })
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5107")
                .hasMessageContaining("compressed with lz4")
                .hasMessageContaining("ADR-053");
    }

    @ParameterizedTest
    @ValueSource(strings = {"snappy", "zstd"})
    void theSinkWritesCompressedBatchesThatReadBackExactlyOnce(String codec) throws Exception {
        String compressed = KafkaBroker.topic("sink-" + codec, 1, true);
        String plain = KafkaBroker.topic("sink-plain-" + codec, 1, true);
        write(compressed, codec);
        write(plain, "none");

        List<KafkaBroker.Seen> seen = KafkaBroker.readCommitted(compressed);
        assertThat(seen).hasSize(400);
        assertThat(seen.get(0).value()).contains("\"amount\":0");
        assertThat(readAll(compressed, 400)).hasSize(400);
        assertThat(logSize(compressed))
                .as("the same rows take less of the log compressed with " + codec)
                .isLessThan(logSize(plain) / 2);
    }

    // ---------------------------------------------------------------------------------------

    private void write(String topic, String codec) {
        Map<String, String> config = new HashMap<>(Map.of(
                "bootstrap.servers",
                KafkaBroker.bootstrap(),
                "topic",
                topic,
                "schema",
                "user_id:STRING,amount:INT64",
                "key.columns",
                "user_id",
                "staging.topic",
                topic + ".staging",
                "kafka.compression.type",
                codec));
        KafkaSinkPlugin sink = new KafkaSinkPlugin();
        sink.configure(new Ctx(topic, config));
        sink.open();
        sinks.add(sink);
        List<RowView> batch = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            batch.add(rows.row(LATEST, 1, "user-with-a-long-repetitive-name-" + i, (long) i % 7));
        }
        sink.beginTransaction(1);
        sink.write(batch);
        sink.commit(sink.prepare(1));
    }

    private List<String> readAll(String topic, int count) {
        KafkaSourcePlugin plugin = source(topic);
        try (Collected collected = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(), null)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (collected.rows().size() < count) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("only " + collected.described() + " arrived");
                }
                reader.poll(collected, count - collected.rows().size());
                Thread.sleep(10);
            }
            return collected.described();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private KafkaSourcePlugin source(String topic) {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", KafkaBroker.bootstrap());
        options.put("topic", topic);
        options.put("schema", "user_id:STRING,amount:INT64");
        options.put("start.timeout", "20s");
        KafkaSourcePlugin plugin = new KafkaSourcePlugin();
        plugin.configure(new KafkaSourcePluginTest.Ctx("txn", options));
        plugin.open();
        sources.add(plugin);
        return plugin;
    }

    /** A topic whose broker recompresses every batch with {@code codec}, whatever the producer sent. */
    private static String topicCompressedBy(String codec) {
        String name = "broker-" + codec + "-" + System.nanoTime() % 1_000_000;
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KafkaBroker.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(name, Optional.of(1), Optional.empty())
                            .configs(Map.of("compression.type", codec))))
                    .all()
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create topic " + name, e);
        }
        return name;
    }

    private static KafkaProducer<byte[], byte[]> producer(String codec) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBroker.bootstrap());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, codec);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 50);
        return new KafkaProducer<>(config);
    }

    /** Bytes the broker holds for partition 0 of {@code topic}. */
    private static long logSize(String topic) throws Exception {
        TopicPartition partition = new TopicPartition(topic, 0);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KafkaBroker.bootstrap()))) {
            List<Integer> brokers = admin.describeCluster().nodes().get(30, TimeUnit.SECONDS).stream()
                    .map(node -> node.id())
                    .toList();
            long size = 0;
            for (Map<String, LogDirDescription> dirs : admin.describeLogDirs(brokers)
                    .allDescriptions()
                    .get(30, TimeUnit.SECONDS)
                    .values()) {
                for (LogDirDescription dir : dirs.values()) {
                    ReplicaInfo replica = dir.replicaInfos().get(partition);
                    if (replica != null) {
                        size += replica.size();
                    }
                }
            }
            return size;
        }
    }

    private static SourcePartition partition() {
        return new SourcePartition("txn", 0, Map.of());
    }

    private static String row(String user, long amount) {
        return "{\"user_id\":\"" + user + "\",\"amount\":" + amount + "}";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
