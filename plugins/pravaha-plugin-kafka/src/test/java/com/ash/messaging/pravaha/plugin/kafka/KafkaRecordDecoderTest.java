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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The source's decoding, held to the sink's encoding: what {@link KafkaRecords} writes, {@link
 * KafkaRecordDecoder} reads back to the same values -- which is what makes a {@code kafka-sink} topic
 * readable by the {@code kafka} source -- and anything else is refused with a reason, never guessed.
 */
class KafkaRecordDecoderTest {

    private static final StreamSchema ALL = StreamSchema.builder("all")
            .field("id", Types.int32())
            .field("flag", Types.bool())
            .field("tiny", Types.int8())
            .field("small", Types.int16())
            .field("big", Types.int64())
            .field("ratio", Types.float32())
            .field("score", Types.float64())
            .field("price", Types.decimal(12, 3))
            .field("note", Types.string().withNullable(true))
            .field("blob", Types.bytes())
            .field("day", Types.date())
            .field("at", Types.time())
            .field("ts", Types.timestamp())
            .build();

    private static final StreamSchema LATEST = StreamSchema.builder("latest")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private final TestRows rows = new TestRows();

    @AfterEach
    void tearDown() {
        rows.close();
    }

    @Test
    void whatTheSinkWritesInEitherModeReadsBackToTheSameValues() throws Exception {
        long ts = Instant.parse("2026-09-19T10:15:30.123456789Z").getEpochSecond() * 1_000_000_000L + 123_456_789L;
        Object[] values = {
            7,
            true,
            (byte) -3,
            (short) 1234,
            9_000_000_000L,
            1.5f,
            Double.NEGATIVE_INFINITY,
            new BigDecimal("123456789.125"),
            null,
            new byte[] {1, 2, (byte) 255},
            (int) LocalDate.parse("2026-09-19").toEpochDay(),
            LocalTime.parse("10:15:30.5").toNanoOfDay(),
            ts
        };
        byte[] upsert = new KafkaRecords(ALL, new int[] {0}, false)
                .encode(rows.row(ALL, 1, values))
                .value();
        byte[] changelog = new KafkaRecords(ALL, new int[0], true)
                .encode(rows.row(ALL, -2, values))
                .value();

        KafkaValueDecoder.Row plain = new KafkaRecordDecoder(ALL, false, -1).decode(upsert, 5_000L);
        KafkaValueDecoder.Row change = new KafkaRecordDecoder(ALL, true, -1).decode(changelog, 5_000L);

        for (KafkaValueDecoder.Row row : new KafkaValueDecoder.Row[] {plain, change}) {
            Object[] read = row.values();
            assertThat(read[0]).isEqualTo(7);
            assertThat(read[1]).isEqualTo(true);
            assertThat(read[2]).isEqualTo((byte) -3);
            assertThat(read[3]).isEqualTo((short) 1234);
            assertThat(read[4]).isEqualTo(9_000_000_000L);
            assertThat(read[5]).isEqualTo(1.5f);
            assertThat(read[6]).isEqualTo(Double.NEGATIVE_INFINITY);
            assertThat(read[7]).isEqualTo(new BigDecimal("123456789.125"));
            assertThat(read[8]).isNull();
            assertThat((byte[]) read[9]).containsExactly(1, 2, 255);
            assertThat(read[10]).isEqualTo(values[10]);
            assertThat(read[11]).isEqualTo(values[11]);
            assertThat(read[12])
                    .as("nanosecond precision survives the ISO instant")
                    .isEqualTo(ts);
            assertThat(row.eventTimeNanos())
                    .as("no event.time: the record's timestamp")
                    .isEqualTo(5_000_000_000L);
        }
        assertThat(plain.weight()).isEqualTo(1L);
        assertThat(change.weight()).as("the changelog's weight is the row's").isEqualTo(-2L);
    }

    @Test
    void aJsonRowIsMatchedByNameInAnyOrderIgnoringWhatTheSchemaDoesNotName() throws Exception {
        KafkaRecordDecoder decoder = new KafkaRecordDecoder(LATEST, false, -1);

        assertThat(values(decoder, "{\"extra\":{\"deep\":[1,2]},\"Amount\":300,\"USER_ID\":\"u1\"}"))
                .isEqualTo("[u1, 300]");
    }

    @Test
    void anEventTimeColumnIsTheRowsEventTimeAndTakesEpochMillisOrAnInstant() throws Exception {
        StreamSchema withTime = StreamSchema.builder("t")
                .field("id", Types.int64())
                .field("at", Types.timestamp())
                .build();
        KafkaRecordDecoder decoder = new KafkaRecordDecoder(withTime, false, 1);

        assertThat(decoder.decode(bytes("{\"id\":1,\"at\":1789000000000}"), 1L).eventTimeNanos())
                .isEqualTo(1_789_000_000_000L * 1_000_000L);
        assertThat(decoder.decode(bytes("{\"id\":1,\"at\":\"2026-09-19T12:00:00+02:00\"}"), 1L)
                        .eventTimeNanos())
                .isEqualTo(Instant.parse("2026-09-19T10:00:00Z").getEpochSecond() * 1_000_000_000L);
    }

