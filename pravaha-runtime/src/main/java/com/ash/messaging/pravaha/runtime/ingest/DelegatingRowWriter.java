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
package com.ash.messaging.pravaha.runtime.ingest;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;

/**
 * A {@link RowWriter} that wraps the engine's writer and runs a hook when the plugin commits.
 *
 * <p>Shared by the two ingest paths because they differ only in what the hook does: the direct pump
 * publishes the inbox cell the plugin wrote into, the partitioned pump hashes the staged row and
 * hands it to the lane that owns its key. Everything before the commit is identical, and two copies
 * of thirteen delegating setters is two places for them to drift.
 */
record DelegatingRowWriter(BinaryRowWriter delegate, Runnable onCommit) implements RowWriter {

    @Override
    public StreamSchema schema() {
        return delegate.schema();
    }

    @Override
    public RowWriter setNull(int ordinal) {
        delegate.setNull(ordinal);
        return this;
    }

    @Override
    public RowWriter setBoolean(int ordinal, boolean value) {
        delegate.setBoolean(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setByte(int ordinal, byte value) {
        delegate.setByte(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setShort(int ordinal, short value) {
        delegate.setShort(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setInt(int ordinal, int value) {
        delegate.setInt(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setLong(int ordinal, long value) {
        delegate.setLong(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setFloat(int ordinal, float value) {
        delegate.setFloat(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setDouble(int ordinal, double value) {
        delegate.setDouble(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setDecimal(int ordinal, long high, long low) {
        delegate.setDecimal(ordinal, high, low);
        return this;
    }

    @Override
    public RowWriter setBytes(int ordinal, byte[] value) {
        delegate.setBytes(ordinal, value);
        return this;
    }

    @Override
    public RowWriter setString(int ordinal, String value) {
        delegate.setString(ordinal, value);
        return this;
    }

    @Override
    public RowWriter weight(long weight) {
        delegate.weight(weight);
        return this;
    }

    @Override
    public RowWriter eventTimestampNanos(long nanos) {
        delegate.eventTimestampNanos(nanos);
        return this;
    }

    @Override
    public RowWriter sequence(long sequence) {
        delegate.sequence(sequence);
        return this;
    }

    @Override
    public int commit() {
        int size = delegate.commit();
        onCommit.run();
        return size;
    }

    @Override
    public void abort() {
        // The cell stays claimed and unpublished. The lane's drain stops at an unpublished cell
        // rather than skipping it, so an aborted row would stall this lane's input permanently.
        // Nothing in the SPI aborts today; if something starts to, this needs a cancel path on
        // the inbox rather than a comment.
        delegate.abort();
        throw new UnsupportedOperationException(
                "a plugin aborted a row mid-write, which the ingest path cannot yet undo: the claimed "
                        + "inbox cell would stay unpublished and stall this lane. Report this -- it needs a "
                        + "cancel path on RowInbox, not a workaround here.");
    }
}
