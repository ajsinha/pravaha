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
import java.util.Set;
import java.util.TreeMap;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.SinkFactory;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SINKKEYROWS-1 against the Kafka sink in upsert mode: the compacted topic keeps, per key, the row
 * the view shows.
 *
 * <p>The engine's own delivery writes through {@link KafkaSinkPlugin} into Kafka's {@link
 * MockProducer}; the topic is read back as compaction leaves it -- the last record per key, a
 * tombstone deleting it. A keyed view holding two rows of a key whose shown row is retracted goes
 * back to the row behind it (VIEWW-1); fed the changelog, the sink wrote a tombstone for that key.
 */
class KafkaUpsertKeyRowsTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private final List<MockProducer<byte[], byte[]>> producers = new ArrayList<>();
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), TXN).writingTo(new Sinks());
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    @Test
    void retractingTheShownRowOfAKeyWithTwoRowsLeavesTheTopicHoldingTheRowBehindIt() {
        RegisteredQuery query =
                registry.registerWritingTo("latest", "SELECT user_id, amount FROM txn", List.of(0), DANA, "latest");

        feed(query, "u1", 10, 1);
        query.commit();
        feed(query, "u1", 20, 1);
        query.commit();
        feed(query, "u2", 5, 1);
        query.commit();
        feed(query, "u1", 20, -1);
        query.commit();

        assertThat(compacted())
                .as("what a compacted topic holds is what the view shows")
                .containsExactly(
                        Map.entry("{\"user_id\":\"u1\"}", "{\"user_id\":\"u1\",\"amount\":10}"),
                        Map.entry("{\"user_id\":\"u2\"}", "{\"user_id\":\"u2\",\"amount\":5}"));

        feed(query, "u1", 10, -1);
        query.commit();
        assertThat(compacted()).containsOnlyKeys("{\"user_id\":\"u2\"}");
    }

    /** The topic as compaction leaves it: the last value per key, a tombstone removing the key. */
    private Map<String, String> compacted() {
        Map<String, String> topic = new TreeMap<>();
        for (MockProducer<byte[], byte[]> producer : producers) {
            for (ProducerRecord<byte[], byte[]> record : producer.history()) {
                String key = new String(record.key(), StandardCharsets.UTF_8);
                if (record.value() == null) {
                    topic.remove(key);
                } else {
                    topic.put(key, new String(record.value(), StandardCharsets.UTF_8));
                }
            }
        }
        return topic;
    }

    private void feed(RegisteredQuery query, String user, long amount, long weight) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(weight).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** Binds one name to an upsert-mode Kafka sink over Kafka's mocks, not transactional. */
    private final class Sinks implements SinkFactory, KafkaClients {

        private KafkaSinkPlugin configured() {
            KafkaSinkPlugin plugin = new KafkaSinkPlugin(this);
            Map<String, String> config = new HashMap<>(Map.of(
                    "bootstrap.servers", "localhost:9092",
                    "topic", "latest",
                    "schema", "user_id:STRING,amount:INT64",
                    "key.columns", "user_id",
                    "transactional", "false"));
            plugin.configure(new Ctx("latest", config));
            return plugin;
        }

        @Override
        public SinkCapabilities capabilitiesOf(String sinkName) {
            return configured().capabilities();
        }

        @Override
        public Description describe(String sinkName) {
            KafkaSinkPlugin plugin = configured();
            return new Description(plugin.capabilities(), plugin.schema(), plugin.keyColumns());
        }

        @Override
        public StreamSinkPlugin open(String sinkName) {
            KafkaSinkPlugin plugin = configured();
            plugin.open();
            return plugin;
        }

        @Override
        public Producer<byte[], byte[]> producer(Map<String, Object> config) {
            MockProducer<byte[], byte[]> producer =
                    new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer());
            producers.add(producer);
            return producer;
        }

        @Override
        public Consumer<byte[], byte[]> consumer(Map<String, Object> config) {
            return new MockConsumer<>(OffsetResetStrategy.NONE);
        }

        @Override
        public void prepareTopics(KafkaSinkOptions options) {}
    }
}
