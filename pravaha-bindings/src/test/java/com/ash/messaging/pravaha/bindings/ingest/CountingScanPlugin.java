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

    /**
     * Records the store sent back, after the pushed filters -- what a real store's network and
     * decode cost is proportional to, and what pushdown exists to shrink. ADR-039 item 6.
     */
    static final AtomicLong ROWS_READ = new AtomicLong();

    /** Every request a reader was created with, in creation order. */
    static final List<ReadRequest> REQUESTS = new CopyOnWriteArrayList<>();

    /**
     * At most this many records per poll, so a test can make a scan take many polls to drain and
     * put a join in the middle of one. Zero means no limit beyond the caller's.
     */
    static volatile int maxPerPoll;

    /** How long a poll that hands over records takes, so a drain lasts long enough to join into. */
    static volatile long pollDelayMillis;

    /**
     * SRC-10. Lets a test hold one specific reader's <em>next empty poll</em> open before it
     * returns, so a transient state that would otherwise close itself in a millisecond or two --
     * a catch-up reader that has delivered its history and is one empty poll from closing -- can
     * be observed deterministically instead of raced. {@code 0}, the ordinal no reader is ever
     * given, means nothing is held. Set to a reader's 1-based creation order (see {@link #CREATED})
     * to arm it; the matching {@link #heldPollGate} is what a test releases.
     */
    static volatile int holdEmptyPollForReaderNumber;

    /** The gate {@link #holdEmptyPollForReaderNumber}'s held poll waits on. Set by the test. */
    static volatile java.util.concurrent.CountDownLatch heldPollGate;

    static final StreamSchema SCHEMA = StreamSchema.builder("shared")
            .field("id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private DeliveryGuarantee guarantee = DeliveryGuarantee.AT_LEAST_ONCE;
    private boolean ordered;
    private boolean replayable = true;
    private boolean deletes;

    static void reset() {
        STORE.clear();
        OPEN.set(0);
        CREATED.set(0);
        SCANS.set(0);
        ROWS_READ.set(0);
        REQUESTS.clear();
        maxPerPoll = 0;
        pollDelayMillis = 0;
        holdEmptyPollForReaderNumber = 0;
        heldPollGate = null;
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
        this.deletes = Boolean.parseBoolean(context.get("deletes", "false"));
    }

    @Override
    public void open() {}

    @Override
    public void close() {}

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                replayable,
                ordered,
                deletes,
                false,
                guarantee,
                EnumSet.of(PushdownKind.FILTER, PushdownKind.PROJECT),
                Duration.ofMillis(10));
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
        int ordinal = CREATED.incrementAndGet();
        ReadRequest honoured = request == null ? ReadRequest.NOTHING : request;
        REQUESTS.add(honoured);
        return new Reader(resumeFrom, ordinal, honoured);
    }

    /** Whether a record satisfies every filter and, if there are alternatives, one of them. */
    static boolean matches(long[] record, ReadRequest request) {
        if (!all(record, request.filters())) {
            return false;
        }
        return request.alternatives().isEmpty()
                || request.alternatives().stream().anyMatch(alternative -> all(record, alternative));
    }

    private static boolean all(long[] record, List<ReadRequest.Filter> filters) {
        for (ReadRequest.Filter filter : filters) {
            if (!matches(record, filter)) {
                return false;
            }
        }
        return true;
    }

    /** Honours a filter on the two numeric columns and ignores the rest, as a store may. */
    private static boolean matches(long[] record, ReadRequest.Filter filter) {
        long value;
        if (filter.column().equals("id")) {
            value = record[0];
        } else if (filter.column().equals("amount")) {
            value = record[1];
        } else {
            return true;
        }
        if (!(filter.value() instanceof Number number)) {
            return true;
        }
        long literal = number.longValue();
        return switch (filter.comparison()) {
            case EQ -> value == literal;
            case NE -> value != literal;
            case LT -> value < literal;
            case LE -> value <= literal;
            case GT -> value > literal;
            case GE -> value >= literal;
            case IS_NULL -> false;
            case IS_NOT_NULL -> true;
        };
    }

    /** A scan of the store from a watermark, buffered and drained -- {@code LutScanReader}'s shape. */
    private static final class Reader implements PartitionReader {

        private final ArrayDeque<long[]> buffered = new ArrayDeque<>();
        private final int ordinal;
        private final ReadRequest request;
        private int watermark;

        /** Where the scan being drained began, which is the position until it is drained. */
        private int scanStart;

        private boolean paused;
        private boolean closed;
        private long sequence;

        Reader(SourceOffset from, int ordinal, ReadRequest request) {
            this.watermark = from == null || from.isBeginning() ? 0 : Integer.parseInt(from.token());
            this.scanStart = watermark;
            this.ordinal = ordinal;
            this.request = request;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            if (paused || closed) {
                return 0;
            }
            if (buffered.isEmpty()) {
                // The scan. Like a last-update-time filter, it takes everything the store has gained
                // since the watermark -- honouring what was pushed, as a store does -- and the
                // position moves past it once the scan is drained, exactly as LutScanReader's does:
                // until then position() still names where this scan began.
                SCANS.incrementAndGet();
                int size = STORE.size();
                scanStart = watermark;
                for (int i = watermark; i < size; i++) {
                    long[] record = STORE.get(i);
                    if (matches(record, request)) {
                        buffered.add(record);
                        ROWS_READ.incrementAndGet();
                    }
                }
                watermark = size;
            }
            int limit = maxPerPoll > 0 ? Math.min(maxRecords, maxPerPoll) : maxRecords;
            int emitted = 0;
            while (emitted < limit && !buffered.isEmpty()) {
                long[] record = buffered.poll();
                RowWriter writer = sink.beginRow();
                writer.setLong(0, record[0]);
                if (request.columns().isEmpty() || request.columns().contains("user_id")) {
                    writer.setString(1, "u" + (record[0] % 7));
                } else {
                    writer.setUnread(1);
                }
                writer.setLong(2, record[1]);
                writer.weight(1L)
                        .eventTimestampNanos(System.currentTimeMillis() * 1_000_000L)
                        .sequence(++sequence)
                        .commit();
                emitted++;
            }
            if (emitted == 0 && ordinal == holdEmptyPollForReaderNumber) {
                // SRC-10's deterministic reproduction: this is the exact poll that would otherwise
                // close a catch-up reader within a millisecond or two of its history being
                // delivered. Parking here, rather than the test racing to observe it, is what turns
                // "sometimes caught it" into "always caught it".
                java.util.concurrent.CountDownLatch gate = heldPollGate;
                if (gate != null) {
                    try {
                        gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            long delay = pollDelayMillis;
            if (emitted > 0 && delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(Integer.toString(buffered.isEmpty() ? watermark : scanStart));
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
