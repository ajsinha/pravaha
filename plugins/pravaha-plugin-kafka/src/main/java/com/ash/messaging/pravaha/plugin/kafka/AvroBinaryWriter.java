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
package com.ash.messaging.pravaha.plugin.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Avro's binary encoding, written: the other half of {@link AvroBinary}, from the same specification
 * and with the same rules (zig-zag varints, little-endian IEEE 754, a long length before bytes and
 * strings, a long branch index before a union's value). One per record; it grows as it writes.
 */
final class AvroBinaryWriter {

    private byte[] buffer = new byte[64];
    private int size;

    /** What has been written, copied out. */
    byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    void writeBoolean(boolean value) {
        write(value ? 1 : 0);
    }

    /** An int or a long: both are one zig-zag varint. */
    void writeLong(long value) {
        long raw = (value << 1) ^ (value >> 63);
        while ((raw & ~0x7FL) != 0) {
            write((int) ((raw & 0x7F) | 0x80));
            raw >>>= 7;
        }
        write((int) raw);
    }

    void writeFloat(float value) {
        littleEndian(Float.floatToRawIntBits(value), 4);
    }

    void writeDouble(double value) {
        littleEndian(Double.doubleToRawLongBits(value), 8);
    }

    void writeBytes(byte[] value) {
        writeLong(value.length);
        writeFixed(value);
    }

    void writeString(String value) {
        writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Exactly these bytes, with no length: a {@code fixed}, or a prefix. */
    void writeFixed(byte[] value) {
        ensure(value.length);
        System.arraycopy(value, 0, buffer, size, value.length);
        size += value.length;
    }

    /** One byte as it is, unencoded: the Confluent wire format's magic byte and schema id. */
    void write(int b) {
        ensure(1);
        buffer[size++] = (byte) b;
    }

    private void littleEndian(long bits, int width) {
        ensure(width);
        for (int i = 0; i < width; i++) {
            buffer[size++] = (byte) (bits >>> (8 * i));
        }
    }

    private void ensure(int more) {
        if (size + more > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + more));
        }
    }
}
