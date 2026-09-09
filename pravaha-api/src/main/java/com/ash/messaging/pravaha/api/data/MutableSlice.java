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
package com.ash.messaging.pravaha.api.data;

/**
 * A reusable window onto bytes that live somewhere else.
 *
 * <p>Exists so that reading a variable-width field costs no allocation: the caller owns one slice
 * and the accessor repoints it. Deliberately mutable, deliberately not thread-safe -- each lane
 * owns its own (design section 8.4).
 */
public final class MutableSlice {

    private long address;
    private int length;

    public long address() {
        return address;
    }

    public int length() {
        return length;
    }

    /** Repoints this slice. Returns {@code this} so accessors can be chained. */
    public MutableSlice wrap(long newAddress, int newLength) {
        if (newLength < 0) {
            throw new IllegalArgumentException("length must be non-negative, got " + newLength);
        }
        this.address = newAddress;
        this.length = newLength;
        return this;
    }

    /** Resets to an empty slice pointing nowhere. */
    public MutableSlice clear() {
        this.address = 0L;
        this.length = 0;
        return this;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    @Override
    public String toString() {
        return "MutableSlice[0x" + Long.toHexString(address) + ", " + length + "B]";
    }
}
