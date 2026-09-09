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
