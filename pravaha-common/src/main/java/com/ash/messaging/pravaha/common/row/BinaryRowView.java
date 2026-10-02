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
package com.ash.messaging.pravaha.common.row;

import java.nio.charset.StandardCharsets;

import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * Reads a row encoded by {@link BinaryRowWriter}.
 *
 * <p>A cursor, not a value: {@link #wrap(MemoryRegion, int)} repoints it at the next row, so
 * iterating a batch allocates nothing at all. One cursor per lane; sharing one across threads is a
 * bug rather than a performance trade-off (design section 8.4).
 *
 * <p>Accessors do not validate the field's type. The plan already fixed the ordinal and its type at
 * registration, so re-checking on every record would be paying for a question already answered --
 * and this is the single hottest code path in the engine. Bounds are still enforced by the
 * underlying {@link MemoryRegion}.
 */
public final class BinaryRowView implements RowView {

    private final RowLayout layout;
    private MemoryRegion region;
    private int offset;

    public BinaryRowView(RowLayout layout) {
        this.layout = layout;
    }

    /** Points this cursor at a row. Returns {@code this} so calls can be chained. */
    public BinaryRowView wrap(MemoryRegion newRegion, int rowOffset) {
        this.region = newRegion;
        this.offset = rowOffset;
        return this;
    }

    public RowLayout layout() {
        return layout;
    }

    public MemoryRegion region() {
        return region;
    }

    @Override
    public int offset() {
        return offset;
    }

    @Override
    public int length() {
        return region.getInt(offset + RowLayout.OFFSET_TOTAL_LENGTH);
    }

    @Override
    public StreamSchema schema() {
        return layout.schema();
    }

    @Override
    public long weight() {
        return region.getLong(offset + RowLayout.OFFSET_WEIGHT);
    }

    @Override
    public long eventTimestampNanos() {
        return region.getLong(offset + RowLayout.OFFSET_EVENT_TIME);
    }

    @Override
    public long sequence() {
        return region.getLong(offset + RowLayout.OFFSET_SEQUENCE);
    }

    /** Schema version this row was encoded against; guards against decoding with the wrong layout. */
    public int schemaId() {
        return region.getInt(offset + RowLayout.OFFSET_SCHEMA_ID);
    }

    @Override
    public boolean isNull(int ordinal) {
        byte b = region.getByte(offset + layout.nullByteOffset(ordinal));
        return (b & layout.nullBitMask(ordinal)) != 0;
    }

    @Override
    public boolean getBoolean(int ordinal) {
        return region.getBoolean(offset + layout.offsetOf(ordinal));
    }

    @Override
    public byte getByte(int ordinal) {
        return region.getByte(offset + layout.offsetOf(ordinal));
    }

    @Override
    public short getShort(int ordinal) {
        return region.getShort(offset + layout.offsetOf(ordinal));
    }

    @Override
    public int getInt(int ordinal) {
        return region.getInt(offset + layout.offsetOf(ordinal));
    }

    @Override
    public long getLong(int ordinal) {
        return region.getLong(offset + layout.offsetOf(ordinal));
    }

    @Override
    public float getFloat(int ordinal) {
        return region.getFloat(offset + layout.offsetOf(ordinal));
    }

    @Override
    public double getDouble(int ordinal) {
        return region.getDouble(offset + layout.offsetOf(ordinal));
    }

    @Override
    public long getDecimalHigh(int ordinal) {
        return region.getLong(offset + layout.offsetOf(ordinal));
    }

    @Override
    public long getDecimalLow(int ordinal) {
        return region.getLong(offset + layout.offsetOf(ordinal) + 8);
    }

    @Override
    public MutableSlice getBytes(int ordinal, MutableSlice out) {
        int slot = offset + layout.offsetOf(ordinal);
        int payloadOffset = region.getInt(slot);
        int payloadLength = region.getInt(slot + 4);
        // Offsets are stored relative to the row, so a row stays valid wherever it is copied.
        return out.wrap(offset + payloadOffset, payloadLength);
    }

    /**
     * Compares a variable-width field against a UTF-8 literal without materialising a {@code String}.
     *
     * <p>This is what generated code emits for {@code WHERE status = 'COMPLETED'} (design section 12.3).
     */
    public boolean utf8Equals(int ordinal, byte[] literal) {
        int slot = offset + layout.offsetOf(ordinal);
        int payloadLength = region.getInt(slot + 4);
        if (payloadLength != literal.length) {
            return false;
        }
        return region.equalsBytes(offset + region.getInt(slot), literal);
    }

    /** The byte length of a text or binary column's value, read from its slot. */
    public int payloadLength(int ordinal) {
        return region.getInt(offset + layout.offsetOf(ordinal) + 4);
    }

    /**
     * Whether a text column holds exactly {@code ascii}, compared in place with nothing allocated.
     *
     * <p>The caller guarantees every char of {@code ascii} is below {@code 0x80}. Under that
     * condition this is the same answer as {@code getString(ordinal).equals(ascii)}: UTF-8 decodes
     * an ASCII character only from the single byte of the same value -- an overlong or malformed
     * sequence decodes to U+FFFD, never to ASCII -- so the decoded text equals {@code ascii} exactly
     * when the stored bytes are its bytes. It exists because the interpreted {@code WHERE status =
     * 'COMPLETED'} decoded a String for every row, and that allocation was half of a lane's time
     * and most of its garbage (gate P2, 2026-09-26).
     */
    public boolean asciiEquals(int ordinal, String ascii) {
        int slot = offset + layout.offsetOf(ordinal);
        int length = region.getInt(slot + 4);
        if (length != ascii.length()) {
            return false;
        }
        int at = offset + region.getInt(slot);
        for (int i = 0; i < length; i++) {
            if (region.getByte(at + i) != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getString(int ordinal) {
        int slot = offset + layout.offsetOf(ordinal);
        int payloadOffset = region.getInt(slot);
        int payloadLength = region.getInt(slot + 4);
        byte[] bytes = new byte[payloadLength];
        region.getBytes(offset + payloadOffset, bytes, 0, payloadLength);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Copies the raw bytes of this row. Off the hot path -- used by the DLQ and the debugger. */
    public byte[] toByteArray() {
        byte[] out = new byte[length()];
        region.getBytes(offset, out, 0, out.length);
        return out;
    }

    @Override
    public String toString() {
        if (region == null) {
            return "BinaryRowView[unbound]";
        }
        StringBuilder sb = new StringBuilder(schema().name())
                .append('{')
                .append("w=")
                .append(weight())
                .append(", seq=")
                .append(sequence());
        for (int i = 0; i < layout.fieldCount(); i++) {
            sb.append(", ").append(schema().field(i).name()).append('=');
            sb.append(isNull(i) ? "null" : RowDebug.render(this, i));
        }
        return sb.append('}').toString();
    }
}
