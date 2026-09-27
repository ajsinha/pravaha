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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * The captured table's columns, the stream schema they become, and the conversion of a binlog row
 * value to what a row holds.
 *
 * <p>Typed from {@code information_schema}, never guessed: the binlog carries values without names,
 * in column order, and with {@code binlog_row_image = FULL} every column is present in every image.
 * A column type with no exact mapping is refused at open by name.
 *
 * <p>Values arrive as the binlog library decodes them with {@code CHAR_AND_BINARY_AS_BYTE_ARRAY} and
 * {@code DATE_AND_TIME_AS_LONG_MICRO}: integers as signed Java numbers (an {@code UNSIGNED} column is
 * reinterpreted here), text as bytes in the column's character set, and temporal values as
 * microseconds since the epoch. {@code DATETIME} names no zone and is read as UTC.
 */
final class MySqlSchema {

    /** How a column's binlog value is read. */
    enum Kind {
        TINY,
        TINY_UNSIGNED,
        SHORT,
        SHORT_UNSIGNED,
        MEDIUM,
        MEDIUM_UNSIGNED,
        INT,
        INT_UNSIGNED,
        BIG,
        BIG_UNSIGNED,
        FLOAT,
        DOUBLE,
        DECIMAL,
        STRING,
        BYTES,
        DATE,
        DATETIME,
        TIMESTAMP
    }

    /** One column as {@code information_schema.COLUMNS} has it. */
    record Column(String name, String dataType, String columnType, boolean nullable, String charset) {}

    /** The stream, and for each of its fields how to read the binlog value. */
    record Mapping(StreamSchema schema, Kind[] kinds, Charset[] charsets) {

        int columnCount() {
            return kinds.length;
        }
    }

    private static final Pattern DECIMAL = Pattern.compile("decimal\\((\\d+),(\\d+)\\).*");
    private static final long MICROS_PER_DAY = 86_400_000_000L;

    private MySqlSchema() {}

    static List<Column> load(MySqlClient client, MySqlCdcOptions options) throws IOException {
        List<Column> columns = new ArrayList<>();
        for (String[] row : client.query("SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, "
                + "COALESCE(CHARACTER_SET_NAME, '') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '"
                + options.database() + "' AND TABLE_NAME = '" + options.table() + "' ORDER BY ORDINAL_POSITION")) {
            columns.add(new Column(
                    row[0],
                    row[1].toLowerCase(Locale.ROOT),
                    row[2].toLowerCase(Locale.ROOT),
                    "YES".equals(row[3]),
                    row[4]));
        }
        return columns;
    }

    static Mapping resolve(MySqlCdcOptions options, List<Column> columns) {
        if (columns.isEmpty()) {
            throw mismatch(
                    options,
                    "table " + options.qualifiedTable() + " does not exist, or user '" + options.user()
                            + "' has no SELECT on it. GRANT SELECT ON " + options.qualifiedTable() + " TO '"
                            + options.user() + "'@'%';");
        }
        StreamSchema.Builder builder = StreamSchema.builder(options.streamName());
        Kind[] kinds = new Kind[columns.size()];
        Charset[] charsets = new Charset[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            kinds[i] = kind(options, column);
            charsets[i] = kinds[i] == Kind.STRING ? charset(options, column) : null;
            builder.field(column.name(), type(kinds[i], column).withNullable(column.nullable()));
        }
        if (!options.eventTimeColumn().isEmpty()) {
            StreamSchema provisional = builder.build();
            boolean found = provisional.fields().stream()
                    .anyMatch(f -> f.name().equals(options.eventTimeColumn())
                            && f.type().typeName() == TypeName.TIMESTAMP_LTZ);
            if (!found) {
                throw MySqlCdcOptions.bad(
                        options.instanceName(),
                        "event.time '" + options.eventTimeColumn() + "' must name a DATETIME or TIMESTAMP column; "
                                + "the columns are "
                                + provisional.fields().stream().map(Field::name).toList());
            }
            builder.eventTime(options.eventTimeColumn());
        }
        return new Mapping(builder.build(), kinds, charsets);
    }

    static Kind kind(MySqlCdcOptions options, Column column) {
        boolean unsigned = column.columnType().contains("unsigned");
        Kind kind =
                switch (column.dataType()) {
                    case "tinyint" -> unsigned ? Kind.TINY_UNSIGNED : Kind.TINY;
                    case "smallint" -> unsigned ? Kind.SHORT_UNSIGNED : Kind.SHORT;
                    case "mediumint" -> unsigned ? Kind.MEDIUM_UNSIGNED : Kind.MEDIUM;
                    case "int", "integer" -> unsigned ? Kind.INT_UNSIGNED : Kind.INT;
                    case "bigint" -> unsigned ? Kind.BIG_UNSIGNED : Kind.BIG;
                    case "float" -> Kind.FLOAT;
                    case "double", "real" -> Kind.DOUBLE;
                    case "decimal", "numeric" -> Kind.DECIMAL;
                    case "char", "varchar", "tinytext", "text", "mediumtext", "longtext" -> Kind.STRING;
                    case "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob" -> Kind.BYTES;
                    case "date" -> Kind.DATE;
                    case "datetime" -> Kind.DATETIME;
                    case "timestamp" -> Kind.TIMESTAMP;
                    default -> null;
                };
        if (kind == null || (kind == Kind.DECIMAL && precisionAndScale(column) == null)) {
            throw mismatch(
                    options,
                    "column '" + column.name() + "' of " + options.qualifiedTable() + " is " + column.columnType()
                            + ", which mysql-cdc does not map yet (mapped: integers, FLOAT, DOUBLE, DECIMAL up to 38 "
                            + "digits, CHAR/VARCHAR/TEXT, BINARY/VARBINARY/BLOB, DATE, DATETIME, TIMESTAMP).");
        }
        return kind;
    }

