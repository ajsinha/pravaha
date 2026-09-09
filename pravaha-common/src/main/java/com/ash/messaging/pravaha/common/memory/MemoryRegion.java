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

/**
 * A contiguous, index-addressed block of off-heap memory.
 *
 * <p>Every accessor is a single load or store at a constant offset once the JIT has inlined it,
 * which is what keeps field access at roughly four cycles rather than the hundreds a hash lookup
 * costs (design section 29.1).
 *
 * <p>Deliberately not thread-safe. Each lane owns its own regions; that ownership is what removes
 * locking from the hot path entirely (design section 13.1). Sharing one across threads is a bug, not a
 * performance trade-off.
 *
 * <p>Indices are {@code int} rather than absolute {@code long} addresses on purpose: it keeps
 * lifetime tied to the region object, so a use-after-free is impossible by construction rather
 * than by discipline.
 */
public interface MemoryRegion extends AutoCloseable {

    /** Usable size in bytes. */
    int capacity();

    boolean getBoolean(int index);

    void putBoolean(int index, boolean value);

    byte getByte(int index);

    void putByte(int index, byte value);

    short getShort(int index);

    void putShort(int index, short value);

    int getInt(int index);

    void putInt(int index, int value);

    long getLong(int index);

    void putLong(int index, long value);

    float getFloat(int index);

    void putFloat(int index, float value);

    double getDouble(int index);

    void putDouble(int index, double value);

    /** Copies {@code length} bytes from this region into {@code dst}. */
    void getBytes(int index, byte[] dst, int dstOffset, int length);

    /** Copies {@code length} bytes from {@code src} into this region. */
    void putBytes(int index, byte[] src, int srcOffset, int length);

    /** Copies within or between regions. {@code src} may be {@code this}. */
    void copyFrom(int index, MemoryRegion src, int srcIndex, int length);

    /**
     * Compares {@code literal.length} bytes at {@code index} against {@code literal} without
     * allocating.
     *
     * <p>This is what generated code uses for {@code WHERE status = 'COMPLETED'} -- a UTF-8 byte
     * comparison, never a {@code String} materialisation (design section 12.3).
     */
    boolean equalsBytes(int index, byte[] literal);

    /** Sets {@code length} bytes to {@code value}. */
    void setMemory(int index, int length, byte value);

    /** Releases the underlying memory. Idempotent. */
    @Override
    void close();
}
