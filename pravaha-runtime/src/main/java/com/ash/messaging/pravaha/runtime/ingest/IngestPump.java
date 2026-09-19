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

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetter;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterQueue;
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

    /**
     * Told the event time of every row this pump writes.
     *
     * <p>Set by {@link QueryExecution} when it owns a watermark tracker. A pump on its own does not
     * know what partition it is, or what else is feeding the same query, so it reports rather than
     * decides.
     */
    private LongConsumer eventTimeObserver = nanos -> {};

    private final AtomicLong rowsPumped = new AtomicLong();
    private final AtomicLong pauses = new AtomicLong();
    private final AtomicLong resumes = new AtomicLong();
    private final AtomicLong pausedNanos = new AtomicLong();

    private final int input;

    /**
     * Serialises a poll against a checkpoint's reading of the offset. See {@link #freezeIngest}.
     *
     * <p>Not fair: fairness would cost an acquire's worth of handoff on every poll to give priority
     * to a caller that arrives a few times a minute.
     */
    private final java.util.concurrent.locks.ReentrantLock ingest = new java.util.concurrent.locks.ReentrantLock();

    private volatile boolean paused;
    private long pausedSince;
    private long claimed = -1L;

    /**
     * Where records that could not be decoded go, or {@code null} when nothing asked for one.
     *
     * <p>Null rather than a no-op implementation on purpose: the reader has to be able to tell the
     * difference. With no queue, {@link PartitionReader.RecordSink#reject} answers {@code false} and
     * the reader fails exactly as it always has. A do-nothing queue would answer {@code true} and
     * turn every deployment into one that discards bad records silently.
     */
    private DeadLetterQueue deadLetters;

    private String deadLetterQueryId = "";

    /**
     * A cell-sized buffer rows are decoded into while a dead-letter queue is attached.
     *
     * <p><strong>This is the one copy the fast path does not pay.</strong> Ordinarily the plugin
     * decodes straight into a claimed inbox cell, which is what makes ingest one copy end to end --
     * and it is also why a decode failure cannot simply be shrugged off: the cell is already
     * claimed, {@code RowInbox.drain} "stops at the first cell that is claimed but not yet
     * published", and there is no way to give a claim back. A reader that aborted its row and kept
     * going would leave that cell claimed for ever and the lane would never see another row.
     *
     * <p>So dead-lettering decodes into this buffer instead and copies the finished row into the
     * inbox on commit. A record that fails to decode never reaches the inbox at all, and there is
     * nothing to give back. The cost is one extra copy per row, paid only by a deployment that has
     * asked for a dead-letter queue, and it buys the property the queue exists for: a bad record
     * does not stop the pipeline.
     */
    private MemoryRegion staging;

    private BinaryRowWriter stagingWriter;

    private final AtomicLong rowsRejected = new AtomicLong();

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
                            + ". Raise pravaha.lane.inbox.cell-bytes; a row that cannot fit is not a runtime condition.");
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
        // Held for the whole poll, so that a checkpoint taking this pump's offset sees it between
        // rows and never during one. See freezeIngest.
        ingest.lock();
        try {
            updateBackpressure();
            if (paused) {
                return 0;
            }
            int room = freeCells();
            if (room == 0) {
                return 0;
            }
            int moved = reader.poll(sink, Math.min(maxRecords, room));
            rowsPumped.addAndGet(moved);
            return moved;
        } finally {
            ingest.unlock();
        }
    }

    /**
     * Applies backpressure and reports how many rows this pump's lane can take right now.
     *
     * <p>SRC-3. One reader now feeds several lanes, and a poll is one call with no way to give a row
     * back -- so the caller has to ask for no more than the smallest of those lanes can hold, which
     * means asking each of them first.
     *
     * <p>It runs the hysteresis as well as reading the fill, because for a pump whose rows arrive
     * through {@link #sharedSink()} nothing else is going to: {@link #pumpOnce} is what normally
     * pauses a source at the high watermark and resumes it at the low one, and that pump is never
     * called. Zero here is backpressure, and a shared reader answers it by not reading at all rather
     * than by reading and dropping this lane's copy.
     */
    public int roomForSharedPoll() {
        updateBackpressure();
        return paused ? 0 : freeCells();
    }

    /**
     * Where a shared reader writes this pump's copy of a record. SRC-3.
     *
     * <p>The same sink {@link #pumpOnce} hands its own reader: it claims a cell in this lane's inbox
     * and publishes it on commit. What differs is who drives it -- one reader writing one decoded
     * record into several lanes, from the single thread that owns all of them. The confinement is
     * unchanged and in fact narrowed: an inbox still has exactly one producer thread, and there are
     * now fewer of those than there are lanes.
     *
     * <p>Only safe to use between {@link #freezeIngest} and {@link #thawIngest}, which is the same
     * window {@code pumpOnce} holds for the length of its own poll: a checkpoint reading this pump's
     * offset must see it between rows and never during one.
     */
    public PartitionReader.RecordSink sharedSink() {
        return sink;
    }

    /**
     * Counts rows a shared reader wrote through {@link #sharedSink()}. SRC-3.
     *
     * <p>Told rather than observed, because the sink does not know how many rows a poll moved --
     * only the reader that was polled does, and it was polled once for all of them.
     */
    public void countSharedRows(int rows) {
        rowsPumped.addAndGet(rows);
    }

    /**
     * Holds this pump between rows, so a barrier can be taken across it.
     *
     * <p>A checkpoint has to record two things that must agree: where the source is, and which rows
     * the lane has been handed. Reading them one after the other from the coordinator's thread
     * cannot make them agree in either order -- read the lane's cursor first and the offset runs
     * ahead of the state, so a restore resumes past rows nothing counted; read the offset first and
     * the state runs ahead of the offset, so a restore replays rows already counted. One is silent
     * loss and the other is a silent double count, and the gap between them is however many rows a
     * poll moved.
     *
     * <p>So the pump is stopped instead, at a point where it is holding no row: {@code pumpOnce}
     * takes the same lock for the length of its poll. One uncontended acquire per poll -- per batch
     * of up to a few hundred rows, not per row -- against the alternative of a guarantee that
     * cannot be stated.
     *
     * <p>Timed rather than unconditional, because {@code reader.poll} runs plugin code. A checkpoint
     * that cannot get in says so and is abandoned, which is what the rest of the checkpoint path
     * already does with a lane that will not answer.
     *
     * @return false if the deadline passed while the pump was mid-poll; nothing is held in that case
     */
    public boolean freezeIngest(Duration timeout) {
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

    /** The lane this pump feeds, so a caller freezing it knows whose cursors to read. */
    public Lane lane() {
        return lane;
    }

    /** The lane input this pump feeds: 0 for anything but a join. */
    public int input() {
        return input;
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
    private final PartitionReader.RecordSink sink = new PartitionReader.RecordSink() {

        @Override
        public RowWriter beginRow() {
            return IngestPump.this.beginRow();
        }

        @Override
        public boolean reject(byte[] raw, String sourceOffset, String reason) {
            DeadLetterQueue queue = deadLetters;
            if (queue == null) {
                return false;
            }
            // accept must not throw -- the reader is already handling a failure and cannot handle a
            // second one. FileDeadLetterQueue counts its own write failures instead, which is what
            // failures() is for.
            queue.accept(new DeadLetter(
                    deadLetterQueryId,
                    reason,
                    sourceOffset,
                    raw,
                    UUID.randomUUID().toString(),
                    System.nanoTime()));
            rowsRejected.incrementAndGet();
            return true;
        }
    };

    private RowWriter beginRow() {
        if (deadLetters != null) {
            // Decode into staging; the row reaches the inbox only if it decodes.
            stagingWriter.begin(staging, 0, lane.inboxCellBytes());
            // Abandoning a staged row is free: nothing has been claimed, so there is nothing to
            // give back. That is the whole reason rows are staged while a queue is attached.
            return new DelegatingRowWriter(stagingWriter, this::publishStagedRow, eventTimeObserver, () -> {});
        }
        claimed = lane.claim(input);
        if (claimed == com.ash.messaging.pravaha.common.queue.RowInbox.NO_SPACE) {
            throw new PravahaException(
                    RuntimeErrors.BACKPRESSURED,
                    "lane " + lane.laneId() + "'s inbox filled during a poll that was sized to fit. Either "
                            + "another producer is writing to this lane's inbox, which the single-writer ingest "
                            + "path does not allow, or the free-cell calculation is wrong.");
        }
        writer.begin(lane.inboxRegion(input), lane.cellOffset(input, claimed));
        return new DelegatingRowWriter(writer, () -> lane.publish(input, claimed), eventTimeObserver);
    }

    private void publishStagedRow() {
        if (!lane.offer(input, staging, 0, stagingWriter.sizeSoFar())) {
            throw new PravahaException(
                    RuntimeErrors.BACKPRESSURED,
                    "lane " + lane.laneId() + "'s inbox filled during a poll that was sized to fit. Either "
                            + "another producer is writing to this lane's inbox, which the single-writer ingest "
                            + "path does not allow, or the free-cell calculation is wrong.");
        }
    }

    /**
     * Sends records this pump's reader cannot decode to {@code queue} instead of failing the source.
     *
     * <p>Set once, at wiring time, before the pump is first polled -- it changes how every row is
     * written, not just the failing ones (see {@link #staging}), so changing it mid-stream would
     * change the meaning of a claim the pump is already holding.
     *
     * <p>Passing {@code null} is how a deployment stays as it is, and it is the default. This is
     * opt-in because the two rules a dead-letter queue exists to keep -- never drop a record
     * silently, never let one record stop the pipeline -- pull against each other for anybody who
     * has not set the queue up: without somewhere durable to put the record, "keep going" is just
     * "drop it".
     *
     * @param queryId which query is rejecting, recorded on every entry; one source can feed many
     */
    public void deadLetteringTo(DeadLetterQueue queue, String queryId) {
        if (queue == null) {
            closeStaging();
            this.deadLetters = null;
            this.deadLetterQueryId = "";
            return;
        }
        if (staging == null) {
            this.staging = MemoryAccess.best().allocate(lane.inboxCellBytes());
            this.stagingWriter = new BinaryRowWriter(layout);
        }
        this.deadLetters = queue;
        this.deadLetterQueryId = queryId == null ? "" : queryId;
    }

    /** Records this pump could not decode and sent to the dead-letter queue. */
    public long rowsRejected() {
        return rowsRejected.get();
    }

    private void closeStaging() {
        if (staging != null) {
            staging.close();
            staging = null;
            stagingWriter = null;
        }
    }

    /**
     * Reports each row's event time to {@code observer} as it is written.
     *
     * <p>Set once, at wiring time, before the pump is first polled. A pump whose observer changed
     * mid-stream would hand two watermark calculations half of one partition's history each, and
     * both would be wrong in the direction that closes windows too early. Public because
     * {@code QueryExecution} lives in another package; the constraint is stated rather than
     * enforced by visibility.
     */
    public void observeEventTimeWith(LongConsumer observer) {
        this.eventTimeObserver = observer == null ? nanos -> {} : observer;
    }

    /** Where the reader is, for the checkpoint. */
    public SourceOffset position() {
        return reader.position();
    }

    /** Passes on that a checkpoint recording {@code offset} is durable. See {@code PartitionReader}. */
    public void checkpointed(SourceOffset offset) {
        reader.checkpointed(offset);
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
        try {
            reader.close();
        } finally {
            // The queue itself belongs to whoever handed it over -- closing it here would close it
            // under the other pumps feeding the same query.
            closeStaging();
        }
    }
}
