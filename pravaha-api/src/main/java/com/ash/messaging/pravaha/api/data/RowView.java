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
 * Zero-copy, cursor-style read access to one row in an arena.
 *
 * <p>Field access is by ordinal, resolved at code-generation time, so a read is a single load at a
 * constant offset rather than a hash lookup (design section 8.4). Implementations are mutable cursors and
 * are deliberately <em>not</em> thread-safe: each lane owns its own, which is what removes locking
 * from the hot path entirely.
 *
 * <p>Generated code never calls {@link #getString(int)} -- it compares UTF-8 bytes directly via
 * {@link #getBytes(int, MutableSlice)}. The string accessor exists for the debugger, the dead-letter
 * queue and logging, none of which are on the hot path.
 */
public interface RowView {

    /** Byte offset of this row within its owning region. */
    int offset();

    /** Total encoded length of this row in bytes. */
    int length();

    /** The schema this row was encoded against. */
    StreamSchema schema();

    /**
     * Presentation of {@link #weight()} for sinks and clients.
     *
     * <p>Derived from the sign of the weight rather than stored: internally there is only
     * arithmetic, and inserts, updates and deletes stop being three cases (design section 9.2).
     */
    default RowKind rowKind() {
        return RowKind.ofWeight(weight());
    }

    /**
     * Z-set weight (design section 9.2). Positive adds, negative retracts. Never zero on a live row: a
     * zero-weight row has been consolidated away and must not propagate.
     */
    long weight();

    /** Event time in nanoseconds since epoch, UTC. */
    long eventTimestampNanos();

    /** Monotonic per-partition sequence, used for ordering, dedupe and checkpoint offsets. */
    long sequence();

    boolean isNull(int ordinal);

    boolean getBoolean(int ordinal);

    byte getByte(int ordinal);

    short getShort(int ordinal);

    int getInt(int ordinal);

    long getLong(int ordinal);

    float getFloat(int ordinal);

    double getDouble(int ordinal);

    /** High 64 bits of a 128-bit unscaled decimal. */
    long getDecimalHigh(int ordinal);

    /** Low 64 bits of a 128-bit unscaled decimal. */
    long getDecimalLow(int ordinal);

    /** Points {@code out} at this field's bytes without copying. Returns {@code out}. */
    MutableSlice getBytes(int ordinal, MutableSlice out);

    /** Materialises a {@code String}. Allocates -- off the hot path only. */
    String getString(int ordinal);
}
