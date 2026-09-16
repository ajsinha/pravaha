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
package com.ash.messaging.pravaha.serving;

import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.row.Decimals;

/**
 * A {@link RowWriter} that collects a result row as plain values.
 *
 * <p>A result leaves the engine and outlives every arena in it, so it cannot stay a flyweight over
 * memory the pipeline is about to rewind. Decoding to values costs an object per column and buys a
 * row the caller can hold.
 *
 * <p>Boxing on a result path is acceptable in a way it never is on the row path. A request/response
 * query is dominated by the client's round trip; an allocation per column is not what makes it slow.
 */
final class ValueCollectingWriter implements RowWriter {

    private final StreamSchema schema;
    private final Consumer<Object[]> onCommit;
    private Object[] values;

    ValueCollectingWriter(StreamSchema schema, Consumer<Object[]> onCommit) {
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

    /**
     * A {@code BigDecimal}, not the two raw limbs.
     *
     * <p>Finding TY-19. This handed back {@code new long[]{high, low}} -- the storage form, with the
     * scale left behind in the schema. Nothing outside this engine can read that: it reached a
     * caller as {@code [J@301434fb}, an answer in the shape of an answer. Until TY-19 was fixed the
     * question never arose, because a DECIMAL column failed the scan before any row was produced.
     *
     * <p>The allocation is the one this class exists to make. A result leaves the engine and
     * outlives every arena in it; the row path still adds decimals as two-limb integers and this
     * never runs there (see {@code Decimals}).
     */
    @Override
    public RowWriter setDecimal(int ordinal, long high, long low) {
        values[ordinal] = Decimals.toBigDecimal(
                high, low, ((DecimalType) schema.field(ordinal).type()).scale());
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
