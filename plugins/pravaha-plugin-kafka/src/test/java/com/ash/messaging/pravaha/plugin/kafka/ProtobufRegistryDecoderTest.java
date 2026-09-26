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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TimestampProto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code format: protobuf} with the registry describing each record: the schema fetched serialized,
 * its references fetched by subject and version, the message chosen by the record's message indexes,
 * and every way that can fail refused by name.
 */
class ProtobufRegistryDecoderTest {

    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private SchemaRegistry client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        client = new SchemaRegistry("shipments", url(), null, "", Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
    }

    @Test
    void aRecordIsReadAsTheMessageItsIndexesSelectInTheSchemaItsIdNamesWithItsReferences() throws Exception {
        serveShipments();
        ProtobufRegistryDecoder decoder =
                new ProtobufRegistryDecoder(KafkaSchema.parse("s", "id:INT64,carrier:STRING"), -1, "", client);
        Descriptor shipment = shipmentDescriptor().findMessageTypeByName("Shipment");
        byte[] body = DynamicMessage.newBuilder(shipment)
                .setField(shipment.findFieldByName("id"), 9L)
                .setField(shipment.findFieldByName("carrier"), "dhl")
                .build()
                .toByteArray();

        assertThat(decoder.decode(
                                framed(21, new AvroWriter().number(1).number(1).bytes(), body), 0)
                        .values())
                .containsExactly(9L, "dhl");
        assertThat(decoder.decode(
                                framed(21, new AvroWriter().number(1).number(1).bytes(), body), 0)
                        .values())
                .containsExactly(9L, "dhl");
        assertThat(requests)
                .as("the schema and its one reference, each fetched once and serialized")
                .containsExactly(
                        "/schemas/ids/21?format=serialized", "/subjects/order-value/versions/1?format=serialized");
    }

    @Test
    void aRecordSelectingAnotherMessageThanSchemaMessageIsADeadLetterNamingBoth() throws Exception {
        serveShipments();
        ProtobufRegistryDecoder decoder =
                new ProtobufRegistryDecoder(KafkaSchema.parse("s", "id:INT64"), -1, "pravaha.ship.Shipment", client);
        Descriptor note = shipmentDescriptor().findMessageTypeByName("Note");
        byte[] body = DynamicMessage.newBuilder(note)
                .setField(note.findFieldByName("text"), "x")
                .build()
                .toByteArray();

        assertThatThrownBy(() -> decoder.decode(framed(21, new byte[] {0}, body), 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("schema id 21's message indexes [0] select pravaha.ship.Note")
                .hasMessageContaining("schema.message is pravaha.ship.Shipment");
    }

    @Test
    void aMessageThatDoesNotMapOrIndexesThatSelectNothingAreDeadLetters() throws Exception {
        serveShipments();
        ProtobufRegistryDecoder decoder =
                new ProtobufRegistryDecoder(KafkaSchema.parse("s", "id:INT64,weight:FLOAT64"), -1, "", client);
        byte[] empty = new byte[0];

        assertThatThrownBy(() -> decoder.decode(
                        framed(21, new AvroWriter().number(1).number(1).bytes(), empty), 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("schema id 21 cannot be read into this stream's columns")
                .hasMessageContaining("no field for column 'weight'");
        assertThatThrownBy(() -> decoder.decode(
                        framed(21, new AvroWriter().number(1).number(5).bytes(), empty), 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("message indexes [5] select nothing");
    }

    @Test
    void aRegistryThatAnswersWithProtoSourceOrAnAvroSchemaIsRefusedByNameAndStopsTheReader() {
        bodies.put(
                "/schemas/ids/31?format=serialized",
                "{\"schemaType\":\"PROTOBUF\",\"schema\":\"syntax = \\\"proto3\\\"; message A { int64 id = 1; }\"}");
        bodies.put("/schemas/ids/32?format=serialized", "{\"schema\":\"{\\\"type\\\":\\\"string\\\"}\"}");
        ProtobufRegistryDecoder decoder =
                new ProtobufRegistryDecoder(KafkaSchema.parse("s", "id:INT64"), -1, "", client);

        assertThatThrownBy(() -> decoder.decode(framed(31, new byte[] {0}, new byte[0]), 0))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5109")
                .hasMessageContaining("answered with .proto source rather than a serialized descriptor")
                .hasMessageContaining("schema.descriptor");
        assertThatThrownBy(() -> decoder.decode(framed(32, new byte[] {0}, new byte[0]), 0))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5109")
                .hasMessageContaining("not PROTOBUF");
    }

    @Test
    void aProtobufBindingWithARegistryAndNoDescriptorConfiguresAndAsksNothingUntilARecord() {
        Map<String, String> config = new HashMap<>();
        config.put("bootstrap.servers", "kafka-1:9092");
        config.put("topic", "shipments");
        config.put("schema", "id:INT64,carrier:STRING");
        config.put("format", "protobuf");
        config.put("schema.registry.url", url());
        KafkaSourceOptions options = new KafkaSourceOptions(new Ctx("shipments", config));

        assertThat(options.newDecoder()).isInstanceOf(ProtobufRegistryDecoder.class);
        assertThat(requests).isEmpty();
        options.close();
    }

    // ---- the registry's answers ----------------------------------------------------------------

    private void serveShipments() {
        bodies.put(
                "/schemas/ids/21?format=serialized",
                serialized(
                        shipmentFile(),
                        ",\"references\":[{\"name\":\"pravaha/test/order.proto\","
                                + "\"subject\":\"order-value\",\"version\":1}]"));
        bodies.put("/subjects/order-value/versions/1?format=serialized", serialized(ProtoFixtures.orderFile(), ""));
    }

    private static String serialized(FileDescriptorProto file, String extra) {
        return "{\"schemaType\":\"PROTOBUF\",\"schema\":\""
                + Base64.getEncoder().encodeToString(file.toByteArray()) + "\"" + extra + "}";
    }

    /** {@code shipment.proto}: a Note, then a Shipment that holds a {@code pravaha.test.Order}. */
    private static FileDescriptorProto shipmentFile() {
        return FileDescriptorProto.newBuilder()
                .setName("shipment.proto")
                .setSyntax("proto3")
                .setPackage("pravaha.ship")
                .addDependency("pravaha/test/order.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Note")
                        .addField(field("text", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Shipment")
                        .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                        .addField(field("carrier", 2, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("order", 3, FieldDescriptorProto.Type.TYPE_MESSAGE).toBuilder()
                                .setTypeName(".pravaha.test.Order")))
                .build();
    }

    private static FileDescriptor shipmentDescriptor() throws Exception {
        FileDescriptor order = FileDescriptor.buildFrom(
                ProtoFixtures.orderFile(), new FileDescriptor[] {TimestampProto.getDescriptor()});
        return FileDescriptor.buildFrom(shipmentFile(), new FileDescriptor[] {order});
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static byte[] framed(int schemaId, byte[] indexes, byte[] body) {
        byte[] framed = new byte[5 + indexes.length + body.length];
        framed[1] = (byte) (schemaId >>> 24);
        framed[2] = (byte) (schemaId >>> 16);
        framed[3] = (byte) (schemaId >>> 8);
        framed[4] = (byte) schemaId;
        System.arraycopy(indexes, 0, framed, 5, indexes.length);
        System.arraycopy(body, 0, framed, 5 + indexes.length, body.length);
        return framed;
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void answer(HttpExchange exchange) throws IOException {
        String asked = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getRawQuery());
        requests.add(asked);
        String body = bodies.get(asked);
        byte[] bytes = (body == null ? "{\"error_code\":40403}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
