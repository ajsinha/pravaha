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
package com.ash.messaging.pravaha.registry;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.row.Decimals;

/**
 * A view's row values, written into a binary row and into a checkpoint (ADR-056).
 *
 * <p>A view holds each value as its own class -- a {@code Long}, a {@code BigDecimal}, a {@code
 * byte[]} -- and a query reading the view is fed binary rows, so this is where one becomes the
 * other. Each value is checkpointed as its own class, for the reason {@code ServedView}'s snapshot
 * gives: an {@code Integer} that came back a {@code Long} is not equal to the one the view goes on
 * holding, and a retraction would miss the row it withdraws.
 */
final class ViewRowValues {

    private static final byte NULL = 0;
    private static final byte BOOLEAN = 1;
    private static final byte BYTE = 2;
    private static final byte SHORT = 3;
    private static final byte INT = 4;
    private static final byte LONG = 5;
    private static final byte FLOAT = 6;
    private static final byte DOUBLE = 7;
    private static final byte DECIMAL = 8;
    private static final byte STRING = 9;
    private static final byte BYTES = 10;

    /** The column types a view read as an input can carry into a row. */
    static final java.util.Set<TypeName> CARRIED =
            java.util.EnumSet.complementOf(java.util.EnumSet.of(TypeName.ARRAY, TypeName.MAP, TypeName.ROW));

    private ViewRowValues() {}

    static void write(RowWriter writer, StreamSchema schema, int ordinal, Object value) {
        if (value == null) {
            writer.setNull(ordinal);
            return;
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
                int scale = ((DecimalType) schema.field(ordinal).type()).scale();
                BigDecimal decimal = (BigDecimal) value;
                writer.setDecimal(ordinal, Decimals.high(decimal, scale), Decimals.low(decimal, scale));
            }
            case STRING -> writer.setString(ordinal, String.valueOf(value));
            case BYTES -> writer.setBytes(ordinal, (byte[]) value);
            default ->
                throw new PravahaException(
                        RegistryErrors.CHAIN_UNSUPPORTED,
                        "column '" + schema.field(ordinal).name() + "' is "
                                + schema.field(ordinal).type().typeName()
                                + ", which a query over a view cannot be fed (ADR-056)");
        }
    }

    static void writeRow(DataOutput out, Object[] row) throws IOException {
        for (Object value : row) {
            switch (value) {
                case null -> out.writeByte(NULL);
                case Boolean v -> {
                    out.writeByte(BOOLEAN);
                    out.writeBoolean(v);
                }
                case Byte v -> {
                    out.writeByte(BYTE);
                    out.writeByte(v);
                }
                case Short v -> {
                    out.writeByte(SHORT);
                    out.writeShort(v);
                }
                case Integer v -> {
                    out.writeByte(INT);
                    out.writeInt(v);
                }
                case Long v -> {
                    out.writeByte(LONG);
                    out.writeLong(v);
                }
                case Float v -> {
                    out.writeByte(FLOAT);
                    out.writeInt(Float.floatToRawIntBits(v));
                }
                case Double v -> {
                    out.writeByte(DOUBLE);
                    out.writeLong(Double.doubleToRawLongBits(v));
                }
                case BigDecimal v -> {
                    out.writeByte(DECIMAL);
                    out.writeInt(v.scale());
                    writeBytes(out, v.unscaledValue().toByteArray());
                }
                case String v -> {
                    out.writeByte(STRING);
                    writeBytes(out, v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                case byte[] v -> {
                    out.writeByte(BYTES);
                    writeBytes(out, v);
                }
                default ->
                    throw new IOException(
                            "a view value of class " + value.getClass().getName());
            }
        }
    }

    static Object[] readRow(DataInput in, int columns) throws IOException {
        Object[] row = new Object[columns];
        for (int i = 0; i < columns; i++) {
            byte tag = in.readByte();
            row[i] = switch (tag) {
                case NULL -> null;
                case BOOLEAN -> in.readBoolean();
                case BYTE -> in.readByte();
                case SHORT -> in.readShort();
                case INT -> in.readInt();
                case LONG -> in.readLong();
                case FLOAT -> Float.intBitsToFloat(in.readInt());
                case DOUBLE -> Double.longBitsToDouble(in.readLong());
                case DECIMAL -> {
                    int scale = in.readInt();
                    yield new BigDecimal(new BigInteger(readBytes(in)), scale);
                }
                case STRING -> new String(readBytes(in), java.nio.charset.StandardCharsets.UTF_8);
                case BYTES -> readBytes(in);
                default -> throw new IOException("unknown value tag " + tag);
            };
        }
        return row;
    }

    private static void writeBytes(DataOutput out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("a value of " + length + " bytes");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
