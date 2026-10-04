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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Locale;

import com.datastax.oss.driver.api.core.cql.Row;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * Declaring what a table's columns look like, and moving them into rows.
 *
 * <p>Cassandra genuinely has a schema, unlike Aerospike -- {@code system_schema.columns} could be
 * read to infer one. This plugin declares it instead, in the same {@code name:TYPE} form the
 * Aerospike and filesystem plugins use, for one reason: a Cassandra table's own type vocabulary
 * (collections, UDTs, counters, {@code varint}/{@code decimal}) is wider than the set this engine can
 * honestly represent, and inference would have to guess at exactly the columns where guessing is
 * most likely to be silently wrong. A declaration that disagrees with the table is a decode error
 * naming the column, not a silent narrowing.
 *
 * <p>{@code DECIMAL} is refused for the same reason {@link com.ash.messaging.pravaha.plugin.aerospike}
 * refuses it: Cassandra's {@code decimal} and {@code varint} have no lossless mapping to this
 * engine's 128-bit unscaled representation without an agreed precision and scale this plugin does not
 * ask for. Store the value as {@code TEXT} and parse it in the query, or as a fixed-point integer of
 * minor units and declare {@code INT64}.
 */
public final class CassandraSchemas {

    private CassandraSchemas() {}

    /** Parses {@code name:TYPE,name:TYPE}, with {@code ?} marking a column that may be null. */
    public static StreamSchema parse(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : spec.split(",", -1)) {
            String[] parts = column.strip().split(":", -1);
            if (parts.length != 2) {
                throw new ConfigurationException(
                        CassandraErrors.BAD_CONFIGURATION,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            builder.field(parts[0].strip(), typeFor(parts[0].strip(), parts[1].strip()));
        }
        return builder.build();
    }

    private static PravahaType typeFor(String column, String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        boolean nullable = upper.endsWith("?");
        if (nullable) {
            upper = upper.substring(0, upper.length() - 1);
        }
        PravahaType type =
                switch (upper) {
                    case "BOOLEAN", "BOOL" -> Types.bool();
                    case "INT8", "TINYINT" -> Types.int8();
                    case "INT16", "SMALLINT" -> Types.int16();
                    case "INT32", "INT" -> Types.int32();
                    case "INT64", "BIGINT", "COUNTER" -> Types.int64();
                    case "FLOAT32", "FLOAT" -> Types.float32();
                    case "FLOAT64", "DOUBLE" -> Types.float64();
                    case "STRING", "TEXT", "VARCHAR", "ASCII", "UUID", "TIMEUUID", "INET" -> Types.string();
                    case "BYTES", "BLOB" -> Types.bytes();
                    case "TIMESTAMP" -> Types.timestamp();
                    case "DECIMAL", "VARINT" ->
                        throw new ConfigurationException(
                                CassandraErrors.UNSUPPORTED_TYPE,
                                "column '" + column + "' is declared " + upper + ", which has no lossless mapping "
                                        + "to this engine's 128-bit decimal without an agreed precision and scale. "
                                        + "Store it as TEXT and parse it in the query, or as an integer of minor "
                                        + "units and declare INT64.");
                    default ->
                        throw new ConfigurationException(
                                CassandraErrors.BAD_CONFIGURATION,
                                "unknown type '" + name + "' for column '" + column + "'. Supported: BOOLEAN, "
                                        + "INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, STRING, BYTES, TIMESTAMP. "
                                        + "UUID, TIMEUUID and INET read as STRING; COUNTER reads as INT64.");
                };
        return nullable ? type.withNullable(true) : type;
    }

    /**
     * Copies one driver row's columns into a Pravaha row, by name and in schema order.
     *
     * <p>Unlike Aerospike, a CQL column that was never written is simply {@code NULL} -- there is no
     * "absent bin" distinct from a stored null to preserve, so {@link Row#isNull(String)} is the
     * whole story.
     */
    static void copyInto(Row row, StreamSchema schema, RowWriter writer) {
        copyInto(row, schema, writer, null);
    }

    /**
     * As {@link #copyInto(Row, StreamSchema, RowWriter)}, for a statement that selected only some
     * columns: one not selected is written with {@link RowWriter#setUnread}, because the engine
     * said nothing reads it. {@code read} null means every column was selected.
     */
    static void copyInto(Row row, StreamSchema schema, RowWriter writer, boolean[] read) {
        for (int ordinal = 0; ordinal < schema.fields().size(); ordinal++) {
            if (read != null && !read[ordinal]) {
                writer.setUnread(ordinal);
                continue;
            }
            String column = schema.field(ordinal).name();
            if (row.isNull(column)) {
                writer.setNull(ordinal);
                continue;
            }
            TypeName type = schema.field(ordinal).type().typeName();
            try {
                switch (type) {
                    case BOOLEAN -> writer.setBoolean(ordinal, row.getBoolean(column));
                    case INT8 -> writer.setByte(ordinal, row.getByte(column));
                    case INT16 -> writer.setShort(ordinal, row.getShort(column));
                    case INT32, DATE -> writer.setInt(ordinal, row.getInt(column));
                    case INT64, TIME -> writer.setLong(ordinal, row.getLong(column));
                    case TIMESTAMP_LTZ -> writer.setLong(ordinal, timestampNanos(row, column));
                    case FLOAT32 -> writer.setFloat(ordinal, row.getFloat(column));
                    case FLOAT64 -> writer.setDouble(ordinal, row.getDouble(column));
                    case BYTES -> writer.setBytes(ordinal, bytesOf(row.getByteBuffer(column)));
                    default -> writer.setString(ordinal, stringOf(row, column));
                }
            } catch (RuntimeException e) {
                throw new PravahaException(
                        CassandraErrors.UNSUPPORTED_TYPE,
                        "column '" + column + "' does not hold what it is declared as (" + type + "): "
                                + e.getMessage(),
                        e);
            }
        }
    }

    /** The event-time column, read as nanoseconds since the epoch. Only {@code TIMESTAMP} may hold it. */
    static long timestampNanos(Row row, String column) {
        Instant instant = row.getInstant(column);
        if (instant == null) {
            throw new PravahaException(
                    CassandraErrors.UNSUPPORTED_TYPE,
                    "event.time column '" + column + "' is null on a row that has one; declare it NOT NULL in "
                            + "Cassandra or choose a column that always holds a value");
        }
        try {
            // Checked, as the filesystem codec is (FARTIME-1): past 2262-04-11 the product wraps.
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        } catch (ArithmeticException outOfRange) {
            throw new PravahaException(
                    CassandraErrors.UNSUPPORTED_TYPE,
                    "column '" + column + "' holds " + instant + ", outside the range this engine holds a TIMESTAMP "
                            + "in (nanoseconds since 1970 in 64 bits: 1677-09-21 to 2262-04-11 UTC)");
        }
    }

    private static byte[] bytesOf(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /** {@code UUID}, {@code TIMEUUID} and {@code INET} all render through the object's own {@code toString}. */
    private static String stringOf(Row row, String column) {
        Object value = row.getObject(column);
        return value == null ? null : value.toString();
    }
}
