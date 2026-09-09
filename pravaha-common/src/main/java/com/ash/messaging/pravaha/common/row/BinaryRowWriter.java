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

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * Builds a row directly in a {@link MemoryRegion}, with no intermediate object.
 *
 * <p>The usage is always begin, write, commit. {@link #abort()} discards a partially built row and
 * gives back its space, which is what a source plugin does when it meets a malformed record --
 * one bad record must not cost the batch.
 *
 * <p>Fixed-width fields may be written in any order. Variable-width fields must be written in
 * ascending ordinal order, because their payload is appended as it arrives; writing them out of
 * order is rejected rather than silently producing a corrupt row.
 *
 * <p>Every NOT NULL field must be written before {@link #commit()}. That check is the reason a
 * missing field surfaces at the source plugin that forgot it, rather than as a mystery zero
 * several operators downstream.
 */
public final class BinaryRowWriter implements RowWriter {

    private final RowLayout layout;
    private final long requiredMask;

    private MemoryRegion region;
    private int rowOffset;
    private int payloadCursor;
    private int lastVariableOrdinal;
    private long writtenMask;
    private boolean open;

    public BinaryRowWriter(RowLayout layout) {
        if (layout.fieldCount() > Long.SIZE) {
            throw new IllegalArgumentException(
                    "BinaryRowWriter tracks written fields in a long bitmask and so supports at most "
                            + Long.SIZE + " fields; " + layout.schema().name() + " has "
                            + layout.fieldCount());
        }
        this.layout = layout;
        long mask = 0L;
        for (int i = 0; i < layout.fieldCount(); i++) {
            if (!layout.schema().field(i).type().nullable()) {
                mask |= 1L << i;
            }
        }
        this.requiredMask = mask;
    }

    /**
     * Starts a row at {@code offset} in {@code target}.
     *
     * <p>The caller owns placement -- typically an arena bump pointer -- because only it knows how
     * much room the row will need once its variable-width payload is appended.
     */
    public BinaryRowWriter begin(MemoryRegion target, int offset) {
        if (open) {
            throw new IllegalStateException("a row is already open; commit or abort it first");
        }
        this.region = target;
        this.rowOffset = offset;
        this.payloadCursor = layout.fixedEnd();
        this.lastVariableOrdinal = -1;
        this.writtenMask = 0L;
        this.open = true;
        // Clearing the header and null bitmap is what lets a caller reuse arena space safely;
        // a stale null bit from a previous row would read as a null field in this one.
        region.setMemory(offset, layout.fixedEnd(), (byte) 0);
        region.putInt(offset + RowLayout.OFFSET_SCHEMA_ID, layout.schema().version());
        return this;
    }

    @Override
    public StreamSchema schema() {
        return layout.schema();
    }

    /** Bytes written so far, including any variable-width payload. */
    public int sizeSoFar() {
        return payloadCursor;
    }

    private void checkOpen() {
        if (!open) {
            throw new IllegalStateException("no row is open; call begin() first");
        }
    }

    private void markWritten(int ordinal) {
        writtenMask |= 1L << ordinal;
    }

    @Override
    public RowWriter setNull(int ordinal) {
        checkOpen();
        if (!layout.schema().field(ordinal).type().nullable()) {
            throw new IllegalArgumentException("field " + ordinal + " ('"
                    + layout.schema().field(ordinal).name() + "') is NOT NULL and cannot be set null");
        }
        int byteOffset = rowOffset + layout.nullByteOffset(ordinal);
        region.putByte(byteOffset, (byte) (region.getByte(byteOffset) | layout.nullBitMask(ordinal)));
        if (layout.isVariableWidth(ordinal)) {
            // A null var-len field still needs a well-formed empty pointer, so a reader that
            // ignores the null bit reads an empty slice rather than garbage.
            int slot = rowOffset + layout.offsetOf(ordinal);
            region.putInt(slot, payloadCursor);
            region.putInt(slot + 4, 0);
            lastVariableOrdinal = ordinal;
        }
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setBoolean(int ordinal, boolean value) {
        checkOpen();
        layout.checkType(ordinal, TypeName.BOOLEAN);
        region.putBoolean(rowOffset + layout.offsetOf(ordinal), value);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setByte(int ordinal, byte value) {
        checkOpen();
        layout.checkType(ordinal, TypeName.INT8);
        region.putByte(rowOffset + layout.offsetOf(ordinal), value);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setShort(int ordinal, short value) {
        checkOpen();
        layout.checkType(ordinal, TypeName.INT16);
        region.putShort(rowOffset + layout.offsetOf(ordinal), value);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setInt(int ordinal, int value) {
        checkOpen();
        TypeName actual = layout.schema().field(ordinal).type().typeName();
        if (actual != TypeName.INT32 && actual != TypeName.DATE) {
            layout.checkType(ordinal, TypeName.INT32);
        }
        region.putInt(rowOffset + layout.offsetOf(ordinal), value);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setLong(int ordinal, long value) {
        checkOpen();
        TypeName actual = layout.schema().field(ordinal).type().typeName();
        if (actual != TypeName.INT64 && actual != TypeName.TIME && actual != TypeName.TIMESTAMP_LTZ) {
            layout.checkType(ordinal, TypeName.INT64);
        }
        region.putLong(rowOffset + layout.offsetOf(ordinal), value);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setFloat(int ordinal, float value) {
        checkOpen();
        layout.checkType(ordinal, TypeName.FLOAT32);
        region.putFloat(rowOffset + layout.offsetOf(ordinal), value);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setDouble(int ordinal, double value) {
        checkOpen();
        layout.checkType(ordinal, TypeName.FLOAT64);
        region.putDouble(rowOffset + layout.offsetOf(ordinal), value);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setDecimal(int ordinal, long high, long low) {
        checkOpen();
        layout.checkType(ordinal, TypeName.DECIMAL);
        int at = rowOffset + layout.offsetOf(ordinal);
        region.putLong(at, high);
        region.putLong(at + 8, low);
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setBytes(int ordinal, byte[] value) {
        return setBytes(ordinal, value, 0, value.length);
    }

    /** Appends {@code length} bytes of {@code value} as this field's payload. */
    public RowWriter setBytes(int ordinal, byte[] value, int sourceOffset, int length) {
        checkOpen();
        if (!layout.isVariableWidth(ordinal)) {
            throw new IllegalArgumentException("field " + ordinal + " ('"
                    + layout.schema().field(ordinal).name() + "') is fixed-width; use the typed setter");
        }
        if (ordinal <= lastVariableOrdinal) {
            throw new IllegalArgumentException(
                    "variable-width fields must be written in ascending ordinal order; field " + ordinal
                            + " comes after field " + lastVariableOrdinal + ", which was already written");
        }
        int slot = rowOffset + layout.offsetOf(ordinal);
        region.putInt(slot, payloadCursor);
        region.putInt(slot + 4, length);
        region.putBytes(rowOffset + payloadCursor, value, sourceOffset, length);
        payloadCursor += length;
        lastVariableOrdinal = ordinal;
        markWritten(ordinal);
        return this;
    }

    @Override
    public RowWriter setString(int ordinal, String value) {
        return setBytes(ordinal, value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public RowWriter weight(long weight) {
        checkOpen();
        region.putLong(rowOffset + RowLayout.OFFSET_WEIGHT, weight);
        return this;
    }

    @Override
    public RowWriter eventTimestampNanos(long nanos) {
        checkOpen();
        region.putLong(rowOffset + RowLayout.OFFSET_EVENT_TIME, nanos);
        return this;
    }

    @Override
    public RowWriter sequence(long sequence) {
        checkOpen();
        region.putLong(rowOffset + RowLayout.OFFSET_SEQUENCE, sequence);
        return this;
    }

    @Override
    public int commit() {
        checkOpen();
        long missing = requiredMask & ~writtenMask;
        if (missing != 0L) {
            throw new IllegalStateException("NOT NULL field(s) never written: " + describeMissing(missing)
                    + " in schema " + layout.schema().name());
        }
        region.putInt(rowOffset + RowLayout.OFFSET_TOTAL_LENGTH, payloadCursor);
        open = false;
        return rowOffset;
    }

    @Override
    public void abort() {
        open = false;
        payloadCursor = 0;
    }

    private String describeMissing(long missing) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < layout.fieldCount(); i++) {
            if ((missing & (1L << i)) != 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append('\'').append(layout.schema().field(i).name()).append('\'');
            }
        }
        return sb.toString();
    }
}
