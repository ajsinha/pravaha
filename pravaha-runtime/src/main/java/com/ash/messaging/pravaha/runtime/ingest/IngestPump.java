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

import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.lane.Lane;

/**
 * Moves rows from a plugin's reader into a lane, and applies backpressure when the lane cannot keep
 * up.
 *
 * <p>This is the missing half of the chain in design section 13.5. The lane already refuses rows
 * when its inbox is full; what turns that refusal into <em>the source slowing down</em> rather than
 * a hot loop discarding work is here: the pump watches the inbox fill and calls
 * {@link PartitionReader#pause()} at the high watermark, which a plugin implements as a Kafka pause,
 * a scan throttle, or an HTTP 429. Without it, backpressure stops at the engine boundary and the
 * lag becomes invisible -- which is the same as not having it.
 *
 * <p><strong>Rows are decoded straight into the lane's inbox.</strong> The pump claims a cell, hands
 * the plugin a writer positioned in it, and publishes on commit, so a record goes from the plugin's
 * decoder to the lane's memory once. The obvious implementation -- decode into a buffer, then copy
 * into the inbox -- is a second copy of every row on the hottest path in the system.
 *
 * <p><strong>It never asks for more than it can hold.</strong> A poll is bounded by the free cells
 * in the inbox, so the plugin is never handed a row the pump cannot place. That is what makes "no
 * unbounded buffer anywhere" (NFR-9) true at this boundary rather than aspirational: there is no
 * queue between the reader and the lane to grow.
 */
public final class IngestPump implements AutoCloseable {

    private final PartitionReader reader;
    private final Lane lane;
    private final BackpressurePolicy policy;
    private final RowLayout layout;
    private final BinaryRowWriter writer;

    private final AtomicLong rowsPumped = new AtomicLong();
    private final AtomicLong pauses = new AtomicLong();
    private final AtomicLong resumes = new AtomicLong();
    private final AtomicLong pausedNanos = new AtomicLong();

    private volatile boolean paused;
    private long pausedSince;
    private long claimed = -1L;

    public IngestPump(PartitionReader reader, Lane lane, StreamSchema schema, BackpressurePolicy policy) {
        this.reader = reader;
        this.lane = lane;
        this.policy = policy;
        this.layout = RowLayout.of(schema);
        this.writer = new BinaryRowWriter(layout);
        if (layout.fixedEnd() > lane.inboxCellBytes()) {
            throw new PravahaException(
                    RuntimeErrors.BACKPRESSURED,
                    "stream '" + schema.name() + "' needs at least " + layout.fixedEnd()
                            + " bytes a row but lane " + lane.laneId() + "'s inbox cells are "
                            + lane.inboxCellBytes()
                            + ". Raise lane.inbox.cell.size; a row that cannot fit is not a runtime condition.");
        }
    }

    /**
     * Polls once, moving up to {@code maxRecords} rows into the lane.
     *
     * <p>Checks the watermarks first, so a source that has fallen behind is paused before the next
     * poll rather than after it -- pausing after would let one more full batch in each time, which
     * is how a "bounded" buffer acquires a batch of slack per poll.
     *
     * @return how many rows were moved; zero when paused or when the source had nothing
     */
    public int pumpOnce(int maxRecords) {
        updateBackpressure();
        if (paused) {
            return 0;
        }
        int room = freeCells();
        if (room == 0) {
            return 0;
        }
        int moved = reader.poll(this::beginRow, Math.min(maxRecords, room));
        rowsPumped.addAndGet(moved);
        return moved;
    }

    /** Applies the hysteresis. Pausing and resuming are edge-triggered, never repeated per poll. */
    private void updateBackpressure() {
        double fill = lane.inboxFill();
        if (!paused && fill >= policy.highWatermark()) {
            reader.pause();
            paused = true;
            pausedSince = System.nanoTime();
            pauses.incrementAndGet();
        } else if (paused && fill <= policy.lowWatermark()) {
            reader.resume();
            paused = false;
            pausedNanos.addAndGet(System.nanoTime() - pausedSince);
            resumes.incrementAndGet();
        }
    }

    private int freeCells() {
        return (int) Math.max(0, Math.round((1.0 - lane.inboxFill()) * lane.inboxCells()));
    }

    /**
     * Hands the plugin a writer positioned in a claimed inbox cell.
     *
     * <p>Only ever called from inside {@link #pumpOnce}, which has already established there is
     * room -- so a claim failing here is a bug in the bookkeeping rather than backpressure, and it
     * says so instead of returning something the plugin cannot use.
     */
    private RowWriter beginRow() {
        claimed = lane.claim();
        if (claimed == com.ash.messaging.pravaha.common.queue.RowInbox.NO_SPACE) {
            throw new PravahaException(
                    RuntimeErrors.BACKPRESSURED,
                    "lane " + lane.laneId() + "'s inbox filled during a poll that was sized to fit. Either "
                            + "another producer is writing to this lane's inbox, which the single-writer ingest "
                            + "path does not allow, or the free-cell calculation is wrong.");
        }
        writer.begin(lane.inboxRegion(), lane.cellOffset(claimed));
        return new PublishOnCommit(writer, () -> lane.publish(claimed));
    }

    /** Where the reader is, for the checkpoint. */
    public SourceOffset position() {
        return reader.position();
    }

    public boolean isPaused() {
        return paused;
    }

    public long rowsPumped() {
        return rowsPumped.get();
    }

    public long pauseCount() {
        return pauses.get();
    }

    public long resumeCount() {
        return resumes.get();
    }

    /**
     * Nanoseconds spent with the source paused.
     *
     * <p>Against wall-clock this is {@code backpressure.ratio} -- the fraction of time the engine
     * spent unable to accept input, which design section 13.5 names as the primary capacity-planning
     * signal. A number near zero means headroom; a number near one means the lane is the limit.
     */
    public long pausedNanos() {
        return pausedNanos.get() + (paused ? System.nanoTime() - pausedSince : 0L);
    }

    @Override
    public void close() {
        reader.close();
    }

    /** Publishes the claimed cell when the plugin commits its row, and not before. */
    private record PublishOnCommit(BinaryRowWriter delegate, Runnable onCommit) implements RowWriter {

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
}
