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
package com.ash.messaging.pravaha.embedded;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ViewChange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a pushed Java value becomes in a row, and what a row's value becomes in a record -- in the
 * engine's units, and refused by name when it would not fit.
 */
class RowConversionTest {

    private static final StreamSchema EVERYTHING = StreamSchema.builder("everything")
            .field("flag", Types.bool())
            .field("tiny", Types.int8())
            .field("small", Types.int16())
            .field("mid", Types.int32())
            .field("big", Types.int64())
            .field("ratio", Types.float32())
            .field("precise", Types.float64())
            .field("price", Types.decimal(10, 2))
            .field("day", Types.date())
            .field("at_time", Types.time())
            .field("ts", Types.timestamp())
            .field("label", Types.string())
            .field("blob", Types.bytes())
            .build();

    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    record Everything(
            boolean flag,
            byte tiny,
            short small,
            int mid,
            long big,
            float ratio,
            double precise,
            BigDecimal price,
            LocalDate day,
            LocalTime atTime,
            Instant ts,
            String label,
            byte[] blob) {}

    @Test
    void everyTypeARowCanCarryRoundTripsThroughAView() {
        Instant at = Instant.parse("2026-09-19T10:15:30.123456789Z");
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream(EVERYTHING);
            engine.start();
            engine.register("mirror", "SELECT * FROM everything", "label");
            engine.push("everything", new Object[] {
                true,
                (byte) 1,
                (short) 2,
                3,
                4L,
                1.5f,
                2.25,
                new BigDecimal("12.34"),
                LocalDate.of(2026, 9, 19),
                LocalTime.of(10, 15),
                at,
                "row",
                new byte[] {7, 8}
            });

            Everything row =
                    engine.query(Everything.class, "SELECT * FROM mirror").get(0);
            assertThat(row.flag()).isTrue();
            assertThat(row.tiny()).isEqualTo((byte) 1);
            assertThat(row.small()).isEqualTo((short) 2);
            assertThat(row.mid()).isEqualTo(3);
            assertThat(row.big()).isEqualTo(4L);
            assertThat(row.ratio()).isEqualTo(1.5f);
            assertThat(row.precise()).isEqualTo(2.25);
            assertThat(row.price()).isEqualByComparingTo("12.34");
            assertThat(row.day()).isEqualTo(LocalDate.of(2026, 9, 19));
            assertThat(row.atTime()).isEqualTo(LocalTime.of(10, 15));
            assertThat(row.ts()).isEqualTo(at);
            assertThat(row.label()).isEqualTo("row");
            assertThat(row.blob()).containsExactly(7, 8);
        }
    }

    @Test
    void aValueThatDoesNotFitIsRefusedNamingTheColumn() {
        RowEncoder encoder = new RowEncoder(EVERYTHING);
        Object[] good = {
            true, (byte) 1, (short) 2, 3, 4L, 1.5f, 2.25, new BigDecimal("1.00"), 0, 0L, 0L, "x", new byte[0]
        };
        assertThat(encoder.validate(good)).hasSize(13);

        assertRefused(encoder, with(good, 1, 300), "'tiny'");
        assertRefused(encoder, with(good, 0, "yes"), "Boolean");
        assertRefused(encoder, with(good, 3, 1.5), "integral");
        assertRefused(encoder, with(good, 5, "1.5"), "a Number");
        assertRefused(encoder, with(good, 7, new BigDecimal("1.234")), "without rounding");
        assertRefused(encoder, with(good, 7, "1"), "BigDecimal");
        assertRefused(encoder, with(good, 9, 86_400_000_000_000L), "'at_time'");
        assertRefused(encoder, with(good, 11, 42), "a String");
        assertRefused(encoder, with(good, 12, "bytes"), "byte[]");
        assertRefused(encoder, with(good, 11, null), "NOT NULL");
        StreamSchema optional = StreamSchema.builder("optional")
                .field("note", Types.string().withNullable(true))
                .build();
        assertThat(new RowEncoder(optional).validate(new Object[] {null})[0])
                .as("a nullable column takes a null")
                .isNull();
        assertThatThrownBy(() -> encoder.fromMap(Map.of("nope", 1)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("'nope'");

        StreamSchema strict = StreamSchema.builder("strict")
                .field("id", Types.int64().withNullable(false))
                .build();
        assertRefused(new RowEncoder(strict), new Object[] {null}, "NOT NULL");
    }

    record Narrow(int big, String label) {}

    record Missing(String nothing) {}

    record Primitive(long big) {}

    @Test
    void aRecordIsMatchedByNameAndRefusedWhenItCannotBeFilled() {
        StreamSchema schema = StreamSchema.builder("out")
                .field("big", Types.int64())
                .field("label", Types.string())
                .build();
        assertThat(RowMapping.toRecord(Narrow.class, schema, new Object[] {5L, "x"}))
                .isEqualTo(new Narrow(5, "x"));
        assertThatThrownBy(() -> RowMapping.toRecord(Narrow.class, schema, new Object[] {5_000_000_000L, "x"}))
                .hasMessageContaining("'big'");
        assertThatThrownBy(() -> RowMapping.reader(Missing.class, schema))
                .hasMessageContaining("'nothing'")
                .hasMessageContaining("[big, label]");
        assertThatThrownBy(() -> RowMapping.reader(String.class, schema)).hasMessageContaining("not a record");
        assertThatThrownBy(() -> RowMapping.toRecord(Primitive.class, schema, new Object[] {null, "x"}))
                .hasMessageContaining("cannot hold one");
        assertThat(RowMapping.toMap(schema, new Object[] {1L, null})).containsEntry("label", null);

        RowChange change = new RowChange(schema, new ViewChange(new Object[] {9L, "y"}, -1));
        assertThat(change.isRetraction()).isTrue();
        assertThat(change.toString()).startsWith("-");
        assertThat(change.get("label")).isEqualTo("y");
        assertThat(change.schema()).isSameAs(schema);
        assertThatThrownBy(() -> change.get("nope")).hasMessageContaining("[big, label]");
        assertThat(change).isEqualTo(new RowChange(schema, new ViewChange(new Object[] {9L, "y"}, -1)));
        assertThat(change.hashCode())
                .isEqualTo(new RowChange(schema, new ViewChange(new Object[] {9L, "y"}, -1)).hashCode());
    }

    @Test
    void aContinuousQueryNeedsANameSqlAndAKeyAndASinkKeepsItsViewForEver() {
        assertThatThrownBy(() ->
                        ContinuousQuery.named(" ").sql("SELECT 1").keyedBy("a").build())
                .hasMessageContaining("name");
        assertThatThrownBy(() -> ContinuousQuery.named("q").keyedBy("a").build())
                .hasMessageContaining("no SQL");
        assertThatThrownBy(
                        () -> ContinuousQuery.named("q").sql("SELECT a FROM t").build())
                .hasMessageContaining("key column");
        assertThatThrownBy(() -> ContinuousQuery.named("q")
                        .sql("SELECT a FROM t")
                        .keyedBy(List.of("a"))
                        .retaining(java.time.Duration.ofHours(1))
                        .writingTo("out")
                        .build())
                .hasMessageContaining("for ever");
    }

    private static Object[] with(Object[] row, int ordinal, Object value) {
        Object[] copy = row.clone();
        copy[ordinal] = value;
        return copy;
    }

    private static void assertRefused(RowEncoder encoder, Object[] row, String mentioning) {
        assertThatThrownBy(() -> encoder.validate(row))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8102")
                .hasMessageContaining(mentioning);
    }
}
