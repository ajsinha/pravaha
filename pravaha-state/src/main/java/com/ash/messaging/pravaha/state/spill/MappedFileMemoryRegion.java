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
package com.ash.messaging.pravaha.state.spill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * {@link MemoryRegion} over a file mapped into memory, for state a query holds that no longer fits
 * in RAM.
 *
 * <p>ADR-037 item B2. Deliberately a near-copy of {@code ByteBufferMemoryRegion}'s accessor pattern
 * rather than a new one invented for this class: {@link MappedByteBuffer} <em>is</em> a {@code
 * ByteBuffer}, the same {@link VarHandle}-based view accessors apply unchanged, and a second,
 * independently-drifting implementation of sixteen primitive accessors is a worse outcome than the
 * duplication -- this is the one place {@code pravaha-common}'s own implementation could not simply
 * be reused: it is package-private, final, and built to call {@code ByteBuffer.allocateDirect}
 * itself rather than accept a buffer that already exists.
 *
 * <p>Byte order is pinned to {@link ByteOrder#LITTLE_ENDIAN}, matching every other {@code
 * MemoryRegion} implementation, for the same reason: a row layout that read differently depending
 * on which tier happened to hold it would be a checkpoint that a different implementation reads
 * back wrong.
 *
 * <p><strong>{@link #copyFrom} does not assume its source is one of these.</strong> The two call
 * sites that copy <em>into</em> spillable state ({@code JoinSide.copyRow}, {@code
 * VariableKeyStateMap.createEntry}) always copy <em>from</em> a row arena, which is a plain
 * off-heap region, never a mapped file -- so unlike {@code ByteBufferMemoryRegion}'s own {@code
 * copyFrom}, which refuses a source of any other concrete type, this one goes through the public
 * {@link MemoryRegion} interface (a byte array round trip) and works with whatever it is given.
 * That costs an allocation on what is already the slow tier; it is not called on the row-processing
 * hot path, only when an entry crosses into overflow state.
 *
 * <p><strong>Release.</strong> Java 21 has no supported way to unmap a {@link MappedByteBuffer}
 * eagerly -- the same limitation {@code ByteBufferMemoryRegion} documents for a direct buffer, and
 * for the same underlying reason. {@link #close()} drops the reference, truncates the backing file to
 * nothing -- which frees its disk blocks at once, where a delete alone would leave them allocated
 * until the collector unmapped the buffer -- and best-effort deletes it; a failure of either is not
 * surfaced, because a leftover temp file is a cleanup nuisance and not a correctness problem for the
 * query that produced it.
 */
public final class MappedFileMemoryRegion implements MemoryRegion {

    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    private static final VarHandle SHORT_HANDLE = MethodHandles.byteBufferViewVarHandle(short[].class, ORDER);
    private static final VarHandle INT_HANDLE = MethodHandles.byteBufferViewVarHandle(int[].class, ORDER);
    private static final VarHandle LONG_HANDLE = MethodHandles.byteBufferViewVarHandle(long[].class, ORDER);
    private static final VarHandle FLOAT_HANDLE = MethodHandles.byteBufferViewVarHandle(float[].class, ORDER);
    private static final VarHandle DOUBLE_HANDLE = MethodHandles.byteBufferViewVarHandle(double[].class, ORDER);

    private final int capacity;
    private final Path file;
    private MappedByteBuffer buffer;
    private boolean closed;

    /** Told once, when the region closes: how the access that mapped it gives its bytes back to the quota. */
    private final Runnable onClose;

    MappedFileMemoryRegion(MappedByteBuffer buffer, Path file, int capacity, Runnable onClose) {
        buffer.order(ORDER);
        this.buffer = buffer;
        this.file = file;
        this.capacity = capacity;
        this.onClose = onClose;
    }

    private MappedByteBuffer buf() {
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
        // See the class javadoc: deliberately not restricted to one concrete source type. The
        // extra array is the price of that generality, paid only on the overflow tier.
        byte[] scratch = new byte[length];
        src.getBytes(srcIndex, scratch, 0, length);
        putBytes(index, scratch, 0, length);
    }

    @Override
    public boolean equalsBytes(int index, byte[] literal) {
        if (index < 0 || literal.length > capacity - index) {
            return false;
        }
        MappedByteBuffer b = buf();
        for (int i = 0; i < literal.length; i++) {
            if (b.get(index + i) != literal[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setMemory(int index, int length, byte value) {
        MappedByteBuffer b = buf();
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
        if (closed) {
            return;
        }
        closed = true;
        buffer = null;
        onClose.run();
        // Truncated before it is deleted. Java cannot unmap the buffer, and a deleted file's blocks
        // stay allocated for as long as any mapping of it lives -- which is until the collector
        // finds the buffer, however long that is. Truncating frees them now: compaction releases a
        // slab to give its disk back (ADR-044), and "at some later collection" is not that. Safe
        // because nothing reads through the buffer again -- every accessor goes through buf(),
        // which refuses a closed region before it touches the mapping.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(0);
        } catch (IOException e) {
            // Best-effort, for the same reason as the delete below.
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Best-effort, deliberately: see the class javadoc. A file this process cannot remove
            // right now is the operating system's problem, not a reason to fail a query that has
            // already finished with its state.
        }
    }

    @Override
    public String toString() {
        return "MappedFileMemoryRegion[" + capacity + "B, " + file + (closed ? ", closed" : "") + "]";
    }

    /** Wraps {@code IOException} so a mapping failure surfaces through the same unchecked path
     * every other allocation failure in this codebase does. */
    static RuntimeException wrap(IOException e) {
        return new UncheckedIOException("cannot map spill file: " + e.getMessage(), e);
    }
}
