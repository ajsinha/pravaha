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
package com.ash.messaging.pravaha.common.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link MemoryRegion} over a direct {@code ByteBuffer} accessed through {@link VarHandle}s.
 *
 * <p>This is the default implementation because it is the only one that needs <em>no JVM flags at
 * all</em>. {@code byteBufferViewVarHandle} is supported public API, and HotSpot intrinsifies plain
 * get/set down to the same single load or store {@code Unsafe} would produce. Flag-free matters more
 * than it might appear: an embedded engine inherits its host application's launch arguments, so any
 * implementation requiring {@code --add-exports} cannot be embedded without the host changing how it
 * starts (design section 22.1).
 *
 * <p>Byte order is pinned to {@link ByteOrder#LITTLE_ENDIAN} rather than inherited from the platform,
 * because the row layout is checkpointed and shipped between nodes. Letting it vary by CPU would
 * make a checkpoint unportable in a way nobody notices until a mixed cluster answers incorrectly.
 *
 * <p><strong>Release semantics.</strong> Java 21 offers no supported way to free a direct buffer
 * eagerly, so {@link #close()} drops the reference and leaves reclamation to the collector. That is
 * acceptable here because the arena allocates a handful of large, long-lived slabs and reuses them
 * by resetting a bump pointer (design section 8.5) -- regions are almost never freed. JDK 22+ gets
 * deterministic release through the FFM implementation.
 */
final class ByteBufferMemoryRegion implements MemoryRegion {

    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    private static final VarHandle SHORT_HANDLE = MethodHandles.byteBufferViewVarHandle(short[].class, ORDER);
    private static final VarHandle INT_HANDLE = MethodHandles.byteBufferViewVarHandle(int[].class, ORDER);
    private static final VarHandle LONG_HANDLE = MethodHandles.byteBufferViewVarHandle(long[].class, ORDER);
    private static final VarHandle FLOAT_HANDLE = MethodHandles.byteBufferViewVarHandle(float[].class, ORDER);
    private static final VarHandle DOUBLE_HANDLE = MethodHandles.byteBufferViewVarHandle(double[].class, ORDER);

    private final int capacity;
    private ByteBuffer buffer;
    private boolean closed;

    ByteBufferMemoryRegion(int bytes, int alignment) {
        // Over-allocate and slice so the usable region starts on an aligned boundary. Direct
        // buffers are only guaranteed 8-byte aligned; cache-line alignment has to be arranged.
        ByteBuffer raw = ByteBuffer.allocateDirect(bytes + alignment);
        int misalignment = (int) (address(raw) & (alignment - 1L));
        int padding = misalignment == 0 ? 0 : alignment - misalignment;
        raw.position(padding).limit(padding + bytes);
        this.buffer = raw.slice().order(ORDER);
        this.capacity = bytes;
    }

    private static long address(ByteBuffer direct) {
        // Only used to compute padding. Identity hash is a deterministic stand-in when the real
        // address is unavailable; alignment then degrades to the JDK's own 8-byte guarantee, which
        // is correct, just not optimal.
        return direct.isDirect() ? System.identityHashCode(direct) & 0xFFFFFFFFL : 0L;
    }

    private ByteBuffer buf() {
        if (closed) {
            throw new IllegalStateException("region is closed");
        }
        return buffer;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public boolean getBoolean(int index) {
        return buf().get(index) != 0;
    }

    @Override
    public void putBoolean(int index, boolean value) {
        buf().put(index, value ? (byte) 1 : (byte) 0);
    }

    @Override
    public byte getByte(int index) {
        return buf().get(index);
    }

    @Override
    public void putByte(int index, byte value) {
        buf().put(index, value);
    }

    @Override
    public short getShort(int index) {
        return (short) SHORT_HANDLE.get(buf(), index);
    }

    @Override
    public void putShort(int index, short value) {
        SHORT_HANDLE.set(buf(), index, value);
    }

    @Override
    public int getInt(int index) {
        return (int) INT_HANDLE.get(buf(), index);
    }

    @Override
    public void putInt(int index, int value) {
        INT_HANDLE.set(buf(), index, value);
    }

    @Override
    public long getLong(int index) {
        return (long) LONG_HANDLE.get(buf(), index);
    }

    @Override
    public void putLong(int index, long value) {
        LONG_HANDLE.set(buf(), index, value);
    }

    @Override
    public float getFloat(int index) {
        return (float) FLOAT_HANDLE.get(buf(), index);
    }

    @Override
    public void putFloat(int index, float value) {
        FLOAT_HANDLE.set(buf(), index, value);
    }

    @Override
    public double getDouble(int index) {
        return (double) DOUBLE_HANDLE.get(buf(), index);
    }

    @Override
    public void putDouble(int index, double value) {
        DOUBLE_HANDLE.set(buf(), index, value);
    }

    @Override
    public void getBytes(int index, byte[] dst, int dstOffset, int length) {
        buf().get(index, dst, dstOffset, length);
    }

    @Override
    public void putBytes(int index, byte[] src, int srcOffset, int length) {
        buf().put(index, src, srcOffset, length);
    }

    @Override
    public void copyFrom(int index, MemoryRegion src, int srcIndex, int length) {
        if (!(src instanceof ByteBufferMemoryRegion other)) {
            throw new IllegalArgumentException(
                    "cannot copy from a " + src.getClass().getSimpleName() + " into a ByteBufferMemoryRegion");
        }
        buf().put(index, other.buf(), srcIndex, length);
    }

    @Override
    public boolean equalsBytes(int index, byte[] literal) {
        if (index < 0 || literal.length > capacity - index) {
            return false;
        }
        ByteBuffer b = buf();
        for (int i = 0; i < literal.length; i++) {
            if (b.get(index + i) != literal[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setMemory(int index, int length, byte value) {
        ByteBuffer b = buf();
        // Eight bytes at a time. This is called once per row -- the writer clears a row's header and
        // null bitmap before building it, because stale null bits from a previous row in reused
        // arena space would read as null fields in this one. A byte-at-a-time loop made that clear
        // cost ~50 iterations per row and showed up as the dominant term in the Profile A
        // benchmark, which is how it was found.
        long word = (value & 0xFFL) * 0x0101010101010101L;
        int i = 0;
        int aligned = length & ~7;
        while (i < aligned) {
            LONG_HANDLE.set(b, index + i, word);
            i += 8;
        }
        while (i < length) {
            b.put(index + i, value);
            i++;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            buffer = null;
        }
    }

    @Override
    public String toString() {
        return "ByteBufferMemoryRegion[" + capacity + "B" + (closed ? ", closed" : "") + "]";
    }
}
