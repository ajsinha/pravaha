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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.Decimals;

/**
 * A sink's rows as values: read out of the engine's rows, bound into statements, and written to and
 * read back from the staging table.
 *
 * <p>A value is the Java object for its declared type -- {@code Boolean}, {@code Byte}, {@code
 * Short}, {@code Integer}, {@code Long}, {@code Float}, {@code Double}, {@code BigDecimal}, {@code
 * String}, {@code byte[]} -- with the engine's own encodings for time: a {@code DATE} is days since
 * the epoch, a {@code TIME} nanoseconds since midnight, a {@code TIMESTAMP} nanoseconds since the
 * epoch, UTC. Those are converted to {@code java.time} only when bound, so a staged row is exactly
 * the row the engine wrote.
 */
final class JdbcSinkRows {

    /** One change: a row's values and its weight, negative for a retraction. */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    record Change(Object[] values, long weight) {}

    private static final int FORMAT = 1;

    private final StreamSchema schema;

    JdbcSinkRows(StreamSchema schema) {
        this.schema = schema;
    }

    /**
     * Copies a row out of the engine's memory, which the SPI does not let a sink keep past {@code
     * write}.
     */
    Change read(RowView row) {
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
        return new Change(values, row.weight());
    }

    /**
     * A decimal at the scale the row was <em>written</em> with.
     *
     * <p>The registry compares a sink's schema with the query's output by type name, not by scale, so
     * a sink declared {@code DECIMAL(10,2)} can be handed a {@code DECIMAL(38,9)} column. Reading the
     * unscaled value with the declared scale would multiply every amount by ten million; the row's own
     * schema says what it holds.
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

    /**
     * Copies a byte column. The generic row has no way to expose its bytes without a copy into the
     * row's own memory, so this needs the engine's binary row, which every row a delivery writes is.
     */
    private static byte[] bytesOf(RowView row, int ordinal) {
        if (!(row instanceof BinaryRowView binary)) {
            throw new PravahaException(
                    JdbcErrors.WRITE_FAILED,
                    "cannot read a BYTES column from a " + row.getClass().getSimpleName()
                            + "; this sink writes the engine's binary rows");
        }
        MutableSlice slice = binary.getBytes(ordinal, new MutableSlice());
        byte[] copy = new byte[slice.length()];
        binary.region().getBytes(slice.offset(), copy, 0, slice.length());
        return copy;
    }

    /** A value made comparable, for keying: a byte array by its content. */
    static Object comparable(Object value) {
        return value instanceof byte[] raw ? ByteBuffer.wrap(raw) : value;
    }

