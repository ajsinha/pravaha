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
import java.util.Base64;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.Decimals;

/**
 * A row as a Kafka record's key and value, in JSON; or, with a {@link ValueEncoder}, an upsert
 * value in Avro or protobuf behind the same JSON key.
 *
 * <p><strong>Upsert mode.</strong> The key is a JSON object of the key columns, in {@code
 * key.columns} order: {@code {"user_id":"u1"}}. The value is a JSON object of every column, by the
 * schema's names. A retraction is a <em>tombstone</em> -- the same key, a null value -- which is
 * what a compacted topic and every table-shaped consumer (Kafka Streams' {@code KTable}, ksqlDB,
 * Kafka Connect sinks) read as a delete.
 *
 * <p><strong>Changelog mode.</strong> The value is {@code {"op":"insert"|"delete","weight":n,
 * "row":{...}}}: the change itself, not the state after it, for a consumer maintaining its own
 * aggregate (it must apply the weight) or another engine reading the stream. The key is the key
 * columns when {@code key.columns} is set, otherwise the whole row -- so a row's insert and its
 * later retraction land on the same partition, in order, which a null key would not promise.
 *
 * <p>Values: numbers as JSON numbers ({@code DECIMAL} too, written with its exact digits), a
 * non-finite float as the string {@code "NaN"}, {@code "Infinity"} or {@code "-Infinity"} (JSON has
 * no literal for them), {@code BYTES} as base64, {@code DATE} as {@code 2026-09-19}, {@code TIME} as
 * {@code 10:15:30.5}, {@code TIMESTAMP} as an ISO-8601 instant in UTC. The encoding is deterministic
 * -- the same key always makes the same bytes -- which compaction relies on: it collapses records
 * whose key bytes are equal, not whose meaning is.
 */
final class KafkaRecords {

    private final StreamSchema schema;
    private final int[] keyOrdinals;
    private final boolean changelog;
    private final ValueEncoder valueEncoder;

    /**
     * An upsert value in a format other than JSON ({@link AvroRowWriter}, {@link ProtobufRowWriter}):
     * the row's values, as {@link #read} copies them, into the bytes of one record's value.
     */
    interface ValueEncoder {
        byte[] encode(Object[] values);
    }

    KafkaRecords(StreamSchema schema, int[] keyOrdinals, boolean changelog) {
        this(schema, keyOrdinals, changelog, null);
    }

    /** With {@code valueEncoder} null the value is JSON; otherwise upsert mode only, tombstones as ever. */
    KafkaRecords(StreamSchema schema, int[] keyOrdinals, boolean changelog, ValueEncoder valueEncoder) {
        if (changelog && valueEncoder != null) {
            throw new IllegalArgumentException("the changelog envelope is JSON only");
        }
        this.schema = schema;
        this.keyOrdinals = keyOrdinals.clone();
        this.changelog = changelog;
        this.valueEncoder = valueEncoder;
    }

    /** A record's key and value; the value is null for a tombstone. */
    record Encoded(byte[] key, byte[] value) {}

