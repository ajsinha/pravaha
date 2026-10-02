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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Row;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An Avro writer schema mapped to a stream's columns: what it reads, and what it refuses by name. */
class AvroRowReaderTest {

    @Test
    void everyTypeAndLogicalTypeThisSourceReadsBecomesItsColumn() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse(
                "orders",
                "flag:BOOLEAN,small:INT16,count:INT32,total:INT64,ratio:FLOAT32,weight:FLOAT64,"
                        + "name:STRING,colour:STRING,blob:BYTES,hash:BYTES,amount:DECIMAL(12,2),"
                        + "fee:DECIMAL(9,3),day:DATE,clock:TIME,micro:TIME,at:TIMESTAMP,precise:TIMESTAMP");
        AvroRowReader reader = AvroRowReader.map(schema, AvroSchema.parse(FULL_SCHEMA), -1);

        byte[] value = new AvroWriter()
                .bool(true)
                .integer(-7)
                .integer(42)
                .number(9_000_000_000L)
                .float32(1.5f)
                .float64(2.25)
                .text("ashutosh")
                .integer(1)
                .binary(new byte[] {1, 2, 3})
                .fixed(new byte[] {4, 5, 6, 7})
                .decimalBytes(new BigDecimal("1234.56"), 2)
                .decimalFixed(new BigDecimal("-0.125"), 3, 8)
                .integer(20_000)
                .integer(3_600_000)
                .number(3_600_000_001L)
                .number(1_700_000_000_123L)
                .number(1_700_000_000_123_456L)
                .bytes();

