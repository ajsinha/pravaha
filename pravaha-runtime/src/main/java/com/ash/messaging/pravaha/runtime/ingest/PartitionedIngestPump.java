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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.exec.JoinKeys;
import com.ash.messaging.pravaha.runtime.lane.Lane;

/**
 * An ingest pump that routes each row to the lane that owns its key.
 *
 * <p>A join on several lanes only works if a row and the rows it can match land on the same lane.
 * Nothing about a source partition guarantees that -- Kafka partitions by whatever the producer
 * chose, a file has no partitioning at all -- so the rows have to be redistributed on the way in,
 * by the join key, with the same hash the join itself uses.
 *
 * <p><strong>This costs a copy per row and there is no way around it.</strong> The lane cannot be
 * chosen until the key is known, the key is not known until the plugin has written the row, and the
 * plugin writes into whatever buffer it is given. So the row is written into a staging cell, hashed,
 * and copied into the chosen lane's inbox. That copy is what a shuffle costs; doing it here rather
 * than inside the engine means it happens once, at the edge, instead of once per hop.
 *
 * <p>Backpressure is the conservative reading: the pump pauses when the <em>fullest</em> target lane
 * is over the high watermark, because the next row may be for that lane and there is no way to know
 * before reading it. A single hot key filling one lane therefore slows the whole source, which is
 * correct -- the alternative is dropping the row or buffering it unboundedly, and both are worse.
 */
public final class PartitionedIngestPump implements AutoCloseable {

    private final PartitionReader reader;
    private final List<Lane> lanes;
    private final LaneChooser chooser;
    private final int input;
    private final int[] keyOrdinals;
    private final StreamSchema schema;
    private final BackpressurePolicy policy;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final MemoryRegion staging;

    private final AtomicLong rowsPumped = new AtomicLong();
    private final AtomicLong pauses = new AtomicLong();
    private final AtomicLong resumes = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final long[] rowsPerLane;

    private volatile boolean paused;

    /** Maps a key hash to a lane index, which is the lane group's virtual-partition assignment. */
    public interface LaneChooser {
        int laneFor(long keyHash);
    }

    public PartitionedIngestPump(
            PartitionReader reader,
            List<Lane> lanes,
            LaneChooser chooser,
            int input,
            StreamSchema schema,
            int[] keyOrdinals,
            BackpressurePolicy policy,
            MemoryAccess access) {
        this.reader = reader;
        this.lanes = List.copyOf(lanes);
        this.chooser = chooser;
        this.input = input;
        this.schema = schema;
        this.keyOrdinals = keyOrdinals.clone();
        this.policy = policy;
        this.layout = RowLayout.of(schema);
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);
        this.rowsPerLane = new long[this.lanes.size()];
        int cellBytes = this.lanes.get(0).inboxCellBytes();
        if (layout.fixedEnd() > cellBytes) {
            throw new PravahaException(
                    RuntimeErrors.BACKPRESSURED,
                    "stream '" + schema.name() + "' needs at least " + layout.fixedEnd()
                            + " bytes a row but inbox cells are " + cellBytes + ". Raise lane.inbox.cell.size.");
        }
        for (int ordinal : this.keyOrdinals) {
            JoinKeys.checkJoinable(schema, ordinal, schema.name());
        }
        this.staging = access.allocate(cellBytes);
    }

    /**
     * Polls once, routing up to {@code maxRecords} rows to their lanes.
     *
     * @return how many rows were delivered
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

    private RowWriter beginRow() {
        writer.begin(staging, 0);
        return new DelegatingRowWriter(writer, this::route);
    }

    /** Hashes the staged row's key and hands it to the lane that owns it. */
    private void route() {
        int length = writer.sizeSoFar();
        long hash = JoinKeys.hash(view.wrap(staging, 0), keyOrdinals, schema);
        int lane = chooser.laneFor(hash);
        if (!lanes.get(lane).offer(input, staging, 0, length)) {
            // Sized to fit before the poll, so this is bookkeeping being wrong rather than
            // backpressure -- and a row silently dropped here would be indistinguishable from a
            // join that failed to match it.
            rejected.incrementAndGet();
            throw new PravahaException(
                    RuntimeErrors.BACKPRESSURED,
                    "lane " + lane + "'s input " + input + " filled during a poll sized to fit. With rows routed "
                            + "by key, one lane can fill while others are empty; the free-cell estimate must be "
                            + "taken over the fullest lane, not the average.");
        }
        rowsPerLane[lane]++;
    }

    private void updateBackpressure() {
        double fill = fullestLane();
        if (!paused && fill >= policy.highWatermark()) {
            reader.pause();
            paused = true;
            pauses.incrementAndGet();
        } else if (paused && fill <= policy.lowWatermark()) {
            reader.resume();
            paused = false;
            resumes.incrementAndGet();
        }
    }

    /** The fullest target, because the next row's lane is unknown until it has been read. */
    private double fullestLane() {
        double highest = 0;
        for (Lane lane : lanes) {
            highest = Math.max(highest, lane.inboxFill(input));
        }
        return highest;
    }

    private int freeCells() {
        return (int) Math.max(0, Math.round((1.0 - fullestLane()) * lanes.get(0).inboxCells()));
    }

    /** Where the reader is, for the checkpoint. */
    public SourceOffset position() {
        return reader.position();
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

    public boolean isPaused() {
        return paused;
    }

    /** How many rows went to each lane, which is the only way to see a skewed key from outside. */
    public long[] rowsPerLane() {
        return rowsPerLane.clone();
    }

    @Override
    public void close() {
        staging.close();
        reader.close();
    }
}
