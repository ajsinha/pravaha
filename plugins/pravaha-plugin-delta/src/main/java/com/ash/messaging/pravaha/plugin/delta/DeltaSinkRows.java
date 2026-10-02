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
import java.util.ArrayList;
import java.util.List;

import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.StructType;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.Decimals;

/**
 * A Delta sink's rows: read out of the engine's rows, written to and read back from staging, and
 * handed to Delta Kernel as columnar batches.
 *
 * <p>A value is the Java object for its declared type -- {@code Boolean}, {@code Byte}, {@code
 * Short}, {@code Integer}, {@code Long}, {@code Float}, {@code Double}, {@code BigDecimal}, {@code
 * String}, {@code byte[]} -- with the engine's own encodings for time: a {@code DATE} is days since
 * the epoch and a {@code TIMESTAMP} nanoseconds since the epoch, UTC. A staged row is byte for byte
 * the row the engine wrote; the conversion to what Delta stores happens once, at the commit.
 */
final class DeltaSinkRows {

    /** One change: a row's values and its weight, negative for a retraction. */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    record Change(Object[] values, long weight) {}

    private static final int FORMAT = 1;

    private final StreamSchema schema;

    DeltaSinkRows(StreamSchema schema) {
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
     * A decimal at the scale the row was <em>written</em> with, not the declared one: the registry
     * compares a sink's schema with the query's output by type name and not by scale, so a sink
     * declared {@code DECIMAL(10,2)} can be handed a {@code DECIMAL(38,9)} column, and reading the
     * unscaled value at the wrong scale multiplies every amount by a power of ten.
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
     * Copies a byte column. The generic row cannot expose its bytes without a copy into the row's
     * own memory, so this needs the engine's binary row, which every row a delivery writes is.
     */
    private static byte[] bytesOf(RowView row, int ordinal) {
        if (!(row instanceof BinaryRowView binary)) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED,
                    "cannot read a BYTES column from a " + row.getClass().getSimpleName()
                            + "; this sink writes the engine's binary rows");
        }
        MutableSlice slice = binary.getBytes(ordinal, new MutableSlice());
        byte[] copy = new byte[slice.length()];
        binary.region().getBytes(slice.offset(), copy, 0, slice.length());
        return copy;
    }

    /** A batch of changes as bytes, for one staging file. */
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
                        DeltaErrors.SINK_WRITE_FAILED,
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
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED, "a staged batch is unreadable: " + e.getMessage(), e);
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

    // ---- keys ----------------------------------------------------------------------------

    /**
     * A change's key, as a list that compares by value.
     *
     * <p>Canonicalised so that a key read back out of the table equals the key of the change that
     * would replace it: a {@code byte[]} by its content, a {@code TIMESTAMP} in <em>microseconds</em>
     * -- what Delta stores -- and a {@code DECIMAL} at its declared scale, since {@code
     * BigDecimal.equals} counts trailing zeros.
     */
    List<Object> keyOf(Object[] values, int[] keyOrdinals) {
        List<Object> key = new ArrayList<>(keyOrdinals.length);
        for (int ordinal : keyOrdinals) {
            Object value = values[ordinal];
            if (value == null) {
                throw new PravahaException(
                        DeltaErrors.SINK_WRITE_FAILED,
                        "key column '" + schema.field(ordinal).name()
                                + "' is null in a row for this sink; a record cannot be keyed by nothing");
            }
            key.add(
                    switch (schema.field(ordinal).type().typeName()) {
                        case TIMESTAMP_LTZ -> microsOf(schema.field(ordinal).name(), (Long) value);
                        case BYTES -> ByteBuffer.wrap((byte[]) value);
                        case DECIMAL ->
                            ((BigDecimal) value)
                                    .setScale(
                                            ((DecimalType) schema.field(ordinal).type()).scale(),
                                            java.math.RoundingMode.UNNECESSARY);
                        default -> value;
                    });
        }
        return key;
    }

    /** The same key, read out of a Delta batch's row -- values as Delta already stores them. */
    static List<Object> keyOf(ColumnarBatch batch, int rowId, int[] keyOrdinals, List<DataType> types) {
        List<Object> key = new ArrayList<>(keyOrdinals.length);
        for (int ordinal : keyOrdinals) {
            ColumnVector vector = batch.getColumnVector(ordinal);
            DataType type = types.get(ordinal);
            key.add(
                    switch (type) {
                        case io.delta.kernel.types.BooleanType ignored -> vector.getBoolean(rowId);
                        case io.delta.kernel.types.ByteType ignored -> vector.getByte(rowId);
                        case io.delta.kernel.types.ShortType ignored -> vector.getShort(rowId);
                        case io.delta.kernel.types.IntegerType ignored -> vector.getInt(rowId);
                        case io.delta.kernel.types.DateType ignored -> vector.getInt(rowId);
                        case io.delta.kernel.types.LongType ignored -> vector.getLong(rowId);
                        case io.delta.kernel.types.TimestampType ignored -> vector.getLong(rowId);
                        case io.delta.kernel.types.BinaryType ignored -> ByteBuffer.wrap(vector.getBinary(rowId));
                        case io.delta.kernel.types.DecimalType decimal ->
                            vector.getDecimal(rowId).setScale(decimal.getScale(), java.math.RoundingMode.UNNECESSARY);
                        default -> vector.getString(rowId);
                    });
        }
        return key;
    }

    // ---- what Delta is handed ------------------------------------------------------------

    /**
     * The changes as one Delta columnar batch, in the table's own encodings.
     *
     * @param changelog true to append the {@code _op} and {@code _weight} columns the changelog mode
     *     declares; false for the declared columns alone
     */
    ColumnarBatch batchOf(List<Change> changes, StructType deltaSchema, boolean changelog) {
        return new ChangeBatch(changes, deltaSchema, schema, changelog);
    }

    /** A selection vector: which rows of a batch survive a rewrite. */
    static ColumnVector selection(boolean[] keep) {
        return new ColumnVector() {
            @Override
            public DataType getDataType() {
                return BooleanType.BOOLEAN;
            }

            @Override
            public int getSize() {
                return keep.length;
            }

            @Override
            public void close() {}

            @Override
            public boolean isNullAt(int rowId) {
                return false;
            }

            @Override
            public boolean getBoolean(int rowId) {
                return keep[rowId];
            }
        };
    }

    /**
     * Delta's {@code TIMESTAMP} is microseconds since the epoch and the engine's is nanoseconds, so
     * a value carrying sub-microsecond precision cannot be written without losing it.
     *
     * <p>It is refused rather than rounded. A rounded timestamp is a value nobody can tell from a
     * true one: the table would read back plausible and slightly wrong for ever, and a key column
     * rounded this way would stop matching the row it was meant to replace. The house rule is that
     * where the engine could accept something doubtful or refuse it, it refuses.
     */
    static long microsOf(String column, long nanos) {
        if (nanos % 1_000L != 0L) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED,
                    "column '" + column + "' holds the timestamp " + nanos + "ns, which Delta cannot store: its "
                            + "TIMESTAMP is microseconds and this value is not a whole number of them. Rounding it "
                            + "would put a value in the table that reads as true and is not. Truncate the column in "
                            + "the query, or give the stream an event time the source records in microseconds.");
        }
        return nanos / 1_000L;
    }

    /**
     * The engine's values as Delta's column vectors. One vector per column, reading straight out of
     * the change list, because a batch exists only for the length of one commit.
     */
    private record ChangeBatch(List<Change> changes, StructType deltaSchema, StreamSchema schema, boolean changelog)
            implements ColumnarBatch {

        @Override
        public StructType getSchema() {
            return deltaSchema;
        }

        @Override
        public int getSize() {
            return changes.size();
        }

        @Override
        public ColumnVector getColumnVector(int ordinal) {
            if (changelog && ordinal >= schema.fieldCount()) {
                boolean op = ordinal == schema.fieldCount();
                return new ChangeColumn(changes, null, deltaSchema.at(ordinal).getDataType(), -1, op);
            }
            return new ChangeColumn(
                    changes,
                    schema.field(ordinal).name(),
                    deltaSchema.at(ordinal).getDataType(),
                    ordinal,
                    false);
        }

        /** Kernel's write path drops a partition column from the batch before it writes Parquet. */
        @Override
        public ColumnarBatch withDeletedColumnAt(int ordinal) {
            return new WithoutColumn(this, ordinal);
        }
    }

    /**
     * A batch with one column left out, which is what a partition column is to the Parquet file: its
     * value is in the directory name and the log, not in the file.
     */
    private record WithoutColumn(ColumnarBatch base, int dropped) implements ColumnarBatch {

        @Override
        public StructType getSchema() {
            StructType schema = new StructType();
            StructType all = base.getSchema();
            for (int i = 0; i < all.length(); i++) {
                if (i != dropped) {
                    schema = schema.add(all.at(i));
                }
            }
            return schema;
        }

        @Override
        public ColumnVector getColumnVector(int ordinal) {
            return base.getColumnVector(ordinal < dropped ? ordinal : ordinal + 1);
        }

        @Override
        public int getSize() {
            return base.getSize();
        }

        @Override
        public ColumnarBatch withDeletedColumnAt(int ordinal) {
            return new WithoutColumn(this, ordinal);
        }
    }

    /**
     * One column of {@link ChangeBatch}: a declared column by ordinal, or -- with {@code ordinal}
     * {@code -1} -- changelog mode's {@code _op} (when {@code opColumn}) or {@code _weight}.
     */
    private record ChangeColumn(List<Change> changes, String name, DataType type, int ordinal, boolean opColumn)
            implements ColumnVector {

        @Override
        public DataType getDataType() {
            return type;
        }

        @Override
        public int getSize() {
            return changes.size();
        }

        @Override
        public void close() {}

        @Override
        public boolean isNullAt(int rowId) {
            return ordinal >= 0 && changes.get(rowId).values()[ordinal] == null;
        }

        private Object value(int rowId) {
            return changes.get(rowId).values()[ordinal];
        }

        @Override
        public boolean getBoolean(int rowId) {
            return (Boolean) value(rowId);
        }

        @Override
        public byte getByte(int rowId) {
            return (Byte) value(rowId);
        }

        @Override
        public short getShort(int rowId) {
            return (Short) value(rowId);
        }

        @Override
        public int getInt(int rowId) {
            return (Integer) value(rowId);
        }

        @Override
        public long getLong(int rowId) {
            if (ordinal < 0) {
                return changes.get(rowId).weight();
            }
            long raw = (Long) value(rowId);
            return type instanceof io.delta.kernel.types.TimestampType ? microsOf(name, raw) : raw;
        }

        @Override
        public float getFloat(int rowId) {
            return (Float) value(rowId);
        }

        @Override
        public double getDouble(int rowId) {
            return (Double) value(rowId);
        }

        @Override
        public byte[] getBinary(int rowId) {
            return (byte[]) value(rowId);
        }

        @Override
        public String getString(int rowId) {
            if (ordinal < 0) {
                return opColumn ? (changes.get(rowId).weight() < 0 ? "delete" : "insert") : null;
            }
            return (String) value(rowId);
        }

        @Override
        public BigDecimal getDecimal(int rowId) {
            return (BigDecimal) value(rowId);
        }
    }
}