        Row row = reader.read(value, 0, 5_000L);
        assertThat(row.values())
                .containsExactly(
                        true,
                        (short) -7,
                        42,
                        9_000_000_000L,
                        1.5f,
                        2.25,
                        "ashutosh",
                        "GREEN",
                        new byte[] {1, 2, 3},
                        new byte[] {4, 5, 6, 7},
                        new BigDecimal("1234.56"),
                        new BigDecimal("-0.125"),
                        20_000,
                        3_600_000_000_000L,
                        3_600_000_001_000L,
                        1_700_000_000_123_000_000L,
                        1_700_000_000_123_456_000L);
        assertThat(row.weight()).isEqualTo(1L);
        assertThat(row.eventTimeNanos())
                .as("no event.time column, so the record's own timestamp")
                .isEqualTo(5_000_000_000L);
    }

    @Test
    void aNullableUnionReadsNullAndTheBranchAlike() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64,note:STRING?");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"note\",\"type\":[\"null\",\"string\"]}]}"),
                -1);

        assertThat(reader.read(new AvroWriter().number(1).union(0).bytes(), 0, 0)
                        .values())
                .containsExactly(1L, null);
        assertThat(reader.read(new AvroWriter().number(2).union(1).text("hi").bytes(), 0, 0)
                        .values())
                .containsExactly(2L, "hi");
    }

    @Test
    void aNullInANotNullColumnIsRefusedPerRecordTheWayTheJsonFormatRefusesAMissingMember() {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64,note:STRING");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"note\",\"type\":[\"null\",\"string\"]}]}"),
                -1);
        byte[] nullNote = new AvroWriter().number(1).union(0).bytes();

        assertThatThrownBy(() -> reader.read(nullNote, 0, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("column 'note'")
                .hasMessageContaining("NOT NULL");
    }

    @Test
    void aFieldNoColumnNamesIsSkippedWholeAndTheFieldsAfterItStillRead() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64,name:STRING");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"audit\",\"type\":{\"type\":\"record\",\"name\":\"A\",\"fields\":["
                        + "{\"name\":\"who\",\"type\":\"string\"},{\"name\":\"tags\",\"type\":"
                        + "{\"type\":\"array\",\"items\":\"long\"}}]}},"
                        + "{\"name\":\"name\",\"type\":\"string\"}]}"),
                -1);

        byte[] value = new AvroWriter()
                .number(5)
                .text("root")
                .block(2)
                .number(1)
                .number(2)
                .block(0)
                .text("orders")
                .bytes();
        assertThat(reader.read(value, 0, 0).values()).containsExactly(5L, "orders");
    }

    @Test
    void theColumnNamesAreMatchedExactlyThenIgnoringCase() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("s", "orderId:INT64");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse(
                        "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"ORDERID\",\"type\":\"long\"}]}"),
                -1);
        assertThat(reader.read(new AvroWriter().number(7).bytes(), 0, 0).values())
                .containsExactly(7L);
    }

    @Test
    void aSchemaWithNoFieldForAColumnIsRefusedByNameBeforeAnyRecordIsRead() {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64,missing:STRING");
        AvroSchema.Node writer = AvroSchema.parse(
                "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}");

        assertThatThrownBy(() -> AvroRowReader.map(schema, writer, -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("no field for column [missing]")
                .hasMessageContaining("[id]");
    }

    @Test
    void aFieldWhoseTypeCannotBecomeItsColumnIsRefusedWithBothTypesNamed() {
        StreamSchema schema = KafkaSchema.parse("s", "at:TIMESTAMP");
        AvroSchema.Node plainLong = AvroSchema.parse(
                "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"at\",\"type\":\"long\"}]}");

        assertThatThrownBy(() -> AvroRowReader.map(schema, plainLong, -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("Avro field 'at'")
                .hasMessageContaining("timestamp-millis");
    }

    @Test
    void aLocalTimestampIsRefusedRatherThanAssumedToBeUtc() {
        StreamSchema schema = KafkaSchema.parse("s", "at:TIMESTAMP");
        AvroSchema.Node local = AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"at\","
                + "\"type\":{\"type\":\"long\",\"logicalType\":\"local-timestamp-millis\"}}]}");

        assertThatThrownBy(() -> AvroRowReader.map(schema, local, -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("has no zone");
    }

    @Test
    void aUnionWhoseOtherBranchDoesNotFitIsRefused() {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64?");
        AvroSchema.Node writer = AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"id\","
                + "\"type\":[\"null\",\"long\",\"string\"]}]}");

        assertThatThrownBy(() -> AvroRowReader.map(schema, writer, -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("branch string");
    }

    @Test
    void aSchemaThatIsNotARecordIsRefused() {
        assertThatThrownBy(
                        () -> AvroRowReader.map(KafkaSchema.parse("s", "id:INT64"), AvroSchema.parse("\"long\""), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("not a record");
    }

    @Test
    void bytesLeftOverAreRefusedBecauseTheyMeanTheWrongSchema() {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse(
                        "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}"),
                -1);
        byte[] tooMuch = new AvroWriter().number(1).number(2).bytes();

        assertThatThrownBy(() -> reader.read(tooMuch, 0, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("leaves 1 byte(s) of the value unread");
    }

    @Test
    void aDecimalWithMorePlacesThanItsColumnIsRefusedRatherThanRounded() {
        StreamSchema schema = KafkaSchema.parse("s", "amount:DECIMAL(9,2)");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"amount\",\"type\":"
                        + "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":9,\"scale\":4}}]}"),
                -1);
        byte[] fourPlaces =
                new AvroWriter().decimalBytes(new BigDecimal("1.2345"), 4).bytes();

        assertThatThrownBy(() -> reader.read(fourPlaces, 0, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("refused rather than rounded");
    }

    @Test
    void anIntOutOfAColumnsRangeIsRefusedRatherThanTruncated() {
        StreamSchema schema = KafkaSchema.parse("s", "small:INT16");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse(
                        "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"small\",\"type\":\"int\"}]}"),
                -1);
        byte[] tooBig = new AvroWriter().integer(70_000).bytes();

        assertThatThrownBy(() -> reader.read(tooBig, 0, 0))
                .isInstanceOf(Undecodable.class)
                .hasMessageContaining("70000 is out of its range");
    }

    @Test
    void anEventTimeColumnBecomesTheRowsEventTime() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64,at:TIMESTAMP");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"at\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}]}"),
                1);

        Row row = reader.read(
                new AvroWriter().number(1).number(1_700_000_000_000L).bytes(), 0, 99L);
        assertThat(row.eventTimeNanos()).isEqualTo(1_700_000_000_000_000_000L);
    }

    @Test
    void aRecursiveSchemaParsesAndItsSelfReferenceIsSkippable() throws Undecodable {
        StreamSchema schema = KafkaSchema.parse("s", "id:INT64");
        AvroRowReader reader = AvroRowReader.map(
                schema,
                AvroSchema.parse("{\"type\":\"record\",\"name\":\"Node\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"next\",\"type\":[\"null\",\"Node\"]}]}"),
                -1);

        byte[] chain = new AvroWriter()
                .number(1)
                .union(1)
                .number(2)
                .union(1)
                .number(3)
                .union(0)
                .bytes();
        assertThat(reader.read(chain, 0, 0).values()).containsExactly(1L);
    }

    @Test
    void aSchemaThatIsNotJsonOrNamesAnUnknownTypeIsInvalid() {
        assertThatThrownBy(() -> AvroSchema.parse("{oops"))
                .isInstanceOf(AvroSchema.Invalid.class)
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> AvroSchema.parse(
                        "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"a\",\"type\":\"widget\"}]}"))
                .isInstanceOf(AvroSchema.Invalid.class)
                .hasMessageContaining("'widget' is not an Avro type");
    }

    private static final String FULL_SCHEMA = """
            {"type":"record","name":"Everything","namespace":"pravaha.test","fields":[
              {"name":"flag","type":"boolean"},
              {"name":"small","type":"int"},
              {"name":"count","type":"int"},
              {"name":"total","type":"long"},
              {"name":"ratio","type":"float"},
              {"name":"weight","type":"double"},
              {"name":"name","type":"string"},
              {"name":"colour","type":{"type":"enum","name":"Colour","symbols":["RED","GREEN","BLUE"]}},
              {"name":"blob","type":"bytes"},
              {"name":"hash","type":{"type":"fixed","name":"Hash","size":4}},
              {"name":"amount","type":{"type":"bytes","logicalType":"decimal","precision":12,"scale":2}},
              {"name":"fee","type":{"type":"fixed","name":"Fee","size":8,"logicalType":"decimal",
                                    "precision":9,"scale":3}},
              {"name":"day","type":{"type":"int","logicalType":"date"}},
              {"name":"clock","type":{"type":"int","logicalType":"time-millis"}},
              {"name":"micro","type":{"type":"long","logicalType":"time-micros"}},
              {"name":"at","type":{"type":"long","logicalType":"timestamp-millis"}},
              {"name":"precise","type":{"type":"long","logicalType":"timestamp-micros"}}]}""";
}
