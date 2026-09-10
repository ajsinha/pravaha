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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/** Collects a plugin's row as plain values, so a test can assert on it without an arena. */
final class ValueWriter implements RowWriter {

    private final StreamSchema schema;
    private final Consumer<Object[]> onCommit;
    private Object[] values;

    ValueWriter(StreamSchema schema, Consumer<Object[]> onCommit) {
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
        values[ordinal] = value == null ? null : value.clone();
        return this;
    }

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
        return 0;
    }

    @Override
    public void abort() {
        values = new Object[schema.fields().size()];
    }
}
