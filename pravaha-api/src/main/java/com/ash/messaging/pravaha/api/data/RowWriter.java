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
 * Cursor-style write access for building one row directly in an arena.
 *
 * <p>Fields must be written in ordinal order for variable-width columns, because their payload is
 * appended as it arrives. Fixed-width columns may be written in any order. Implementations validate
 * this in assertions rather than on every call, so the checks cost nothing in production.
 *
 * <p>Usage is always claim, write, commit -- {@link #abort()} discards a partially built row, which
 * is what a source plugin does when it hits a malformed record.
 */
public interface RowWriter {

    /** The schema being written. */
    StreamSchema schema();

    RowWriter setNull(int ordinal);

    RowWriter setBoolean(int ordinal, boolean value);

    RowWriter setByte(int ordinal, byte value);

    RowWriter setShort(int ordinal, short value);

    RowWriter setInt(int ordinal, int value);

    RowWriter setLong(int ordinal, long value);

    RowWriter setFloat(int ordinal, float value);

    RowWriter setDouble(int ordinal, double value);

    /** Writes a 128-bit unscaled decimal. */
    RowWriter setDecimal(int ordinal, long high, long low);

    RowWriter setBytes(int ordinal, byte[] value);

    /** Encodes as UTF-8. Allocates when {@code value} is not ASCII -- off the hot path only. */
    RowWriter setString(int ordinal, String value);

    /** Convenience for {@code weight(kind.weight())}; the weight is what is actually stored. */
    default RowWriter rowKind(RowKind kind) {
        return weight(kind.weight());
    }

    RowWriter weight(long weight);

    RowWriter eventTimestampNanos(long nanos);

    RowWriter sequence(long sequence);

    /**
     * Finalises the row and returns its byte offset within the owning region.
     *
     * @throws IllegalStateException if a NOT NULL field was never written
     */
    int commit();

    /** Discards the partially written row and releases its arena space. */
    void abort();
}
