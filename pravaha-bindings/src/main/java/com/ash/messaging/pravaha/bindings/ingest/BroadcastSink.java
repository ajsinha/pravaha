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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.List;

import com.ash.messaging.pravaha.api.data.RowKind;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;

/**
 * One record, written once by the reader and into every lane that asked for it.
 *
 * <p>SRC-3. This is the whole of the fan-out. A shared reader decodes a record exactly once -- one
 * scan, one parse, one pass over the bytes the store sent -- and each setter it calls lands in every
 * subscribed lane's own inbox cell. The copies are unavoidable and they are the point: a lane's
 * inbox is memory that lane alone reads, so N queries reading one set is N writes of each row and
 * <em>one</em> read of the set, which is the ratio the finding is about.
 *
 * <p><strong>The confinement is not weakened, it is narrowed.</strong> Every cell claimed here is
 * claimed by the one thread that drives this group's reader, so each inbox still has exactly one
 * producer -- fewer producer threads than before, not more. Nothing here touches an arena, an
 * operator's state or a lane's cursor; those stay on the lane's own thread exactly as they were.
 *
 * <p>Rejected because it looked simpler: buffering the batch and replaying it per consumer. That
 * reintroduces the unbounded queue between reader and lane that {@code IngestPump}'s contract
 * promises is not there (SRC-7 is the same queue one level down), and it costs a copy into the
 * buffer on top of the copies into the cells. Writing through to every cell as the record is decoded
 * costs no buffer at all.
 */
final class BroadcastSink implements PartitionReader.RecordSink {

    private final PartitionReader.RecordSink[] sinks;
    private final RowWriter[] writers;
    private final Fan fan;

    BroadcastSink(List<PartitionReader.RecordSink> targets) {
        this.sinks = targets.toArray(new PartitionReader.RecordSink[0]);
        this.writers = new RowWriter[sinks.length];
        this.fan = new Fan(writers);
    }

    @Override
    public RowWriter beginRow() {
        for (int i = 0; i < sinks.length; i++) {
            writers[i] = sinks[i].beginRow();
        }
        return fan;
    }

    /**
     * Offers an undecodable record to every consumer's dead-letter queue.
     *
     * <p>True only when all of them took it, because {@code false} is the reader's instruction to
     * fail -- and a reader that carried on because <em>one</em> consumer had a queue would be
     * discarding the record for every consumer that had not asked for that. Consumers that do have a
     * queue still get their copy either way: a record the shared feed is about to fail on is exactly
     * the one an operator will want to look at.
     */
    @Override
    public boolean reject(byte[] raw, String sourceOffset, String reason) {
        boolean all = true;
        for (PartitionReader.RecordSink sink : sinks) {
            // Non-short-circuiting on purpose: every queue that exists gets the record.
            all = sink.reject(raw, sourceOffset, reason) && all;
        }
        return all;
    }

    /**
     * A writer that is several writers.
     *
     * <p>Each call is forwarded in order to every target, so the ordinal-order rule variable-width
     * columns depend on is satisfied for all of them by being satisfied for one: they see the same
     * call sequence, because there is only one call sequence.
     */
    private static final class Fan implements RowWriter {

        private final RowWriter[] targets;

        Fan(RowWriter[] targets) {
            this.targets = targets;
        }

        @Override
        public StreamSchema schema() {
            return targets[0].schema();
        }

        @Override
        public RowWriter setNull(int ordinal) {
            for (RowWriter target : targets) {
                target.setNull(ordinal);
            }
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            for (RowWriter target : targets) {
                target.setBoolean(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            for (RowWriter target : targets) {
                target.setByte(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            for (RowWriter target : targets) {
                target.setShort(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            for (RowWriter target : targets) {
                target.setInt(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            for (RowWriter target : targets) {
                target.setLong(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            for (RowWriter target : targets) {
                target.setFloat(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            for (RowWriter target : targets) {
                target.setDouble(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            for (RowWriter target : targets) {
                target.setDecimal(ordinal, high, low);
            }
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            for (RowWriter target : targets) {
                target.setBytes(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            for (RowWriter target : targets) {
                target.setString(ordinal, value);
            }
            return this;
        }

        @Override
        public RowWriter rowKind(RowKind kind) {
            for (RowWriter target : targets) {
                target.rowKind(kind);
            }
            return this;
        }

        @Override
        public RowWriter weight(long weight) {
            for (RowWriter target : targets) {
                target.weight(weight);
            }
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            for (RowWriter target : targets) {
                target.eventTimestampNanos(nanos);
            }
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            for (RowWriter target : targets) {
                target.sequence(sequence);
            }
            return this;
        }

        /**
         * Publishes the row into every lane.
         *
         * <p>The offset returned is the first target's, and it is meaningless for the others -- each
         * wrote into its own region. Nothing in the ingest path reads it; the plugins that commit a
         * row discard it. Recorded here so that the day something does read it, it is read knowing
         * there are N of them.
         *
         * <p>All targets are committed even if one throws, and the first failure is the one
         * rethrown. A half-published record would leave the lanes disagreeing about a row the source
         * only sent once, and the exception is what stops the feed and says so.
         */
        @Override
        public int commit() {
            int first = 0;
            RuntimeException failure = null;
            for (int i = 0; i < targets.length; i++) {
                try {
                    int offset = targets[i].commit();
                    if (i == 0) {
                        first = offset;
                    }
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            return first;
        }

        @Override
        public void abort() {
            RuntimeException failure = null;
            for (RowWriter target : targets) {
                try {
                    target.abort();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
