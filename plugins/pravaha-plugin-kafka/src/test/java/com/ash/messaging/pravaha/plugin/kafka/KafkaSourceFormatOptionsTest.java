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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code format: avro} and {@code format: protobuf} as a binding configures them: every combination
 * that cannot work refused <em>by name</em> when the query registers, and the ones that can work
 * turned into a decoder that reads a record.
 */
class KafkaSourceFormatOptionsTest {

    private static final String AVRO_SCHEMA = "{\"type\":\"record\",\"name\":\"Txn\",\"fields\":["
            + "{\"name\":\"user_id\",\"type\":\"string\"},{\"name\":\"amount\",\"type\":\"long\"}]}";

    @TempDir
    Path directory;

    @Test
    void avroNeedsExactlyOneOfASchemaFileAndARegistryAndSaysSoEitherWay() throws IOException {
        assertRefused(Map.of("format", "avro"), "format: avro needs exactly one of schema.file", "has neither");
        assertRefused(
                Map.of(
                        "format", "avro",
                        "schema.file", avroFile().toString(),
                        "schema.registry.url", "http://registry:8081"),
                "format: avro needs exactly one of schema.file",
                "has both");
    }

    @Test
    void protobufNeedsBothItsDescriptorAndItsMessage() throws IOException {
        assertRefused(Map.of("format", "protobuf"), "format: protobuf needs schema.descriptor", "no schema.descriptor");
        assertRefused(
                Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        descriptorFile().toString()),
                "format: protobuf needs schema.descriptor",
                "no schema.message");
    }

    @Test
    void anOptionOfAnotherFormatIsRefusedWithTheFormatItBelongsTo() throws IOException {
        assertRefused(
                Map.of("schema.file", avroFile().toString()),
                "sets schema.file with format: json",
                "belongs to format: avro");
        assertRefused(
                Map.of("format", "changelog", "schema.registry.url", "http://registry:8081"),
                "sets schema.registry.url with format: changelog",
                "belongs to format: avro");
        assertRefused(
                Map.of("schema.descriptor", descriptorFile().toString()),
                "sets schema.descriptor with format: json",
                "belongs to format: protobuf");
        assertRefused(
                Map.of(
                        "format", "avro",
                        "schema.file", avroFile().toString(),
                        "schema.message", "pravaha.test.Order"),
                "sets schema.message with format: avro",
                "belongs to format: protobuf");
        assertRefused(
                Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        descriptorFile().toString(),
                        "schema.message",
                        ProtoFixtures.ORDER,
                        "schema.file",
                        avroFile().toString()),
                "sets schema.file with format: protobuf",
                "belongs to format: avro");
    }

    @Test
    void registryCredentialsWithNoRegistryAreRefused() {
        assertRefused(
                Map.of("schema.registry.token", "t"),
                "sets schema.registry.user, schema.registry.password or schema.registry.token without "
                        + "schema.registry.url",
                "no registry to send them to");
    }

    @Test
    void aRegistryUrlThatIsNotHttpIsRefused() {
        assertRefused(
                Map.of("format", "avro", "schema.registry.url", "registry:8081"),
                "is not an http:// or https:// URL",
                "registry:8081");
    }

    @Test
    void anHttpsRegistryWithHostnameVerificationTurnedOffIsRefusedRatherThanQuietlyVerified() {
        assertRefused(
                Map.of(
                        "format", "avro",
                        "schema.registry.url", "https://registry:8081",
                        "tls.enabled", "true",
                        "tls.verify-hostname", "false"),
                "sets tls.verify-hostname: false with an https schema.registry.url",
                "use http:// for the registry");
    }

    @Test
    void aSchemaFileThatCannotBeReadIsRefusedWithTheReason() {
        assertRefused(
                Map.of(
                        "format",
                        "avro",
                        "schema.file",
                        directory.resolve("nothing.avsc").toString()),
                "cannot read schema.file",
                "nothing.avsc");
    }

    @Test
    void aSchemaFileThatIsNotAvroOrDoesNotMapIsRefusedWithTheSchemaCode() throws IOException {
        Path notAvro = directory.resolve("broken.avsc");
        Files.writeString(notAvro, "{\"type\":\"record\"", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> options(Map.of("format", "avro", "schema.file", notAvro.toString())))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5108")
                .hasMessageContaining("is not an Avro schema");

        Path other = directory.resolve("other.avsc");
        Files.writeString(
                other,
                "{\"type\":\"record\",\"name\":\"Other\",\"fields\":[{\"name\":\"user_id\",\"type\":\"string\"}]}",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> options(Map.of("format", "avro", "schema.file", other.toString())))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5108")
                .hasMessageContaining("no field for column [amount]");
    }

    @Test
    void aDescriptorThatIsNotOneOrHasNoSuchMessageIsRefusedWithTheSchemaCode() throws IOException {
        Path notADescriptor = directory.resolve("broken.desc");
        Files.writeString(notADescriptor, "this is not a descriptor set", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> options(Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        notADescriptor.toString(),
                        "schema.message",
                        ProtoFixtures.ORDER)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5108");

        assertThatThrownBy(() -> options(Map.of(
                        "format", "protobuf",
                        "schema.descriptor", descriptorFile().toString(),
                        "schema.message", "pravaha.test.Nope")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5108")
                .hasMessageContaining("has no message called 'pravaha.test.Nope'");
    }

    @Test
    void aProtobufMessageWithNoFieldForAColumnIsRefusedAtConfigurationRatherThanPerRecord() throws IOException {
        assertThatThrownBy(() -> options(Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        descriptorFile().toString(),
                        "schema.message",
                        ProtoFixtures.ORDER,
                        "schema",
                        "user_id:STRING,amount:INT64")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5108")
                .hasMessageContaining("no field for column 'user_id'");
    }

    @Test
    void anAvroBindingMakesADecoderThatReadsARecord() throws IOException, Undecodable {
        KafkaSourceOptions options =
                options(Map.of("format", "avro", "schema.file", avroFile().toString()));

        assertThat(options.format).isEqualTo(KafkaSourceOptions.Format.AVRO);
        assertThat(options.registryRequests()).as("no registry configured").isEqualTo(-1);
        KafkaValueDecoder decoder = options.newDecoder();
        assertThat(decoder).isInstanceOf(AvroValueDecoder.class);
        byte[] value = new AvroWriter().text("u1").number(250).bytes();
        assertThat(decoder.decode(value, 3_000L).values()).containsExactly("u1", 250L);
        options.close();
    }

    @Test
    void aProtobufBindingMakesADecoderThatReadsAMessage() throws IOException, Undecodable {
        KafkaSourceOptions options = options(Map.of(
                "format", "protobuf",
                "schema.descriptor", descriptorFile().toString(),
                "schema.message", "Order",
                "schema", "id:INT64,name:STRING"));

        assertThat(options.format).isEqualTo(KafkaSourceOptions.Format.PROTOBUF);
        KafkaValueDecoder decoder = options.newDecoder();
        assertThat(decoder).isInstanceOf(ProtobufValueDecoder.class);
        var order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        byte[] value = com.google.protobuf.DynamicMessage.newBuilder(order)
                .setField(order.findFieldByName("id"), 9L)
                .setField(order.findFieldByName("name"), "nine")
                .build()
                .toByteArray();
        assertThat(decoder.decode(value, 0).values()).containsExactly(9L, "nine");
        options.close();
    }

    @Test
    void aRegistryBindingMakesADecoderAndAskesTheRegistryNothingUntilARecordArrives() throws IOException {
        KafkaSourceOptions options = options(Map.of(
                "format", "avro",
                "schema.registry.url", "http://127.0.0.1:1/subjects-are-not-read",
                "schema.registry.user", "u",
                "schema.registry.password", "p"));

        assertThat(options.newDecoder()).isInstanceOf(AvroValueDecoder.class);
        assertThat(options.registryRequests())
                .as("opening a binding never calls the registry: schemas are fetched by id, with the records")
                .isZero();
        options.close();
    }

    // ---------------------------------------------------------------------------------------

    private Path avroFile() throws IOException {
        Path file = directory.resolve("txn.avsc");
        Files.writeString(file, AVRO_SCHEMA, StandardCharsets.UTF_8);
        return file;
    }

    private Path descriptorFile() throws IOException {
        Path file = directory.resolve("order.desc");
        Files.write(file, ProtoFixtures.orderDescriptorSet());
        return file;
    }

    private static void assertRefused(Map<String, String> overrides, String... messages) {
        var thrown = assertThatThrownBy(() -> options(overrides))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5100");
        for (String message : messages) {
            thrown.hasMessageContaining(message);
        }
    }

    private static KafkaSourceOptions options(Map<String, String> overrides) {
        Map<String, String> config = new HashMap<>();
        config.put("bootstrap.servers", "kafka-1:9092");
        config.put("topic", "txn");
        config.put("schema", "user_id:STRING,amount:INT64");
        config.putAll(overrides);
        return new KafkaSourceOptions(new Ctx("txn", config));
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
