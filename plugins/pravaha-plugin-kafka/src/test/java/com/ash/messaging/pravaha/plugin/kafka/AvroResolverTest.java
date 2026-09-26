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
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bytes written with one Avro schema read as another, by the specification's resolution rules; and
 * a nested record's fields as columns. Every writer value here is written by {@link AvroWriter} from
 * the <em>writer</em> schema, so what is checked is that the reader reads those bytes as the reader
 * schema says -- not that it reads what it wrote.
 */
class AvroResolverTest {

    private static final String READER = record(
            "Order",
            "",
            field("id", "\"long\""),
            field("customer", "\"string\"", "\"aliases\":[\"client\"]"),
            field("amount", "\"double\""),
            field("region", "\"string\"", "\"default\":\"EU\""));

    private static final StreamSchema COLUMNS =
            KafkaSchema.parse("orders", "id:INT64,customer:STRING,amount:FLOAT64,region:STRING");

    @Test
    void anOlderWriterIsReadWithAFieldAddedByDefaultAFieldRemovedARenameAndAPromotion() throws Undecodable {
        // v1 of the producer: an int id, the customer under its old name, a float amount, a field the
        // reader dropped, and no region at all.
        String writer = record(
                "Order",
                "",
                field("id", "\"int\""),
                field("client", "\"string\""),
                field("legacy", "{\"type\":\"array\",\"items\":\"string\"}"),
                field("amount", "\"float\""));
        AvroRowReader reader = AvroRowReader.map(COLUMNS, AvroSchema.parse(writer), AvroSchema.parse(READER), -1);

        byte[] value = new AvroWriter()
                .integer(7)
                .text("acme")
                .block(1)
                .text("x")
                .block(0)
                .float32(2.5f)
                .bytes();
        assertThat(reader.read(value, 0, 0).values()).containsExactly(7L, "acme", 2.5, "EU");
    }

