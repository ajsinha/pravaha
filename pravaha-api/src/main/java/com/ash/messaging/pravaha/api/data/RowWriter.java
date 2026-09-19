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

    /**
     * Fills a column the engine did not ask the source for (a pushed projection,
     * {@code ReadRequest.columns()}), so the row can still be committed.
     *
     * <p>Null where the column allows it, and the type's zero where it does not: a {@code NOT NULL}
     * column has to hold <em>something</em> for the row to commit, and the engine never reads a
     * column it left out of its request, so which value is immaterial -- the planner only leaves a
     * column out once nothing above the scan reads it. Like every other setter, variable-width
     * columns must still be written in ordinal order.
     */
    default RowWriter setUnread(int ordinal) {
        PravahaType type = schema().field(ordinal).type();
        if (type.nullable()) {
            return setNull(ordinal);
        }
        return switch (type.typeName()) {
            case BOOLEAN -> setBoolean(ordinal, false);
            case INT8 -> setByte(ordinal, (byte) 0);
            case INT16 -> setShort(ordinal, (short) 0);
            case INT32, DATE -> setInt(ordinal, 0);
            case INT64, TIME, TIMESTAMP_LTZ -> setLong(ordinal, 0L);
            case FLOAT32 -> setFloat(ordinal, 0f);
            case FLOAT64 -> setDouble(ordinal, 0d);
            case DECIMAL -> setDecimal(ordinal, 0L, 0L);
            case STRING -> setString(ordinal, "");
            case BYTES, ARRAY, MAP, ROW -> setBytes(ordinal, new byte[0]);
        };
    }

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
