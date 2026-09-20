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
package com.ash.messaging.pravaha.bindings.ingest;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A {@link RowWriter} that keeps the values instead of encoding them (ADR-048).
 *
 * <p>What the debugger's replay reads through. A plugin writes a record the only way it knows --
 * through a row writer -- and a debug session needs the values themselves, because it shows them
 * to a person and writes them into an exported fixture. Encoding them into a frame and decoding
 * them straight back would be two conversions and a schema to agree on.
 *
 * <p>The row's metadata comes with them: a weight of {@code -1} is a delete, and a session that
 * reported it as an insert would show a retraction as an arrival.
 */
final class ValueCapturingWriter implements RowWriter {

    /** Called once, when the row is committed. Never called for an aborted row. */
    @FunctionalInterface
    interface Captured {
        void row(Object[] values, long weight, long eventTimeNanos, long sequence);
    }

    private final StreamSchema schema;
    private final Captured captured;
    private Object[] values;
    private long weight = 1;
    private long eventTimeNanos;
    private long sequence;

    ValueCapturingWriter(StreamSchema schema, Captured captured) {
        this.schema = schema;
        this.captured = captured;
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
        // Kept as the pair the engine moves, because that is lossless; a debug session renders it
        // and a fixture cannot carry it, which is refused where the fixture is written rather than
        // by dropping the value here.
        values[ordinal] = new long[] {high, low};
        return this;
    }

    @Override
    public RowWriter setBytes(int ordinal, byte[] value) {
        values[ordinal] = value == null ? null : value.clone();
        return this;
    }

    @Override
    public RowWriter setString(int ordinal, String value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter weight(long value) {
        this.weight = value;
        return this;
    }

    @Override
    public RowWriter eventTimestampNanos(long nanos) {
        this.eventTimeNanos = nanos;
        return this;
    }

    @Override
    public RowWriter sequence(long value) {
        this.sequence = value;
        return this;
    }

    @Override
    public int commit() {
        captured.row(values, weight, eventTimeNanos, sequence);
        values = new Object[schema.fields().size()];
        weight = 1;
        eventTimeNanos = 0;
        sequence = 0;
        // No region and so no offset: the caller is a debug replay that keeps the values, not a
        // pump that publishes a frame at a position.
        return 0;
    }

    @Override
    public void abort() {
        values = new Object[schema.fields().size()];
        weight = 1;
        eventTimeNanos = 0;
        sequence = 0;
    }
}
