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

    /**
     * How long this pump has spent with nowhere to put a row, in episodes rather than per row.
     *
     * <p>One clock for the fan-out, not one per lane: this pump stops when the <em>fullest</em>
     * lane has no room, because a poll cannot be given back and a row it cannot place would have
     * to be dropped. So the episode is the fan-out's, and {@link #freeCells} already takes the
     * minimum. The lane it is attributed to is the one that was full.
     */
    private final BackpressureClock blocked = new BackpressureClock();

    /** Whose writer this is, for a lane several queries share. */
    private String queryId = "";

    /** Serialises a poll against a checkpoint's reading of the offset. See {@link #freezeIngest}. */
    private final java.util.concurrent.locks.ReentrantLock ingest = new java.util.concurrent.locks.ReentrantLock();

    private volatile boolean paused;
    private java.util.function.LongConsumer eventTimeObserver = nanos -> {};

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
                            + " bytes a row but inbox cells are " + cellBytes
                            + ". Raise pravaha.lane.inbox.cell-bytes.");
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
        // Held for the whole poll, so a checkpoint sees this pump between rows. The reason is the
        // same as IngestPump.freezeIngest's and matters more here: one poll fans rows out to every
        // lane, so an offset read while a poll was in flight would be ahead of some lanes' state
        // and behind others'.
        ingest.lock();
        try {
            updateBackpressure();
            if (paused) {
                blocked.blocked(blockedLane().backpressure(), queryId);
                return 0;
            }
            int room = freeCells();
            if (room == 0) {
                blocked.blocked(blockedLane().backpressure(), queryId);
                return 0;
            }
            blocked.cleared(queryId);
            int moved = reader.poll(this::beginRow, Math.min(maxRecords, room));
            rowsPumped.addAndGet(moved);
            return moved;
        } finally {
            ingest.unlock();
        }
    }

    /**
     * Holds this pump between rows, so a barrier can be taken across it. See
     * {@link IngestPump#freezeIngest}.
     *
     * @return false if the deadline passed while the pump was mid-poll; nothing is held in that case
     */
    public boolean freezeIngest(java.time.Duration timeout) {
        try {
            return ingest.tryLock(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Releases {@link #freezeIngest}. */
    public void thawIngest() {
        ingest.unlock();
    }

    /** The lane input this pump feeds on every lane. */
    public int input() {
        return input;
    }

    private RowWriter beginRow() {
        writer.begin(staging, 0);
        return new DelegatingRowWriter(writer, this::route, eventTimeObserver);
    }

    /**
     * Reports each row's event time to {@code observer} as it is written.
     *
     * <p>This pump did not do it, and {@link IngestPump} did. The asymmetry was not visible from
     * either class and was serious in both directions. Event time never advanced from a partitioned
     * source, so on the multi-lane path windows never closed and join state never evicted --
     * unbounded growth on precisely the path watermarks exist to bound. Worse, because the pump
     * registered no watermark partition either, it was not in the minimum: mixed with a single-lane
     * source, the watermark advanced <em>without accounting for this one</em>, and its own rows
     * could then be judged late and dropped. A wrong answer that looks complete.
     *
     * <p>One observer for the pump rather than one per lane, because the pump reads one source
     * partition: which lane a row is routed to is a function of its key and says nothing about when
     * the row happened. Watermarks track sources, not lanes.
     */
    public void observeEventTimeWith(java.util.function.LongConsumer observer) {
        this.eventTimeObserver = observer == null ? nanos -> {} : observer;
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

    /** Passes on that a checkpoint recording {@code offset} is durable. See {@code PartitionReader}. */
    public void checkpointed(SourceOffset offset) {
        reader.checkpointed(offset);
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

    /**
     * Names the query this pump feeds, so a lane several of them share can say whose writer waited.
     * Set once, at wiring time.
     */
    public PartitionedIngestPump attributedTo(String name) {
        this.queryId = name == null ? "" : name;
        return this;
    }

    /** Episodes in which this pump found nowhere to put a row on at least one of its lanes. */
    public long backpressureWaits() {
        return blocked.waits();
    }

    /** How long those episodes lasted, including one in progress. */
    public long backpressureWaitNanos() {
        return blocked.waitNanos();
    }

    /** Whether this pump is waiting for room right now. */
    public boolean isBackpressured() {
        return blocked.waiting();
    }

    /**
     * The lane holding the most, which is the one that stops the fan-out.
     *
     * <p>Attribution has to name one lane, and naming the fullest is the only choice that points
     * at the lane an operator should look at: a poll is bounded by the tightest of them.
     */
    private Lane blockedLane() {
        Lane fullest = lanes.get(0);
        double highest = -1;
        for (Lane lane : lanes) {
            double fill = lane.inboxFill(input);
            if (fill > highest) {
                highest = fill;
                fullest = lane;
            }
        }
        return fullest;
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
