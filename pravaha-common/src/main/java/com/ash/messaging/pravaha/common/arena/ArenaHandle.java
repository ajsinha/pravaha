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
package com.ash.messaging.pravaha.common.arena;

/**
 * Encodes a slab index and a byte offset into a single {@code long}.
 *
 * <p>A record would be the obvious shape and is exactly wrong here: the hot path allocates one of
 * these per row, and an object per row is the cost this whole design exists to avoid. Packing into
 * a primitive keeps allocation at zero and the encode/decode at one shift and one mask.
 *
 * <p>Layout: slab index in the high 32 bits, offset in the low 32.
 */
public final class ArenaHandle {

    /** Returned by an allocation that could not be satisfied. Never a valid handle. */
    public static final long NULL = -1L;

    private ArenaHandle() {}

    public static long of(int slab, int offset) {
        return ((long) slab << 32) | (offset & 0xFFFFFFFFL);
    }

    public static int slab(long handle) {
        return (int) (handle >>> 32);
    }

    public static int offset(long handle) {
        return (int) handle;
    }

    /** Renders a handle for diagnostics. Off the hot path. */
    public static String describe(long handle) {
        return handle == NULL
                ? "ArenaHandle[null]"
                : "ArenaHandle[slab=" + slab(handle) + ", offset=" + offset(handle) + "]";
    }
}
