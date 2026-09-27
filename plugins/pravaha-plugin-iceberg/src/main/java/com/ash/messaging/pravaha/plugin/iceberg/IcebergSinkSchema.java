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
package com.ash.messaging.pravaha.plugin.iceberg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.types.Types.NestedField;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.Decimals;

/**
 * An Iceberg sink's columns: the binding's {@code schema} parsed, mapped to an Iceberg schema,
 * checked against an existing table, and each engine row read into the Java values Iceberg's
 * generic Parquet writer takes.
 *
 * <p>{@code INT8} and {@code INT16} are widened to Iceberg's {@code int}, which has nothing
 * narrower. A {@code TIMESTAMP} is {@code timestamptz} and a {@code TIME} is {@code time}, both in
 * microseconds: a value that is not a whole number of microseconds is refused rather than rounded.
 */
final class IcebergSinkSchema {

    static final String OP_COLUMN = "_op";
    static final String WEIGHT_COLUMN = "_weight";

    private static final Pattern DECIMAL = Pattern.compile("^DECIMAL\\((\\d+),\\s*(\\d+)\\)$");

    private IcebergSinkSchema() {}

    /** {@code name:TYPE,...}, a trailing {@code ?} for nullable, as every other sink here. */
    static StreamSchema parse(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : splitColumns(spec)) {
            String[] parts = column.strip().split(":", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new ConfigurationException(
                        IcebergErrors.SINK_BAD_CONFIGURATION,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            builder.field(parts[0].strip(), typeFor(parts[1].strip()));
        }
        return builder.build();
    }

    private static List<String> splitColumns(String spec) {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : spec.toCharArray()) {
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (c == ',' && depth <= 0) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            columns.add(current.toString());
        }
        return columns;
    }

    private static PravahaType typeFor(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        boolean nullable = upper.endsWith("?");
        if (nullable) {
            upper = upper.substring(0, upper.length() - 1).strip();
        }
        PravahaType type =
                switch (upper) {
                    case "BOOLEAN", "BOOL" -> com.ash.messaging.pravaha.api.data.Types.bool();
                    case "INT8", "BYTE" -> com.ash.messaging.pravaha.api.data.Types.int8();
                    case "INT16", "SHORT" -> com.ash.messaging.pravaha.api.data.Types.int16();
                    case "INT32", "INT" -> com.ash.messaging.pravaha.api.data.Types.int32();
                    case "INT64", "LONG" -> com.ash.messaging.pravaha.api.data.Types.int64();
                    case "FLOAT32", "FLOAT" -> com.ash.messaging.pravaha.api.data.Types.float32();
                    case "FLOAT64", "DOUBLE" -> com.ash.messaging.pravaha.api.data.Types.float64();
                    case "STRING", "VARCHAR", "TEXT" -> com.ash.messaging.pravaha.api.data.Types.string();
                    case "BYTES", "BINARY" -> com.ash.messaging.pravaha.api.data.Types.bytes();
                    case "TIMESTAMP" -> com.ash.messaging.pravaha.api.data.Types.timestamp();
                    case "DATE" -> com.ash.messaging.pravaha.api.data.Types.date();
                    case "TIME" -> com.ash.messaging.pravaha.api.data.Types.time();
                    default -> decimalOrRefusal(name, upper);
                };
        return nullable ? type.withNullable(true) : type;
    }

