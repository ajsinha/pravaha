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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.google.protobuf.Descriptors.Descriptor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code kafka-sink}'s Avro and protobuf values, proved by round trip: a row written by the sink's
 * own encoding and read back by this plugin's source decoders, with the same schema, is the same row.
 */
class KafkaSinkFormatsTest {

    static final String ALL_AVRO = "{\"type\":\"record\",\"name\":\"All\",\"namespace\":\"t\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"string\"},"
            + "{\"name\":\"flag\",\"type\":\"boolean\"},"
            + "{\"name\":\"tiny\",\"type\":\"int\"},"
            + "{\"name\":\"small\",\"type\":\"long\"},"
            + "{\"name\":\"mid\",\"type\":\"int\"},"
            + "{\"name\":\"big\",\"type\":\"long\"},"
            + "{\"name\":\"f32\",\"type\":\"float\"},"
            + "{\"name\":\"f64\",\"type\":\"double\"},"
            + "{\"name\":\"amount\",\"type\":{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":10,"
            + "\"scale\":2}},"
            + "{\"name\":\"fixedamt\",\"type\":{\"type\":\"fixed\",\"name\":\"Amt\",\"size\":8,\"logicalType\":"
            + "\"decimal\",\"precision\":12,\"scale\":3}},"
            + "{\"name\":\"blob\",\"type\":\"bytes\"},"
            + "{\"name\":\"day\",\"type\":{\"type\":\"int\",\"logicalType\":\"date\"}},"
            + "{\"name\":\"clock\",\"type\":{\"type\":\"long\",\"logicalType\":\"time-micros\"}},"
            + "{\"name\":\"clockms\",\"type\":{\"type\":\"int\",\"logicalType\":\"time-millis\"}},"
            + "{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}},"
            + "{\"name\":\"atms\",\"type\":[\"null\",{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}]},"
            + "{\"name\":\"note\",\"type\":[\"null\",\"string\"]},"
            + "{\"name\":\"extra\",\"type\":[\"null\",\"long\"]}]}";

    static final String ALL_COLUMNS = "id:STRING,flag:BOOLEAN,tiny:INT8,small:INT16,mid:INT32,big:INT64,"
            + "f32:FLOAT32,f64:FLOAT64,amount:DECIMAL(10,2),fixedamt:DECIMAL(12,3),blob:BYTES,"
            + "day:DATE,clock:TIME(6),clockms:TIME(3),at:TIMESTAMP(6),atms:TIMESTAMP(3),note:STRING?";

    /** ALL_COLUMNS' declared time precisions, which decide the Avro time fields they may go to. */
    static final int[] ALL_PRECISIONS = KafkaSchema.precisions(ALL_COLUMNS);

    private final TestRows rows = new TestRows();

    @TempDir
    Path directory;

    @AfterEach
    void close() {
        rows.close();
    }

    // ---- Avro ---------------------------------------------------------------------------------

    @Test
    void avroRowsComeBackThroughTheSourceAsTheSameRows() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("all", ALL_COLUMNS);
        AvroSchema.Node writer = AvroSchema.parse(ALL_AVRO);
        KafkaRecords records =
                new KafkaRecords(schema, new int[] {0}, false, AvroRowWriter.map(schema, writer, -1, ALL_PRECISIONS));
        AvroRowReader reader = AvroRowReader.map(schema, writer, -1);

