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
package com.ash.messaging.pravaha.embedded;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;

/**
 * Writes an application's Java values into the engine's binary row form, for one stream.
 *
 * <p>Two steps rather than one, and the split is the point. {@link #validate} converts a whole batch
 * to the exact values the row will hold -- arity, NOT NULL, kind and range -- before any of it is
 * written anywhere, so a push with one bad row in it delivers nothing rather than the rows before
 * it. {@link #write} then only copies.
 *
 * <p>The accepted Java types, per column type: {@code Boolean}; any integral {@code Number} that
 * fits, for the integer types; any {@code Number} for the floating ones and {@code DECIMAL} (or a
 * {@code BigDecimal}, which must not need rounding at the column's scale); {@code String}; {@code
 * byte[]}; {@link Instant} or epoch nanoseconds for {@code TIMESTAMP}; {@link LocalDate} or epoch
 * days for {@code DATE}; {@link LocalTime} or nanoseconds of day for {@code TIME}. These are the
 * same units the filesystem codec writes, so a row pushed here and the same row read from a file
 * are the same row.
 */
final class RowEncoder {

    private final StreamSchema schema;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final int eventTimeOrdinal;

    RowEncoder(StreamSchema schema) {
        this.schema = schema;
        this.layout = RowLayout.of(schema);
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);
        // Stamped only from a column that holds nanoseconds, which is what the filesystem codec does
        // too: an event time read from a DATE's epoch days would put every row in 1970.
        int ordinal = schema.eventTimeOrdinal().orElse(-1);
        TypeName type = ordinal < 0 ? null : schema.field(ordinal).type().typeName();
        this.eventTimeOrdinal = type == TypeName.INT64 || type == TypeName.TIMESTAMP_LTZ ? ordinal : -1;
    }

    StreamSchema schema() {
        return schema;
    }

    /** Values by column name, in column order; a column the map does not name is null. */
    Object[] fromMap(Map<String, ?> row) {
        Object[] values = new Object[schema.fieldCount()];
        for (Map.Entry<String, ?> entry : row.entrySet()) {
            if (!schema.hasField(entry.getKey())) {
                throw rejected("names a column '" + entry.getKey() + "' that stream '" + schema.name()
                        + "' does not have; its columns are "
                        + schema.fields().stream().map(Field::name).toList());
            }
            values[schema.indexOf(entry.getKey())] = entry.getValue();
        }
        return values;
    }

    /** The values the row will hold, converted and checked, or a refusal naming the column. */
    Object[] validate(Object[] row) {
        if (row == null || row.length != schema.fieldCount()) {
            throw rejected("has " + (row == null ? 0 : row.length) + " values and stream '" + schema.name() + "' has "
                    + schema.fieldCount() + " columns "
                    + schema.fields().stream().map(Field::name).toList());
        }
        Object[] converted = new Object[row.length];
        for (int ordinal = 0; ordinal < row.length; ordinal++) {
            Field field = schema.field(ordinal);
            Object value = row[ordinal];
            if (value == null) {
                if (!field.type().nullable()) {
                    throw rejected("has null in NOT NULL column '" + field.name() + "'");
                }
                continue;
            }
            converted[ordinal] = convert(field, value);
        }
        return converted;
    }

    /** Bytes {@link #write} will need for these validated values. */
    int sizeOf(Object[] values) {
        int payload = 0;
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (values[ordinal] instanceof byte[] bytes) {
                payload += bytes.length;
            }
        }
        return layout.rowSize(payload);
    }

    /**
     * Writes validated values into {@code arena} and returns a view over them, or null if the arena
     * is full. The view is valid until the arena is reset.
     */
    BinaryRowView write(Object[] values, RowArena arena, long sequence) {
        int size = sizeOf(values);
        long handle = arena.allocate(size);
        if (handle == ArenaHandle.NULL) {
            return null;
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle), size);
        long eventTime = 0L;
        boolean stamped = false;
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            Object value = values[ordinal];
            if (value == null) {
                writer.setNull(ordinal);
                continue;
            }
            switch (schema.field(ordinal).type().typeName()) {
                case BOOLEAN -> writer.setBoolean(ordinal, (Boolean) value);
                case INT8 -> writer.setByte(ordinal, ((Number) value).byteValue());
                case INT16 -> writer.setShort(ordinal, ((Number) value).shortValue());
                case INT32, DATE -> writer.setInt(ordinal, ((Number) value).intValue());
                case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, ((Number) value).longValue());
                case FLOAT32 -> writer.setFloat(ordinal, ((Number) value).floatValue());
                case FLOAT64 -> writer.setDouble(ordinal, ((Number) value).doubleValue());
                case DECIMAL -> {
                    long[] limbs = (long[]) value;
                    writer.setDecimal(ordinal, limbs[0], limbs[1]);
                }
                default -> writer.setBytes(ordinal, (byte[]) value);
            }
            if (ordinal == eventTimeOrdinal && value instanceof Long nanos) {
                eventTime = nanos;
                stamped = true;
            }
        }
        writer.weight(1L).sequence(sequence);
        if (stamped) {
            writer.eventTimestampNanos(eventTime);
        }
        writer.commit();
        return view.wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    private Object convert(Field field, Object value) {
        TypeName type = field.type().typeName();
        return switch (type) {
            case BOOLEAN -> {
                if (value instanceof Boolean flag) {
                    yield flag;
                }
                throw mismatch(field, value, "a Boolean");
            }
            case INT8 -> integral(field, value, Byte.MIN_VALUE, Byte.MAX_VALUE);
            case INT16 -> integral(field, value, Short.MIN_VALUE, Short.MAX_VALUE);
            case INT32 -> integral(field, value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case INT64 -> integral(field, value, Long.MIN_VALUE, Long.MAX_VALUE);
            case FLOAT32, FLOAT64 -> {
                if (value instanceof Number number) {
                    yield number.doubleValue();
                }
                throw mismatch(field, value, "a Number");
            }
            case DECIMAL -> {
                BigDecimal decimal;
                if (value instanceof BigDecimal exact) {
                    decimal = exact;
                } else if (value instanceof Number number) {
                    decimal = new BigDecimal(number.toString());
                } else {
                    throw mismatch(field, value, "a BigDecimal or a Number");
                }
                int scale = ((DecimalType) field.type()).scale();
                try {
                    yield new long[] {Decimals.high(decimal, scale), Decimals.low(decimal, scale)};
                } catch (ArithmeticException e) {
                    throw rejected("has " + decimal + " in column '" + field.name() + "', which does not fit "
                            + field.type().sqlName() + " without rounding: " + e.getMessage());
                }
            }
            case DATE -> {
                if (value instanceof LocalDate date) {
                    yield date.toEpochDay();
                }
                yield integral(field, value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
            case TIME -> {
                if (value instanceof LocalTime time) {
                    yield time.toNanoOfDay();
                }
                yield integral(field, value, 0, 86_400_000_000_000L - 1);
            }
            case TIMESTAMP_LTZ -> {
                if (value instanceof Instant instant) {
                    yield Math.addExact(
                            Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
                }
                yield integral(field, value, Long.MIN_VALUE, Long.MAX_VALUE);
            }
            case STRING -> {
                if (value instanceof CharSequence text) {
                    yield text.toString().getBytes(StandardCharsets.UTF_8);
                }
                throw mismatch(field, value, "a String");
            }
            case BYTES -> {
                if (value instanceof byte[] bytes) {
                    yield bytes.clone();
                }
                throw mismatch(field, value, "a byte[]");
            }
            default ->
                throw rejected("column '" + field.name() + "' is " + type + ", which a pushed row cannot carry yet; "
                        + "bind a source plugin that reads it instead");
        };
    }

    private Long integral(Field field, Object value, long min, long max) {
        long result;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            result = ((Number) value).longValue();
        } else if (value instanceof BigInteger big && big.bitLength() < 64) {
            result = big.longValue();
        } else {
            throw mismatch(field, value, "an integral Number");
        }
        if (result < min || result > max) {
            throw rejected("has " + result + " in column '" + field.name() + "', which is outside what "
                    + field.type().typeName() + " holds");
        }
        return result;
    }

    private PravahaException mismatch(Field field, Object value, String expected) {
        return rejected("has a " + value.getClass().getSimpleName() + " in column '" + field.name() + "' ("
                + field.type().typeName() + "), which needs " + expected);
    }

    private PravahaException rejected(String what) {
        return new PravahaException(EmbeddedErrors.ROW_REJECTED, "a row pushed to '" + schema.name() + "' " + what);
    }
}
