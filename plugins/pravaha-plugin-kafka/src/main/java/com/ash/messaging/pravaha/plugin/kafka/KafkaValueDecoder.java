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

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A Kafka record's value, turned into one row: one implementation per {@code format}.
 *
 * <p>{@link KafkaRecordDecoder} reads {@code json} and {@code changelog}, {@link AvroValueDecoder}
 * reads {@code avro}, {@link ProtobufValueDecoder} reads {@code protobuf}. Each maps the value to the
 * binding's declared schema <em>by column name</em> and refuses -- with {@link Undecodable}, a
 * sentence saying what -- anything it would otherwise have to guess. A refusal is a dead letter, or
 * {@code PRV-5105} when there is no dead-letter queue; it never becomes a row.
 *
 * <p>An implementation is used by one {@link KafkaPartitionReader}'s fetch thread and needs no
 * synchronisation of its own; what several of them share (the schema registry's cache) is
 * thread-safe in its own right.
 */
interface KafkaValueDecoder {

    /**
     * @param value the record's value, never null (a tombstone is handled before this)
     * @param recordTimestampMillis the record's Kafka timestamp, or negative when it has none
     */
    Row decode(byte[] value, long recordTimestampMillis) throws Undecodable;

    /**
     * A decoded record.
     *
     * @param values one per column, in the Java type {@link KafkaPartitionReader} writes: {@code
     *     Boolean}, {@code Byte}, {@code Short}, {@code Integer} (also a {@code DATE}'s epoch day),
     *     {@code Long} (also a {@code TIME}'s nanosecond of day and a {@code TIMESTAMP}'s epoch
     *     nanoseconds), {@code Float}, {@code Double}, {@code BigDecimal} at the column's scale,
     *     {@code byte[]}, {@code String}, or null
     */
    record Row(Object[] values, long weight, long eventTimeNanos) {}

    /** Why a record could not be decoded, as a sentence. Carries no stack: it is data, not a bug. */
    final class Undecodable extends Exception {
        private static final long serialVersionUID = 1L;

        Undecodable(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * A writer schema -- an Avro record, a Protobuf message -- that cannot become rows of the
     * binding's declared schema, with a sentence saying which column or field stopped it. Refused at
     * configuration ({@code PRV-5108}) when the schema is configured, and a dead letter when it
     * arrived with the record from a registry.
     */
    final class Unmappable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unmappable(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * The two checks every format ends with, and the row they make: no null in a column declared
     * {@code NOT NULL}, and an event time -- the {@code event.time} column's value, or the record's
     * own Kafka timestamp.
     */
    static Row finish(
            StreamSchema schema, Object[] values, int eventTimeOrdinal, long weight, long recordTimestampMillis)
            throws Undecodable {
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (values[ordinal] == null && !schema.field(ordinal).type().nullable()) {
                throw new Undecodable("column '" + schema.field(ordinal).name() + "' is "
                        + "missing or null, and is declared NOT NULL");
            }
        }
        long eventTime;
        if (eventTimeOrdinal >= 0) {
            if (values[eventTimeOrdinal] == null) {
                throw new Undecodable("event.time column '"
                        + schema.field(eventTimeOrdinal).name() + "' is missing or null, so the row has no event time");
            }
            eventTime = (Long) values[eventTimeOrdinal];
        } else {
            // A record with no timestamp (-1, a pre-0.10 message format) gets the epoch rather than a
            // negative time a watermark would have to reason about.
            eventTime = recordTimestampMillis < 0 ? 0L : Math.multiplyExact(recordTimestampMillis, 1_000_000L);
        }
        return new Row(values, weight, eventTime);
    }
}
