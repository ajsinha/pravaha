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
