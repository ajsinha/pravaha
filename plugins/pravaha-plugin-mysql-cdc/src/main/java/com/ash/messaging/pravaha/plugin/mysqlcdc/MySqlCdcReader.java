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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Hands the engine whole transactions from a {@link BinlogStream}.
 *
 * <p>A poll takes a transaction only if all of it fits, so a checkpoint -- taken between polls --
 * never falls inside one. A transaction larger than any poll has ever had room for can only arrive
 * in parts; the position then records how many of its changes the engine holds, and a restore from
 * there delivers exactly the rest ({@link BinlogOffset}). The position otherwise moves to the end of
 * each transaction delivered, and past transactions that do not touch the table.
 */
final class MySqlCdcReader implements PartitionReader {

    private final BinlogStream stream;
    private final StreamSchema schema;
    private volatile BinlogOffset position;
    private volatile boolean paused;
    private volatile boolean closed;
    private int headTaken;
    private int largestOffered;
    private long sequence;

    MySqlCdcReader(BinlogStream stream, StreamSchema schema, BinlogOffset start) {
        this.stream = stream;
        this.schema = schema;
        this.position = start;
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused || closed || maxRecords <= 0) {
            return 0;
        }
        largestOffered = Math.max(largestOffered, maxRecords);
        int taken = 0;
        while (true) {
            BinlogTransaction head = stream.peek();
            if (head == null) {
                break;
            }
            if (head.failure() != null) {
                if (taken > 0) {
                    return taken;
                }
                throw head.failure();
            }
            int remaining = head.size() - headTaken;
            int room = maxRecords - taken;
            if (remaining <= room) {
                deliver(sink, head, headTaken, remaining);
                taken += remaining;
                stream.remove(head);
                headTaken = 0;
                position = head.offset();
                continue;
            }
            if (taken == 0 && remaining > largestOffered) {
                // Larger than any poll has ever had room for: it can only arrive in parts.
                deliver(sink, head, headTaken, room);
                headTaken += room;
                taken += room;
                position = new BinlogOffset(
                        position.file(),
                        position.position(),
                        head.alreadyDelivered() + headTaken,
                        position.gtidSet(),
                        position.isGtid() ? head.gtid() : null);
            }
            break;
        }
        if (taken == 0 && stream.peek() == null) {
            PravahaException stopped = stream.failure();
            if (stopped != null) {
                throw stopped;
            }
        }
        return taken;
    }

    private void deliver(RecordSink sink, BinlogTransaction transaction, int from, int count) {
        for (int index = from; index < from + count; index++) {
            BinlogTransaction.Change change = transaction.changes().get(index);
            String at = transaction.file() + ":" + transaction.endPosition() + "#"
                    + (transaction.alreadyDelivered() + index);
            if (change.rejected() != null) {
                if (!sink.reject(change.raw(), at, change.rejected())) {
                    throw new PravahaException(
                            MySqlCdcErrors.UNREPRESENTABLE_CHANGE,
                            "a change at " + at + " cannot be read: " + change.rejected()
                                    + ". Configure a dead-letter queue to set such rows aside, or fix the value.");
                }
                continue;
            }
            Object[] values = change.values();
            long eventTime = transaction.commitNanos();
            if (schema.eventTimeOrdinal().isPresent()
                    && values[schema.eventTimeOrdinal().getAsInt()] instanceof Long stamped) {
                eventTime = stamped;
            }
            RowWriter writer = sink.beginRow();
            try {
                for (int field = 0; field < values.length; field++) {
                    MySqlSchema.write(writer, field, schema.field(field).type(), values[field]);
                }
                writer.weight(change.weight())
                        .eventTimestampNanos(eventTime)
                        .sequence(sequence++)
                        .commit();
            } catch (RuntimeException e) {
                writer.abort();
                throw e;
            }
        }
    }

    @Override
    public SourceOffset position() {
        return position.toSourceOffset();
    }

    BinlogStream stream() {
        return stream;
    }

    boolean isClosed() {
        return closed;
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
