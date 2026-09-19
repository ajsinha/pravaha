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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;

/**
 * Collects rows with their weights, and keeps the Z-set they sum to -- what a view over them would
 * hold. Used to check that a delete-detecting reader's output sums to the store.
 */
final class WeightedCollector implements PartitionReader.RecordSink {

    /** One emitted row: its values, weight and event time. */
    record Emitted(List<Object> values, long weight, long eventTimeNanos) {}

    private final StreamSchema schema;
    final List<Emitted> rows = new ArrayList<>();

    /** Row values to summed weight, with zero weights removed. */
    final Map<List<Object>, Long> view = new HashMap<>();

    WeightedCollector(StreamSchema schema) {
        this.schema = schema;
    }

    /** A collector starting from a copy of another's view, as a restored checkpoint would. */
    static WeightedCollector restoredFrom(StreamSchema schema, Map<List<Object>, Long> view) {
        WeightedCollector collector = new WeightedCollector(schema);
        collector.view.putAll(view);
        return collector;
    }

    /**
     * Polls until a poll returns nothing and, for a delete-detecting reader, its pass is over -- such
     * a reader returns nothing from a poll that only read unchanged rows, part way through a pass.
     */
    WeightedCollector drain(PartitionReader reader) {
        int polled;
        do {
            polled = reader.poll(this, 7);
        } while (polled > 0 || (reader instanceof DetectingTokenRangeReader detecting && detecting.passInProgress()));
        return this;
    }

    void clearRows() {
        rows.clear();
    }

    @Override
    public RowWriter beginRow() {
        return new Writer();
    }

    private final class Writer implements RowWriter {
        private final Object[] values = new Object[schema.fieldCount()];
        private long weight = 1;
        private long eventTime;

        @Override
        public StreamSchema schema() {
            return schema;
        }

        private RowWriter set(int ordinal, Object value) {
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
            return set(ordinal, List.of(high, low));
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            return set(ordinal, value == null ? null : Arrays.toString(value));
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            return set(ordinal, value);
        }

        @Override
        public RowWriter weight(long weight) {
            this.weight = weight;
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
            List<Object> row = Arrays.asList(values.clone());
            rows.add(new Emitted(row, weight, eventTime));
            view.merge(row, weight, Long::sum);
            if (view.get(row) == 0L) {
                view.remove(row);
            }
            return 0;
        }

        @Override
        public void abort() {}
    }
}