    private static PravahaType type(Kind kind, Column column) {
        return switch (kind) {
            case TINY, TINY_UNSIGNED, SHORT -> Types.int16();
            case SHORT_UNSIGNED, MEDIUM, MEDIUM_UNSIGNED, INT -> Types.int32();
            case INT_UNSIGNED, BIG -> Types.int64();
            case BIG_UNSIGNED -> Types.decimal(38, 0);
            case FLOAT -> Types.float32();
            case DOUBLE -> Types.float64();
            case DECIMAL -> Types.decimal(38, precisionAndScale(column)[1]);
            case STRING -> Types.string();
            case BYTES -> Types.bytes();
            case DATE -> Types.date();
            case DATETIME, TIMESTAMP -> Types.timestamp();
        };
    }

    /** {@code decimal(p,s)} with {@code p <= 38}, or null. */
    private static int[] precisionAndScale(Column column) {
        Matcher matcher = DECIMAL.matcher(column.columnType());
        if (!matcher.matches()) {
            return null;
        }
        int precision = Integer.parseInt(matcher.group(1));
        int scale = Integer.parseInt(matcher.group(2));
        return precision <= 38 ? new int[] {precision, scale} : null;
    }

    private static Charset charset(MySqlCdcOptions options, Column column) {
        return switch (column.charset().toLowerCase(Locale.ROOT)) {
            case "utf8mb4", "utf8mb3", "utf8" -> StandardCharsets.UTF_8;
            case "latin1" -> Charset.forName("windows-1252");
            case "ascii" -> StandardCharsets.US_ASCII;
            default ->
                throw mismatch(
                        options,
                        "column '" + column.name() + "' is in character set '" + column.charset()
                                + "', which mysql-cdc does not decode (utf8mb4, utf8mb3, latin1, ascii).");
        };
    }

    /**
     * Converts one binlog value to what {@link #write} takes for its field.
     *
     * @throws IllegalArgumentException when the value is not what the column's type decodes to
     */
    static Object convert(Kind kind, Charset charset, PravahaType type, Serializable raw) {
        if (raw == null) {
            return null;
        }
        return switch (kind) {
            case TINY, SHORT -> number(raw).shortValue();
            case TINY_UNSIGNED -> (short) (number(raw).intValue() & 0xFF);
            case SHORT_UNSIGNED -> number(raw).intValue() & 0xFFFF;
            case MEDIUM, INT -> number(raw).intValue();
            case MEDIUM_UNSIGNED -> number(raw).intValue() & 0xFFFFFF;
            case INT_UNSIGNED -> Integer.toUnsignedLong(number(raw).intValue());
            case BIG -> number(raw).longValue();
            case BIG_UNSIGNED ->
                new BigInteger(Long.toUnsignedString(number(raw).longValue()));
            case FLOAT -> number(raw).floatValue();
            case DOUBLE -> number(raw).doubleValue();
            case DECIMAL -> unscaled((BigDecimal) raw, ((DecimalType) type).scale());
            case STRING -> raw instanceof byte[] bytes ? new String(bytes, charset) : raw.toString();
            case BYTES -> raw instanceof byte[] bytes ? bytes : raw.toString().getBytes(StandardCharsets.ISO_8859_1);
            case DATE -> Math.toIntExact(Math.floorDiv(number(raw).longValue(), MICROS_PER_DAY));
            case DATETIME, TIMESTAMP -> Math.multiplyExact(number(raw).longValue(), 1_000L);
        };
    }

    private static Number number(Serializable raw) {
        if (raw instanceof Number n) {
            return n;
        }
        throw new IllegalArgumentException(
                "expected a number, got " + raw.getClass().getSimpleName());
    }

    private static BigInteger unscaled(BigDecimal value, int scale) {
        BigInteger unscaled = value.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        if (unscaled.bitLength() > 127) {
            throw new IllegalArgumentException(value + " does not fit a 38-digit decimal");
        }
        return unscaled;
    }

    /** Writes a value {@link #convert} produced, or a null. */
    static void write(RowWriter writer, int ordinal, PravahaType type, Object value) {
        if (value == null) {
            writer.setNull(ordinal);
            return;
        }
        switch (type.typeName()) {
            case INT16 -> writer.setShort(ordinal, ((Number) value).shortValue());
            case INT32, DATE -> writer.setInt(ordinal, ((Number) value).intValue());
            case INT64, TIMESTAMP_LTZ -> writer.setLong(ordinal, ((Number) value).longValue());
            case FLOAT32 -> writer.setFloat(ordinal, ((Number) value).floatValue());
            case FLOAT64 -> writer.setDouble(ordinal, ((Number) value).doubleValue());
            case DECIMAL -> {
                BigInteger unscaled = (BigInteger) value;
                writer.setDecimal(ordinal, unscaled.shiftRight(64).longValue(), unscaled.longValue());
            }
            case STRING -> writer.setString(ordinal, (String) value);
            case BYTES -> writer.setBytes(ordinal, (byte[]) value);
            default -> throw new IllegalStateException("no writer for " + type.typeName());
        }
    }

    private static ConfigurationException mismatch(MySqlCdcOptions options, String message) {
        return new ConfigurationException(
                MySqlCdcErrors.SCHEMA_MISMATCH, "plugin '" + options.instanceName() + "': " + message);
    }
}
