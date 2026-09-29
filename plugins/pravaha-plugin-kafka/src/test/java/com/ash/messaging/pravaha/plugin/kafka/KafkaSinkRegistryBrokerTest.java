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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KSF-1 to KSF-4 against a real Kafka broker and a real Confluent Schema Registry: an Avro key and
 * value behind the ids they were registered under, a Protobuf value behind its id and message
 * indexes read back through the registry's own serialized descriptor, a millisecond timestamp
 * declared as such, and ids that do not name the binding's schema refused at configuration.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class KafkaSinkRegistryBrokerTest {

    private static final String VALUE_AVRO = "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"t\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"},"
            + "{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}]}";
    private static final String KEY_AVRO = "{\"type\":\"record\",\"name\":\"OrderKey\",\"namespace\":\"t\","
            + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";
    private static final String PROTO = "syntax = \"proto3\";\npackage t;\n"
            + "message Note { string text = 1; }\n"
            + "message Holder {\n  message Order { int64 id = 1; string name = 2; }\n}\n";
    private static final String COLUMNS = "id:INT64,name:STRING,at:TIMESTAMP(3)";

    private static Network network;
    private static ConfluentKafkaContainer kafka;
    private static GenericContainer<?> registry;
    private static int topics;

    @TempDir
    Path directory;

    private final TestRows rows = new TestRows();
    private final List<KafkaSinkPlugin> opened = new ArrayList<>();

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0")
                .withNetwork(network)
                .withListener("kafka:19092")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                .withStartupTimeout(Duration.ofMinutes(3));
        kafka.start();
        registry = new GenericContainer<>("confluentinc/cp-schema-registry:7.6.0")
                .withNetwork(network)
                .withExposedPorts(8081)
                .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
                .waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3));
        registry.start();
    }

    @AfterAll
    static void stop() {
        if (registry != null) {
            registry.stop();
        }
        if (kafka != null) {
            kafka.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @AfterEach
    void tearDown() {
        opened.forEach(KafkaSinkPlugin::close);
        rows.close();
    }

    @Test
    void anAvroKeyAndValueGoOutBehindTheIdsTheyWereRegisteredUnder() throws Exception {
        String topic = topic("avro");
        int valueId = register(topic + "-value", VALUE_AVRO, null);
        int keyId = register(topic + "-key", KEY_AVRO, null);
        KafkaSinkPlugin sink = open(
                topic,
                Map.of(
                        "format",
                        "avro",
                        "schema.id",
                        Integer.toString(valueId),
                        "key.format",
                        "avro",
                        "key.schema.id",
                        Integer.toString(keyId)));
        StreamSchema schema = KafkaSchema.parse("orders", COLUMNS);
        sink.beginTransaction(1);
        // A nanosecond part the column's declared TIMESTAMP(3) does not carry: floored, not refused.
        sink.write(List.of(
                rows.row(schema, 1, 7L, "tea", 1_700_000_000_123_456_789L),
                rows.row(schema, -1, 8L, "old", 1_700_000_000_000_000_000L)));
        sink.commit(sink.prepare(1));

        List<ConsumerRecord<byte[], byte[]>> records = read(topic);
        assertThat(records).hasSize(2);
        StreamSchema keySchema = KafkaSchema.parse("key", "id:INT64");
        try (SchemaRegistry client = new SchemaRegistry("check", url(), null, "", Duration.ofSeconds(10))) {
            for (ConsumerRecord<byte[], byte[]> record : records) {
                assertThat(SchemaRegistry.framed(record.key())).isTrue();
                assertThat(SchemaRegistry.schemaIdIn(record.key())).isEqualTo(keyId);
            }
            assertThat(AvroRowReader.map(keySchema, AvroSchema.parse(client.schemaText(keyId)), -1)
                            .read(records.get(1).key(), 5, 0L)
                            .values())
                    .containsExactly(8L);
            byte[] value = records.get(0).value();
            assertThat(SchemaRegistry.schemaIdIn(value)).isEqualTo(valueId);
            assertThat(AvroRowReader.map(schema, AvroSchema.parse(client.schemaText(valueId)), -1)
                            .read(value, 5, 0L)
                            .values())
                    .containsExactly(7L, "tea", 1_700_000_000_123_000_000L);
            assertThat(records.get(1).value()).as("a retraction is a tombstone").isNull();
        }
    }

    @Test
    void aProtobufValueCarriesItsIdAndMessageIndexesAndReadsBackThroughTheRegistry() throws Exception {
        String topic = topic("proto");
        int id = register(topic + "-value", PROTO, "PROTOBUF");
        KafkaSinkPlugin sink = open(
                topic,
                Map.of(
                        "schema", "id:INT64,name:STRING",
                        "format", "protobuf",
                        "schema.id", Integer.toString(id),
                        "schema.message", "t.Holder.Order"));
        StreamSchema schema = KafkaSchema.parse("orders", "id:INT64,name:STRING");
        sink.beginTransaction(1);
        sink.write(List.of(rows.row(schema, 1, 7L, "tea")));
        sink.commit(sink.prepare(1));

        byte[] value = read(topic).get(0).value();
        assertThat(SchemaRegistry.schemaIdIn(value)).isEqualTo(id);
        assertThat(ProtobufRegistryDecoder.messageIndexes(value)).containsExactly(1, 0);
        try (SchemaRegistry client = new SchemaRegistry("check", url(), null, "", Duration.ofSeconds(10))) {
            assertThat(new ProtobufRegistryDecoder(schema, -1, "t.Holder.Order", client)
                            .decode(value, 0L)
                            .values())
                    .containsExactly(7L, "tea");
        }
    }

    @Test
    void anIdThatDoesNotNameTheBindingsSchemaIsRefusedAtConfiguration() throws Exception {
        String topic = topic("wrong");
        int keyId = register(topic + "-key", KEY_AVRO, null);
        Path file = directory.resolve("order.avsc");
        Files.writeString(file, VALUE_AVRO, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> open(
                        topic,
                        Map.of(
                                "format", "avro",
                                "schema.file", file.toString(),
                                "schema.id", Integer.toString(keyId))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("is not the schema the registry holds under schema.id " + keyId)
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(KafkaErrors.SCHEMA_UNMAPPABLE);
        assertThatThrownBy(() -> open(topic, Map.of("format", "avro", "schema.id", "99999")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("no schema with id 99999")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(KafkaErrors.REGISTRY_UNAVAILABLE);
    }

    // ---------------------------------------------------------------------------------------

    private static String url() {
        return "http://" + registry.getHost() + ":" + registry.getMappedPort(8081);
    }

    /** Registers {@code schema} under {@code subject} the way a producer's serializer would. */
    private static int register(String subject, String schema, String type) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        if (type != null) {
            body.append("\"schemaType\":\"").append(type).append("\",");
        }
        body.append("\"schema\":");
        KafkaRecords.appendString(body, schema);
        body.append('}');
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url() + "/subjects/" + subject + "/versions"))
                            .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Matcher id = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(response.body());
            if (response.statusCode() != 200 || !id.find()) {
                throw new IllegalStateException("registering " + subject + ": " + response.body());
            }
            return Integer.parseInt(id.group(1));
        }
    }

    private static String topic(String prefix) throws Exception {
        String name = prefix + "-" + (++topics) + "-" + System.nanoTime() % 100_000;
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(name, Optional.of(1), Optional.empty())))
                    .all()
                    .get(60, TimeUnit.SECONDS);
        }
        return name;
    }

    private KafkaSinkPlugin open(String topic, Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "bootstrap.servers",
                kafka.getBootstrapServers(),
                "topic",
                topic,
                "schema",
                COLUMNS,
                "key.columns",
                "id",
                "staging.topic",
                topic + ".staging",
                "schema.registry.url",
                url()));
        config.putAll(extra);
        KafkaSinkPlugin sink = new KafkaSinkPlugin();
        sink.configure(new Ctx(topic + "-sink", config));
        sink.open();
        opened.add(sink);
        return sink;
    }

    private static List<ConsumerRecord<byte[], byte[]>> read(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.ISOLATION_LEVEL_CONFIG,
                "read_committed",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        List<ConsumerRecord<byte[], byte[]>> seen = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long idleSince = System.nanoTime();
            while (System.nanoTime() - idleSince < TimeUnit.SECONDS.toNanos(2)) {
                var records = consumer.poll(Duration.ofMillis(100));
                records.forEach(seen::add);
                if (!records.isEmpty()) {
                    idleSince = System.nanoTime();
                }
            }
        }
        return seen;
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
