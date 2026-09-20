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
package com.ash.messaging.pravaha.plugin.delta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.delta.kernel.types.BinaryType;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.ByteType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.ShortType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampType;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * The {@code name:TYPE} schema a Delta sink is declared with, and the Delta table shape it means.
 *
 * <p>Declared rather than read from the table, for the reason {@code JdbcSinkSchema} gives: the
 * registry compares a sink's schema with the query's output <em>before</em> the sink is opened
 * (PRV-8010), so it has to be answerable from configuration alone. The table is then checked against
 * the declaration when the sink opens ({@link #refuseMismatch}), so the two cannot quietly disagree.
 *
 * <p>The mapping to Delta is {@link DeltaTypes}' read mapping run backwards, and it refuses in the
 * same places rather than approximating:
 *
 * <ul>
 *   <li><strong>{@code TIME} has no Delta type.</strong> Delta has {@code DATE} and {@code
 *       TIMESTAMP} and nothing for a time of day. Writing one as a {@code BIGINT} of nanoseconds
 *       would produce a column no Delta reader interprets as a time, so the column is refused by
 *       name.
 *   <li><strong>{@code TIMESTAMP} is microseconds in Delta and nanoseconds here.</strong> The column
 *       maps; a <em>value</em> carrying sub-microsecond precision does not, and is refused when it is
 *       written rather than rounded (see {@code DeltaSinkRows}).
 * </ul>
 */
final class DeltaSinkSchema {

    /** Changelog mode's operation column: {@code insert} or {@code delete}. */
    static final String OP_COLUMN = "_op";

    /** Changelog mode's weight column: the change's Z-set weight, signed. */
    static final String WEIGHT_COLUMN = "_weight";

    private static final Pattern DECIMAL = Pattern.compile("^DECIMAL\\((\\d+),\\s*(\\d+)\\)$");

    private DeltaSinkSchema() {}

    /** Parses {@code name:TYPE,name:TYPE}. */
    static StreamSchema parse(String streamName, String spec) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (String column : splitColumns(spec)) {
            String[] parts = column.strip().split(":", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new ConfigurationException(
                        DeltaErrors.SINK_BAD_CONFIGURATION,
                        "schema entry '" + column.strip() + "' is not 'name:TYPE'. Example: id:INT64,name:STRING");
            }
            builder.field(parts[0].strip(), typeFor(parts[1].strip()));
        }
        return builder.build();
    }

    /** Splits on the commas between columns, not the one inside {@code DECIMAL(p,s)}. */
    private static List<String> splitColumns(String spec) {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
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
                    case "BOOLEAN", "BOOL" -> Types.bool();
                    case "INT8", "BYTE" -> Types.int8();
                    case "INT16", "SHORT" -> Types.int16();
                    case "INT32", "INT" -> Types.int32();
                    case "INT64", "LONG" -> Types.int64();
                    case "FLOAT32", "FLOAT" -> Types.float32();
                    case "FLOAT64", "DOUBLE" -> Types.float64();
                    case "STRING", "VARCHAR", "TEXT" -> Types.string();
                    case "BYTES", "BINARY" -> Types.bytes();
                    case "TIMESTAMP" -> Types.timestamp();
                    case "DATE" -> Types.date();
                    case "TIME" -> Types.time();
                    default -> decimalOrRefusal(name, upper);
                };
        return nullable ? type.withNullable(true) : type;
    }

    private static PravahaType decimalOrRefusal(String original, String upper) {
        Matcher decimal = DECIMAL.matcher(upper);
        if (decimal.matches()) {
            return Types.decimal(Integer.parseInt(decimal.group(1)), Integer.parseInt(decimal.group(2)));
        }
        throw new ConfigurationException(
                DeltaErrors.SINK_BAD_CONFIGURATION,
                "unknown type '" + original + "'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, FLOAT32, "
                        + "FLOAT64, STRING, BYTES, DATE, TIMESTAMP, DECIMAL(p,s). Suffix with ? for nullable. "
                        + "TIME parses and is then refused, because Delta has no time-of-day type.");
    }

    /**
     * The Delta table shape this sink writes: the declared columns, plus changelog mode's two.
     *
     * <p>The changelog columns are last and named {@code _op} and {@code _weight}. A declaration that
     * already has a column of either name is refused, rather than one silently winning.
     */
    static StructType toDeltaSchema(String instanceName, StreamSchema schema, boolean changelog) {
        StructType delta = new StructType();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String name = schema.field(ordinal).name();
            if (changelog && (name.equalsIgnoreCase(OP_COLUMN) || name.equalsIgnoreCase(WEIGHT_COLUMN))) {
                throw new ConfigurationException(
                        DeltaErrors.SINK_BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' is in changelog mode, which adds the columns " + OP_COLUMN
                                + " and " + WEIGHT_COLUMN + " to the table, and its schema already declares '" + name
                                + "'. Rename the query's column, or use mode: upsert.");
            }
            PravahaType type = schema.field(ordinal).type();
            delta = delta.add(name, toDeltaType(instanceName, name, type), type.nullable());
        }
        if (changelog) {
            delta = delta.add(OP_COLUMN, StringType.STRING, false).add(WEIGHT_COLUMN, LongType.LONG, false);
        }
        return delta;
    }

    /** One Pravaha type as Delta stores it, or a refusal naming the column. */
    static DataType toDeltaType(String instanceName, String column, PravahaType type) {
        return switch (type.typeName()) {
            case BOOLEAN -> BooleanType.BOOLEAN;
            case INT8 -> ByteType.BYTE;
            case INT16 -> ShortType.SHORT;
            case INT32 -> IntegerType.INTEGER;
            case INT64 -> LongType.LONG;
            case FLOAT32 -> FloatType.FLOAT;
            case FLOAT64 -> DoubleType.DOUBLE;
            case STRING -> StringType.STRING;
            case BYTES -> BinaryType.BINARY;
            case DATE -> DateType.DATE;
            case TIMESTAMP_LTZ -> TimestampType.TIMESTAMP;
            case DECIMAL ->
                new io.delta.kernel.types.DecimalType(((DecimalType) type).precision(), ((DecimalType) type).scale());
            default ->
                throw new ConfigurationException(
                        DeltaErrors.UNSUPPORTED_TYPE,
                        "plugin '" + instanceName + "' cannot write column '" + column + "', which is "
                                + type.typeName() + ". Delta has no type for it -- a TIME would have to become a "
                                + "BIGINT of nanoseconds that no Delta reader reads as a time. Project the column "
                                + "away, or convert it in the query to a type Delta has.");
        };
    }

    /**
     * Refuses a table that is not the one the binding describes, before a row is written.
     *
     * <p>Compared by position, name and type, exactly as the registry compares the query's output
     * with the binding (PRV-8010) -- for the same reason. A sink writes each row through its own
     * schema, so a table whose third column is somewhere else takes every value in the wrong place
     * and nothing fails. Nullability is compared in one direction only: a table column that forbids
     * nulls under a declaration that permits them is refused, since the first null would fail the
     * write; the opposite is harmless.
     */
    static void refuseMismatch(String instanceName, String path, StructType wanted, StructType actual) {
        for (int i = 0; i < Math.min(wanted.length(), actual.length()); i++) {
            StructField want = wanted.at(i);
            StructField have = actual.at(i);
            if (!want.getName().equalsIgnoreCase(have.getName())) {
                throw mismatch(
                        instanceName,
                        path,
                        "column " + i + " of the table is '" + have.getName() + "' and this binding declares '"
                                + want.getName() + "'");
            }
            if (!want.getDataType().equivalent(have.getDataType())) {
                throw mismatch(
                        instanceName,
                        path,
                        "column '" + have.getName() + "' is " + have.getDataType() + " in the table and "
                                + want.getDataType() + " in this binding");
            }
            if (want.isNullable() && !have.isNullable()) {
                throw mismatch(
                        instanceName,
                        path,
                        "column '" + have.getName() + "' is NOT NULL in the table and "
                                + "nullable in this binding, so the first null the query produces would fail the commit");
            }
        }
        if (wanted.length() != actual.length()) {
            throw mismatch(
                    instanceName,
                    path,
                    "the table has columns " + actual.fieldNames() + " and this binding declares "
                            + wanted.fieldNames());
        }
    }

    private static PravahaException mismatch(String instanceName, String path, String what) {
        return new PravahaException(
                DeltaErrors.SINK_TABLE_MISMATCH,
                "plugin '" + instanceName + "' cannot write the Delta table at " + path + ": " + what
                        + ". This sink never alters a table's schema -- a rewrite of a lakehouse table's columns is "
                        + "not a thing a streaming sink should do on its own. Change the binding's schema, evolve "
                        + "the table with the engine that owns it, or point the sink at a new path.");
    }
}
