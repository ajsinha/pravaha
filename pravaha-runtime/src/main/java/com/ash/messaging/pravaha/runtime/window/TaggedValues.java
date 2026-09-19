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
package com.ash.messaging.pravaha.runtime.window;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * One value, tagged with its shape -- the encoding a windowed aggregate's group keys and distinct
 * values share, in a checkpoint and off-heap alike.
 *
 * <p>Shared by the group keys and the distinct sets, which hold the same kinds of value for the same
 * reason. A distinct set used to be longs, so this did not apply to it; keying it by the value rather
 * than by the slot's bits made the two the same problem.
 *
 * <p>Moved out of {@link SlicedAggregateState} unchanged when that class's accumulators and distinct
 * sets moved into their own off-heap stores ({@link OffHeapAccumulators}, {@link DistinctValueCounts}):
 * three classes reading one format is exactly the case for one place that writes it.
 */
final class TaggedValues {

    static final byte NULL = 0;
    static final byte STRING = 1;
    static final byte DOUBLE = 2;
    static final byte BOOLEAN = 3;
    static final byte LONG = 4;

    private TaggedValues() {}

    static void writeTagged(DataOutput out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(NULL);
        } else if (value instanceof String string) {
            out.writeByte(STRING);
            out.writeUTF(string);
        } else if (value instanceof Double || value instanceof Float) {
            out.writeByte(DOUBLE);
            out.writeDouble(((Number) value).doubleValue());
        } else if (value instanceof Boolean flag) {
            out.writeByte(BOOLEAN);
            out.writeBoolean(flag);
        } else {
            out.writeByte(LONG);
            out.writeLong(((Number) value).longValue());
        }
    }

    static Object readTagged(DataInput in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case NULL -> null;
            case STRING -> in.readUTF();
            case DOUBLE -> in.readDouble();
            case BOOLEAN -> in.readBoolean();
            case LONG -> in.readLong();
            default -> throw new IOException("unknown key-value tag " + tag + " in the checkpoint");
        };
    }

    static void writeKeyValues(DataOutput out, Object[] keyValues) throws IOException {
        out.writeInt(keyValues == null ? -1 : keyValues.length);
        if (keyValues == null) {
            return;
        }
        for (Object value : keyValues) {
            writeTagged(out, value);
        }
    }

    static Object[] readKeyValues(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        Object[] values = new Object[length];
        for (int i = 0; i < length; i++) {
            values[i] = readTagged(in);
        }
        return values;
    }

    /** {@link #writeKeyValues}, into a byte array. */
    static byte[] encodeKeyValues(Object[] keyValues) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writeKeyValues(out, keyValues);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws IOException; this exists so the checked exception
            // on the shared writeKeyValues signature does not have to leak to every caller.
            throw new IllegalStateException(e);
        }
    }

    /** {@link #readKeyValues}, from a byte array this class wrote. */
    static Object[] decodeKeyValues(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            return readKeyValues(in);
        } catch (IOException e) {
            // Reading from a ByteArrayInputStream cannot fail on I/O; a failure here means bytes
            // this class wrote are not what it expects, which is a bug here rather than a condition a
            // caller could have caused.
            throw new IllegalStateException("cannot decode key values this engine wrote itself", e);
        }
    }

    /**
     * A distinct value as the bytes its identity is compared by: a tag, then the value.
     *
     * <p>Not {@link #writeTagged}'s encoding, because that one is for reading back and this one is for
     * equality. {@code writeUTF} prefixes a length and caps a string at 64 KiB; here the length is the
     * key's own, so a string needs neither. A double is compared by {@link Double#doubleToLongBits},
     * which is what {@link Double#equals} compares -- so {@code -0.0} and {@code 0.0} stay two values
     * and every NaN is one, exactly as the on-heap {@code HashMap} this replaced counted them.
     */
    static byte[] identityBytes(Object value) {
        if (value == null) {
            return new byte[] {NULL};
        }
        if (value instanceof String string) {
            byte[] utf8 = string.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] bytes = new byte[1 + utf8.length];
            bytes[0] = STRING;
            System.arraycopy(utf8, 0, bytes, 1, utf8.length);
            return bytes;
        }
        if (value instanceof Boolean flag) {
            return new byte[] {BOOLEAN, (byte) (flag ? 1 : 0)};
        }
        long bits = value instanceof Double || value instanceof Float
                ? Double.doubleToLongBits(((Number) value).doubleValue())
                : ((Number) value).longValue();
        byte[] bytes = new byte[9];
        bytes[0] = value instanceof Double || value instanceof Float ? DOUBLE : LONG;
        for (int i = 0; i < 8; i++) {
            bytes[1 + i] = (byte) (bits >>> (8 * i));
        }
        return bytes;
    }

    /** The value {@link #identityBytes} encoded, back as an object. */
    static Object fromIdentityBytes(byte[] bytes) {
        return switch (bytes[0]) {
            case NULL -> null;
            case STRING -> new String(bytes, 1, bytes.length - 1, java.nio.charset.StandardCharsets.UTF_8);
            case BOOLEAN -> bytes[1] != 0;
            case DOUBLE, LONG -> {
                long bits = 0;
                for (int i = 0; i < 8; i++) {
                    bits |= (bytes[1 + i] & 0xFFL) << (8 * i);
                }
                yield bytes[0] == DOUBLE ? (Object) Double.longBitsToDouble(bits) : (Object) bits;
            }
            default -> throw new IllegalStateException("unknown distinct-value tag " + bytes[0]);
        };
    }
}
