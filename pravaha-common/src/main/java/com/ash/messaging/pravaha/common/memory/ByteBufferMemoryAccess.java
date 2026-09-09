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
 * The default {@link MemoryAccess}: direct {@code ByteBuffer}s addressed through {@code VarHandle}s.
 *
 * <p>Chosen as the default for one decisive reason -- it requires no JVM flags. See
 * {@link ByteBufferMemoryRegion} for why that outweighs any marginal throughput difference.
 */
public final class ByteBufferMemoryAccess implements MemoryAccess {

    /** The single instance; the implementation holds no mutable state. */
    public static final ByteBufferMemoryAccess INSTANCE = new ByteBufferMemoryAccess();

    private ByteBufferMemoryAccess() {}

    @Override
    public String name() {
        return "bytebuffer";
    }

    @Override
    public MemoryRegion allocate(int bytes) {
        return allocate(bytes, CACHE_LINE_BYTES);
    }

    @Override
    public MemoryRegion allocate(int bytes, int alignment) {
        Allocations.checkSize(bytes);
        Allocations.checkAlignment(alignment);
        return new ByteBufferMemoryRegion(bytes, alignment);
    }
}
