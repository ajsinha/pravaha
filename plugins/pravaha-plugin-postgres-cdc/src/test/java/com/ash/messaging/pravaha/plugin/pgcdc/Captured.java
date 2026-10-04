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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;

/**
 * A record sink that keeps each committed row as its weight and values, for assertions that read
 * like the Z-set they describe: {@code "-1 [42, silver]"}.
 */
final class Captured implements PartitionReader.RecordSink {

    /** One committed row. */
    record Row(long weight, List<Object> values, long eventTimeNanos) {
        @Override
        public String toString() {
            return (weight > 0 ? "+" : "") + weight + " " + values;
        }
    }

    private final StreamSchema schema;
    private final List<Row> rows = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();
    private boolean acceptRejects;

    Captured(StreamSchema schema) {
        this.schema = schema;
    }

    Captured acceptingRejects() {
        this.acceptRejects = true;
        return this;
    }

    List<Row> rows() {
        return rows;
    }

    List<String> texts() {
        return rows.stream().map(Row::toString).toList();
    }

    List<String> rejected() {
        return rejected;
    }

    void clear() {
        rows.clear();
    }

    @Override
    public boolean reject(byte[] raw, String sourceOffset, String reason) {
        rejected.add(reason);
        return acceptRejects;
    }

    /** Polls until {@code done} or the deadline, returning how many polls delivered something. */
    static void pollUntil(PartitionReader reader, Captured sink, int max, BooleanSupplier done, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting; captured so far: " + sink.texts());
            }
            if (reader.poll(sink, max) == 0) {
                PgServer.sleep(10);
            }
        }
    }

    @Override
    public RowWriter beginRow() {
        Object[] values = new Object[schema.fieldCount()];
        return new RowWriter() {
            private long weight;
            private long eventTime;

            @Override
            public StreamSchema schema() {
                return schema;
            }

            private RowWriter set(int ordinal, @Nullable Object value) {
                values[ordinal] = value;
                return this;
            }

            @Override
            public RowWriter setNull(int ordinal) {
                return set(ordinal, null);
            }

            @Override
            public RowWriter setBoolean(int ordinal, boolean value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setByte(int ordinal, byte value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setShort(int ordinal, short value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setInt(int ordinal, int value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setLong(int ordinal, long value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setFloat(int ordinal, float value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setDouble(int ordinal, double value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setDecimal(int ordinal, long high, long low) {
                return set(
                        ordinal,
                        java.math.BigInteger.valueOf(high)
                                .shiftLeft(64)
                                .add(java.math.BigInteger.valueOf(low)
                                        .and(java.math.BigInteger.ONE
                                                .shiftLeft(64)
                                                .subtract(java.math.BigInteger.ONE))));
            }

            @Override
            public RowWriter setBytes(int ordinal, byte[] value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter setString(int ordinal, String value) {
                return set(ordinal, value);
            }

            @Override
            public RowWriter weight(long value) {
                this.weight = value;
                return this;
            }

            @Override
            public RowWriter eventTimestampNanos(long nanos) {
                this.eventTime = nanos;
                return this;
            }

            @Override
            public RowWriter sequence(long sequence) {
                return this;
            }

            @Override
            public int commit() {
                rows.add(new Row(weight, Arrays.asList(values.clone()), eventTime));
                return 1;
            }

            @Override
            public void abort() {
                // Nothing kept until commit.
            }
        };
    }
}
