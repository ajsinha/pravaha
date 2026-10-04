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
package com.ash.messaging.pravaha.runtime.exec;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * Rows held by an operator past the batch that delivered them, as plain Java values.
 *
 * <p>An operator that must remember rows -- a top-N holding every row of a partition, a session
 * window holding the rows it has not yet closed over -- cannot keep a {@link RowView}: the view is a
 * flyweight over an arena that is reset between batches. So the values are copied out, one Java
 * object per column: a {@code Long} for every integer and time type, a {@code Double} or {@code
 * Float}, a {@code long[]{high, low}} for a decimal, a {@code String}, a {@code ByteBuffer} for bytes
 * (a {@code byte[]} would compare by identity), and {@code null}. This allocates, and is used only
 * by operators whose state is rows.
 *
 * <p>{@link #TOTAL_ORDER} orders two such rows column by column, nulls first, so that a tie on the
 * keys a query orders by is broken the same way on every run.
 */
final class MaterializedRows {

    private MaterializedRows() {}

    /** Whether rows with a column of this type can be held. */
    static boolean holds(TypeName type) {
        return switch (type) {
            case BOOLEAN,
                    INT8,
                    INT16,
                    INT32,
                    DATE,
                    INT64,
                    TIME,
                    TIMESTAMP_LTZ,
                    FLOAT32,
                    FLOAT64,
                    DECIMAL,
                    STRING,
                    BYTES -> true;
            default -> false;
        };
    }

    /** Copies every column of {@code row} out. */
    static Object[] read(RowView row, StreamSchema schema) {
        Object[] values = new Object[schema.fieldCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = read(row, i, schema.field(i).type().typeName());
        }
        return values;
    }

    private static Object read(RowView row, int ordinal, TypeName type) {
        if (row.isNull(ordinal)) {
            return null;
        }
        return switch (type) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> (long) row.getByte(ordinal);
            case INT16 -> (long) row.getShort(ordinal);
            case INT32, DATE -> (long) row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            case DECIMAL -> new long[] {row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal)};
            case STRING -> row.getString(ordinal);
            case BYTES -> {
                MutableSlice slice = new MutableSlice();
                row.getBytes(ordinal, slice);
                byte[] bytes = new byte[slice.length()];
                if (bytes.length > 0 && row instanceof BinaryRowView binary) {
                    binary.region().getBytes(slice.offset(), bytes, 0, bytes.length);
                }
                yield ByteBuffer.wrap(bytes);
            }
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "a " + type + " column cannot be held by an operator that keeps rows");
        };
    }

    /** Writes {@code values} into columns {@code 0..values.length-1} of {@code writer}. */
    static void write(RowWriter writer, StreamSchema schema, Object[] values) {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value == null) {
                writer.setNull(i);
                continue;
            }
            switch (schema.field(i).type().typeName()) {
                case BOOLEAN -> writer.setBoolean(i, (Boolean) value);
                case INT8 -> writer.setByte(i, ((Long) value).byteValue());
                case INT16 -> writer.setShort(i, ((Long) value).shortValue());
                case INT32, DATE -> writer.setInt(i, ((Long) value).intValue());
                case FLOAT32 -> writer.setFloat(i, (Float) value);
                case FLOAT64 -> writer.setDouble(i, (Double) value);
                case DECIMAL -> writer.setDecimal(i, ((long[]) value)[0], ((long[]) value)[1]);
                case STRING -> writer.setString(i, (String) value);
                case BYTES -> {
                    ByteBuffer buffer = ((ByteBuffer) value).duplicate();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    writer.setBytes(i, bytes);
                }
                default -> writer.setLong(i, (Long) value);
            }
        }
    }

    /** The variable-width bytes a row of these values needs, for sizing an arena allocation. */
    static int payloadBytes(Object[] values) {
        int bytes = 0;
        for (Object value : values) {
            if (value instanceof String text) {
                bytes += text.length() * 3;
            } else if (value instanceof ByteBuffer buffer) {
                bytes += buffer.remaining();
            }
        }
        return bytes + 64;
    }

    /** Compares two values of one column, nulls first, decimals as signed 128-bit integers. */
    @SuppressWarnings({"unchecked", "rawtypes", "ReferenceEquality"
    }) // identity is the question here: a sentinel, a thread or the very object
    static int compareValues(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof long[] l && right instanceof long[] r) {
            int high = Long.compare(l[0], r[0]);
            return high != 0 ? high : Long.compareUnsigned(l[1], r[1]);
        }
        return ((Comparable) left).compareTo(right);
    }

    /** Every column in order: the order that makes a top-N's numbering of ties deterministic. */
    static final Comparator<Object[]> TOTAL_ORDER = (left, right) -> {
        for (int i = 0; i < left.length; i++) {
            int c = compareValues(left[i], right[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    };

    /** Writes one held row to a checkpoint: a presence flag per column, then its value. */
    static void writeTo(java.io.DataOutputStream out, StreamSchema schema, Object[] values) throws java.io.IOException {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            out.writeBoolean(value != null);
            if (value == null) {
                continue;
            }
            switch (schema.field(i).type().typeName()) {
                case BOOLEAN -> out.writeBoolean((Boolean) value);
                case FLOAT32 -> out.writeFloat((Float) value);
                case FLOAT64 -> out.writeDouble((Double) value);
                case DECIMAL -> {
                    out.writeLong(((long[]) value)[0]);
                    out.writeLong(((long[]) value)[1]);
                }
                case STRING -> {
                    byte[] utf8 = ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeInt(utf8.length);
                    out.write(utf8);
                }
                case BYTES -> {
                    ByteBuffer buffer = ((ByteBuffer) value).duplicate();
                    out.writeInt(buffer.remaining());
                    while (buffer.hasRemaining()) {
                        out.writeByte(buffer.get());
                    }
                }
                default -> out.writeLong((Long) value);
            }
        }
    }

    /** Reads one row written by {@link #writeTo}. */
    static Object[] readFrom(java.io.DataInputStream in, StreamSchema schema) throws java.io.IOException {
        Object[] values = new Object[schema.fieldCount()];
        for (int i = 0; i < values.length; i++) {
            if (!in.readBoolean()) {
                continue;
            }
            TypeName type = schema.field(i).type().typeName();
            values[i] = switch (type) {
                case BOOLEAN -> in.readBoolean();
                case FLOAT32 -> in.readFloat();
                case FLOAT64 -> in.readDouble();
                case DECIMAL -> new long[] {in.readLong(), in.readLong()};
                case STRING, BYTES -> {
                    byte[] bytes = new byte[in.readInt()];
                    in.readFully(bytes);
                    yield type == TypeName.STRING
                            ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            : ByteBuffer.wrap(bytes);
                }
                default -> in.readLong();
            };
        }
        return values;
    }

    /** The values at {@code ordinals}, as a key with value equality. */
    static java.util.List<Object> key(Object[] values, java.util.List<Integer> ordinals) {
        Object[] key = new Object[ordinals.size()];
        for (int i = 0; i < key.length; i++) {
            Object value = values[ordinals.get(i)];
            key[i] = value instanceof long[] decimal ? Arrays.asList(decimal[0], decimal[1]) : value;
        }
        return Arrays.asList(key);
    }
}
