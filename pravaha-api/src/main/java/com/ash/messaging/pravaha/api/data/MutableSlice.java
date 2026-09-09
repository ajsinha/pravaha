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
package com.ash.messaging.pravaha.api.data;

/**
 * A reusable window onto bytes that live somewhere else -- an offset and a length, never a copy.
 *
 * <p>Exists so that reading a variable-width field costs no allocation: the caller owns one slice
 * and the accessor repoints it. The offset is relative to whatever region the row itself lives in,
 * which the caller already holds; carrying a raw address here would let a slice outlive its memory.
 *
 * <p>Deliberately mutable and deliberately not thread-safe -- each lane owns its own
 * (design section 8.4).
 */
public final class MutableSlice {

    private int offset;
    private int length;

    /** Byte offset within the owning region. */
    public int offset() {
        return offset;
    }

    public int length() {
        return length;
    }

    /** Repoints this slice. Returns {@code this} so accessors can be chained. */
    public MutableSlice wrap(int newOffset, int newLength) {
        if (newOffset < 0) {
            throw new IllegalArgumentException("offset must be non-negative, got " + newOffset);
        }
        if (newLength < 0) {
            throw new IllegalArgumentException("length must be non-negative, got " + newLength);
        }
        this.offset = newOffset;
        this.length = newLength;
        return this;
    }

    /** Resets to an empty slice pointing at nothing. */
    public MutableSlice clear() {
        this.offset = 0;
        this.length = 0;
        return this;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    @Override
    public String toString() {
        return "MutableSlice[@" + offset + ", " + length + "B]";
    }
}
