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

    private final int input;

    private volatile boolean paused;
    private long pausedSince;
    private long claimed = -1L;

    public IngestPump(PartitionReader reader, Lane lane, StreamSchema schema, BackpressurePolicy policy) {
        this(reader, lane, 0, schema, policy);
    }

    /**
     * Feeds one of a lane's inputs.
     *
     * <p>The input index is what makes a join feedable: each side has its own inbox, so each side
     * backpressures on its own occupancy. Sharing one would mean the faster side's burst pausing
     * the slower side's source, which is the opposite of what is needed -- a join waiting on its
     * right side wants the right side to go faster, not the left to be throttled.
     *
     * @param input which of the lane's inboxes to write into
     */
    public IngestPump(PartitionReader reader, Lane lane, int input, StreamSchema schema, BackpressurePolicy policy) {
        this.reader = reader;
        this.lane = lane;
        this.input = input;
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
        double fill = lane.inboxFill(input);
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
        return (int) Math.max(0, Math.round((1.0 - lane.inboxFill(input)) * lane.inboxCells()));
    }

    /**
     * Hands the plugin a writer positioned in a claimed inbox cell.
     *
     * <p>Only ever called from inside {@link #pumpOnce}, which has already established there is
     * room -- so a claim failing here is a bug in the bookkeeping rather than backpressure, and it
     * says so instead of returning something the plugin cannot use.
     */
    private RowWriter beginRow() {
        claimed = lane.claim(input);
        if (claimed == com.ash.messaging.pravaha.common.queue.RowInbox.NO_SPACE) {
            throw new PravahaException(
                    RuntimeErrors.BACKPRESSURED,
                    "lane " + lane.laneId() + "'s inbox filled during a poll that was sized to fit. Either "
                            + "another producer is writing to this lane's inbox, which the single-writer ingest "
                            + "path does not allow, or the free-cell calculation is wrong.");
        }
        writer.begin(lane.inboxRegion(input), lane.cellOffset(input, claimed));
        return new DelegatingRowWriter(writer, () -> lane.publish(input, claimed));
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
}
