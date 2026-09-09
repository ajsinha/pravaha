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
