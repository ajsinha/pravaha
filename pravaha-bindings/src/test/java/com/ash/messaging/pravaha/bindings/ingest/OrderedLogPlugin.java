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

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.BoundedPartitionReader;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.OrderedPositions;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * An ordered, exactly-once log whose readers can stop at a position (ADR-054): Kafka's shape without a
 * broker. A position is the number of records consumed, so two positions compare as numbers and a
 * bounded read is exact. It counts what reading it costs, so a test can tell one reader from N.
 */
public final class OrderedLogPlugin implements StreamSourcePlugin {

    static final List<long[]> LOG = new CopyOnWriteArrayList<>();

    /** Readers alive right now. */
    static final AtomicInteger OPEN = new AtomicInteger();

    /** Records handed out by every reader together: one read of the log per reader that covers it. */
    static final AtomicLong RECORDS_READ = new AtomicLong();

    static final StreamSchema SCHEMA = StreamSchema.builder("log")
            .field("id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    /** Readers ever created; the first is the shared one in these tests. */
    static final AtomicInteger CREATED = new AtomicInteger();

    /**
     * Readers created after the first read at most this many records a poll, pausing between polls, so
     * a catch-up lasts long enough for the shared reader to move on under it. Zero means no limit.
     */
    static volatile int slowAfterFirst;

    /** Every reader created while this is set reads at most this many records a poll. Zero: no limit. */
    static volatile int slowEvery;

    /** Whether to declare the order; off shows the same source unshared. */
    private boolean declareOrder = true;

    static void reset() {
        LOG.clear();
        OPEN.set(0);
        RECORDS_READ.set(0);
        CREATED.set(0);
        slowAfterFirst = 0;
        slowEvery = 0;
    }

    static void append(long id, long amount) {
        LOG.add(new long[] {id, amount});
    }

    @Override
    public String name() {
        return "ordered-log";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        declareOrder = Boolean.parseBoolean(context.get("declare.order", "true"));
    }

    @Override
    public void open() {}

    @Override
    public void close() {}

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                true,
                true,
                false,
                false,
                DeliveryGuarantee.EXACTLY_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofMillis(1));
    }

    @Override
    public OrderedPositions orderedPositions() {
        return declareOrder ? (a, b) -> Long.compare(indexOf(a), indexOf(b)) : null;
    }

    static long indexOf(SourceOffset offset) {
        return offset == null || offset.isBeginning() ? 0 : Long.parseLong(offset.token());
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(SCHEMA);
    }

    @Override
    public List<SourcePartition> partitions(String streamName) {
        return List.of(new SourcePartition(streamName, 0, Map.of()));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        OPEN.incrementAndGet();
        int ordinal = CREATED.incrementAndGet();
        return new Reader(indexOf(resumeFrom), slowEvery > 0 ? slowEvery : ordinal > 1 ? slowAfterFirst : 0);
    }

    private static final class Reader implements BoundedPartitionReader {

        private long next;
        private boolean paused;
        private boolean closed;
        private final int perPoll;

        Reader(long from, int perPoll) {
            this.next = from;
            this.perPoll = perPoll;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            return pollBefore(sink, maxRecords, new SourceOffset(Long.toString(Long.MAX_VALUE)));
        }

        @Override
        public int pollBefore(RecordSink sink, int maxRecords, SourceOffset bound) {
            if (paused || closed) {
                return 0;
            }
            long limit = Math.min(LOG.size(), indexOf(bound));
            int most = perPoll > 0 ? Math.min(maxRecords, perPoll) : maxRecords;
            if (perPoll > 0 && next < limit) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int emitted = 0;
            while (emitted < most && next < limit) {
                long[] record = LOG.get((int) next);
                RowWriter writer = sink.beginRow();
                writer.setLong(0, record[0]);
                writer.setString(1, "u" + (record[0] % 7));
                writer.setLong(2, record[1]);
                writer.weight(1L)
                        .eventTimestampNanos(System.currentTimeMillis() * 1_000_000L)
                        .sequence(next + 1)
                        .commit();
                next++;
                emitted++;
                RECORDS_READ.incrementAndGet();
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return next == 0 ? SourceOffset.BEGINNING : new SourceOffset(Long.toString(next));
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
                OPEN.decrementAndGet();
            }
        }
    }
}
