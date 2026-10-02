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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.HexFormat;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowWriter;

/**
 * PostgreSQL's text output format, turned into the values a row holds.
 *
 * <p>The stream carries every value as its type's text representation. Each conversion here is the
 * inverse of one output function, under the settings the driver fixes on every connection
 * ({@code DateStyle=ISO}, {@code bytea_output=hex} by default); a value in any other shape is refused
 * by the caller rather than guessed at.
 *
 * <p>{@code timestamp} without a time zone is read as UTC. It names no zone, so any choice is a
 * convention; UTC is the one that does not change with the machine the engine runs on.
 * {@code timestamptz} carries its offset in the text and needs no convention.
 */
final class PgValues {

    static final int BOOL = 16;
    static final int BYTEA = 17;
    static final int CHAR = 18;
    static final int NAME = 19;
    static final int INT8 = 20;
    static final int INT2 = 21;
    static final int INT4 = 23;
    static final int TEXT = 25;
    static final int FLOAT4 = 700;
    static final int FLOAT8 = 701;
    static final int BPCHAR = 1042;
    static final int VARCHAR = 1043;
    static final int DATE = 1082;
    static final int TIMESTAMP = 1114;
    static final int TIMESTAMPTZ = 1184;
    static final int NUMERIC = 1700;
    static final int UUID = 2950;

    private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    private PgValues() {}

    /**
     * Converts one text value to what {@link #write} takes for {@code type}.
     *
     * @throws IllegalArgumentException when the text is not a value of that type as this reads it
     */
    static Object parse(String text, int typeOid, PravahaType type) {
        return switch (type.typeName()) {
            case BOOLEAN ->
                switch (text) {
                    case "t" -> true;
                    case "f" -> false;
                    default -> throw new IllegalArgumentException("'" + text + "' is not a boolean");
                };
            case INT16 -> Short.parseShort(text);
            case INT32 -> Integer.parseInt(text);
            case INT64 -> Long.parseLong(text);
            case FLOAT32 -> Float.parseFloat(text);
            case FLOAT64 -> Double.parseDouble(text);
            case DECIMAL -> decimal(text, ((DecimalType) type).scale());
            case STRING -> text;
            case BYTES -> {
                if (!text.startsWith("\\x")) {
                    throw new IllegalArgumentException("a bytea value not in hex output format; set "
                            + "bytea_output = 'hex', the PostgreSQL default, for this role or database");
                }
                yield HexFormat.of().parseHex(text, 2, text.length());
            }
            case DATE -> (int) LocalDate.parse(text).toEpochDay();
            case TIMESTAMP_LTZ -> typeOid == TIMESTAMPTZ ? zoned(text) : utc(LocalDateTime.parse(text, LOCAL));
            default -> throw new IllegalArgumentException("no conversion to " + type.typeName());
        };
    }

    private static BigInteger decimal(String text, int scale) {
        BigInteger unscaled =
                new BigDecimal(text).setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        if (unscaled.bitLength() > 127) {
            throw new IllegalArgumentException("'" + text + "' does not fit a 38-digit decimal");
        }
        return unscaled;
    }

    /** {@code 2026-09-19 10:11:12.5+05:30}: the offset is the trailing sign and what follows it. */
    private static long zoned(String text) {
        int sign = Math.max(text.lastIndexOf('+'), text.lastIndexOf('-'));
        if (sign < 19) {
            throw new IllegalArgumentException("'" + text + "' has no UTC offset");
        }
        LocalDateTime local = LocalDateTime.parse(text.substring(0, sign), LOCAL);
        ZoneOffset offset = ZoneOffset.of(text.substring(sign));
        return nanos(local.toEpochSecond(offset), local.getNano());
    }

    private static long utc(LocalDateTime local) {
        return nanos(local.toEpochSecond(ZoneOffset.UTC), local.getNano());
    }

    private static long nanos(long seconds, int nano) {
        return Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), nano);
    }

    /** Writes a value {@link #parse} produced, or a null. */
    static void write(RowWriter writer, int ordinal, PravahaType type, Object value) {
        if (value == null) {
            writer.setNull(ordinal);
            return;
        }
        switch (type.typeName()) {
            case BOOLEAN -> writer.setBoolean(ordinal, (Boolean) value);
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
}
