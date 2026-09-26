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

import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;

/**
 * A cursor over one record's Avro binary encoding, written from the specification.
 *
 * <p>The whole format, which is why writing it beats taking a dependency for it:
 *
 * <ul>
 *   <li><strong>null</strong> is no bytes at all.
 *   <li><strong>boolean</strong> is one byte, 0 or 1.
 *   <li><strong>int</strong> and <strong>long</strong> are <em>zig-zag</em> varints: the value is
 *       first mapped to an unsigned one, {@code (n << 1) ^ (n >> 63)}, so a small negative number is
 *       small; that is then written seven bits at a time, least significant group first, with the
 *       high bit of every byte but the last set. Reading it as a plain varint would turn -1 into 1
 *       and 1 into -1 ({@code AvroBinaryTest} holds that pair).
 *   <li><strong>float</strong> and <strong>double</strong> are 4 and 8 bytes, IEEE 754,
 *       <em>little</em>-endian.
 *   <li><strong>bytes</strong> and <strong>string</strong> are a long length then that many bytes; a
 *       string's are UTF-8.
 *   <li><strong>fixed</strong> is exactly the declared number of bytes, with no length.
 *   <li><strong>enum</strong> is an int: the position of the symbol in the schema.
 *   <li><strong>array</strong> and <strong>map</strong> are blocks: a long count, then that many
 *       items (a map's each a string key and a value), repeated until a count of zero. A
 *       <em>negative</em> count means {@code -count} items preceded by a long byte size, which is
 *       what lets a reader skip the block whole.
 *   <li><strong>union</strong> is a long: the position of the branch in the schema, then the branch.
 *   <li><strong>record</strong> is its fields, in the schema's order, and nothing else -- no names,
 *       no tags, no terminator. Which is why the writer's schema must be exactly the one the bytes
 *       were written with, and why a wrong one shows up as nonsense rather than an error.
 * </ul>
 *
 * <p>Every read is bounded by the value's length: a truncated record is {@link Undecodable}, never a
 * {@link ArrayIndexOutOfBoundsException} out of a fetch thread.
 */
final class AvroBinary {

    private final byte[] bytes;
    private int position;

    AvroBinary(byte[] bytes, int from) {
        this.bytes = bytes;
        this.position = from;
    }

    int position() {
        return position;
    }

    boolean atEnd() {
        return position >= bytes.length;
    }

    int remaining() {
        return bytes.length - position;
    }

    boolean readBoolean() throws Undecodable {
        int value = next("a boolean");
        if (value != 0 && value != 1) {
            throw new Undecodable("a boolean is the byte " + value + ", which is neither 0 nor 1");
        }
        return value == 1;
    }

    /** A zig-zag varint, refused past the range of an int. */
    int readInt() throws Undecodable {
        long value = readLong();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new Undecodable("an Avro int holds " + value + ", which is out of an int's range");
        }
        return (int) value;
    }

    /** A zig-zag varint. */
    long readLong() throws Undecodable {
        long raw = 0;
        int shift = 0;
        while (shift <= 63) {
            int b = next("a varint");
            raw |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                // Zig-zag: the low bit is the sign, the rest the magnitude.
                return (raw >>> 1) ^ -(raw & 1);
            }
            shift += 7;
        }
        throw new Undecodable("a varint runs past ten bytes, so the value is not Avro");
    }

    float readFloat() throws Undecodable {
        return Float.intBitsToFloat((int) littleEndian(4, "a float"));
    }

    double readDouble() throws Undecodable {
        return Double.longBitsToDouble(littleEndian(8, "a double"));
    }

    byte[] readBytes() throws Undecodable {
        return readFixed(length("bytes"), "bytes");
    }

    String readString() throws Undecodable {
        return new String(readFixed(length("a string"), "a string"), StandardCharsets.UTF_8);
    }

    byte[] readFixed(int size, String what) throws Undecodable {
        if (size < 0 || size > remaining()) {
            throw new Undecodable("the value ends in the middle of " + what + " (" + size + " bytes needed, "
                    + remaining() + " left)");
        }
        byte[] out = new byte[size];
        System.arraycopy(bytes, position, out, 0, size);
        position += size;
        return out;
    }

    /** A block's item count: positive, or {@code -count} with a byte size that is read and dropped. */
    long blockCount() throws Undecodable {
        long count = readLong();
        if (count < 0) {
            readLong();
            return -count;
        }
        return count;
    }

    /** Reads past one value of {@code node}: a field no column of the stream's schema names. */
    void skip(AvroSchema.Node node) throws Undecodable {
        switch (node.kind) {
            case NULL -> {
                // No bytes.
            }
            case BOOLEAN -> next("a boolean");
            case INT, LONG, ENUM -> readLong();
            case FLOAT -> littleEndian(4, "a float");
            case DOUBLE -> littleEndian(8, "a double");
            case BYTES, STRING -> readFixed(length("bytes"), "bytes");
            case FIXED -> readFixed(node.size, "a fixed");
            case RECORD -> {
                for (AvroSchema.Field field : node.fields()) {
                    skip(field.type());
                }
            }
            case ARRAY -> {
                for (long count = blockCount(); count > 0; count = blockCount()) {
                    for (long i = 0; i < count; i++) {
                        skip(node.element);
                    }
                }
            }
            case MAP -> {
                for (long count = blockCount(); count > 0; count = blockCount()) {
                    for (long i = 0; i < count; i++) {
                        readFixed(length("a map key"), "a map key");
                        skip(node.values);
                    }
                }
            }
            case UNION -> skip(branch(node));
        }
    }

    /** The branch a union's index selects. */
    AvroSchema.Node branch(AvroSchema.Node union) throws Undecodable {
        return union.branches.get(branchIndex(union));
    }

    /** The index of the branch a union's value is written in, checked against the union. */
    int branchIndex(AvroSchema.Node union) throws Undecodable {
        long index = readLong();
        if (index < 0 || index >= union.branches.size()) {
            throw new Undecodable("a union names branch " + index + " of " + union.branches.size()
                    + ", so the value was not written with this schema");
        }
        return (int) index;
    }

    private int length(String what) throws Undecodable {
        long length = readLong();
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new Undecodable("the length of " + what + " is " + length);
        }
        return (int) length;
    }

    private long littleEndian(int width, String what) throws Undecodable {
        if (remaining() < width) {
            throw new Undecodable("the value ends in the middle of " + what);
        }
        long value = 0;
        for (int i = 0; i < width; i++) {
            value |= (long) (bytes[position + i] & 0xFF) << (8 * i);
        }
        position += width;
        return value;
    }

    private int next(String what) throws Undecodable {
        if (position >= bytes.length) {
            throw new Undecodable("the value ends in the middle of " + what);
        }
        return bytes[position++] & 0xFF;
    }
}