    /** Binds one value to parameter {@code index}, as the column it is written into expects. */
    void bind(PreparedStatement statement, int index, int ordinal, Object value, JdbcSinkTable.Column column)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, column.sqlType());
            return;
        }
        TypeName type = schema.field(ordinal).type().typeName();
        switch (type) {
            case BOOLEAN -> statement.setBoolean(index, (Boolean) value);
            case INT8 -> statement.setShort(index, (Byte) value);
            case INT16 -> statement.setShort(index, (Short) value);
            case INT32 -> statement.setInt(index, (Integer) value);
            case INT64 -> statement.setLong(index, (Long) value);
            case FLOAT32 -> statement.setFloat(index, (Float) value);
            case FLOAT64 -> statement.setDouble(index, (Double) value);
            case DECIMAL -> statement.setBigDecimal(index, (BigDecimal) value);
            case BYTES -> statement.setBytes(index, (byte[]) value);
            case DATE -> statement.setObject(index, LocalDate.ofEpochDay((Integer) value));
            case TIME -> statement.setObject(index, LocalTime.ofNanoOfDay((Long) value));
            case TIMESTAMP_LTZ -> {
                long nanos = (Long) value;
                Instant instant = Instant.ofEpochSecond(
                        Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
                // A column with a zone takes the instant; one without takes the UTC wall clock --
                // what the engine's timestamps are -- rather than the session's zone, which would
                // shift every value by wherever the database server happens to be.
                if (column.withTimeZone()) {
                    statement.setObject(index, instant.atOffset(ZoneOffset.UTC));
                } else {
                    statement.setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
                }
            }
            default -> statement.setString(index, (String) value);
        }
    }

    /** A batch of changes as bytes, for one row of the staging table. */
    byte[] encode(List<Change> changes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT);
            out.writeInt(schema.fieldCount());
            out.writeInt(changes.size());
            for (Change change : changes) {
                out.writeLong(change.weight());
                for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
                    Object value = change.values()[ordinal];
                    out.writeBoolean(value != null);
                    if (value != null) {
                        writeValue(out, schema.field(ordinal).type().typeName(), value);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The inverse of {@link #encode}; refuses a payload written for another shape. */
    List<Change> decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int format = in.readInt();
            int columns = in.readInt();
            if (format != FORMAT || columns != schema.fieldCount()) {
                throw new PravahaException(
                        JdbcErrors.WRITE_FAILED,
                        "a staged batch is format " + format + " with " + columns + " columns, and this sink reads "
                                + "format " + FORMAT + " with " + schema.fieldCount() + ". It was staged by a sink "
                                + "configured differently under the same transaction.id.");
            }
            int count = in.readInt();
            List<Change> changes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long weight = in.readLong();
                Object[] values = new Object[columns];
                for (int ordinal = 0; ordinal < columns; ordinal++) {
                    if (in.readBoolean()) {
                        values[ordinal] =
                                readValue(in, schema.field(ordinal).type().typeName());
                    }
                }
                changes.add(new Change(values, weight));
            }
            return changes;
        } catch (IOException e) {
            throw new PravahaException(JdbcErrors.WRITE_FAILED, "a staged batch is unreadable: " + e.getMessage(), e);
        }
    }

    private static void writeValue(DataOutputStream out, TypeName type, Object value) throws IOException {
        switch (type) {
            case BOOLEAN -> out.writeBoolean((Boolean) value);
            case INT8 -> out.writeByte((Byte) value);
            case INT16 -> out.writeShort((Short) value);
            case INT32, DATE -> out.writeInt((Integer) value);
            case INT64, TIME, TIMESTAMP_LTZ -> out.writeLong((Long) value);
            case FLOAT32 -> out.writeFloat((Float) value);
            case FLOAT64 -> out.writeDouble((Double) value);
            case DECIMAL -> {
                BigDecimal decimal = (BigDecimal) value;
                byte[] unscaled = decimal.unscaledValue().toByteArray();
                out.writeInt(decimal.scale());
                out.writeInt(unscaled.length);
                out.write(unscaled);
            }
            case BYTES -> {
                byte[] raw = (byte[]) value;
                out.writeInt(raw.length);
                out.write(raw);
            }
            default -> {
                byte[] text = ((String) value).getBytes(StandardCharsets.UTF_8);
                out.writeInt(text.length);
                out.write(text);
            }
        }
    }

    private static Object readValue(DataInputStream in, TypeName type) throws IOException {
        return switch (type) {
            case BOOLEAN -> in.readBoolean();
            case INT8 -> in.readByte();
            case INT16 -> in.readShort();
            case INT32, DATE -> in.readInt();
            case INT64, TIME, TIMESTAMP_LTZ -> in.readLong();
            case FLOAT32 -> in.readFloat();
            case FLOAT64 -> in.readDouble();
            case DECIMAL -> {
                int scale = in.readInt();
                byte[] unscaled = new byte[in.readInt()];
                in.readFully(unscaled);
                yield new BigDecimal(new BigInteger(unscaled), scale);
            }
            case BYTES -> {
                byte[] raw = new byte[in.readInt()];
                in.readFully(raw);
                yield raw;
            }
            default -> {
                byte[] text = new byte[in.readInt()];
                in.readFully(text);
                yield new String(text, StandardCharsets.UTF_8);
            }
        };
    }
}
