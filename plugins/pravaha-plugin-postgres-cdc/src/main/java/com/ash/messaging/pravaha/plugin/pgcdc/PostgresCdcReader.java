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
 */
final class PostgresCdcReader implements PartitionReader {

    private final CdcStream stream;
    private final StreamSchema schema;

    private volatile CdcOffset position;
    private volatile boolean paused;
    private int headTaken;
    private int largestOffered;
    private long sequence;
    private volatile boolean closed;

    private PostgresCdcReader(CdcStream stream, StreamSchema schema, CdcOffset start) {
        this.stream = stream;
        this.schema = schema;
        this.position = start;
    }

    /** Starts streaming from {@code start} and waits, bounded, to have read the log as it stands. */
    static PostgresCdcReader open(CdcOptions options, CdcSchema.Mapping mapping, int tableOid, CdcOffset start) {
        CdcStream stream = new CdcStream(options, mapping, tableOid, start);
        stream.start();
        stream.awaitCaughtUp(options.startTimeout());
        return new PostgresCdcReader(stream, mapping.schema(), start);
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
                position = CdcOffset.at(head.endLsn());
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
                position = CdcOffset.at(head.endLsn());
                continue;
            }
            if (taken == 0 && remaining > largestOffered) {
                // Larger than any poll has ever had room for: it can only arrive in parts.
                written += deliver(sink, head, headTaken, room);
                stream.consumed(room);
                headTaken += room;
                position = new CdcOffset(position.lsn(), head.endLsn(), (long) head.alreadyDelivered() + headTaken);
            }
            break;
        }
        if (taken == 0 && stream.peek() == null && stream.failure() != null) {
            throw stream.failure();
        }
        return written;
    }

    private int deliver(RecordSink sink, CdcTransaction transaction, int from, int count) {
        int written = 0;
        for (int index = from; index < from + count; index++) {
            CdcTransaction.Change change = transaction.changes().get(index);
            if (change.rejected() != null) {
                String at = CdcOffset.format(transaction.endLsn()) + "#" + (transaction.alreadyDelivered() + index);
                if (!sink.reject(change.raw(), at, change.rejected())) {
                    throw new PravahaException(
                            CdcErrors.UNREPRESENTABLE_CHANGE,
                            "a change in the transaction ending at " + at + " cannot be read: " + change.rejected()
                                    + ". Configure a dead-letter queue to set such rows aside, or fix the value.");
                }
                continue;
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
            written++;
        }
        return written;
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
        }
    }
}
