/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.common.row;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * A schema paired with one row of values, used by the round-trip properties.
 *
 * <p>Values are boxed here because this is test scaffolding, not the engine. The whole point of the
 * binary layout is that the engine never does this.
 *
 * @param schema the schema the values conform to
 * @param values one entry per field; {@code null} means the field is null
 */
record RowSample(StreamSchema schema, List<Object> values) {

    /** Writes this sample into {@code region} at {@code offset} and returns the encoded row size. */
    int write(MemoryRegion region, int offset, RowLayout layout, BinaryRowWriter writer) {
        writer.begin(region, offset);
        writer.weight(1L).eventTimestampNanos(1_700_000_000_000_000_000L).sequence(42L);

        // Fixed-width fields first, then variable-width in ascending ordinal order -- the writer
        // requires the latter because payload is appended as it arrives.
        for (int i = 0; i < schema.fieldCount(); i++) {
            if (!layout.isVariableWidth(i)) {
                writeField(writer, i, values.get(i));
            }
        }
        for (int i = 0; i < schema.fieldCount(); i++) {
            if (layout.isVariableWidth(i)) {
                writeField(writer, i, values.get(i));
            }
        }
        writer.commit();
        return writer.sizeSoFar();
    }

    private void writeField(BinaryRowWriter w, int ordinal, Object value) {
        if (value == null) {
            w.setNull(ordinal);
            return;
        }
        switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> w.setBoolean(ordinal, (Boolean) value);
            case INT8 -> w.setByte(ordinal, (Byte) value);
            case INT16 -> w.setShort(ordinal, (Short) value);
            case INT32, DATE -> w.setInt(ordinal, (Integer) value);
            case INT64, TIME, TIMESTAMP_LTZ -> w.setLong(ordinal, (Long) value);
            case FLOAT32 -> w.setFloat(ordinal, (Float) value);
            case FLOAT64 -> w.setDouble(ordinal, (Double) value);
            case DECIMAL -> {
                long[] limbs = (long[]) value;
                w.setDecimal(ordinal, limbs[0], limbs[1]);
            }
            case STRING -> w.setString(ordinal, (String) value);
            case BYTES, ARRAY, MAP, ROW -> w.setBytes(ordinal, (byte[]) value);
        }
    }

    /** Reads the row back and asserts every field matches what was written. */
    void assertMatches(BinaryRowView view, MutableSlice slice) {
        for (int i = 0; i < schema.fieldCount(); i++) {
            Object expected = values.get(i);
            if (expected == null) {
                if (!view.isNull(i)) {
                    throw new AssertionError("field " + i + " ('"
                            + schema.field(i).name() + "') should be null but reads as " + read(view, i, slice));
                }
                continue;
            }
            if (view.isNull(i)) {
                throw new AssertionError(
                        "field " + i + " ('" + schema.field(i).name() + "') should be " + expected + " but reads null");
            }
            Object actual = read(view, i, slice);
            if (!equal(expected, actual)) {
                throw new AssertionError("field " + i + " ('" + schema.field(i).name() + "', "
                        + schema.field(i).type().sqlName() + ") wrote " + render(expected) + " but read "
                        + render(actual));
            }
        }
    }

    private Object read(BinaryRowView view, int ordinal, MutableSlice slice) {
        PravahaType type = schema.field(ordinal).type();
        return switch (type.typeName()) {
            case BOOLEAN -> view.getBoolean(ordinal);
            case INT8 -> view.getByte(ordinal);
            case INT16 -> view.getShort(ordinal);
            case INT32, DATE -> view.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> view.getLong(ordinal);
            case FLOAT32 -> view.getFloat(ordinal);
            case FLOAT64 -> view.getDouble(ordinal);
            case DECIMAL -> new long[] {view.getDecimalHigh(ordinal), view.getDecimalLow(ordinal)};
            case STRING -> view.getString(ordinal);
            case BYTES, ARRAY, MAP, ROW -> readBytes(view, ordinal, slice);
        };
    }

    private static byte[] readBytes(BinaryRowView view, int ordinal, MutableSlice slice) {
        view.getBytes(ordinal, slice);
        byte[] out = new byte[slice.length()];
        view.region().getBytes(slice.offset(), out, 0, out.length);
        return out;
    }

    private static boolean equal(Object expected, Object actual) {
        if (expected instanceof byte[] e && actual instanceof byte[] a) {
            return Arrays.equals(e, a);
        }
        if (expected instanceof long[] e && actual instanceof long[] a) {
            return Arrays.equals(e, a);
        }
        return expected.equals(actual);
    }

    private static String render(Object value) {
        if (value instanceof byte[] b) {
            return "bytes" + Arrays.toString(b);
        }
        if (value instanceof long[] l) {
            return "decimal" + Arrays.toString(l);
        }
        if (value instanceof String s) {
            return '\'' + s + "' (" + s.getBytes(StandardCharsets.UTF_8).length + "B utf8)";
        }
        return String.valueOf(value);
    }
}
