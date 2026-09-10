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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A {@link RowWriter} that collects plain values instead of encoding a row.
 *
 * <p>For the one place where a row must outlive every arena in sight: a lookup cache. Everywhere
 * else in the engine a row is bytes in an arena and a flyweight over it, which is exactly right
 * while the arena is alive and exactly wrong for something kept across batches. Decoding to values
 * costs an object per column and buys an entry that cannot be invalidated by a rewind.
 *
 * <p>Boxing is acceptable here and nowhere else on the row path. A lookup is a network round trip;
 * an allocation per column is not what makes it slow.
 */
final class ValueRowWriter implements RowWriter {

    private final StreamSchema schema;
    private final Consumer<Object[]> onCommit;
    private Object[] values;

    ValueRowWriter(StreamSchema schema, Consumer<Object[]> onCommit) {
        this.schema = schema;
        this.onCommit = onCommit;
        this.values = new Object[schema.fields().size()];
    }

    @Override
    public StreamSchema schema() {
        return schema;
    }

    @Override
    public RowWriter setNull(int ordinal) {
        values[ordinal] = null;
        return this;
    }

    @Override
    public RowWriter setBoolean(int ordinal, boolean value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setByte(int ordinal, byte value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setShort(int ordinal, short value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setInt(int ordinal, int value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setLong(int ordinal, long value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setFloat(int ordinal, float value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setDouble(int ordinal, double value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setDecimal(int ordinal, long high, long low) {
        values[ordinal] = new long[] {high, low};
        return this;
    }

    @Override
    public RowWriter setString(int ordinal, String value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter setBytes(int ordinal, byte[] value) {
        // Copied, because a plugin is entitled to reuse the array it handed over -- and a cache
        // that aliased it would start returning whatever the next lookup happened to write.
        values[ordinal] = value == null ? null : value.clone();
        return this;
    }

    /** Ignored: a looked-up row is a fact about the store, not a change to it. */
    @Override
    public RowWriter weight(long weight) {
        return this;
    }

    @Override
    public RowWriter eventTimestampNanos(long nanos) {
        return this;
    }

    @Override
    public RowWriter sequence(long sequence) {
        return this;
    }

    @Override
    public int commit() {
        onCommit.accept(values);
        values = new Object[schema.fields().size()];
        // No bytes were written, so there is no offset to report. Callers of this writer collect
        // values through the consumer; a plugin that used the return value would be reading an
        // offset into a region that does not exist.
        return 0;
    }

    @Override
    public void abort() {
        values = new Object[schema.fields().size()];
    }
}