    private static PravahaType decimalOrRefusal(String original, String upper) {
        Matcher decimal = DECIMAL.matcher(upper);
        if (decimal.matches()) {
            return com.ash.messaging.pravaha.api.data.Types.decimal(
                    Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        throw new ConfigurationException(
                IcebergErrors.SINK_BAD_CONFIGURATION,
                "unknown type '" + original + "'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, FLOAT32, "
                        + "FLOAT64, STRING, BYTES, DATE, TIME, TIMESTAMP, DECIMAL(p,s). Suffix with ? for nullable.");
    }

    /**
     * The Iceberg schema for a binding: the declared columns with ids 1..n, then {@code _op} and
     * {@code _weight} in changelog mode. In upsert mode the key columns are the identifier fields.
     */
    static Schema toIceberg(String instanceName, StreamSchema schema, boolean changelog, List<String> keys) {
        List<NestedField> fields = new ArrayList<>();
        Set<Integer> identifiers = new HashSet<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String name = schema.field(ordinal).name();
            if (changelog && (name.equalsIgnoreCase(OP_COLUMN) || name.equalsIgnoreCase(WEIGHT_COLUMN))) {
                throw new ConfigurationException(
                        IcebergErrors.SINK_BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' is in changelog mode, which adds the columns " + OP_COLUMN
                                + " and " + WEIGHT_COLUMN + ", and its schema already declares '" + name + "'");
            }
            PravahaType type = schema.field(ordinal).type();
            int id = ordinal + 1;
            Type iceberg = icebergType(instanceName, name, type);
            fields.add(
                    type.nullable()
                            ? NestedField.optional(id, name, iceberg)
                            : NestedField.required(id, name, iceberg));
            if (keys.stream().anyMatch(name::equalsIgnoreCase)) {
                identifiers.add(id);
            }
        }
        if (changelog) {
            int next = schema.fieldCount() + 1;
            fields.add(NestedField.required(next, OP_COLUMN, Types.StringType.get()));
            fields.add(NestedField.required(next + 1, WEIGHT_COLUMN, Types.LongType.get()));
        }
        return new Schema(fields, identifiers);
    }

    private static Type icebergType(String instanceName, String column, PravahaType type) {
        return switch (type.typeName()) {
            case BOOLEAN -> Types.BooleanType.get();
            case INT8, INT16, INT32 -> Types.IntegerType.get();
            case INT64 -> Types.LongType.get();
            case FLOAT32 -> Types.FloatType.get();
            case FLOAT64 -> Types.DoubleType.get();
            case STRING -> Types.StringType.get();
            case BYTES -> Types.BinaryType.get();
            case DATE -> Types.DateType.get();
            case TIME -> Types.TimeType.get();
            case TIMESTAMP_LTZ -> Types.TimestampType.withZone();
            case DECIMAL -> Types.DecimalType.of(((DecimalType) type).precision(), ((DecimalType) type).scale());
            default ->
                throw new ConfigurationException(
                        IcebergErrors.SINK_BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' cannot write column '" + column + "', which is "
                                + type.typeName() + "; Iceberg has no type this sink maps it to");
        };
    }

    /**
     * Refuses a table whose columns are not the binding's, by name, type and position; a required
     * table column under a nullable declaration too, since the first null would fail a commit.
     */
    static void refuseMismatch(String instanceName, String path, Schema wanted, Schema actual) {
        List<NestedField> want = wanted.columns();
        List<NestedField> have = actual.columns();
        for (int i = 0; i < Math.min(want.size(), have.size()); i++) {
            NestedField w = want.get(i);
            NestedField h = have.get(i);
            if (!w.name().equalsIgnoreCase(h.name())) {
                throw mismatch(
                        instanceName,
                        path,
                        "column " + i + " of the table is '" + h.name() + "' and this binding declares '" + w.name()
                                + "'");
            }
            if (!w.type().equals(h.type())) {
                throw mismatch(
                        instanceName,
                        path,
                        "column '" + h.name() + "' is " + h.type() + " in the table and " + w.type()
                                + " in this binding");
            }
            if (w.isOptional() && h.isRequired()) {
                throw mismatch(
                        instanceName,
                        path,
                        "column '" + h.name() + "' is required in the table and " + "nullable in this binding");
            }
        }
        if (want.size() != have.size()) {
            throw mismatch(
                    instanceName,
                    path,
                    "the table has columns " + names(have) + " and this binding declares " + names(want));
        }
    }

    private static List<String> names(List<NestedField> fields) {
        return fields.stream().map(NestedField::name).toList();
    }

    static PravahaException mismatch(String instanceName, String path, String what) {
        return new ConfigurationException(
                IcebergErrors.SINK_TABLE_MISMATCH,
                "plugin '" + instanceName + "' cannot write the Iceberg table at " + path + ": " + what
                        + ". This sink never alters a table's schema. Change the binding's schema, evolve the table "
                        + "with the engine that owns it, or point the sink at a new path.");
    }

    /** One engine row as the values Iceberg's generic writer takes, copied out of engine memory. */
    static Object[] read(StreamSchema schema, RowView row) {
        Object[] values = new Object[schema.fieldCount()];
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (!row.isNull(ordinal)) {
                values[ordinal] = valueOf(schema, row, ordinal);
            }
        }
        return values;
    }

    private static Object valueOf(StreamSchema schema, RowView row, int ordinal) {
        PravahaType type = schema.field(ordinal).type();
        return switch (type.typeName()) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> (int) row.getByte(ordinal);
            case INT16 -> (int) row.getShort(ordinal);
            case INT32 -> row.getInt(ordinal);
            case INT64 -> row.getLong(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            case DATE -> LocalDate.ofEpochDay(row.getInt(ordinal));
            case TIME -> LocalTime.ofNanoOfDay(wholeMicros(schema, ordinal, row.getLong(ordinal)));
            case TIMESTAMP_LTZ -> {
                long nanos = wholeMicros(schema, ordinal, row.getLong(ordinal));
                yield Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L))
                        .atOffset(ZoneOffset.UTC);
            }
            case DECIMAL -> decimalOf(schema, row, ordinal, (DecimalType) type);
            case BYTES -> ByteBuffer.wrap(bytesOf(row, ordinal));
            default -> row.getString(ordinal);
        };
    }

    private static long wholeMicros(StreamSchema schema, int ordinal, long nanos) {
        if (nanos % 1_000L != 0) {
            throw new PravahaException(
                    IcebergErrors.SINK_WRITE_FAILED,
                    "column '" + schema.field(ordinal).name() + "' holds " + nanos + " ns, which is not a whole "
                            + "number of microseconds; Iceberg stores microseconds, and a rounded time reads as true "
                            + "and is not. Truncate it in the query.");
        }
        return nanos;
    }

    /** At the scale the row was written with, then moved to the declared scale without rounding. */
    private static BigDecimal decimalOf(StreamSchema schema, RowView row, int ordinal, DecimalType declared) {
        int scale = declared.scale();
        StreamSchema written = row.schema();
        if (written != null
                && ordinal < written.fieldCount()
                && written.field(ordinal).type() instanceof DecimalType actual) {
            scale = actual.scale();
        }
        BigDecimal value = Decimals.toBigDecimal(row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal), scale);
        try {
            return value.setScale(declared.scale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new PravahaException(
                    IcebergErrors.SINK_WRITE_FAILED,
                    "column '" + schema.field(ordinal).name() + "' holds " + value + ", which does not fit " + declared
                            + " without rounding",
                    e);
        }
    }

    private static byte[] bytesOf(RowView row, int ordinal) {
        if (!(row instanceof BinaryRowView binary)) {
            throw new PravahaException(
                    IcebergErrors.SINK_WRITE_FAILED,
                    "cannot read a BYTES column from a " + row.getClass().getSimpleName()
                            + "; this sink writes the engine's binary rows");
        }
        MutableSlice slice = binary.getBytes(ordinal, new MutableSlice());
        byte[] copy = new byte[slice.length()];
        binary.region().getBytes(slice.offset(), copy, 0, slice.length());
        return copy;
    }
}
