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
package com.ash.messaging.pravaha.server.ingest;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * A scan-shaped source that counts what reading it costs.
 *
 * <p>Written for SRC-3, and shaped like the source the finding is about rather than like a file: a
 * store that can be appended to, an offset that is a position in it, and a <em>scan</em> per poll
 * that finds its buffer empty. That last part is what makes "N queries are N scans" measurable in a
 * unit test -- the counter it moves is the analogue of Aerospike's {@code pi_query_*}, which is
 * where {@code AerospikeSourceScaleIT} reads the same number from a real cluster.
 *
 * <p>Its declared capabilities are configurable because the sharing gate is made of them: a source
 * promising exactly-once must keep a reader per query, and a test that could not say so would not be
 * testing the gate, only the happy path behind it.
 */
public final class CountingScanPlugin implements StreamSourcePlugin {

    /** Static because {@link java.util.ServiceLoader} constructs the instance, not the test. */
    static final List<long[]> STORE = new CopyOnWriteArrayList<>();

    /** Readers alive right now. One per query is the defect; one per binding is the fix. */
    static final AtomicInteger OPEN = new AtomicInteger();

    /** Readers ever created, so a catch-up that has come and gone is still visible. */
    static final AtomicInteger CREATED = new AtomicInteger();

    /** Polls that went to the store. The scan count. */
    static final AtomicLong SCANS = new AtomicLong();

    static final StreamSchema SCHEMA = StreamSchema.builder("shared")
            .field("id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private DeliveryGuarantee guarantee = DeliveryGuarantee.AT_LEAST_ONCE;
    private boolean ordered;
    private boolean replayable = true;

    static void reset() {
        STORE.clear();
        OPEN.set(0);
        CREATED.set(0);
        SCANS.set(0);
    }

    /** Appends a record, as a writer to the store would. */
    static void append(long id, long amount) {
        STORE.add(new long[] {id, amount});
    }

    @Override
    public String name() {
        return "counting-scan";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.guarantee = DeliveryGuarantee.valueOf(context.get("guarantee", DeliveryGuarantee.AT_LEAST_ONCE.name()));
        this.ordered = Boolean.parseBoolean(context.get("ordered", "false"));
        this.replayable = Boolean.parseBoolean(context.get("replayable", "true"));
    }

    @Override
    public void open() {}

    @Override
    public void close() {}

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                replayable, ordered, false, false, guarantee, EnumSet.of(PushdownKind.FILTER), Duration.ofMillis(10));
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
        return createReader(partition, resumeFrom, ReadRequest.NOTHING);
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom, ReadRequest request) {
        OPEN.incrementAndGet();
        CREATED.incrementAndGet();
        return new Reader(resumeFrom);
    }

    /** A scan of the store from a watermark, buffered and drained -- {@code LutScanReader}'s shape. */
    private static final class Reader implements PartitionReader {

        private final ArrayDeque<long[]> buffered = new ArrayDeque<>();
        private int watermark;
        private boolean paused;
        private boolean closed;
        private long sequence;

        Reader(SourceOffset from) {
            this.watermark = from == null || from.isBeginning() ? 0 : Integer.parseInt(from.token());
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            if (paused || closed) {
                return 0;
            }
            if (buffered.isEmpty()) {
                // The scan. Like a last-update-time filter, it takes everything the store has gained
                // since the watermark and moves the watermark to where the scan started.
                SCANS.incrementAndGet();
                int size = STORE.size();
                for (int i = watermark; i < size; i++) {
                    buffered.add(STORE.get(i));
                }
                watermark = size;
            }
            int emitted = 0;
            while (emitted < maxRecords && !buffered.isEmpty()) {
                long[] record = buffered.poll();
                RowWriter writer = sink.beginRow();
                writer.setLong(0, record[0]);
                writer.setString(1, "u" + (record[0] % 7));
                writer.setLong(2, record[1]);
                writer.weight(1L)
                        .eventTimestampNanos(System.currentTimeMillis() * 1_000_000L)
                        .sequence(++sequence)
                        .commit();
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(Integer.toString(watermark));
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