    Encoded encode(RowView row) {
        Object[] values = read(row);
        long weight = row.weight();
        byte[] key = keyOrdinals.length > 0 ? object(values, keyOrdinals) : object(values, allOrdinals());
        if (!changelog) {
            if (weight < 0) {
                return new Encoded(key, null);
            }
            return new Encoded(key, valueEncoder != null ? valueEncoder.encode(values) : object(values, allOrdinals()));
        }
        StringBuilder value = new StringBuilder(64);
        value.append("{\"op\":\"")
                .append(weight < 0 ? "delete" : "insert")
                .append("\",\"weight\":")
                .append(weight)
                .append(",\"row\":");
        appendObject(value, values, allOrdinals());
        value.append('}');
        return new Encoded(key, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private int[] allOrdinals() {
        int[] all = new int[schema.fieldCount()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    private byte[] object(Object[] values, int[] ordinals) {
        StringBuilder json = new StringBuilder(64);
        appendObject(json, values, ordinals);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendObject(StringBuilder json, Object[] values, int[] ordinals) {
        json.append('{');
        for (int i = 0; i < ordinals.length; i++) {
            int ordinal = ordinals[i];
            if (i > 0) {
                json.append(',');
            }
            appendString(json, schema.field(ordinal).name());
            json.append(':');
            appendValue(json, schema.field(ordinal).type().typeName(), values[ordinal]);
        }
        json.append('}');
    }

    private static void appendValue(StringBuilder json, TypeName type, Object value) {
        if (value == null) {
            json.append("null");
            return;
        }
        switch (type) {
            case BOOLEAN, INT8, INT16, INT32, INT64 -> json.append(value);
            case FLOAT32, FLOAT64 -> {
                double d = ((Number) value).doubleValue();
                if (Double.isFinite(d)) {
                    json.append(value);
                } else {
                    appendString(json, value.toString());
                }
            }
            case DECIMAL -> json.append(((BigDecimal) value).toPlainString());
            case BYTES -> appendString(json, Base64.getEncoder().encodeToString((byte[]) value));
            case DATE ->
                appendString(json, LocalDate.ofEpochDay((Integer) value).toString());
            case TIME -> appendString(json, LocalTime.ofNanoOfDay((Long) value).toString());
            case TIMESTAMP_LTZ -> {
                long nanos = (Long) value;
                appendString(
                        json,
                        Instant.ofEpochSecond(
                                        Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L))
                                .toString());
            }
            default -> appendString(json, value.toString());
        }
    }

    /** A JSON string literal, escaped as RFC 8259 requires. */
    static void appendString(StringBuilder json, String text) {
        json.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    /**
     * Copies a row's values out of the engine's memory, which the SPI does not let a sink keep past
     * {@code write}.
     */
    private Object[] read(RowView row) {
        Object[] values = new Object[schema.fieldCount()];
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (row.isNull(ordinal)) {
                continue;
            }
            values[ordinal] = switch (schema.field(ordinal).type().typeName()) {
                case BOOLEAN -> row.getBoolean(ordinal);
                case INT8 -> row.getByte(ordinal);
                case INT16 -> row.getShort(ordinal);
                case INT32, DATE -> row.getInt(ordinal);
                case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
                case FLOAT32 -> row.getFloat(ordinal);
                case FLOAT64 -> row.getDouble(ordinal);
                case DECIMAL -> decimalOf(row, ordinal);
                case BYTES -> bytesOf(row, ordinal);
                default -> row.getString(ordinal);
            };
        }
        return values;
    }

    /**
     * A decimal at the scale the row was <em>written</em> with. The registry compares a sink's schema
     * with the query's output by type name, not by scale, so reading with the declared scale could
     * multiply every amount by a power of ten.
     */
    private BigDecimal decimalOf(RowView row, int ordinal) {
        int scale = ((DecimalType) schema.field(ordinal).type()).scale();
        StreamSchema written = row.schema();
        if (written != null
                && ordinal < written.fieldCount()
                && written.field(ordinal).type() instanceof DecimalType actual) {
            scale = actual.scale();
        }
        return Decimals.toBigDecimal(row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal), scale);
    }

    private static byte[] bytesOf(RowView row, int ordinal) {
        if (!(row instanceof BinaryRowView binary)) {
            throw new PravahaException(
                    KafkaErrors.WRITE_FAILED,
                    "cannot read a BYTES column from a " + row.getClass().getSimpleName()
                            + "; this sink writes the engine's binary rows");
        }
        MutableSlice slice = binary.getBytes(ordinal, new MutableSlice());
        byte[] copy = new byte[slice.length()];
        binary.region().getBytes(slice.offset(), copy, 0, slice.length());
        return copy;
    }
}
