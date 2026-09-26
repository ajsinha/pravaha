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
package com.ash.messaging.pravaha.it.qa.perf;

import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;

/**
 * A sink that counts committed rows and keeps nothing.
 *
 * <p>The counter is the reason it exists. A throughput harness whose sink discards rows can be
 * fed an empty stream, a predicate that matches nothing or a pipeline that was never started, and
 * it will report a magnificent number for doing no work at all. Every measurement in this package
 * asserts against {@link #committed()} afterwards, so a run that moved no rows fails loudly rather
 * than being read as a fast one.
 *
 * <p>The writer accepts and drops field values, which keeps the sink out of the measurement --
 * what is being timed is the path up to the sink, not the sink's own encoding. What it does not
 * drop is the commit: the count is taken there, which is the point at which the engine says a row
 * is output.
 */
final class CountingRowOutput implements RowOutput {

    private final AtomicLong committed = new AtomicLong();

    /**
     * Counted by the lane thread alone and published once a batch.
     *
     * <p>It was {@code committed.incrementAndGet()} per row: a locked instruction on every emitted
     * row, into an {@code AtomicLong} allocated next to the other lanes' sinks' counters by the thread
     * that built them all -- so eight lanes' counters shared cache lines, and the harness measured
     * its own false sharing as the engine's scaling (gate P2, 2026-09-26).
     */
    private long counted;

    private final AtomicLong batches = new AtomicLong();
    private final StreamSchema schema;
    private final Writer writer = new Writer();

    CountingRowOutput(StreamSchema schema) {
        this.schema = schema;
    }

    @Override
    public RowWriter begin() {
        return writer;
    }

    @Override
    public void endOfBatch() {
        committed.lazySet(counted);
        batches.lazySet(batches.get() + 1);
    }

    long committed() {
        return committed.get();
    }

    long batches() {
        return batches.get();
    }

    void reset() {
        counted = 0;
        committed.set(0);
        batches.set(0);
    }

    /** One instance per sink, written only by that sink's lane thread. */
    private final class Writer implements RowWriter {

        @Override
        public StreamSchema schema() {
            return schema;
        }

        @Override
        public RowWriter setNull(int ordinal) {
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
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
            counted++;
            return 0;
        }

        @Override
        public void abort() {}
    }
}
