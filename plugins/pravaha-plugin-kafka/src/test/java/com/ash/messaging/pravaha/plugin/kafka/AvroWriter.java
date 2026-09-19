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

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * An Avro binary <em>writer</em>, written from the specification in the test source, so that what
 * {@link AvroBinary} reads is checked against something written independently of it rather than
 * against itself.
 *
 * <p>It is the encoder a producer uses, in miniature: zig-zag varints, little-endian floats, a
 * length before bytes and strings, blocks for arrays and maps, an index before a union's branch.
 */
final class AvroWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    byte[] bytes() {
        return out.toByteArray();
    }

    /** The Confluent wire format's prefix, in front of whatever has been written. */
    byte[] framed(int schemaId) {
        byte[] body = bytes();
        byte[] framed = new byte[body.length + 5];
        framed[0] = 0;
        framed[1] = (byte) (schemaId >>> 24);
        framed[2] = (byte) (schemaId >>> 16);
        framed[3] = (byte) (schemaId >>> 8);
        framed[4] = (byte) schemaId;
        System.arraycopy(body, 0, framed, 5, body.length);
        return framed;
    }

    AvroWriter bool(boolean value) {
        out.write(value ? 1 : 0);
        return this;
    }

    AvroWriter integer(int value) {
        return number(value);
    }

    /** A zig-zag varint: the sign in the low bit, then seven bits a byte. */
    AvroWriter number(long value) {
        long zigzag = (value << 1) ^ (value >> 63);
        while ((zigzag & ~0x7FL) != 0) {
            out.write((int) ((zigzag & 0x7F) | 0x80));
            zigzag >>>= 7;
        }
        out.write((int) zigzag);
        return this;
    }

    /** A varint that is NOT zig-zagged: what a reader that forgot the zig-zag would write. */
    AvroWriter plainVarint(long value) {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            out.write((int) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        out.write((int) remaining);
        return this;
    }

    AvroWriter float32(float value) {
        return littleEndian(Float.floatToIntBits(value), 4);
    }

    AvroWriter float64(double value) {
        return littleEndian(Double.doubleToLongBits(value), 8);
    }

    AvroWriter text(String value) {
        return binary(value.getBytes(StandardCharsets.UTF_8));
    }

    AvroWriter binary(byte[] value) {
        number(value.length);
        return fixed(value);
    }

    AvroWriter fixed(byte[] value) {
        out.writeBytes(value);
        return this;
    }

    /** A decimal's unscaled value, two's complement big-endian, as {@code bytes}. */
    AvroWriter decimalBytes(BigDecimal value, int scale) {
        return binary(value.setScale(scale).unscaledValue().toByteArray());
    }

    /** The same, left-padded to {@code size} bytes, as {@code fixed}. */
    AvroWriter decimalFixed(BigDecimal value, int scale, int size) {
        BigInteger unscaled = value.setScale(scale).unscaledValue();
        byte[] minimal = unscaled.toByteArray();
        byte[] padded = new byte[size];
        byte sign = (byte) (unscaled.signum() < 0 ? 0xFF : 0x00);
        java.util.Arrays.fill(padded, sign);
        System.arraycopy(minimal, 0, padded, size - minimal.length, minimal.length);
        return fixed(padded);
    }

    /** The branch a union selects. */
    AvroWriter union(int index) {
        return number(index);
    }

    /** A block header: the count of items to follow, or 0 to end. */
    AvroWriter block(long count) {
        return number(count);
    }

    /** A block written the other legal way: a negative count, then the block's size in bytes. */
    AvroWriter sizedBlock(long count, long bytes) {
        number(-count);
        return number(bytes);
    }

    private AvroWriter littleEndian(long bits, int width) {
        for (int i = 0; i < width; i++) {
            out.write((int) ((bits >>> (8 * i)) & 0xFF));
        }
        return this;
    }
}
