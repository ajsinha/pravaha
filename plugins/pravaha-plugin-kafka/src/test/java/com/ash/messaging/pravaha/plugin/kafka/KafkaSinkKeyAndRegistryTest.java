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
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KSF-1 to KSF-3 without a broker: a key written as text, Avro or protobuf; a protobuf value behind
 * the Confluent framing with its message indexes; and every schema id checked against a registry of
 * the test's own at configuration.
 */
class KafkaSinkKeyAndRegistryTest {

    private static final String COLUMNS = "id:INT64,name:STRING";
    private static final String ORDER_AVRO = "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"t\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"}]}";
    private static final String KEY_AVRO = "{\"type\":\"record\",\"name\":\"OrderKey\",\"namespace\":\"t\","
            + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    @TempDir
    Path directory;

    private final TestRows rows = new TestRows();
    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        rows.close();
    }

    // ---- KSF-1: key.format --------------------------------------------------------------------

    @Test
    void aKeyIsWrittenAsTextAvroOrProtobufAndATombstoneKeepsIt() throws Exception {
        StreamSchema schema = KafkaSchema.parse("orders", COLUMNS);
        KafkaSinkOptions text = options(Map.of("key.format", "string"));
        KafkaRecords.Encoded encoded = records(text).encode(rows.row(schema, 1, 42L, "tea"));
        assertThat(new String(encoded.key(), StandardCharsets.UTF_8)).isEqualTo("42");
        assertThat(records(text).encode(rows.row(schema, -1, 42L, "tea")).key())
                .as("a tombstone's key is the same bytes, or compaction would keep the row")
                .isEqualTo(encoded.key());

        KafkaSinkOptions avro = options(Map.of("key.format", "avro", "key.schema.file", write("key.avsc", KEY_AVRO)));
        byte[] avroKey = records(avro).encode(rows.row(schema, 1, 42L, "tea")).key();
        StreamSchema keySchema = KafkaSchema.parse("key", "id:INT64");
        assertThat(AvroRowReader.map(keySchema, AvroSchema.parse(KEY_AVRO), -1)
                        .read(avroKey, 0, 0L)
                        .values())
                .containsExactly(42L);
        assertThat(records(avro).encode(rows.row(schema, 1, 42L, "tea")).value())
                .as("the value keeps its own format")
                .isEqualTo("{\"id\":42,\"name\":\"tea\"}".getBytes(StandardCharsets.UTF_8));

        KafkaSinkOptions framedKey = options(
                Map.of("key.format", "avro", "key.schema.file", write("key2.avsc", KEY_AVRO), "key.schema.id", "300"));
        assertThat(Arrays.copyOf(
                        records(framedKey)
                                .encode(rows.row(schema, 1, 42L, "tea"))
                                .key(),
                        5))
                .containsExactly(0, 0, 0, 1, 44);

        KafkaSinkOptions proto = options(Map.of(
                "schema",
                "id:INT64,name:STRING",
                "key.format",
                "protobuf",
                "key.schema.descriptor",
                write("order.desc", ProtoFixtures.orderDescriptorSet()),
                "key.schema.message",
                ProtoFixtures.ORDER));
        byte[] protoKey = records(proto).encode(rows.row(schema, 1, 42L, "tea")).key();
        assertThat(ProtobufValueDecoder.map(
                                keySchema,
                                -1,
                                ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER),
                                false)
                        .decode(protoKey, 0L)
                        .values())
                .containsExactly(42L);
    }

    @Test
    void aKeyFormatThatCannotWorkIsRefusedByName() {
        refused(Map.of("key.format", "xml"), "key.format 'xml' is not json, string, avro or protobuf");
        refused(
                Map.of("key.format", "string", "key.columns", "id,name"),
                "key.format: string writes one key column as text, and the key has 2 columns");
        refused(Map.of("key.schema.file", "k.avsc"), "key.schema.file is for key.format: avro");
        refused(Map.of("key.format", "string", "key.schema.id", "3"), "key.schema.id is for key.format: avro");
        refused(Map.of("key.format", "avro"), "format: avro needs key.schema.file");
        assertThat(refused(
                                Map.of(
                                        "key.format",
                                        "avro",
                                        "key.schema.file",
                                        write("wrong.avsc", ORDER_AVRO.replace("\"long\"", "\"int\""))),
                                "an int would not hold every value")
                        .errorCode())
                .isEqualTo(KafkaErrors.SCHEMA_UNMAPPABLE);
    }

    // ---- KSF-2: the protobuf framing ----------------------------------------------------------

    @Test
    void aProtobufValueWithARegistryIdCarriesTheConfluentFramingAndReadsBackThroughTheRegistry() throws Undecodable {
        bodies.put("/schemas/ids/21?format=serialized", serialized(twoMessages()));
        KafkaSinkOptions options = options(Map.of(
                "format", "protobuf",
                "schema.registry.url", url(),
                "schema.id", "21",
                "schema.message", "Order"));
        StreamSchema schema = KafkaSchema.parse("orders", COLUMNS);
        byte[] value = records(options).encode(rows.row(schema, 1, 7L, "tea")).value();

        assertThat(Arrays.copyOf(value, 5)).containsExactly(0, 0, 0, 0, 21);
        assertThat(ProtobufRegistryDecoder.messageIndexes(value))
                .as("Order is nested in the file's second message: [1, 0]")
                .containsExactly(1, 0);
        try (SchemaRegistry registry = new SchemaRegistry("check", url(), null, "", Duration.ofSeconds(2))) {
            assertThat(new ProtobufRegistryDecoder(schema, -1, "t.Holder.Order", registry)
                            .decode(value, 0L)
                            .values())
                    .containsExactly(7L, "tea");
        }

        refused(
                Map.of("format", "protobuf", "schema.registry.url", url(), "schema.id", "21", "schema.message", "Note"),
                "message t.Note has no field for column 'id'");
        bodies.put("/schemas/ids/22?format=serialized", serialized(orderFirst()));
        byte[] firstMessage = records(options(Map.of(
                        "format",
                        "protobuf",
                        "schema.registry.url",
                        url(),
                        "schema.id",
                        "22",
                        "schema.message",
                        "t.Order")))
                .encode(rows.row(schema, 1, 7L, "tea"))
                .value();
        assertThat(firstMessage[5]).as("the file's first message is the lone 0").isEqualTo((byte) 0);
    }

    // ---- KSF-3: every id checked --------------------------------------------------------------

    @Test
    void anAvroSchemaIdIsCheckedAgainstTheRegistryAtConfiguration() throws Undecodable {
        bodies.put("/schemas/ids/9", envelope(ORDER_AVRO));
        bodies.put("/schemas/ids/10", envelope(KEY_AVRO));
        String file = write("order.avsc", ORDER_AVRO.replace(",", ", "));

        KafkaSinkOptions matching =
                options(Map.of("format", "avro", "schema.file", file, "schema.id", "9", "schema.registry.url", url()));
        StreamSchema schema = KafkaSchema.parse("orders", COLUMNS);
        byte[] value = records(matching).encode(rows.row(schema, 1, 5L, "x")).value();
        assertThat(Arrays.copyOf(value, 5)).containsExactly(0, 0, 0, 0, 9);

        KafkaSinkOptions fromRegistry =
                options(Map.of("format", "avro", "schema.id", "9", "schema.registry.url", url()));
        assertThat(records(fromRegistry).encode(rows.row(schema, 1, 5L, "x")).value())
                .as("with no schema.file the registry's schema is the writer")
                .isEqualTo(value);

        ConfigurationException wrongId = refused(
                Map.of("format", "avro", "schema.file", file, "schema.id", "10", "schema.registry.url", url()),
                "is not the schema the registry holds under schema.id 10");
        assertThat(wrongId.errorCode()).isEqualTo(KafkaErrors.SCHEMA_UNMAPPABLE);
        refused(
                Map.of("format", "avro", "schema.id", "10", "schema.registry.url", url()),
                "no field for column 'name'");
        assertThatThrownBy(() -> options(Map.of("format", "avro", "schema.id", "404", "schema.registry.url", url())))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("sink 'orders-sink'")
                .hasMessageContaining("no schema with id 404")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(KafkaErrors.REGISTRY_UNAVAILABLE);
        refused(
                Map.of("format", "avro", "schema.file", file, "schema.registry.url", url()),
                "neither schema.id nor key.schema.id");
        refused(Map.of("schema.registry.user", "u"), "without schema.registry.url");
    }

    @Test
    void aProtobufSchemaIdIsCheckedAgainstTheRegistryAndNeedsOne() {
        bodies.put("/schemas/ids/21?format=serialized", serialized(twoMessages()));
        String descriptor = write("order.desc", ProtoFixtures.orderDescriptorSet());
        refused(
                Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        descriptor,
                        "schema.message",
                        "Order",
                        "schema.id",
                        "21",
                        "schema.registry.url",
                        url()),
                "differently from schema id 21");
        refused(
                Map.of(
                        "format",
                        "protobuf",
                        "schema.message",
                        "Missing",
                        "schema.id",
                        "21",
                        "schema.registry.url",
                        url()),
                "has no message called 'Missing'");
        refused(
                Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        descriptor,
                        "schema.message",
                        "Order",
                        "schema.id",
                        "21"),
                "needs schema.registry.url");
    }

    // ---------------------------------------------------------------------------------------

    /** {@code holder.proto}: a Note, then a Holder with a nested Order. */
    private static FileDescriptorProto twoMessages() {
        return FileDescriptorProto.newBuilder()
                .setName("holder.proto")
                .setSyntax("proto3")
                .setPackage("t")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Note")
                        .addField(field("text", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder().setName("Holder").addNestedType(order()))
                .build();
    }

    private static FileDescriptorProto orderFirst() {
        return FileDescriptorProto.newBuilder()
                .setName("order.proto")
                .setSyntax("proto3")
                .setPackage("t")
                .addMessageType(order())
                .build();
    }

    private static DescriptorProto order() {
        return DescriptorProto.newBuilder()
                .setName("Order")
                .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static String serialized(FileDescriptorProto file) {
        return "{\"schemaType\":\"PROTOBUF\",\"schema\":\""
                + Base64.getEncoder().encodeToString(file.toByteArray()) + "\"}";
    }

    private static String envelope(String avro) {
        return "{\"schema\":\"" + avro.replace("\"", "\\\"") + "\"}";
    }

    private void answer(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getQuery());
        String body = bodies.get(key);
        byte[] bytes = (body == null ? "{\"error_code\":40403,\"message\":\"Schema not found\"}" : body)
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String write(String name, String text) {
        return write(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private String write(String name, byte[] bytes) {
        try {
            return Files.write(directory.resolve(name), bytes).toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KafkaRecords records(KafkaSinkOptions options) {
        return new KafkaRecords(
                options.schema, options.keyOrdinals, options.changelog, options.valueEncoder, options.keyEncoder);
    }

    private static KafkaSinkOptions options(Map<String, String> overrides) {
        Map<String, String> config = new HashMap<>(Map.of(
                "bootstrap.servers", "localhost:9092",
                "topic", "orders",
                "schema", COLUMNS,
                "key.columns", "id"));
        config.putAll(overrides);
        return new KafkaSinkOptions(new Ctx("orders-sink", config));
    }

    private static ConfigurationException refused(Map<String, String> overrides, String message) {
        try {
            options(overrides);
        } catch (ConfigurationException e) {
            assertThat(e).hasMessageContaining(message);
            return e;
        }
        throw new AssertionError("not refused: " + overrides);
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
