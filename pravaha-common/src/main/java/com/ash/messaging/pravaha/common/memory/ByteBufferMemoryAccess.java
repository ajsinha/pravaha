/*
 * Copyright the Pravaha authors.
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
