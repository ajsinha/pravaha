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

import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.data.RowKind;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Where a query writes when its answer is meant to be read rather than shipped.
 *
 * <p>The other half of design section 17: the engine already produces a keyed changelog, so serving
 * it is a matter of applying that changelog to an index instead of writing it to a sink. A query
 * that serves and a query that writes differ in where this object sends the rows and nowhere else.
 *
 * <p>Rows are staged into values and applied on commit, because a view keys on the finished row and
 * a writer fills one a field at a time. The weight comes straight through -- a retraction is a
 * removal, an insert is an upsert -- so there is no translation layer to get wrong.
 *
 * <p><strong>Applying is not committing.</strong> Rows land in the view's overlay and become
 * readable when the frontier commits, which the engine does at a checkpoint boundary. Committing per
 * row would make every intermediate state of a batch readable, and a consistent read would then be
 * consistent with nothing.
 */
public final class ViewSink {

    private final ServedView view;
    private final StreamSchema schema;
    private final AtomicLong frontier = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong rowsApplied = new AtomicLong();

    public ViewSink(ServedView view, StreamSchema schema) {
        this.view = view;
        this.schema = schema;
    }

    /** A writer the engine can fill and commit, like any other sink's. */
    public RowWriter begin() {
        return new StagedRow();
    }

    /**
     * Publishes everything applied so far.
     *
     * <p>Called by whatever owns the query's frontier -- a checkpoint, a watermark, the end of a
     * batch. The view does not decide when it is consistent; the engine does, because only the
     * engine knows what a complete prefix of the input is.
     */
    public void commit(long committedFrontier) {
        view.commit(committedFrontier);
    }

    public long rowsApplied() {
        return rowsApplied.get();
    }

    public ServedView view() {
        return view;
    }

    /** Collects values, then applies the finished row. */
    private final class StagedRow implements RowWriter {

        private Object[] values = new Object[schema.fields().size()];
        private long weight = 1;
        private long eventTime;
        private long sequence;

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
        public RowWriter rowKind(RowKind kind) {
            this.weight = kind == RowKind.DELETE || kind == RowKind.UPDATE_BEFORE ? -1 : 1;
            return this;
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
        public RowWriter sequence(long value) {
            this.sequence = value;
            return this;
        }

        @Override
        public int commit() {
            view.applyValues(values, weight, Math.max(eventTime, sequence));
            frontier.accumulateAndGet(Math.max(eventTime, sequence), Math::max);
            rowsApplied.incrementAndGet();
            values = new Object[schema.fields().size()];
            weight = 1;
            return 0;
        }

        @Override
        public void abort() {
            values = new Object[schema.fields().size()];
            weight = 1;
        }
    }

    /** The furthest input position any applied row reflects. */
    public long appliedFrontier() {
        return frontier.get();
    }
}
