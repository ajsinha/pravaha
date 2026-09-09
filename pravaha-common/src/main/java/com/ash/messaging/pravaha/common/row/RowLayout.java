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

import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * The physical byte layout of one row, computed once per schema and then treated as constants.
 *
 * <p>Everything expensive about addressing a field happens here, at registration time. At runtime a
 * field read is a single load at a compile-time constant offset -- which is the whole difference
 * between roughly 500 and roughly 5000 cycles per record (design section 29.1).
 *
 * <pre>
 * ┌────────────┬──────────────┬──────────────┬────────────────────┐
 * │ HEADER     │ NULL BITMAP  │ FIXED REGION │ VARIABLE PAYLOAD   │
 * │ 32 B       │ ceil(n/8) B, │ one slot per │ appended in        │
 * │            │ padded to 8  │ field        │ ordinal order      │
 * └────────────┴──────────────┴──────────────┴────────────────────┘
 *
 * HEADER
 *   +0   weight        int64   Z-set weight; sign carries insert/retract (design section 9.2)
 *   +8   eventTime     int64   nanoseconds since epoch, UTC
 *   +16  sequence      int64   monotonic per partition; ordering, dedupe, checkpoint offsets
 *   +24  schemaId      int32   guards against decoding a row against the wrong schema version
 *   +28  totalLength   int32   lets a row be copied without consulting its schema
 * </pre>
 *
 * <p>Fixed-width fields occupy their natural width, aligned to it. Variable-width fields occupy an
 * 8-byte slot holding {@code (int offset, int length)} pointing into the payload region; offsets are
 * relative to the start of the row, so a row can be copied bytewise to any address and stay valid.
 * That property is what makes checkpointing and cross-node shipping a memcpy rather than a
 * re-encode.
 */
public final class RowLayout {

    /** Fixed header size in bytes. Chosen so the null bitmap starts 8-byte aligned. */
    public static final int HEADER_BYTES = 32;

    public static final int OFFSET_WEIGHT = 0;
    public static final int OFFSET_EVENT_TIME = 8;
    public static final int OFFSET_SEQUENCE = 16;
    public static final int OFFSET_SCHEMA_ID = 24;
    public static final int OFFSET_TOTAL_LENGTH = 28;

    /** Width of a variable-width field's pointer slot: {@code int offset} plus {@code int length}. */
    public static final int VAR_SLOT_BYTES = 8;

    private final StreamSchema schema;
    private final int[] fieldOffsets;
    private final boolean[] variableWidth;
    private final int nullBitmapOffset;
    private final int nullBitmapBytes;
    private final int fixedRegionOffset;
    private final int fixedEnd;
    private final int variableFieldCount;

    private RowLayout(
            StreamSchema schema,
            int[] fieldOffsets,
            boolean[] variableWidth,
            int nullBitmapOffset,
            int nullBitmapBytes,
            int fixedRegionOffset,
            int fixedEnd,
            int variableFieldCount) {
        this.schema = schema;
        this.fieldOffsets = fieldOffsets;
        this.variableWidth = variableWidth;
        this.nullBitmapOffset = nullBitmapOffset;
        this.nullBitmapBytes = nullBitmapBytes;
        this.fixedRegionOffset = fixedRegionOffset;
        this.fixedEnd = fixedEnd;
        this.variableFieldCount = variableFieldCount;
    }

    /** Computes the layout for a schema. Do this once, at query registration. */
    public static RowLayout of(StreamSchema schema) {
        int n = schema.fieldCount();
        if (n == 0) {
            throw new IllegalArgumentException("cannot lay out a row with no fields: " + schema.name());
        }

        int nullBitmapOffset = HEADER_BYTES;
        int nullBitmapBytes = align8((n + 7) / 8);
        int fixedRegionOffset = nullBitmapOffset + nullBitmapBytes;

        int[] offsets = new int[n];
        boolean[] variable = new boolean[n];
        int variableCount = 0;
        int cursor = fixedRegionOffset;

        for (int i = 0; i < n; i++) {
            PravahaType type = schema.field(i).type();
            int width;
            if (type.isFixedWidth()) {
                width = type.fixedWidth();
            } else {
                width = VAR_SLOT_BYTES;
                variable[i] = true;
                variableCount++;
            }
            // Align each field to its own width so no read ever straddles a word boundary.
            // Misaligned access is legal on x86 but costs a penalty on a split cache line, and is
            // outright unsupported on some architectures we would like to keep the door open to.
            cursor = alignTo(cursor, Math.min(width, 8));
            offsets[i] = cursor;
            cursor += width;
        }

        int fixedEnd = align8(cursor);
        return new RowLayout(
                schema,
                offsets,
                variable,
                nullBitmapOffset,
                nullBitmapBytes,
                fixedRegionOffset,
                fixedEnd,
                variableCount);
    }

    private static int align8(int value) {
        return alignTo(value, 8);
    }

    private static int alignTo(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    public StreamSchema schema() {
        return schema;
    }

    public int fieldCount() {
        return schema.fieldCount();
    }

    /** Byte offset of a field's slot, relative to the start of the row. */
    public int offsetOf(int ordinal) {
        return fieldOffsets[ordinal];
    }

    /** Whether this field's slot holds an (offset, length) pointer rather than the value itself. */
    public boolean isVariableWidth(int ordinal) {
        return variableWidth[ordinal];
    }

    public int nullBitmapOffset() {
        return nullBitmapOffset;
    }

    public int nullBitmapBytes() {
        return nullBitmapBytes;
    }

    public int fixedRegionOffset() {
        return fixedRegionOffset;
    }

    /** Where the variable-length payload begins; also the encoded size of a row with no var-len data. */
    public int fixedEnd() {
        return fixedEnd;
    }

    public int variableFieldCount() {
        return variableFieldCount;
    }

    /** Encoded size of a row whose variable-width fields total {@code payloadBytes}. */
    public int rowSize(int payloadBytes) {
        return fixedEnd + payloadBytes;
    }

    /** Byte index of the null-bitmap byte covering {@code ordinal}. */
    public int nullByteOffset(int ordinal) {
        return nullBitmapOffset + (ordinal >>> 3);
    }

    /** Mask selecting {@code ordinal}'s bit within its null-bitmap byte. */
    public byte nullBitMask(int ordinal) {
        return (byte) (1 << (ordinal & 7));
    }

    /**
     * Fails unless the field is of the expected type.
     *
     * <p>Called by the writer, never by generated read paths: at runtime the ordinal and its type
     * are already fixed by the plan, so re-checking would be paying for a question already answered.
     */
    void checkType(int ordinal, TypeName expected) {
        TypeName actual = schema.field(ordinal).type().typeName();
        if (actual != expected) {
            Field f = schema.field(ordinal);
            throw new IllegalArgumentException("field " + ordinal + " ('" + f.name() + "') is " + actual + ", not "
                    + expected + " in schema " + schema.name());
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RowLayout[")
                .append(schema.name())
                .append(" v")
                .append(schema.version())
                .append(", header=")
                .append(HEADER_BYTES)
                .append(", nulls=")
                .append(nullBitmapBytes)
                .append("B@")
                .append(nullBitmapOffset)
                .append(", fixed=")
                .append(fixedEnd - fixedRegionOffset)
                .append("B@")
                .append(fixedRegionOffset)
                .append(", varFields=")
                .append(variableFieldCount)
                .append(']');
        return sb.toString();
    }
}
