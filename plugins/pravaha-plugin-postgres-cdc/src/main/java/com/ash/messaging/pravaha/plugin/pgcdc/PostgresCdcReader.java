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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Hands the engine whole transactions from a {@link CdcStream}, and tells the slot what the engine
 * has checkpointed.
 *
 * <p><strong>Transactions arrive whole.</strong> A poll takes a transaction only if all of it fits
 * in what the engine asked for, so a checkpoint -- which the engine takes between polls -- never
 * falls inside one, and a view never publishes half of a transfer. The one exception is forced: a
 * transaction with more changes than any poll has ever been offered room for can never arrive whole,
 * so it is handed over in order across polls, and the position records how far into it the engine
 * got ({@link CdcOffset}). A restore from a checkpoint cut inside it delivers exactly the rest.
 *
 * <p><strong>The position is an LSN the slot still has.</strong> It moves to the end of each
 * transaction delivered, and to each heartbeat with nothing undelivered before it. The slot is
 * confirmed only from {@link #checkpointed}, so what PostgreSQL may discard is never more than what a
 * restore could ask for again.
 *
 * <p><strong>An initial snapshot is spliced in at its consistent point</strong> ({@link
 * InitialSnapshot}). Until the stream has delivered everything before that point, nothing of the
 * snapshot is delivered; then its rows, in key order, the position recording the last key handed
 * over; then the stream again. A checkpoint may fall anywhere in that, including between two rows
 * of one chunk, and every one of those positions resumes exactly.
 */
final class PostgresCdcReader implements PartitionReader {

    private final CdcStream stream;
    private final StreamSchema schema;
    private final InitialSnapshot.@Nullable CatchUp catchUp;

    private volatile CdcOffset position;
    private volatile boolean paused;
    private volatile @Nullable InitialSnapshot snapshot;
    private volatile long snapshotEstimate = -1L;
    private int headTaken;
    private int largestOffered;
    private long sequence;
    private long markerAskedAt;
    private volatile boolean closed;

    private PostgresCdcReader(
            CdcStream stream,
            StreamSchema schema,
            CdcOffset start,
            @Nullable InitialSnapshot snapshot,
            InitialSnapshot.@Nullable CatchUp catchUp) {
        this.stream = stream;
        this.schema = schema;
        this.position = start;
        this.snapshot = snapshot;
        this.catchUp = catchUp;
        if (snapshot != null) {
            this.snapshotEstimate = snapshot.estimate();
        }
    }

    /** Starts streaming from {@code start} and waits, bounded, to have read the log as it stands. */
    static PostgresCdcReader open(CdcOptions options, CdcSchema.Mapping mapping, int tableOid, CdcOffset start) {
        return open(options, mapping, tableOid, start, null);
    }

    /**
     * As above; when {@code start} is inside an unfinished initial snapshot, first pins a new one to
     * the log and filters the log before it by the key {@code start} reached.
     *
     * @param key the table's primary key; needed only when {@code start} is inside a snapshot
     */
    static PostgresCdcReader open(
            CdcOptions options, CdcSchema.Mapping mapping, int tableOid, CdcOffset start, @Nullable SnapshotKey key) {
        InitialSnapshot snapshot = null;
        InitialSnapshot.CatchUp catchUp = null;
        if (start.inSnapshot()) {
            SnapshotKey tableKey = Objects.requireNonNull(key, "a start inside a snapshot comes with the table's key");
            List<String> after =
                    Objects.requireNonNull(start.snapshot(), "inSnapshot()").after();
            snapshot = InitialSnapshot.begin(options, mapping, tableKey, after);
            catchUp = new InitialSnapshot.CatchUp(options, tableKey, snapshot.consistentPoint(), after);
        }
        CdcStream stream = new CdcStream(options, mapping, tableOid, start, catchUp);
        try {
            stream.start();
        } catch (RuntimeException e) {
            if (snapshot != null) {
                snapshot.close();
                if (catchUp != null) {
                    catchUp.close();
                }
            }
            throw e;
        }
        stream.awaitCaughtUp(options.startTimeout());
        if (snapshot != null) {
            snapshot.start();
            snapshot.awaitFirstChunk(options.startTimeout());
        }
        return new PostgresCdcReader(stream, mapping.schema(), start, snapshot, catchUp);
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused || closed || maxRecords <= 0) {
            return 0;
        }
        largestOffered = Math.max(largestOffered, maxRecords);
        int written = 0;
        int taken = 0;
        while (true) {
            CdcTransaction head = stream.peek();
            InitialSnapshot reading = snapshot;
            if (reading != null) {
                if (head == null) {
                    askForMarker();
                } else if (head.endLsn() > reading.consistentPoint()) {
                    // Everything before the snapshot's point is delivered: its rows go next.
                    PravahaException stopped = reading.failure();
                    if (stopped != null) {
                        if (taken > 0) {
                            return written;
                        }
                        throw stopped;
                    }
                    long point = reading.consistentPoint();
                    while (taken < maxRecords) {
                        CdcTransaction.Change row = reading.peek();
                        if (row == null) {
                            break;
                        }
                        CdcOffset.Snapshot reached = Objects.requireNonNull(
                                position.snapshot(), "reading the snapshot, the position is in it");
                        written += deliver(sink, row, "snapshot@" + row.key());
                        reading.take();
                        taken++;
                        position = new CdcOffset(
                                point,
                                0L,
                                0L,
                                new CdcOffset.Snapshot(
                                        reached.rows() + 1,
                                        Objects.requireNonNull(row.key(), "a snapshot row carries its key")));
                    }
                    if (reading.finished()) {
                        // The whole table is in the engine, as of the point: from here, the stream.
                        position = CdcOffset.at(point);
                        snapshot = null;
                        reading.close();
                        continue;
                    }
                    if (position.lsn() < point) {
                        // At the point, with nothing of the snapshot delivered yet: an empty poll
                        // still leaves a position the next one can resume from.
                        position = new CdcOffset(point, 0L, 0L, position.snapshot());
                    }
                    return written;
                }
            }
            if (head == null) {
                break;
            }
            if (head.failure() != null) {
                if (taken > 0) {
                    // Hand over what came before it first; the refusal is the next poll's.
                    return written;
                }
                throw head.failure();
            }
            int remaining = head.size() - headTaken;
            if (remaining == 0) {
                position = CdcOffset.at(head.endLsn()).withSnapshot(position.snapshot());
                stream.remove(0);
                headTaken = 0;
                continue;
            }
            int room = maxRecords - taken;
            if (remaining <= room) {
                written += deliver(sink, head, headTaken, remaining);
                taken += remaining;
                stream.remove(remaining);
                headTaken = 0;
                position = CdcOffset.at(head.endLsn()).withSnapshot(position.snapshot());
                continue;
            }
            if (taken == 0 && remaining > largestOffered) {
                // Larger than any poll has ever had room for: it can only arrive in parts.
                written += deliver(sink, head, headTaken, room);
                stream.consumed(room);
                headTaken += room;
                position = new CdcOffset(
                        position.lsn(), head.endLsn(), (long) head.alreadyDelivered() + headTaken, position.snapshot());
            }
            break;
        }
        if (taken == 0 && stream.peek() == null && stream.failure() != null) {
            throw stream.failure();
        }
        return written;
    }

    /**
     * With a snapshot waiting on the stream to reach its point and nothing queued, asks for a
     * marker: on a quiet table with the heartbeat off, nothing else would ever arrive to say the
     * point has been passed.
     */
    private void askForMarker() {
        long now = System.nanoTime();
        if (now - markerAskedAt > 1_000_000_000L) {
            markerAskedAt = now;
            stream.requestMarker();
        }
    }

    private int deliver(RecordSink sink, CdcTransaction transaction, int from, int count) {
        int written = 0;
        for (int index = from; index < from + count; index++) {
            int at = transaction.alreadyDelivered() + index;
            written +=
                    deliver(sink, transaction.changes().get(index), CdcOffset.format(transaction.endLsn()) + "#" + at);
        }
        return written;
    }

    private int deliver(RecordSink sink, CdcTransaction.Change change, String at) {
        if (change.rejected() != null) {
            if (!sink.reject(change.raw(), at, change.rejected())) {
                throw new PravahaException(
                        CdcErrors.UNREPRESENTABLE_CHANGE,
                        "a change at " + at + " cannot be read: " + change.rejected()
                                + ". Configure a dead-letter queue to set such rows aside, or fix the value.");
            }
            return 0;
        }
        RowWriter writer = sink.beginRow();
        try {
            Object[] values = change.values();
            for (int field = 0; field < values.length; field++) {
                PgValues.write(writer, field, schema.field(field).type(), values[field]);
            }
            writer.weight(change.weight())
                    .eventTimestampNanos(change.eventTimeNanos())
                    .sequence(sequence++)
                    .commit();
        } catch (RuntimeException e) {
            writer.abort();
            throw e;
        }
        return 1;
    }

    @Override
    public SourceOffset position() {
        return position.toSourceOffset();
    }

    @Override
    public void checkpointed(SourceOffset offset) {
        CdcOffset durable = CdcOffset.parse(offset);
        if (!durable.isBeginning()) {
            stream.requestAck(durable.lsn());
        }
    }

    /** The newest LSN this reader has confirmed to the slot. */
    long confirmed() {
        return stream.confirmed();
    }

    boolean isClosed() {
        return closed;
    }

    CdcStream stream() {
        return stream;
    }

    /** The unfinished initial snapshot, or null. For tests of its memory bound. */
    @Nullable
    InitialSnapshot snapshot() {
        return snapshot;
    }

    /** How far an unfinished initial snapshot has got, for health; empty when there is none. */
    String snapshotProgress() {
        CdcOffset.Snapshot reached = position.snapshot();
        if (reached == null) {
            return "";
        }
        InitialSnapshot reading = snapshot;
        String of = snapshotEstimate > 0 ? " of about " + snapshotEstimate : "";
        String failed = reading != null && reading.failure() != null
                ? " -- stopped: " + reading.failure().getMessage()
                : "";
        return "initial snapshot in progress: " + reached.rows() + " rows delivered" + of + failed;
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            stream.close();
            InitialSnapshot reading = snapshot;
            if (reading != null) {
                reading.close();
            }
            if (catchUp != null) {
                catchUp.close();
            }
        }
    }
}
