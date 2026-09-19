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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code format: avro} and {@code format: protobuf} against a real broker: records written to a
 * topic by a producer that knows nothing of this engine, and read back as rows.
 *
 * <p>The Avro records are written with {@link AvroWriter}, the protobuf ones with {@code
 * DynamicMessage} -- the encoders, not the decoders -- and the registry is an {@code HttpServer} in
 * the test serving the documented REST answer. So what is proved here is the whole path: a topic, a
 * fetch, the five-byte prefix, one schema fetch, and a row of the declared schema.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class KafkaSourceFormatBrokerTest {

    private static final String AVRO_SCHEMA = "{\"type\":\"record\",\"name\":\"Txn\",\"fields\":["
            + "{\"name\":\"user_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"long\"},"
            + "{\"name\":\"note\",\"type\":[\"null\",\"string\"]}]}";

    private final List<KafkaSourcePlugin> plugins = new ArrayList<>();
    private HttpServer registry;

    @TempDir
    Path directory;

    @AfterEach
    void closeEverything() {
        plugins.forEach(KafkaSourcePlugin::close);
        if (registry != null) {
            registry.stop(0);
        }
    }

    @Test
    void anAvroTopicIsReadAsRowsAndARecordThatDoesNotDecodeIsADeadLetter() throws IOException {
        String topic = KafkaBroker.topic("avro", 1, false);
        KafkaBroker.sendBytes(topic, 0, "a", avro("u1", 100, "first"));
        KafkaBroker.sendBytes(topic, 0, "b", avro("u2", 250, null));
        // Not Avro at all: the JSON a producer writes by mistake against an Avro binding.
        KafkaBroker.sendBytes(topic, 0, "c", "{\"user_id\":\"u3\"}".getBytes(StandardCharsets.UTF_8));
        KafkaBroker.sendBytes(topic, 0, "d", avro("u4", 7, null));

        Path schemaFile = directory.resolve("txn.avsc");
        Files.writeString(schemaFile, AVRO_SCHEMA, StandardCharsets.UTF_8);
        KafkaSourcePlugin plugin = open(
                topic,
                "user_id:STRING,amount:INT64,note:STRING?",
                Map.of("format", "avro", "schema.file", schemaFile.toString()));

        try (Collected rows = new Collected(plugin.schema(), true);
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 3);
            assertThat(rows.described())
                    .containsExactly("[u1, 100, first, @1]", "[u2, 250, null, @1]", "[u4, 7, null, @1]");
            assertThat(rows.rejections)
                    .as("the one that is not Avro, set aside with a reason")
                    .hasSize(1);
            assertThat(rows.rejections.get(0).at()).isEqualTo(topic + "/0@2");
        }
    }

    @Test
    void aProtobufTopicIsReadAsRows() throws IOException {
        String topic = KafkaBroker.topic("protobuf", 1, false);
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        KafkaBroker.sendBytes(topic, 0, "1", order(order, 1, "one"));
        KafkaBroker.sendBytes(topic, 0, "2", order(order, 2, "two"));

        Path descriptor = directory.resolve("order.desc");
        Files.write(descriptor, ProtoFixtures.orderDescriptorSet());
        KafkaSourcePlugin plugin = open(
                topic,
                "id:INT64,name:STRING",
                Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        descriptor.toString(),
                        "schema.message",
                        ProtoFixtures.ORDER));

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 2);
            assertThat(rows.described()).containsExactly("[1, one, @1]", "[2, two, @1]");
        }
    }

    @Test
    void aRegistryFramedAvroTopicIsReadWithOneFetchOfTheSchema() {
        String topic = KafkaBroker.topic("avro-registry", 1, false);
        String url = startRegistry(11, AVRO_SCHEMA);
        for (int i = 0; i < 5; i++) {
            KafkaBroker.sendBytes(topic, 0, "k" + i, framedAvro(11, "u" + i, i, null));
        }

        KafkaSourcePlugin plugin = open(
                topic,
                "user_id:STRING,amount:INT64,note:STRING?",
                Map.of("format", "avro", "schema.registry.url", url));

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 5);
            assertThat(rows.described())
                    .containsExactly(
                            "[u0, 0, null, @1]",
                            "[u1, 1, null, @1]",
                            "[u2, 2, null, @1]",
                            "[u3, 3, null, @1]",
                            "[u4, 4, null, @1]");
        }
        assertThat(requests).as("five records, one schema, one request").isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------

    private volatile int requests;

    private String startRegistry(int id, String schema) {
        try {
            registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String body = "{\"id\":" + id + ",\"schema\":\""
                + schema.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        registry.createContext("/schemas/ids/", exchange -> {
            requests++;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        registry.start();
        return "http://127.0.0.1:" + registry.getAddress().getPort();
    }

    private static byte[] avro(String user, long amount, String note) {
        return note(new AvroWriter().text(user).number(amount), note).bytes();
    }

    private static byte[] framedAvro(int schemaId, String user, long amount, String note) {
        return note(new AvroWriter().text(user).number(amount), note).framed(schemaId);
    }

    private static AvroWriter note(AvroWriter writer, String note) {
        return note == null ? writer.union(0) : writer.union(1).text(note);
    }

    private static byte[] order(Descriptor order, long id, String name) {
        return DynamicMessage.newBuilder(order)
                .setField(order.findFieldByName("id"), id)
                .setField(order.findFieldByName("name"), name)
                .build()
                .toByteArray();
    }

    private KafkaSourcePlugin open(String topic, String schema, Map<String, String> overrides) {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", KafkaBroker.bootstrap());
        options.put("topic", topic);
        options.put("schema", schema);
        options.put("start.timeout", "20s");
        options.putAll(overrides);
        KafkaSourcePlugin plugin = new KafkaSourcePlugin();
        plugin.configure(new KafkaSourcePluginTest.Ctx("txn", options));
        plugin.open();
        plugins.add(plugin);
        return plugin;
    }

    private static SourcePartition partition(int index) {
        return new SourcePartition("txn", index, Map.of());
    }

    private static void awaitRows(PartitionReader reader, Collected rows, int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (rows.rows().size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("only " + rows.described() + " arrived");
            }
            reader.poll(rows, count - rows.rows().size());
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
