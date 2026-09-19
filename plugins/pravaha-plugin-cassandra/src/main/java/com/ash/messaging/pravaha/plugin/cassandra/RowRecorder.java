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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A {@link RowWriter} that writes nowhere and remembers what it was told, in a compact byte form.
 *
 * <p>Delete detection by scan comparison needs the whole previous row of every key it has emitted --
 * a retraction in this engine is the old row at weight {@code -1}, not a key -- and it needs to tell
 * whether a key's row changed between two passes. Both come from the same bytes: a row is decoded
 * once, into this recorder, by exactly the code that decodes it for the engine; two passes' rows are
 * equal exactly when their recordings are; and {@link #replay} writes a recording into a real row
 * with the same calls in the same order, so a retraction is bit-for-bit the row it cancels.
 *
 * <p>The encoding is a sequence of {@code (ordinal, tag, payload)} with integers as zig-zag varints,
 * so a small {@code BIGINT} costs three bytes rather than the eleven a boxed value would. Nothing
 * outside this class reads it; the files that persist it treat it as an opaque byte string.
 *
 * <p>This class exists, byte for byte apart from its package, in both the Aerospike and the
 * Cassandra plugin. The two plugins share no module but the API and the common library, and a
 * scan-comparison helper is neither.
 */
final class RowRecorder implements RowWriter {

    private static final byte NULL = 0;
    private static final byte BOOLEAN = 1;
    private static final byte BYTE = 2;
    private static final byte SHORT = 3;
    private static final byte INT = 4;
    private static final byte LONG = 5;
    private static final byte FLOAT = 6;
    private static final byte DOUBLE = 7;
    private static final byte DECIMAL = 8;
    private static final byte BYTES = 9;
    private static final byte STRING = 10;
    private static final byte UNREAD = 11;

    private final StreamSchema schema;
    private byte[] buffer = new byte[64];
    private int length;

    RowRecorder(StreamSchema schema) {
        this.schema = schema;
    }

    /** Starts a new recording, discarding the last. */
    RowRecorder reset() {
        length = 0;
        return this;
    }

    /** The recording so far, as its own array. */
    byte[] toBytes() {
        return Arrays.copyOf(buffer, length);
    }

    @Override
    public StreamSchema schema() {
        return schema;
    }

    @Override
    public RowWriter setNull(int ordinal) {
        header(ordinal, NULL);
        return this;
    }

    @Override
    public RowWriter setBoolean(int ordinal, boolean value) {
        header(ordinal, BOOLEAN);
        put(value ? 1 : 0);
        return this;
    }

    @Override
    public RowWriter setByte(int ordinal, byte value) {
        header(ordinal, BYTE);
        put(value);
        return this;
    }

    @Override
    public RowWriter setShort(int ordinal, short value) {
        header(ordinal, SHORT);
        varlong(value);
        return this;
    }

    @Override
    public RowWriter setInt(int ordinal, int value) {
        header(ordinal, INT);
        varlong(value);
        return this;
    }

    @Override
    public RowWriter setLong(int ordinal, long value) {
        header(ordinal, LONG);
        varlong(value);
        return this;
    }

    @Override
    public RowWriter setFloat(int ordinal, float value) {
        header(ordinal, FLOAT);
        fixed(Float.floatToRawIntBits(value), 4);
        return this;
    }

    @Override
    public RowWriter setDouble(int ordinal, double value) {
        header(ordinal, DOUBLE);
        fixed(Double.doubleToRawLongBits(value), 8);
        return this;
    }

    @Override
    public RowWriter setDecimal(int ordinal, long high, long low) {
        header(ordinal, DECIMAL);
        fixed(high, 8);
        fixed(low, 8);
        return this;
    }

    @Override
    public RowWriter setBytes(int ordinal, byte[] value) {
        if (value == null) {
            return setNull(ordinal);
        }
        header(ordinal, BYTES);
        blob(value);
        return this;
    }

    @Override
    public RowWriter setString(int ordinal, String value) {
        if (value == null) {
            return setNull(ordinal);
        }
        header(ordinal, STRING);
        blob(value.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Recorded as itself rather than as the placeholder the default writes, so a replay calls the
     * target's own {@code setUnread} -- whatever that writer does for a column nothing reads, the
     * retraction does the same thing as the insertion did.
     */
    @Override
    public RowWriter setUnread(int ordinal) {
        header(ordinal, UNREAD);
        return this;
    }

    /** Not part of a row's content: the reader sets these on the real row when it emits one. */
    @Override
    public RowWriter weight(long weight) {
        return this;
    }

    @Override
    public RowWriter eventTimestampNanos(long nanos) {
        return this;
    }

    @Override
    public RowWriter sequence(long sequence) {
        return this;
    }

    @Override
    public int commit() {
        return length;
    }

    @Override
    public void abort() {
        length = 0;
    }

    /** Writes a recording into {@code target} with the calls that made it, in the order they were made. */
    static void replay(byte[] recording, RowWriter target) {
        int[] at = {0};
        while (at[0] < recording.length) {
            int ordinal = (int) readVarlong(recording, at);
            byte tag = recording[at[0]++];
            switch (tag) {
                case NULL -> target.setNull(ordinal);
                case BOOLEAN -> target.setBoolean(ordinal, recording[at[0]++] != 0);
                case BYTE -> target.setByte(ordinal, recording[at[0]++]);
                case SHORT -> target.setShort(ordinal, (short) readVarlong(recording, at));
                case INT -> target.setInt(ordinal, (int) readVarlong(recording, at));
                case LONG -> target.setLong(ordinal, readVarlong(recording, at));
                case FLOAT -> target.setFloat(ordinal, Float.intBitsToFloat((int) readFixed(recording, at, 4)));
                case DOUBLE -> target.setDouble(ordinal, Double.longBitsToDouble(readFixed(recording, at, 8)));
                case DECIMAL -> target.setDecimal(ordinal, readFixed(recording, at, 8), readFixed(recording, at, 8));
                case BYTES -> target.setBytes(ordinal, readBlob(recording, at));
                case STRING -> target.setString(ordinal, new String(readBlob(recording, at), StandardCharsets.UTF_8));
                case UNREAD -> target.setUnread(ordinal);
                default ->
                    throw new IllegalStateException("a recorded row holds tag " + tag + " at byte " + (at[0] - 1)
                            + "; it was not written by this class");
            }
        }
    }

    private void header(int ordinal, byte tag) {
        varlong(ordinal);
        put(tag);
    }

    private void put(int value) {
        if (length == buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[length++] = (byte) value;
    }

    private void varlong(long value) {
        long zigzag = (value << 1) ^ (value >> 63);
        while ((zigzag & ~0x7FL) != 0) {
            put((int) ((zigzag & 0x7F) | 0x80));
            zigzag >>>= 7;
        }
        put((int) zigzag);
    }

    private void fixed(long value, int bytes) {
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            put((int) (value >>> shift));
        }
    }

    private void blob(byte[] value) {
        varlong(value.length);
        if (length + value.length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + value.length));
        }
        System.arraycopy(value, 0, buffer, length, value.length);
        length += value.length;
    }

    private static long readVarlong(byte[] in, int[] at) {
        long zigzag = 0;
        int shift = 0;
        byte next;
        do {
            next = in[at[0]++];
            zigzag |= (long) (next & 0x7F) << shift;
            shift += 7;
        } while ((next & 0x80) != 0);
        return (zigzag >>> 1) ^ -(zigzag & 1);
    }

    private static long readFixed(byte[] in, int[] at, int bytes) {
        long value = 0;
        for (int index = 0; index < bytes; index++) {
            value = (value << 8) | (in[at[0]++] & 0xFF);
        }
        return value;
    }

    private static byte[] readBlob(byte[] in, int[] at) {
        int size = (int) readVarlong(in, at);
        byte[] value = Arrays.copyOfRange(in, at[0], at[0] + size);
        at[0] += size;
        return value;
    }
}