        for (Object[] row : new Object[][] {allTypes("u1", 1, "a note"), allTypes("ü-2", -1, null), extremes()}) {
            byte[] value = records.encode(rows.row(schema, 1, row)).value();
            assertThat(reader.read(Objects.requireNonNull(value), 0, 0L).values())
                    .as("row %s", row[0])
                    .containsExactly(row);
        }
    }

    @Test
    void aRetractionIsStillATombstone() {
        StreamSchema schema = KafkaSchema.parse("all", ALL_COLUMNS);
        KafkaRecords records = new KafkaRecords(
                schema,
                new int[] {0},
                false,
                AvroRowWriter.map(schema, AvroSchema.parse(ALL_AVRO), -1, ALL_PRECISIONS));
        KafkaRecords.Encoded retraction = records.encode(rows.row(schema, -1, allTypes("u1", 1, null)));
        assertThat(retraction.value()).isNull();
        assertThat(new String(retraction.key(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"u1\"}");
    }

    @Test
    void aSchemaIdPutsTheConfluentPrefixInFrontAndRegistersNothing() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("all", ALL_COLUMNS);
        AvroSchema.Node writer = AvroSchema.parse(ALL_AVRO);
        Object[] row = allTypes("u1", 1, "n");
        byte[] value = AvroRowWriter.map(schema, writer, 258, ALL_PRECISIONS).encode(row);
        assertThat(Arrays.copyOf(value, 5)).containsExactly(0, 0, 0, 1, 2);
        assertThat(AvroRowReader.map(schema, writer, -1).read(value, 5, 0L).values())
                .containsExactly(row);
    }

    @Test
    void aColumnTheAvroSchemaCannotHoldExactlyIsRefusedByName() {
        assertUnmappable("x:STRING", "{\"type\":\"record\",\"name\":\"R\",\"fields\":[]}", "no field for column 'x'");
        assertUnmappable("x:INT64", record("\"int\""), "an int would not hold every value");
        assertUnmappable("x:FLOAT64", record("\"float\""), "a float would round it");
        assertUnmappable("x:STRING?", record("\"string\""), "no null branch");
        assertUnmappable("x:STRING", record("[\"null\",\"long\"]"), "no branch of the union");
        assertUnmappable(
                "x:DECIMAL(10,4)",
                record("{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":10,\"scale\":2}"),
                "a scale of at least s");
        assertUnmappable(
                "x:DECIMAL(20,0)",
                record("{\"type\":\"fixed\",\"name\":\"F\",\"size\":4,\"logicalType\":\"decimal\",\"precision\":20}"),
                "big enough for its precision");
        assertUnmappable("x:TIMESTAMP", record("\"long\""), "timestamp-millis or timestamp-micros");
        assertUnmappable("x:DATE", record("\"int\""), "logicalType date");
        assertUnmappable("x:TIME", record("\"long\""), "time-micros");
        assertUnmappable("x:BOOLEAN", record("\"int\""), "does not hold every value");
        assertUnmappable("x:STRING", "\"string\"", "not a record");
        assertUnmappable(
                "x:STRING",
                "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"string\"},"
                        + "{\"name\":\"y\",\"type\":\"long\"}]}",
                "no column names it");
        assertThat(AvroRowWriter.map(
                        KafkaSchema.parse("t", "x:STRING"),
                        AvroSchema.parse(record("\"string\"").replace("\"x\"", "\"X\"")),
                        -1))
                .as("matched ignoring case")
                .isNotNull();
        assertUnmappable(
                "ab:STRING",
                "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"AB\",\"type\":\"string\"},"
                        + "{\"name\":\"Ab\",\"type\":\"string\"}]}",
                "both match column 'ab' ignoring case");
    }

    @Test
    void aTimeDeclaredFinerThanItsAvroFieldIsRefusedAtConfigurationByName() {
        // KSF-4: known at configuration, not at the first value with a nanosecond part.
        String avro = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},"
                + "{\"name\":\"clock\",\"type\":{\"type\":\"long\",\"logicalType\":\"time-micros\"}}]}";
        assertThatThrownBy(() -> map("at:TIMESTAMP,clock:TIME(6)", avro))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("Avro field 'at' is timestamp-millis and column 'at' is TIMESTAMP(9)")
                .hasMessageContaining("Declare the column TIMESTAMP(3)")
                .hasMessageContaining("timestamp-micros field");
        assertThatThrownBy(() -> map("at:TIMESTAMP(6),clock:TIME(6)", avro)).hasMessageContaining("TIMESTAMP(6)");
        assertThatThrownBy(() -> map("at:TIMESTAMP(3),clock:TIME", avro))
                .hasMessageContaining("column 'clock' is TIME(9)")
                .hasMessageContaining("Declare the column TIME(6)");
        assertThat(map("at:TIMESTAMP(3),clock:TIME(6)", avro)).isNotNull();
        assertThat(map("at:TIMESTAMP(0),clock:TIME(3)", avro))
                .as("coarser is fine")
                .isNotNull();
    }

    @Test
    void aDeclaredPrecisionFloorsEveryValueToItsDigitsBeforeItIsWritten() throws Undecodable {
        String avro = "{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                + "{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},"
                + "{\"name\":\"coarse\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}},"
                + "{\"name\":\"clock\",\"type\":{\"type\":\"int\",\"logicalType\":\"time-millis\"}}]}";
        String columns = "at:TIMESTAMP(3),coarse:TIMESTAMP(1),clock:TIME(3)";
        StreamSchema schema = KafkaSchema.parse("t", columns);
        AvroRowWriter writer = map(columns, avro);
        AvroRowReader reader = AvroRowReader.map(schema, AvroSchema.parse(avro), -1);
        // 1.999999999 s, 0.999999999 s before the epoch, and 10:15:30.123456789.
        Object[] written = {1_999_999_999L, -999_999_999L, 36_930_123_456_789L};
        assertThat(reader.read(writer.encode(written), 0, 0L).values())
                .as("floored toward the past: a negative instant goes back, not toward zero")
                .containsExactly(1_999_000_000L, -1_000_000_000L, 36_930_123_000_000L);
    }

    @Test
    void aDecimalWithMorePlacesOrDigitsThanItsFieldIsStillRefusedWhenWritten() {
        StreamSchema schema = KafkaSchema.parse("t", "amount:DECIMAL(4,2)");
        AvroRowWriter writer = AvroRowWriter.map(
                schema,
                AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                        + "{\"name\":\"amount\",\"type\":{\"type\":\"bytes\",\"logicalType\":\"decimal\","
                        + "\"precision\":4,\"scale\":2}}]}"),
                -1);
        assertThatThrownBy(() -> writer.encode(new Object[] {new BigDecimal("1.005")}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("more than the field's 2 places");
        assertThatThrownBy(() -> writer.encode(new Object[] {new BigDecimal("123.00")}))
                .hasMessageContaining("more than the field's 4 digits");
    }

    private static AvroRowWriter map(String columns, String avro) {
        return AvroRowWriter.map(
                KafkaSchema.parse("t", columns), AvroSchema.parse(avro), -1, KafkaSchema.precisions(columns));
    }

    // ---- protobuf -----------------------------------------------------------------------------

    private static final String ORDER_COLUMNS = "id:INT64,name:STRING,flag:BOOLEAN,ratio:FLOAT32,weight:FLOAT64,"
            + "blob:BYTES,amount:DECIMAL(10,2),day:DATE,clock:TIME,at:TIMESTAMP,maybe:STRING?";

    @Test
    void protobufRowsComeBackThroughTheSourceAsTheSameRows() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("orders", ORDER_COLUMNS);
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        KafkaRecords records = new KafkaRecords(schema, new int[] {0}, false, ProtobufRowWriter.map(schema, order));
        ProtobufValueDecoder decoder = ProtobufValueDecoder.map(schema, -1, order, false);

        Object[][] written = {
            {
                7L,
                "seven",
                true,
                1.5f,
                2.25,
                new byte[] {1, 2},
                new BigDecimal("12.34"),
                20_000,
                36_930_500_000_000L,
                1_789_000_000_123_456_789L,
                "here"
            },
            {
                Long.MIN_VALUE,
                "",
                false,
                Float.NaN,
                -0.5,
                new byte[0],
                new BigDecimal("-0.01"),
                -1,
                0L,
                -1_000_000_001L,
                null
            },
        };
        for (Object[] row : written) {
            byte[] value = records.encode(rows.row(schema, 1, row)).value();
            assertThat(decoder.decode(Objects.requireNonNull(value), 0L).values())
                    .as("row %s", row[0])
                    .containsExactly(row);
        }
    }

    @Test
    void aTimestampAsAStringAndIntegersIntoWiderFieldsComeBackToo() throws Undecodable {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        // id is int64, wider than the column; day is a string, which holds an ISO-8601 instant.
        StreamSchema schema = KafkaSchema.parse("orders", "id:INT32,day:TIMESTAMP");
        Object[] row = {-5, 1_789_000_000_000_000_001L};
        byte[] value = ProtobufRowWriter.map(schema, order).encode(row);
        Object[] back = ProtobufValueDecoder.map(schema, -1, order, false)
                .decode(value, 0L)
                .values();
        assertThat(back).containsExactly(row);
    }

    @Test
    void aColumnTheMessageCannotHoldExactlyIsRefusedByName() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        assertProtoUnmappable(order, "nope:STRING", "no field for column 'nope'");
        assertProtoUnmappable(order, "count:INT32", "cannot hold a negative");
        assertProtoUnmappable(order, "name:STRING?", "has no presence");
        assertProtoUnmappable(order, "tags:STRING", "repeated");
        assertProtoUnmappable(order, "colour:STRING", "does not hold every value");
        assertProtoUnmappable(order, "ratio:FLOAT64", "a float would round it");
        assertProtoUnmappable(order, "weight:FLOAT32", "does not hold every value");
        assertProtoUnmappable(order, "id:DECIMAL(5,1)", "protobuf has no decimal");
        assertProtoUnmappable(order, "id:DATE", "ISO-8601 string");
        assertProtoUnmappable(order, "id:TIMESTAMP", "google.protobuf.Timestamp");
        assertThat(ProtobufRowWriter.map(KafkaSchema.parse("o", "ID:INT64"), order))
                .as("matched ignoring case")
                .isNotNull();
    }

    // ---- options ------------------------------------------------------------------------------

    @Test
    void theOptionsBuildTheWriterAndRefuseWhatCannotWork() throws IOException {
        Path avsc = directory.resolve("all.avsc");
        Files.writeString(avsc, ALL_AVRO, StandardCharsets.UTF_8);
        Path desc = directory.resolve("order.desc");
        Files.write(desc, ProtoFixtures.orderDescriptorSet());

        KafkaSinkOptions avro = options(Map.of(
                "schema",
                ALL_COLUMNS,
                "key.columns",
                "id",
                "format",
                "avro",
                "schema.file",
                avsc.toString(),
                "schema.id",
                "9"));
        assertThat(avro.valueEncoder).isInstanceOf(AvroRowWriter.class);
        KafkaSinkOptions proto = options(Map.of(
                "schema",
                ORDER_COLUMNS,
                "key.columns",
                "id",
                "format",
                "protobuf",
                "schema.descriptor",
                desc.toString(),
                "schema.message",
                ProtoFixtures.ORDER));
        assertThat(proto.valueEncoder).isInstanceOf(ProtobufRowWriter.class);
        assertThat(options(Map.of()).valueEncoder).isNull();

        refused(
                Map.of("format", "avro", "schema.file", avsc.toString(), "mode", "changelog"),
                "mode: changelog is JSON only");
        refused(Map.of("format", "protobuf", "mode", "changelog"), "mode: changelog is JSON only");
        refused(Map.of("schema.file", avsc.toString()), "schema.file is for format: avro");
        refused(Map.of("schema.id", "3"), "schema.id is for format: avro");
        refused(Map.of("schema.descriptor", desc.toString()), "schema.descriptor is for format: protobuf");
        refused(Map.of("schema.message", "x"), "schema.message is for format: protobuf");
        refused(Map.of("format", "protobuf"), "needs schema.message");
        refused(Map.of("format", "protobuf", "schema.message", ProtoFixtures.ORDER), "needs schema.descriptor");
        refused(Map.of("format", "protobuf", "schema.descriptor", desc.toString()), "needs schema.message");
        refused(
                Map.of(
                        "format",
                        "protobuf",
                        "schema.descriptor",
                        desc.toString(),
                        "schema.message",
                        ProtoFixtures.ORDER,
                        "schema.id",
                        "4"),
                "schema.id with format: protobuf needs schema.registry.url");
        refused(
                Map.of(
                        "format",
                        "avro",
                        "schema.file",
                        directory.resolve("gone").toString()),
                "cannot read");
        refused(Map.of("format", "avro", "schema.file", avsc.toString(), "schema.id", "-1"), "schema.id must be");
        refused(Map.of("format", "avro", "schema.file", avsc.toString(), "schema.id", "x"), "schema.id must be");

        Path bad = directory.resolve("bad.avsc");
        Files.writeString(bad, "{\"type\":", StandardCharsets.UTF_8);
        assertThat(refused(Map.of("format", "avro", "schema.file", bad.toString()), "is not an Avro schema")
                        .errorCode())
                .isEqualTo(KafkaErrors.SCHEMA_UNMAPPABLE);
        assertThat(refused(Map.of("format", "avro", "schema.file", avsc.toString()), "no field for column")
                        .errorCode())
                .isEqualTo(KafkaErrors.SCHEMA_UNMAPPABLE);
    }

    // ---------------------------------------------------------------------------------------

    /** Every column of {@link #ALL_COLUMNS}, as the source reads them back. */
    private static @Nullable Object[] allTypes(String id, int sign, @Nullable String note) {
        return new Object[] {
            id,
            sign > 0,
            (byte) (12 * sign),
            (short) (1_234 * sign),
            123_456 * sign,
            9_876_543_210L * sign,
            1.25f * sign,
            Math.PI * sign,
            new BigDecimal("1234.56").multiply(BigDecimal.valueOf(sign)),
            new BigDecimal("-98765.432").multiply(BigDecimal.valueOf(sign)),
            new byte[] {0, (byte) 0xFF, 7},
            20_000 * sign,
            36_930_123_456_000L,
            36_930_123_000_000L,
            1_789_000_000_123_456_000L * sign,
            1_789_000_000_123_000_000L * sign,
            note
        };
    }

    private static Object[] extremes() {
        return new Object[] {
            "",
            false,
            Byte.MIN_VALUE,
            Short.MAX_VALUE,
            Integer.MIN_VALUE,
            Long.MIN_VALUE,
            Float.NaN,
            Double.MAX_VALUE,
            new BigDecimal("-99999999.99"),
            new BigDecimal("999999999.999"),
            new byte[0],
            Integer.MIN_VALUE,
            86_399_999_999_000L,
            0L,
            Long.MIN_VALUE / 1_000 * 1_000,
            -1_000_000L,
            null
        };
    }

    private static String record(String type) {
        return "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":" + type + "}]}";
    }

    private static void assertUnmappable(String columns, String avro, String message) {
        StreamSchema schema = KafkaSchema.parse("t", columns);
        assertThatThrownBy(() -> AvroRowWriter.map(schema, AvroSchema.parse(avro), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining(message);
    }

    private static void assertProtoUnmappable(Descriptor message, String columns, String expected) {
        StreamSchema schema = KafkaSchema.parse("t", columns);
        assertThatThrownBy(() -> ProtobufRowWriter.map(schema, message))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining(expected);
    }

    private static KafkaSinkOptions options(Map<String, String> overrides) {
        Map<String, String> config = new HashMap<>(Map.of(
                "bootstrap.servers", "localhost:9092",
                "topic", "spend",
                "schema", "user_id:STRING,amount:INT64",
                "key.columns", "user_id"));
        config.putAll(overrides);
        return new KafkaSinkOptions(new Ctx("formats", config));
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
