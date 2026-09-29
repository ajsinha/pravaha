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
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.shyiko.mysql.binlog.event.TableMapEventData;

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

    /**
     * The stream, and for each of its fields how to read the binlog value, and the column as {@code
     * information_schema} described it when the plugin opened.
     */
    record Mapping(StreamSchema schema, Kind[] kinds, Charset[] charsets, List<Column> columns) {

        Mapping(StreamSchema schema, Kind[] kinds, Charset[] charsets) {
            this(schema, kinds, charsets, List.of());
        }

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
        return new Mapping(builder.build(), kinds, charsets, List.copyOf(columns));
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

    /**
     * How a binlog table map of the captured table disagrees with the columns the stream was typed
     * from, or null when it does not (MYC-4): an {@code ALTER} that keeps the column count -- {@code
     * SMALLINT} to {@code INT UNSIGNED}, {@code DECIMAL(10,2)} to {@code DECIMAL(12,4)} -- would
     * otherwise be read with the old conversion, value by value.
     *
     * <p>Compared: the binlog column type (each of the stream's types has one, or two for the pre-5.6
     * temporal encodings), a decimal's precision and scale, a column the stream declares {@code NOT
     * NULL} arriving nullable, and, when the server writes it ({@code binlog_row_metadata = FULL}), an
     * integer's signedness. {@code CHAR} and {@code BINARY}, {@code VARCHAR} and {@code VARBINARY},
     * {@code TEXT} and {@code BLOB} share a binlog type and are told apart only by character set, which
     * the table map carries only under {@code FULL} metadata; a change between them is not detected.
     */
    static String binlogMismatch(Mapping mapping, TableMapEventData map) {
        if (mapping.columns().size() != mapping.columnCount()) {
            return null;
        }
        byte[] types = map.getColumnTypes();
        int[] metadata = map.getColumnMetadata();
        BitSet nullability = map.getColumnNullability();
        BitSet unsigned =
                map.getEventMetadata() == null ? null : map.getEventMetadata().getSignedness();
        for (int i = 0; i < types.length; i++) {
            Column column = mapping.columns().get(i);
            int actual = types[i] & 0xFF;
            int meta = metadata == null || i >= metadata.length ? 0 : metadata[i];
            String name = "column '" + column.name() + "' (" + column.columnType() + ")";
            if (!binlogTypeMatches(column, actual, meta)) {
                return name + " arrives in the binlog as MySQL type " + actual + " (" + describe(actual, meta) + ")";
            }
            if (actual == 246 && metadata != null) {
                int[] declared = precisionAndScale(column);
                if (declared != null && (declared[0] != (meta & 0xFF) || declared[1] != (meta >> 8))) {
                    return name + " arrives in the binlog as " + describe(actual, meta);
                }
            }
            if (!column.nullable() && nullability != null && nullability.get(i)) {
                return name + " is NOT NULL in the stream and nullable in the binlog";
            }
            Kind kind = mapping.kinds()[i];
            if (unsigned != null
                    && isInteger(kind)
                    && unsigned.get(i) != kind.name().endsWith("_UNSIGNED")) {
                return name + " is " + (unsigned.get(i) ? "UNSIGNED" : "signed") + " in the binlog";
            }
        }
        return null;
    }

    private static boolean isInteger(Kind kind) {
        return kind.ordinal() <= Kind.BIG_UNSIGNED.ordinal();
    }

    /** The binlog type codes a column of this {@code DATA_TYPE} is written with. */
    private static boolean binlogTypeMatches(Column column, int actual, int meta) {
        return switch (column.dataType()) {
            case "tinyint" -> actual == 1;
            case "smallint" -> actual == 2;
            case "mediumint" -> actual == 9;
            case "int", "integer" -> actual == 3;
            case "bigint" -> actual == 8;
            case "float" -> actual == 4;
            case "double", "real" -> actual == 5;
            case "decimal", "numeric" -> actual == 246;
            // CHAR and BINARY are STRING (254) with the real type, also 254, in the metadata's high
            // byte, whose 0x30 bits may carry the length instead; ENUM (247) and SET (248) share 254.
            case "char", "binary" -> actual == 254 && (((meta >> 8) | 0x30) & 0xFF) == 254;
            case "varchar", "varbinary" -> actual == 15;
            case "tinytext", "text", "mediumtext", "longtext", "tinyblob", "blob", "mediumblob", "longblob" ->
                actual == 252;
            case "date" -> actual == 10 || actual == 14;
            case "datetime" -> actual == 18 || actual == 12;
            case "timestamp" -> actual == 17 || actual == 7;
            default -> true;
        };
    }

    private static String describe(int type, int meta) {
        return switch (type) {
            case 1 -> "TINYINT";
            case 2 -> "SMALLINT";
            case 3 -> "INT";
            case 4 -> "FLOAT";
            case 5 -> "DOUBLE";
            case 7, 17 -> "TIMESTAMP";
            case 8 -> "BIGINT";
            case 9 -> "MEDIUMINT";
            case 10, 14 -> "DATE";
            case 11, 19 -> "TIME";
            case 12, 18 -> "DATETIME";
            case 13 -> "YEAR";
            case 15 -> "VARCHAR or VARBINARY";
            case 16 -> "BIT";
            case 245 -> "JSON";
            case 246 -> "DECIMAL(" + (meta & 0xFF) + "," + (meta >> 8) + ")";
            case 247 -> "ENUM";
            case 248 -> "SET";
            case 252 -> "TEXT or BLOB";
            case 254 -> "CHAR, BINARY, ENUM or SET";
            case 255 -> "GEOMETRY";
            default -> "type " + type;
        };
    }

    private static ConfigurationException mismatch(MySqlCdcOptions options, String message) {
        return new ConfigurationException(
                MySqlCdcErrors.SCHEMA_MISMATCH, "plugin '" + options.instanceName() + "': " + message);
    }
}
