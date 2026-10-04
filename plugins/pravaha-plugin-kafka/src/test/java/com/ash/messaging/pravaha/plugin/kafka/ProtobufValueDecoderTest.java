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

import java.math.BigDecimal;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Row;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code format: protobuf}: the mapping, the values, and proto3's defaults told apart from NULL. */
class ProtobufValueDecoderTest {

    private static final String COLUMNS = "id:INT64,name:STRING,flag:BOOLEAN,ratio:FLOAT32,weight:FLOAT64,"
            + "blob:BYTES,colour:STRING,amount:DECIMAL(12,2),day:DATE,clock:TIME,at:TIMESTAMP,"
            + "count:INT64,big:INT64,maybe:STRING?";

    @Test
    void everyKindOfFieldThisSourceMapsBecomesItsColumn() throws Undecodable {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema schema = KafkaSchema.parse("orders", COLUMNS);
        ProtobufValueDecoder decoder = ProtobufValueDecoder.map(schema, -1, order, false);

        DynamicMessage message = DynamicMessage.newBuilder(order)
                .setField(field(order, "id"), 42L)
                .setField(field(order, "name"), "ashutosh")
                .setField(field(order, "flag"), true)
                .setField(field(order, "ratio"), 1.5f)
                .setField(field(order, "weight"), 2.25)
                .setField(field(order, "blob"), ByteString.copyFrom(new byte[] {1, 2, 3}))
                .setField(field(order, "colour"), order.getEnumTypes().get(0).findValueByName("GREEN"))
                .setField(field(order, "amount"), "1234.56")
                .setField(field(order, "day"), "2026-09-19")
                .setField(field(order, "clock"), "10:15:30.5")
                .setField(field(order, "at"), timestamp(order, 1_700_000_000L, 123_000_000))
                .setField(field(order, "count"), -1) // uint32 4294967295, stored as an int
                .setField(field(order, "big"), 7L)
                .setField(field(order, "maybe"), "here")
                .addRepeatedField(field(order, "tags"), "ignored")
                .build();

        Row row = decoder.decode(message.toByteArray(), 5_000L);
        assertThat(row.values())
                .containsExactly(
                        42L,
                        "ashutosh",
                        true,
                        1.5f,
                        2.25,
                        new byte[] {1, 2, 3},
                        "GREEN",
                        new BigDecimal("1234.56"),
                        20_715,
                        36_930_500_000_000L,
                        1_700_000_000_123_000_000L,
                        4_294_967_295L,
                        7L,
                        "here");
        assertThat(row.weight()).isEqualTo(1L);
        assertThat(row.eventTimeNanos()).isEqualTo(5_000_000_000L);
    }

    @Test
    void anAbsentProto3ScalarIsItsTypesDefaultAndOnlyAPresenceFieldIsNull() throws Undecodable {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        // Every column nullable, so nothing here is refused for being NOT NULL: what is asserted is
        // which of them the decoder actually leaves null.
        StreamSchema schema =
                KafkaSchema.parse("orders", "id:INT64?,name:STRING?,flag:BOOLEAN?,at:TIMESTAMP?," + "maybe:STRING?");
        ProtobufValueDecoder decoder = ProtobufValueDecoder.map(schema, -1, order, false);

        Row row = decoder.decode(DynamicMessage.newBuilder(order).build().toByteArray(), 0);

        assertThat(row.values())
                .as("a proto3 scalar with no presence cannot be absent: it is 0, \"\" and false. Only the "
                        + "message field and the proto3 `optional` one are NULL")
                .containsExactly(0L, "", false, null, null);
    }

    @Test
    void aProto3OptionalFieldSetToItsDefaultIsStillPresentAndNotNull() throws Undecodable {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema schema = KafkaSchema.parse("orders", "maybe:STRING?");
        ProtobufValueDecoder decoder = ProtobufValueDecoder.map(schema, -1, order, false);

        DynamicMessage set = DynamicMessage.newBuilder(order)
                .setField(field(order, "maybe"), "")
                .build();
        assertThat(decoder.decode(set.toByteArray(), 0).values()).containsExactly("");
    }

