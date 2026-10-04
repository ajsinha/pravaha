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
package com.ash.messaging.pravaha.serving;

import org.jspecify.annotations.Nullable;

/**
 * How a view snapshot writes one value, and reads it back, as its own class (VIEW-2).
 *
 * <p>Moved out of {@link ServedView} unchanged, to keep that class under the size every class here
 * is held to; the format is the view snapshot's version 2 and belongs to it.
 */
final class ViewValues {

    static final byte NULL = 0;
    static final byte STRING = 1;
    static final byte DOUBLE = 2;
    static final byte BOOLEAN = 3;
    static final byte LONG = 4;
    static final byte BYTES = 5;
    static final byte INT = 6;
    static final byte SHORT = 7;
    static final byte BYTE = 8;
    static final byte FLOAT = 9;
    static final byte DECIMAL = 10;

    private ViewValues() {}

    /**
     * One value, as its own class.
     *
     * <p>Refuses a class it has no tag for rather than writing something near it. Every class a view
     * is given -- by {@link ServedView#apply}, by a {@link ViewSink} writer, by the value readers on the way in
     * -- has one; a class without one is a new way in that this format has to learn about first.
     */
    static void write(java.io.DataOutputStream out, Object value, String view, String column)
            throws java.io.IOException {
        switch (value) {
            case null -> out.writeByte(NULL);
            case String text -> {
                // Length-prefixed bytes, not writeUTF, which refuses anything over 65,535 encoded bytes.
                byte[] encoded = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeByte(STRING);
                out.writeInt(encoded.length);
                out.write(encoded);
            }
            case Long number -> {
                out.writeByte(LONG);
                out.writeLong(number);
            }
            case Integer number -> {
                out.writeByte(INT);
                out.writeInt(number);
            }
            case Short number -> {
                out.writeByte(SHORT);
                out.writeShort(number);
            }
            case Byte number -> {
                out.writeByte(BYTE);
                out.writeByte(number);
            }
            case Double number -> {
                out.writeByte(DOUBLE);
                out.writeLong(Double.doubleToRawLongBits(number));
            }
            case Float number -> {
                out.writeByte(FLOAT);
                out.writeInt(Float.floatToRawIntBits(number));
            }
            case java.math.BigDecimal number -> {
                // Unscaled value and scale: the number exactly, and its scale with it, so 1.50 comes
                // back 1.50 and equal to the 1.50 the engine writes next.
                byte[] unscaled = number.unscaledValue().toByteArray();
                out.writeByte(DECIMAL);
                out.writeInt(number.scale());
                out.writeInt(unscaled.length);
                out.write(unscaled);
            }
            case Boolean flag -> {
                out.writeByte(BOOLEAN);
                out.writeBoolean(flag);
            }
            case byte[] raw -> {
                out.writeByte(BYTES);
                out.writeInt(raw.length);
                out.write(raw);
            }
            default ->
                throw new IllegalStateException(
                        "view '" + view + "' holds a " + value.getClass().getName()
                                + " in column '" + column + "', which a view snapshot has no "
                                + "encoding for. Writing it as something near it is what made a restored view "
                                + "disagree with the live one, so the checkpoint is refused instead.");
        }
    }

    static @Nullable Object read(java.io.DataInputStream in) throws java.io.IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case NULL -> null;
            case STRING -> new String(readBytes(in), java.nio.charset.StandardCharsets.UTF_8);
            case LONG -> in.readLong();
            case INT -> in.readInt();
            case SHORT -> in.readShort();
            case BYTE -> in.readByte();
            case DOUBLE -> Double.longBitsToDouble(in.readLong());
            case FLOAT -> Float.intBitsToFloat(in.readInt());
            case DECIMAL -> {
                int scale = in.readInt();
                yield new java.math.BigDecimal(new java.math.BigInteger(readBytes(in)), scale);
            }
            case BOOLEAN -> in.readBoolean();
            case BYTES -> readBytes(in);
            default -> throw new java.io.IOException("unknown value tag " + tag + " in a view snapshot");
        };
    }

    private static byte[] readBytes(java.io.DataInputStream in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new java.io.IOException("a negative length, " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