    @Test
    void aRecordRenamedThroughAnAliasResolvesAndOneRenamedWithoutIsRefused() throws Undecodable {
        String writer = record("PurchaseV1", "", field("id", "\"long\""));
        String aliased = record("Purchase", "\"aliases\":[\"PurchaseV1\"],", field("id", "\"long\""));
        StreamSchema id = KafkaSchema.parse("s", "id:INT64");

        AvroRowReader reader = AvroRowReader.map(id, AvroSchema.parse(writer), AvroSchema.parse(aliased), -1);
        assertThat(reader.read(new AvroWriter().number(3).bytes(), 0, 0).values())
                .containsExactly(3L);

        String unaliased = record("Purchase", "", field("id", "\"long\""));
        assertThatThrownBy(() -> AvroRowReader.map(id, AvroSchema.parse(writer), AvroSchema.parse(unaliased), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("cannot be resolved against the reader schema")
                .hasMessageContaining("PurchaseV1");
    }

    @Test
    void aReaderFieldWithNoDefaultThatTheWriterLacksIsRefusedByName() {
        String writer = record(
                "Order", "", field("id", "\"long\""), field("customer", "\"string\""), field("amount", "\"double\""));
        String reader = READER.replace(",\"default\":\"EU\"", "");

        assertThatThrownBy(() -> AvroRowReader.map(COLUMNS, AvroSchema.parse(writer), AvroSchema.parse(reader), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("reader field 'region' of Order has no default");
    }

    @Test
    void aTypeChangeThatDoesNotPromoteIsRefusedRatherThanDecodedAsGarbage() {
        String writer = record(
                "Order", "", field("id", "\"string\""), field("customer", "\"string\""), field("amount", "\"double\""));

        assertThatThrownBy(() -> AvroRowReader.map(COLUMNS, AvroSchema.parse(writer), AvroSchema.parse(READER), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("field 'id' of Order")
                .hasMessageContaining("the writer's string is not the reader's long, and does not promote to it");
    }

    @Test
    void aDecimalWhoseScaleChangedIsRefusedBecauseTheSpecificationsFallbackWouldReadRawBytes() {
        String writer = record(
                "P",
                "",
                field("price", "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":9,\"scale\":2}"));
        String reader = record(
                "P",
                "",
                field("price", "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":9,\"scale\":3}"));

        assertThatThrownBy(() -> AvroRowReader.map(
                        KafkaSchema.parse("s", "price:DECIMAL(9,3)"),
                        AvroSchema.parse(writer),
                        AvroSchema.parse(reader),
                        -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("decimal(9,2) is not the reader's decimal(9,3)");
    }

    @Test
    void anEnumSymbolTheReaderLacksTakesItsDefaultAndWithoutOneIsRefused() throws Undecodable {
        String writer = record(
                "E",
                "",
                field("colour", "{\"type\":\"enum\",\"name\":\"Colour\",\"symbols\":[\"RED\",\"TEAL\",\"BLUE\"]}"));
        String withDefault = record(
                "E",
                "",
                field(
                        "colour",
                        "{\"type\":\"enum\",\"name\":\"Colour\",\"symbols\":[\"BLUE\",\"RED\",\"OTHER\"],\"default\":\"OTHER\"}"));
        StreamSchema colour = KafkaSchema.parse("s", "colour:STRING");

        AvroRowReader reader = AvroRowReader.map(colour, AvroSchema.parse(writer), AvroSchema.parse(withDefault), -1);
        assertThat(reader.read(new AvroWriter().integer(0).bytes(), 0, 0).values())
                .containsExactly("RED");
        assertThat(reader.read(new AvroWriter().integer(1).bytes(), 0, 0).values())
                .containsExactly("OTHER");
        assertThat(reader.read(new AvroWriter().integer(2).bytes(), 0, 0).values())
                .containsExactly("BLUE");

        String without = withDefault.replace(",\"default\":\"OTHER\"", "");
        assertThatThrownBy(() -> AvroRowReader.map(colour, AvroSchema.parse(writer), AvroSchema.parse(without), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("has symbols [TEAL] that the reader's Colour does not");
    }

    @Test
    void aWriterUnionBranchThatResolvesToNothingIsRefusedAtMappingNotWhenARecordReachesIt() {
        String writer = record("N", "", field("note", "[\"null\",\"string\",\"boolean\"]"));
        String reader = record("N", "", field("note", "[\"null\",\"string\"]"));

        assertThatThrownBy(() -> AvroRowReader.map(
                        KafkaSchema.parse("s", "note:STRING?"), AvroSchema.parse(writer), AvroSchema.parse(reader), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("writer branch boolean")
                .hasMessageContaining("matches no branch of the reader's union");
    }

    @Test
    void aWriterValueIsReadIntoAReaderUnionAndANullableWriterIsRefusedForAPlainReaderField() throws Undecodable {
        StreamSchema note = KafkaSchema.parse("s", "note:STRING?");
        String plain = record("N", "", field("note", "\"string\""));
        String nullable = record("N", "", field("note", "[\"null\",\"string\"]", "\"default\":null"));

        AvroRowReader widened = AvroRowReader.map(note, AvroSchema.parse(plain), AvroSchema.parse(nullable), -1);
        assertThat(widened.read(new AvroWriter().text("hi").bytes(), 0, 0).values())
                .containsExactly("hi");

        assertThatThrownBy(() -> AvroRowReader.map(note, AvroSchema.parse(nullable), AvroSchema.parse(plain), -1))
                .as("the specification has no reading of a writer null as a reader string, so the pair is refused "
                        + "before any record, not when the first null arrives")
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("writer branch null");
    }

    @Test
    void aNestedRecordsFieldsAreColumnsByTheirPathJoinedWithUnderscores() throws Undecodable {
        String writer = record(
                "Order",
                "",
                field("id", "\"long\""),
                field(
                        "shipping",
                        "[\"null\",{\"type\":\"record\",\"name\":\"Address\",\"fields\":["
                                + "{\"name\":\"city\",\"type\":\"string\"},"
                                + "{\"name\":\"geo\",\"type\":{\"type\":\"record\",\"name\":\"Geo\",\"fields\":["
                                + "{\"name\":\"lat\",\"type\":\"double\"},{\"name\":\"lon\",\"type\":\"double\"}]}}]}]"));
        StreamSchema columns = KafkaSchema.parse("s", "id:INT64,shipping_city:STRING?,shipping_geo_lat:FLOAT64?");
        AvroRowReader reader = AvroRowReader.map(columns, AvroSchema.parse(writer), -1);

        byte[] shipped = new AvroWriter()
                .number(1)
                .union(1)
                .text("Pune")
                .float64(18.5)
                .float64(73.8)
                .bytes();
        assertThat(reader.read(shipped, 0, 0).values()).containsExactly(1L, "Pune", 18.5);
        byte[] notYet = new AvroWriter().number(2).union(0).bytes();
        assertThat(reader.read(notYet, 0, 0).values())
                .as("a null record makes every column under it NULL")
                .containsExactly(2L, null, null);
    }

    @Test
    void aNestedFieldAddedToTheReaderTakesItsDefaultAndTwoPathsForOneColumnAreRefused() throws Undecodable {
        String writer = record(
                "O",
                "",
                field(
                        "addr",
                        "{\"type\":\"record\",\"name\":\"A\",\"fields\":[{\"name\":\"city\",\"type\":\"string\"}]}"));
        String reader = record(
                "O",
                "",
                field(
                        "addr",
                        "{\"type\":\"record\",\"name\":\"A\",\"fields\":["
                                + "{\"name\":\"city\",\"type\":\"string\"},"
                                + "{\"name\":\"zip\",\"type\":\"string\",\"default\":\"000000\"}]}"));
        StreamSchema columns = KafkaSchema.parse("s", "addr_city:STRING,addr_zip:STRING");

        AvroRowReader resolved = AvroRowReader.map(columns, AvroSchema.parse(writer), AvroSchema.parse(reader), -1);
        assertThat(resolved.read(new AvroWriter().text("Pune").bytes(), 0, 0).values())
                .containsExactly("Pune", "000000");

        String ambiguous = record(
                "O",
                "",
                field("addr_city", "\"string\""),
                field(
                        "addr",
                        "{\"type\":\"record\",\"name\":\"A\",\"fields\":[{\"name\":\"city\",\"type\":\"string\"}]}"));
        assertThatThrownBy(() ->
                        AvroRowReader.map(KafkaSchema.parse("s", "addr_city:STRING"), AvroSchema.parse(ambiguous), -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("[addr_city, addr.city] all match column 'addr_city'");
    }

    @Test
    void aDefaultIsReadAsItsTypeAndABadOneIsRefused() throws Undecodable {
        String writer = record("P", "", field("id", "\"long\""));
        String reader = record(
                "P",
                "",
                field("id", "\"long\""),
                field(
                        "price",
                        "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":5,\"scale\":2}",
                        "\"default\":\"\\u0001\\u0000\""));
        StreamSchema columns = KafkaSchema.parse("s", "id:INT64,price:DECIMAL(5,2)");

        AvroRowReader resolved = AvroRowReader.map(columns, AvroSchema.parse(writer), AvroSchema.parse(reader), -1);
        assertThat(resolved.read(new AvroWriter().number(1).bytes(), 0, 0).values())
                .as("bytes defaults are ISO-8859-1 strings: 0x0100 unscaled at scale 2")
                .containsExactly(1L, new BigDecimal("2.56"));

        String wrong = record("P", "", field("id", "\"long\""), field("n", "\"int\"", "\"default\":\"seven\""));
        assertThatThrownBy(() -> AvroRowReader.map(
                        KafkaSchema.parse("s", "id:INT64,n:INT32"),
                        AvroSchema.parse(writer),
                        AvroSchema.parse(wrong),
                        -1))
                .isInstanceOf(Unmappable.class)
                .hasMessageContaining("the default seven is not a int");
    }

    // ---- schemas, written out ----------------------------------------------------------------

    private static String record(String name, String extra, String... fields) {
        return "{\"type\":\"record\",\"name\":\"" + name + "\"," + extra + "\"fields\":[" + String.join(",", fields)
                + "]}";
    }

    private static String field(String name, String type) {
        return "{\"name\":\"" + name + "\",\"type\":" + type + "}";
    }

    private static String field(String name, String type, String extra) {
        return "{\"name\":\"" + name + "\",\"type\":" + type + "," + extra + "}";
    }
}