    @Test
    void anUnsignedSixtyFourBitValuePastTheSignedRangeIsRefusedRatherThanReadAsNegative() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema schema = KafkaSchema.parse("orders", "big:INT64");
        ProtobufValueDecoder decoder = ProtobufValueDecoder.map(schema, -1, order, false);
        byte[] huge = DynamicMessage.newBuilder(order)
                .setField(field(order, "big"), -1L)
                .build()
                .toByteArray();

        assertThatThrownBy(() -> decoder.decode(huge, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("18446744073709551615")
                .hasMessageContaining("past the largest signed 64-bit integer");
    }

    @Test
    void aColumnTheMessageHasNoFieldForIsRefusedByNameAtConfiguration() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema schema = KafkaSchema.parse("orders", "id:INT64,nowhere:STRING");

        assertThatThrownBy(() -> ProtobufValueDecoder.map(schema, -1, order, false))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("no field for column 'nowhere'")
                .hasMessageContaining("tags");
    }

    @Test
    void aRepeatedFieldIsRefusedBecauseAColumnIsOneValue() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema schema = KafkaSchema.parse("orders", "tags:STRING");

        assertThatThrownBy(() -> ProtobufValueDecoder.map(schema, -1, order, false))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("repeated field or a map is many values");
    }

    @Test
    void anInt64FieldIsNotReadAsATimestampBecauseItDoesNotSayItsUnit() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema schema = KafkaSchema.parse("orders", "id:TIMESTAMP");

        assertThatThrownBy(() -> ProtobufValueDecoder.map(schema, -1, order, false))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("does not say whether it counts seconds, millis or micros");
    }

    @Test
    void aDecimalColumnNeedsAStringAndRefusesWhatWouldHaveToBeRounded() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema fromDouble = KafkaSchema.parse("orders", "weight:DECIMAL(12,2)");
        assertThatThrownBy(() -> ProtobufValueDecoder.map(fromDouble, -1, order, false))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("a double would not be exact");

        StreamSchema schema = KafkaSchema.parse("orders", "amount:DECIMAL(12,2)");
        ProtobufValueDecoder decoder = ProtobufValueDecoder.map(schema, -1, order, false);
        byte[] threePlaces = DynamicMessage.newBuilder(order)
                .setField(field(order, "amount"), "1.234")
                .build()
                .toByteArray();
        assertThatThrownBy(() -> decoder.decode(threePlaces, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("refused rather than rounded");

        byte[] notANumber = DynamicMessage.newBuilder(order)
                .setField(field(order, "amount"), "twelve")
                .build()
                .toByteArray();
        assertThatThrownBy(() -> decoder.decode(notANumber, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("'twelve' is not a number");
    }

    @Test
    void bytesThatAreNotTheMessageAreUndecodable() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        ProtobufValueDecoder decoder =
                ProtobufValueDecoder.map(KafkaSchema.parse("orders", "id:INT64"), -1, order, false);

        // A length-delimited field whose length runs past the end of the value.
        byte[] truncated = {0x12, 0x7f, 0x01, 0x02};
        assertThatThrownBy(() -> decoder.decode(truncated, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("is not a pravaha.test.Order");
    }

    @Test
    void theConfluentPrefixAndItsMessageIndexArrayAreReadPast() throws Undecodable {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        StreamSchema schema = KafkaSchema.parse("orders", "id:INT64");
        ProtobufValueDecoder decoder = ProtobufValueDecoder.map(schema, -1, order, true);
        byte[] body = DynamicMessage.newBuilder(order)
                .setField(field(order, "id"), 11L)
                .build()
                .toByteArray();

        // The optimised form: a single zero byte meaning "the first message in the schema".
        assertThat(decoder.decode(framed(7, new byte[] {0}, body), 0).values()).containsExactly(11L);
        // And the explicit form: a count, then that many zig-zag varint indexes.
        byte[] indexes = new AvroWriter().number(2).number(1).number(0).bytes();
        assertThat(decoder.decode(framed(7, indexes, body), 0).values()).containsExactly(11L);
    }

    @Test
    void aValueWithNoRegistryPrefixIsRefusedByNameWhenTheRegistryIsConfigured() {
        Descriptor order = ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), ProtoFixtures.ORDER);
        ProtobufValueDecoder decoder =
                ProtobufValueDecoder.map(KafkaSchema.parse("orders", "id:INT64"), -1, order, true);
        byte[] bare = DynamicMessage.newBuilder(order)
                .setField(field(order, "id"), 11L)
                .build()
                .toByteArray();

        assertThatThrownBy(() -> decoder.decode(bare, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("schema.registry.url is set");
    }

    @Test
    void aDescriptorSetThatIsNotOneOrHasNoSuchMessageIsRefusedWithWhatItDoesHold() {
        assertThatThrownBy(() -> ProtobufSchemas.message(
                        "not a descriptor set at all".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        ProtoFixtures.ORDER))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("--include_imports");

        assertThatThrownBy(() -> ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), "pravaha.test.Nope"))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("has no message called 'pravaha.test.Nope'")
                .hasMessageContaining("pravaha.test.Order");
    }

    @Test
    void aMessageIsAlsoFoundByItsSimpleName() {
        assertThat(ProtobufSchemas.message(ProtoFixtures.orderDescriptorSet(), "Order")
                        .getFullName())
                .isEqualTo(ProtoFixtures.ORDER);
    }

    @Test
    void aWellKnownImportTheSetLeftOutIsTakenFromTheRuntimeAndAnythingElseIsRefused() {
        // protoc without --include_imports: the timestamp file is not in the set, and is found anyway.
        byte[] withoutImports = ProtoFixtures.descriptorSet(false, ProtoFixtures.orderFile());
        assertThat(ProtobufSchemas.message(withoutImports, ProtoFixtures.ORDER).getFullName())
                .isEqualTo(ProtoFixtures.ORDER);

        FileDescriptorProto importsSomethingElse = ProtoFixtures.orderFile().toBuilder()
                .addDependency("acme/money.proto")
                .build();
        byte[] missing = ProtoFixtures.descriptorSet(true, importsSomethingElse);
        assertThatThrownBy(() -> ProtobufSchemas.message(missing, ProtoFixtures.ORDER))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("acme/money.proto")
                .hasMessageContaining("--include_imports");
    }

    @Test
    void aFieldIsMatchedIgnoringCaseWhenNoColumnMatchesExactly() throws Undecodable {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("case.proto")
                .setSyntax("proto3")
                .addMessageType(com.google.protobuf.DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("Cased")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("ORDERID")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .build();
        Descriptor cased = ProtobufSchemas.message(ProtoFixtures.descriptorSet(false, file), "Cased");
        ProtobufValueDecoder decoder =
                ProtobufValueDecoder.map(KafkaSchema.parse("s", "orderId:INT64"), -1, cased, false);
        byte[] value = DynamicMessage.newBuilder(cased)
                .setField(cased.findFieldByName("ORDERID"), 3L)
                .build()
                .toByteArray();

        assertThat(decoder.decode(value, 0).values()).containsExactly(3L);
    }

    // ---------------------------------------------------------------------------------------

    private static FieldDescriptor field(Descriptor message, String name) {
        FieldDescriptor field = message.findFieldByName(name);
        assertThat(field).as(name).isNotNull();
        return field;
    }

    /** A {@code google.protobuf.Timestamp} as the dynamic message the descriptor expects. */
    private static Object timestamp(Descriptor order, long seconds, int nanos) {
        Descriptor type = field(order, "at").getMessageType();
        return DynamicMessage.newBuilder(type)
                .setField(type.findFieldByName("seconds"), seconds)
                .setField(type.findFieldByName("nanos"), nanos)
                .build();
    }

    private static byte[] framed(int schemaId, byte[] indexes, byte[] body) {
        byte[] framed = new byte[5 + indexes.length + body.length];
        framed[0] = 0;
        framed[1] = (byte) (schemaId >>> 24);
        framed[2] = (byte) (schemaId >>> 16);
        framed[3] = (byte) (schemaId >>> 8);
        framed[4] = (byte) schemaId;
        System.arraycopy(indexes, 0, framed, 5, indexes.length);
        System.arraycopy(body, 0, framed, 5 + indexes.length, body.length);
        return framed;
    }
}
