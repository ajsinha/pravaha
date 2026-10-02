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
package com.ash.messaging.pravaha.testkit;

import java.util.Arrays;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A {@link RowWriter} that captures values instead of encoding them.
 *
 * <p>Written because four test suites had each grown their own copy of the same delegating writer,
 * and a fifth was about to. The duplication is not the real cost: each copy was a place where an
 * assertion could quietly depend on rows that no longer exist, since a real writer produces
 * flyweights into an arena and a test that keeps them past the arena's reset is reading freed
 * memory. This copies out, so what a test holds is what it saw.
 *
 * <p>Values arrive as boxed objects, which no production path would tolerate and no test cares
 * about: a test that allocates per row is a test, and one that reads the wrong bytes is a bug that
 * looks like a product bug.
 */
public final class CapturingRowWriter implements RowWriter {

    /** One captured row: its column values, its Z-set weight and its timestamps. */
    @SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the array's contents
    public record Captured(Object[] values, long weight, long eventTimeNanos, long sequence) {

        /** A column as a {@code long}, which is what most assertions want. */
        public long asLong(int ordinal) {
            Object value = values[ordinal];
            if (value == null) {
                throw new IllegalStateException("column " + ordinal + " is null");
            }
            return ((Number) value).longValue();
        }

        public String asString(int ordinal) {
            return (String) values[ordinal];
        }

        public boolean isNull(int ordinal) {
            return values[ordinal] == null;
        }

        @Override
        public String toString() {
            return Arrays.toString(values) + " weight=" + weight;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Captured captured
                    && Arrays.equals(values, captured.values)
                    && weight == captured.weight
                    && eventTimeNanos == captured.eventTimeNanos
                    && sequence == captured.sequence;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values) * 31 + Long.hashCode(weight);
        }
    }

    private final StreamSchema schema;
    private final Consumer<Captured> onCommit;
    private Object[] values;
    private long weight = 1L;
    private long eventTime;
    private long sequence;

    public CapturingRowWriter(StreamSchema schema, Consumer<Captured> onCommit) {
        this.schema = schema;
        this.onCommit = onCommit;
        this.values = new Object[schema.fieldCount()];
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
    public RowWriter setBytes(int ordinal, byte[] value) {
        values[ordinal] = value.clone();
        return this;
    }

    @Override
    public RowWriter setString(int ordinal, String value) {
        values[ordinal] = value;
        return this;
    }

    @Override
    public RowWriter weight(long rowWeight) {
        this.weight = rowWeight;
        return this;
    }

    @Override
    public RowWriter eventTimestampNanos(long nanos) {
        this.eventTime = nanos;
        return this;
    }

    @Override
    public RowWriter sequence(long rowSequence) {
        this.sequence = rowSequence;
        return this;
    }

    @Override
    public int commit() {
        onCommit.accept(new Captured(values.clone(), weight, eventTime, sequence));
        reset();
        return values.length * Long.BYTES;
    }

    @Override
    public void abort() {
        reset();
    }

    private void reset() {
        values = new Object[schema.fieldCount()];
        weight = 1L;
        eventTime = 0;
        sequence = 0;
    }
}
