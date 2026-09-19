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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.util.Locale;

import com.aerospike.client.Record;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * Declaring what a set's records look like, and moving them into rows.
 *
 * <p>Aerospike is schemaless: a set can hold records with different bins and different types in the
 * same bin. Sampling records to guess a schema is what the design calls inference, and it is exactly
 * the thing that fails at three in the morning when the first record with a string in a numeric bin
 * arrives. So the schema is declared, in the same {@code name:TYPE} form the file plugins use, and a
 * record that disagrees with it is a decode error naming the bin rather than a silent change of
 * meaning.
 *
 * <p>Two Aerospike-specific limits are checked at registration rather than at write time. <em>Bin
 * names are limited to 15 bytes</em> -- a longer one is rejected by the server on the first write,
 * which is an hour into a run. And <em>Aerospike's type set is narrow</em>: integers, doubles,
 * strings, blobs, lists and maps. DECIMAL has no representation that round-trips without an agreed
 * encoding, so it is refused rather than approximated in a double, for the same reason it is refused
 * everywhere else in this engine.
 */
public final class AerospikeSchemas {

    /** The server's limit. Longer names are refused on write, so they are refused at registration. */
    public static final int MAX_BIN_NAME_BYTES = 15;

    private AerospikeSchemas() {}

    /** Parses {@code name:TYPE,name:TYPE}, with {@code ?} marking a bin that may be absent. */
    public static StreamSchema parse(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : spec.split(",")) {
            String[] parts = column.strip().split(":");
            if (parts.length != 2) {
                throw new ConfigurationException(
                        AerospikeErrors.BAD_CONFIGURATION,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            String bin = parts[0].strip();
            if (bin.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BIN_NAME_BYTES) {
                throw new ConfigurationException(
                        AerospikeErrors.BAD_CONFIGURATION,
                        "bin name '" + bin + "' is longer than Aerospike's " + MAX_BIN_NAME_BYTES
                                + "-byte limit. The server refuses it on the first write, which is a long way "
                                + "from here; refusing it now is cheaper.");
            }
            builder.field(bin, typeFor(bin, parts[1].strip()));
        }
        return builder.build();
    }

    private static PravahaType typeFor(String bin, String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        boolean nullable = upper.endsWith("?");
        if (nullable) {
            upper = upper.substring(0, upper.length() - 1);
        }
        PravahaType type =
                switch (upper) {
                    case "BOOLEAN", "BOOL" -> Types.bool();
                    case "INT8", "BYTE" -> Types.int8();
                    case "INT16", "SHORT" -> Types.int16();
                    case "INT32", "INT" -> Types.int32();
                    case "INT64", "LONG" -> Types.int64();
                    case "FLOAT32", "FLOAT" -> Types.float32();
                    case "FLOAT64", "DOUBLE" -> Types.float64();
                    case "STRING", "VARCHAR", "TEXT" -> Types.string();
                    case "BYTES", "BLOB", "BINARY" -> Types.bytes();
                    case "TIMESTAMP" -> Types.timestamp();
                    case "DECIMAL" ->
                        throw new ConfigurationException(
                                AerospikeErrors.UNSUPPORTED_TYPE,
                                "bin '" + bin + "' is declared DECIMAL, which Aerospike has no type for. Storing "
                                        + "it as a double loses precision silently, which in a ledger is the "
                                        + "expensive kind of silent. Store it as an integer of minor units and "
                                        + "declare INT64, or as a string and parse it in the query.");
                    default ->
                        throw new ConfigurationException(
                                AerospikeErrors.BAD_CONFIGURATION,
                                "unknown type '" + name + "' for bin '" + bin + "'. Supported: BOOLEAN, INT8, "
                                        + "INT16, INT32, INT64, FLOAT32, FLOAT64, STRING, BYTES, TIMESTAMP.");
                };
        return nullable ? type.withNullable(true) : type;
    }

    /**
     * Copies one record's bins into a row.
     *
     * <p>A bin that is absent is written as null rather than as a zero. Aerospike does not store
     * absent bins at all, so "missing" and "zero" are genuinely different facts about the record,
     * and collapsing them makes every downstream {@code IS NULL} wrong.
     */
    static void copyInto(Record record, StreamSchema schema, RowWriter writer) {
        copyInto(record, schema, writer, null);
    }

    /**
     * As {@link #copyInto(Record, StreamSchema, RowWriter)}, for a scan that named its bins.
     *
     * @param read per ordinal, whether that bin was asked for; null when every bin was. A bin not
     *     asked for is written with {@link RowWriter#setUnread} -- the engine said nothing reads it
     *     -- rather than as the null an absent bin would otherwise read as
     */
    static void copyInto(Record record, StreamSchema schema, RowWriter writer, boolean[] read) {
        // A scan that names bins can return a record holding none of them with no bin map at all.
        java.util.Map<String, Object> bins = record.bins == null ? java.util.Map.of() : record.bins;
        for (int ordinal = 0; ordinal < schema.fields().size(); ordinal++) {
            if (read != null && !read[ordinal]) {
                writer.setUnread(ordinal);
                continue;
            }
            String bin = schema.field(ordinal).name();
            Object value = bins.get(bin);
            if (value == null) {
                writer.setNull(ordinal);
                continue;
            }
            TypeName type = schema.field(ordinal).type().typeName();
            switch (type) {
                case BOOLEAN -> writer.setBoolean(ordinal, asBoolean(bin, value));
                case INT8 ->
                    writer.setByte(
                            ordinal, (byte) inRange(bin, asLong(bin, value), Byte.MIN_VALUE, Byte.MAX_VALUE, "INT8"));
                case INT16 ->
                    writer.setShort(ordinal, (short)
                            inRange(bin, asLong(bin, value), Short.MIN_VALUE, Short.MAX_VALUE, "INT16"));
                case INT32, DATE ->
                    writer.setInt(ordinal, (int)
                            inRange(bin, asLong(bin, value), Integer.MIN_VALUE, Integer.MAX_VALUE, type.name()));
                case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, asLong(bin, value));
                case FLOAT32 -> writer.setFloat(ordinal, asFloat(bin, asDouble(bin, value)));
                case FLOAT64 -> writer.setDouble(ordinal, asDouble(bin, value));
                case BYTES -> writer.setBytes(ordinal, (byte[]) value);
                default -> writer.setString(ordinal, String.valueOf(value));
            }
        }
    }