    @Test
    void theChangelogEnvelopeMustCarryARowAndANonZeroWeightThatAgreesWithItsOp() throws Exception {
        KafkaRecordDecoder decoder = new KafkaRecordDecoder(LATEST, true, -1);

        assertThat(decoder.decode(bytes("{\"row\":{\"user_id\":\"u1\",\"amount\":5},\"weight\":-1}"), 0)
                        .weight())
                .as("op is optional, and members may come in any order")
                .isEqualTo(-1L);
        refused(decoder, "{\"op\":\"insert\",\"weight\":1}", "has no 'row' member");
        refused(decoder, "{\"op\":\"insert\",\"row\":{\"user_id\":\"u1\",\"amount\":5}}", "has no 'weight'");
        refused(decoder, "{\"weight\":0,\"row\":{\"user_id\":\"u1\",\"amount\":5}}", "weight is zero");
        refused(decoder, "{\"op\":\"delete\",\"weight\":1,\"row\":{\"user_id\":\"u1\",\"amount\":5}}", "disagrees");
        refused(decoder, "{\"op\":\"upsert\",\"weight\":1,\"row\":{\"user_id\":\"u1\",\"amount\":5}}", "not insert");
        refused(decoder, "{\"weight\":1.5,\"row\":{\"user_id\":\"u1\",\"amount\":5}}", "not a whole number");
        refused(decoder, "{\"user_id\":\"u1\",\"amount\":5}", "format: json");
    }

    @Test
    void aValueThatIsNotTheDeclaredRowIsRefusedWithAReasonRatherThanGuessed() {
        KafkaRecordDecoder decoder = new KafkaRecordDecoder(ALL, false, -1);
        String rest = ",\"flag\":true,\"tiny\":1,\"small\":1,\"big\":1,\"ratio\":1,\"score\":1,\"price\":1,"
                + "\"blob\":\"AQ==\",\"day\":\"2026-09-19\",\"at\":\"10:00\",\"ts\":0}";

        refused(decoder, "not json", "not valid JSON");
        refused(decoder, "[1,2]", "not a JSON object");
        refused(decoder, "{\"id\":1" + rest + " {}", "more after its JSON object");
        refused(decoder, "{\"id\":\"1\"" + rest, "'id' is INT32 and its value is a string");
        refused(decoder, "{\"id\":1.5" + rest, "a number with a fraction");
        refused(decoder, "{\"id\":2147483648" + rest, "out of its range");
        refused(decoder, rest.replaceFirst(",", "{"), "column 'id' is missing or null");
        refused(decoder, "{\"id\":null" + rest, "column 'id' is missing or null");
        refused(decoder, "{\"id\":1" + rest.replace("\"price\":1", "\"price\":1.0005"), "more than 3 decimal places");
        refused(decoder, "{\"id\":1" + rest.replace("\"price\":1", "\"price\":12345678901"), "more than 12 digits");
        refused(decoder, "{\"id\":1" + rest.replace("\"AQ==\"", "\"%%%\""), "not base64");
        refused(decoder, "{\"id\":1" + rest.replace("\"2026-09-19\"", "\"19/09/2026\""), "not a date");
        refused(decoder, "{\"id\":1" + rest.replace("\"10:00\"", "\"ten\""), "not a time");
        refused(decoder, "{\"id\":1" + rest.replace("\"ts\":0", "\"ts\":\"yesterday\""), "neither an ISO-8601");
        refused(decoder, "{\"id\":1" + rest.replace("\"flag\":true", "\"flag\":1"), "'flag' is BOOLEAN");
        refused(decoder, "{\"id\":1" + rest.replace("\"score\":1", "\"score\":\"many\""), "'score' is FLOAT64");
    }

    @Test
    void aMissingEventTimeIsRefusedRatherThanStampedWithSomethingElse() {
        StreamSchema withTime = StreamSchema.builder("t")
                .field("id", Types.int64())
                .field("at", Types.timestamp().withNullable(true))
                .build();

        refused(new KafkaRecordDecoder(withTime, false, 1), "{\"id\":1}", "has no event time");
    }

    private static void refused(KafkaRecordDecoder decoder, String json, String reason) {
        assertThatThrownBy(() -> decoder.decode(bytes(json), 0L))
                .as(json)
                .isInstanceOf(KafkaRecordDecoder.Undecodable.class)
                .hasMessageContaining(reason);
    }

    private static String values(KafkaRecordDecoder decoder, String json) throws Exception {
        return Arrays.toString(decoder.decode(bytes(json), 0L).values());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
