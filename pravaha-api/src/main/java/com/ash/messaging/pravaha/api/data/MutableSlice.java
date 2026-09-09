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