    /**
     * A stored integer that fits the column it was declared as, or a refusal.
     *
     * <p>Aerospike integers are always 64 bits. A bin holding 300 and declared INT8 used to be cast
     * to 44 and written into the row as though that were the value -- a wrong answer with nothing
     * to notice it by, in a plugin whose whole job is to report what the store holds. It is the same
     * disagreement between a record and its declaration that a string in an integer bin already
     * refused; only the shape of the lie was different.
     */
    private static long inRange(String bin, long value, long low, long high, String declared) {
        if (value < low || value > high) {
            throw new PravahaException(
                    AerospikeErrors.UNSUPPORTED_TYPE,
                    "bin '" + bin + "' holds " + value + ", which does not fit the " + declared
                            + " it is declared as. Aerospike stores every integer as 64 bits, so a record "
                            + "written by another application can exceed the declaration; declare the column "
                            + "INT64 if that is the range the data actually has.");
        }
        return value;
    }

    /** Likewise for the narrower float: a value no float can hold is refused, not rounded to infinity. */
    private static float asFloat(String bin, double value) {
        float narrowed = (float) value;
        if (Float.isInfinite(narrowed) && !Double.isInfinite(value)) {
            throw new PravahaException(
                    AerospikeErrors.UNSUPPORTED_TYPE,
                    "bin '" + bin + "' holds " + value + ", which is outside the range of the FLOAT32 it is "
                            + "declared as. Declare the column FLOAT64 if that is the range the data has.");
        }
        return narrowed;
    }

    private static long asLong(String bin, Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new PravahaException(
                AerospikeErrors.UNSUPPORTED_TYPE,
                "bin '" + bin + "' is declared as an integer but holds a "
                        + value.getClass().getSimpleName() + ". Aerospike is schemaless, so a record written by "
                        + "another application can disagree with the declaration; that is what the declaration "
                        + "is for.");
    }

    private static double asDouble(String bin, Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new PravahaException(
                AerospikeErrors.UNSUPPORTED_TYPE,
                "bin '" + bin + "' is declared floating point but holds a "
                        + value.getClass().getSimpleName());
    }

    private static boolean asBoolean(String bin, Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            // Aerospike stored booleans as 0/1 integers before server 5.6, and plenty of live data
            // still looks like that.
            return number.longValue() != 0;
        }
        throw new PravahaException(
                AerospikeErrors.UNSUPPORTED_TYPE,
                "bin '" + bin + "' is declared BOOLEAN but holds a "
                        + value.getClass().getSimpleName());
    }
}
