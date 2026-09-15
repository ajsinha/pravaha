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

import java.util.function.LongConsumer;

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
 *
 * <p>Public because that argument does not stop at the ingest package. Anything driving a plugin
 * directly -- a test feeding a source into a pipeline, a tool draining one to a file -- needs the
 * same adapter, and a third and fourth copy would drift from these two.
 */
public final class DelegatingRowWriter implements RowWriter {

    private final BinaryRowWriter delegate;
    private final Runnable onCommit;
    private final LongConsumer onEventTime;
    private final Runnable onAbort;

    /**
     * The event time this row was given, held until the row is handed over.
     *
     * <p>Held rather than reported straight away, because the watermark is a statement about rows
     * the engine has -- and between the plugin setting a timestamp and the row reaching a lane, it
     * does not have it yet. Reporting early let the watermark run ahead of rows still in flight and
     * close their window without them.
     */
    private long pendingEventTime;

    private boolean hasEventTime;

    /** Without an observer: the row is written and nothing watches its event time. */
    public DelegatingRowWriter(BinaryRowWriter delegate, Runnable onCommit) {
        this(delegate, onCommit, nanos -> {});
    }

    public DelegatingRowWriter(BinaryRowWriter delegate, Runnable onCommit, LongConsumer onEventTime) {
        this(delegate, onCommit, onEventTime, DelegatingRowWriter::refuseAbort);
    }

    /**
     * With somewhere for an abandoned row to go.
     *
     * <p>Only safe when the writer is not pointed at a claimed inbox cell -- see {@link #abort()}.
     * The pump uses it when a dead-letter queue is attached, because then rows are assembled in a
     * staging buffer and a cell is claimed only once the row is known to be complete.
     */
    public DelegatingRowWriter(
            BinaryRowWriter delegate, Runnable onCommit, LongConsumer onEventTime, Runnable onAbort) {
        this.delegate = delegate;
        this.onCommit = onCommit;
        this.onEventTime = onEventTime;
        this.onAbort = onAbort;
    }

    private static void refuseAbort() {
        throw new UnsupportedOperationException(
                "a plugin aborted a row mid-write, which the ingest path cannot yet undo: the claimed "
                        + "inbox cell would stay unpublished and stall this lane. Report this -- it needs a "
                        + "cancel path on RowInbox, not a workaround here.");
    }

    public BinaryRowWriter delegate() {
        return delegate;
    }

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
        // Where ingest learns what time it is. The plugin sets the event time as it writes each
        // row, so this is the one place every row's timestamp passes through on its way in --
        // without the pump having to decode it back out again. Recorded here and reported at
        // commit: see pendingEventTime.
        pendingEventTime = nanos;
        hasEventTime = true;
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
        // After the hand-over, never before. onCommit is what publishes the row to a lane, and a
        // watermark told about a row the lane cannot yet see is a watermark that can close that
        // row's window without it.
        if (hasEventTime) {
            hasEventTime = false;
            onEventTime.accept(pendingEventTime);
        }
        return size;
    }

    @Override
    public void abort() {
        // On the fast path the cell is already claimed and unpublished, and the lane's drain stops
        // at an unpublished cell rather than skipping it -- so an aborted row would stall this
        // lane's input permanently. There is still no cancel path on RowInbox, so that case still
        // refuses.
        //
        // The other case is now real: with a dead-letter queue attached the pump assembles rows in
        // a staging buffer and claims a cell only on commit, so an abandoned row has claimed
        // nothing and there is nothing to undo. That is what the fourth constructor argument says.
        delegate.abort();
        onAbort.run();
    }
}
