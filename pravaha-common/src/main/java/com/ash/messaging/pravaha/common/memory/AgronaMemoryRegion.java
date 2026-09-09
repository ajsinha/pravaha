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
package com.ash.messaging.pravaha.common.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * {@link MemoryRegion} over an aligned direct {@code ByteBuffer}.
 *
 * <p>Byte order is fixed to {@link ByteOrder#LITTLE_ENDIAN} rather than inherited from the platform.
 * The row layout is a persisted format -- it is checkpointed and shipped between nodes -- so letting
 * it vary by CPU would make a checkpoint unportable in a way nobody would notice until a mixed
 * cluster produced wrong answers.
 *
 * <p>The backing {@code ByteBuffer} is retained so the region owns its memory's lifetime; without
 * that reference the buffer could be collected while an address into it was still live.
 */
final class AgronaMemoryRegion implements MemoryRegion {

    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

    /** Retained so the region owns its memory's lifetime; without it the buffer could be collected. */
    private final ByteBuffer backing;

    private final UnsafeBuffer buffer;
    private final int capacity;
    private boolean closed;

    AgronaMemoryRegion(int bytes, int alignment) {
        this.backing = BufferUtil.allocateDirectAligned(bytes, alignment);
        this.buffer = new UnsafeBuffer(backing);
        this.capacity = bytes;
        this.buffer.setMemory(0, bytes, (byte) 0);
    }

    @Override
    public int capacity() {
        return capacity;
    }

    private UnsafeBuffer buf() {
        if (closed) {
            throw new IllegalStateException("region is closed");
        }
        return buffer;
    }

    @Override
    public boolean getBoolean(int index) {
        return buf().getByte(index) != 0;
    }

    @Override
    public void putBoolean(int index, boolean value) {
        buf().putByte(index, value ? (byte) 1 : (byte) 0);
    }

    @Override
    public byte getByte(int index) {
        return buf().getByte(index);
    }

    @Override
    public void putByte(int index, byte value) {
        buf().putByte(index, value);
    }

    @Override
    public short getShort(int index) {
        return buf().getShort(index, ORDER);
    }

    @Override
    public void putShort(int index, short value) {
        buf().putShort(index, value, ORDER);
    }

    @Override
    public int getInt(int index) {
        return buf().getInt(index, ORDER);
    }

    @Override
    public void putInt(int index, int value) {
        buf().putInt(index, value, ORDER);
    }

    @Override
    public long getLong(int index) {
        return buf().getLong(index, ORDER);
    }

    @Override
    public void putLong(int index, long value) {
        buf().putLong(index, value, ORDER);
    }

    @Override
    public float getFloat(int index) {
        return buf().getFloat(index, ORDER);
    }

    @Override
    public void putFloat(int index, float value) {
        buf().putFloat(index, value, ORDER);
    }

    @Override
    public double getDouble(int index) {
        return buf().getDouble(index, ORDER);
    }

    @Override
    public void putDouble(int index, double value) {
        buf().putDouble(index, value, ORDER);
    }

    @Override
    public void getBytes(int index, byte[] dst, int dstOffset, int length) {
        buf().getBytes(index, dst, dstOffset, length);
    }

    @Override
    public void putBytes(int index, byte[] src, int srcOffset, int length) {
        buf().putBytes(index, src, srcOffset, length);
    }

    @Override
    public void copyFrom(int index, MemoryRegion src, int srcIndex, int length) {
        if (!(src instanceof AgronaMemoryRegion other)) {
            throw new IllegalArgumentException(
                    "cannot copy from a " + src.getClass().getSimpleName() + " into an AgronaMemoryRegion");
        }
        buf().putBytes(index, other.buffer, srcIndex, length);
    }

    @Override
    public boolean equalsBytes(int index, byte[] literal) {
        if (index < 0 || literal.length > capacity - index) {
            return false;
        }
        for (int i = 0; i < literal.length; i++) {
            if (buf().getByte(index + i) != literal[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setMemory(int index, int length, byte value) {
        buf().setMemory(index, length, value);
    }

    @Override
    public void close() {
        // Deliberately does not call BufferUtil.free: allocateDirectAligned returns a *slice*, and a
        // slice carries no Cleaner, so freeing it throws. Reclamation is left to the collector,
        // matching ByteBufferMemoryRegion. Regions are a handful of large, long-lived arena slabs
        // (design section 8.5), so this costs nothing in practice.
        closed = true;
    }

    @Override
    public String toString() {
        return "AgronaMemoryRegion[" + capacity + "B" + (closed ? ", closed" : "") + "]";
    }
}
