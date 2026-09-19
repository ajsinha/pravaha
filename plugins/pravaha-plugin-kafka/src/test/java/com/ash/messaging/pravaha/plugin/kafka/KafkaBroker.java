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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * One real Kafka broker for every container test in this module, started on first use and stopped
 * with the JVM (Testcontainers' reaper). A broker of its own, never one already running on the
 * machine: every topic a test makes is named uniquely, so tests share the broker and nothing else.
 */
final class KafkaBroker {

    /** Confluent Platform 7.6 is Apache Kafka 3.6, in KRaft mode. */
    private static final String IMAGE = "confluentinc/cp-kafka:7.6.0";

    private static final AtomicInteger TOPICS = new AtomicInteger();
    private static ConfluentKafkaContainer container;

    private KafkaBroker() {}

    static synchronized String bootstrap() {
        if (container == null) {
            ConfluentKafkaContainer started = new ConfluentKafkaContainer(IMAGE)
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                    .withStartupTimeout(Duration.ofMinutes(3));
            started.start();
            container = started;
        }
        return container.getBootstrapServers();
    }

    /** A new topic, unique to the caller, compacted or not. */
    static String topic(String prefix, int partitions, boolean compacted) {
        String name = prefix + "-" + TOPICS.incrementAndGet() + "-" + System.nanoTime() % 100_000;
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap()))) {
            NewTopic topic = new NewTopic(name, Optional.of(partitions), Optional.empty())
                    .configs(Map.of(
                            TopicConfig.CLEANUP_POLICY_CONFIG,
                            compacted ? TopicConfig.CLEANUP_POLICY_COMPACT : TopicConfig.CLEANUP_POLICY_DELETE));
            admin.createTopics(List.of(topic)).all().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create topic " + name, e);
        }
        return name;
    }

    /** One record as a test reads it: key and value as text, the value null for a tombstone. */
    record Seen(String key, String value) {
        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    /**
     * Everything on {@code topic} a consumer with {@code isolation} sees, reading from the start
     * until nothing new has arrived for {@code quiet}.
     */
    static List<Seen> read(String topic, String isolation, Duration quiet) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrap(),
                ConsumerConfig.ISOLATION_LEVEL_CONFIG,
                isolation,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        List<Seen> seen = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long idleSince = System.nanoTime();
            while (System.nanoTime() - idleSince < quiet.toNanos()) {
                var records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    seen.add(new Seen(text(record.key()), text(record.value())));
                }
                if (!records.isEmpty()) {
                    idleSince = System.nanoTime();
                }
            }
        }
        return seen;
    }

    static List<Seen> readCommitted(String topic) {
        return read(topic, "read_committed", Duration.ofSeconds(2));
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
