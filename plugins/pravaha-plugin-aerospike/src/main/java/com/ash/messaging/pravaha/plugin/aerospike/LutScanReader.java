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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.Expression;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.query.PartitionFilter;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Reads what changed, by scanning with a server-side last-update-time filter.
 *
 * <p>The whole strategy in one sentence: ask Aerospike for the records whose last update is newer
 * than the last one we saw. The filter runs on the server, so a set of a hundred million records
 * with a thousand recent changes transfers a thousand records rather than a hundred million -- which
 * is the difference between a strategy somebody would run against production and one they would not.
 *
 * <p><strong>The offset is a last-update time, and the boundary is re-read.</strong> Records written
 * in the same nanosecond as the watermark would be missed by a strictly-greater filter and
 * duplicated by a greater-or-equal one. Duplicated is the safe direction -- the engine's
 * deduplicating sink and the Z-set algebra both survive a repeat, and nothing survives a loss -- so
 * the filter is greater-or-equal and the guarantee is at-least-once. Saying it out loud is the
 * point; a plugin that quietly chose the other direction would lose records at exactly the rate the
 * cluster is busy.
 *
 * <p>A scan is a batch, not a stream. Each poll runs one scan and buffers what it returns; when the
 * buffer empties, the next poll starts another. That is why the latency is the scan interval and not
 * the write latency, and why this strategy is a fallback rather than the recommendation.
 */
final class LutScanReader implements PartitionReader {

    /**
     * {@code Exp.lastUpdate()} is nanoseconds since the <strong>Unix</strong> epoch.
     *
     * <p>Worth stating because Aerospike's own record metadata is famously in "Citrusleaf" time --
     * seconds since 1 January 2010 -- and a good deal of published advice says the expression is
     * too. It is not, on server 8 and client 10, and the two mistakes fail in opposite and equally
     * confusing ways. Converting a Unix time down to Citrusleaf makes the threshold forty years too
     * early, so every scan matches every record and the reader loops for ever re-reading the same
     * rows -- which presents as a hang, not as wrong data. Converting the other way asks for records
     * updated forty years hence, and the scan returns nothing at all, which reads as a quiet set.
     *
     * <p>Measured against a real server rather than assumed, and this is the single clearest reason
     * this plugin is tested against one: a mock would have agreed with whichever the code believed.
     */
    private final IAerospikeClient client;

    private final String namespace;
    private final String set;
    private final StreamSchema schema;
    private final int firstPartition;
    private final int partitionCount;
    private final int recordsPerSecond;
    private final int socketTimeoutMillis;
    private final int totalTimeoutMillis;
    private final ReadRequest request;
    private final ArrayDeque<Record> buffered = new ArrayDeque<>();

    private long watermarkNanos;
    private long scanStartedNanos;
    private long recordsRead;
    private long scans;
    private boolean paused;

    /** The ordinal of the event-time column, or -1 when the deployment named none. */
    private final int eventTimeOrdinal;

    LutScanReader(
            IAerospikeClient client,
            String namespace,
            String set,
            StreamSchema schema,
            int firstPartition,
            int partitionCount,
            int recordsPerSecond,
            int socketTimeoutMillis,
            int totalTimeoutMillis,
            SourceOffset resumeFrom,
            ReadRequest request) {
        this.client = client;
        this.namespace = namespace;
        this.set = set;
        this.schema = schema;
        this.firstPartition = firstPartition;
        this.partitionCount = partitionCount;
        this.recordsPerSecond = recordsPerSecond;
        this.socketTimeoutMillis = socketTimeoutMillis;
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.request = request == null ? ReadRequest.NOTHING : request;
        this.watermarkNanos = parse(resumeFrom);
        // The column the schema marked as event time, if any. Resolved once here rather than per
        // record: a name lookup on the ingest path is the sort of thing that does not show up until
        // the throughput graph does.
        this.eventTimeOrdinal = schema.eventTimeOrdinal().orElse(-1);
    }

    private static long parse(SourceOffset offset) {
        if (offset == null || offset.token() == null || offset.token().isBlank()) {
            return 0;
        }
        String token = offset.token();
        if (!token.startsWith("lut=")) {
            throw new PravahaException(
                    AerospikeErrors.MALFORMED_OFFSET,
                    "offset '" + token + "' was not written by this plugin, which writes 'lut=<nanos>'. "
                            + "Resuming from another plugin's offset would read from an arbitrary point.");
        }
        try {
            return Long.parseLong(token.substring(4));
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    AerospikeErrors.MALFORMED_OFFSET, "offset '" + token + "' does not hold a number", e);
        }
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        if (buffered.isEmpty()) {
            scan();
        }
        int emitted = 0;
        while (emitted < maxRecords && !buffered.isEmpty()) {
            Record record = buffered.poll();
            RowWriter writer = sink.beginRow();
            AerospikeSchemas.copyInto(record, schema, writer);
            // The record's own time when a bin holds it, the scan's start time otherwise.
            //
            // The scan time alone was not a neutral default: a windowed query assigns rows by a
            // column and the watermark came from wall-clock, so every window was already long
            // closed when its rows arrived and every record was dropped as late. The view stayed
            // empty and the query reported RUNNING.
            //
            // The client does not expose a record's last-update time -- it is readable only through
            // an expression, which a scan has no place to attach -- so where no bin holds a time,
            // "seen by the scan that began at T" remains the honest answer.
            writer.weight(1L)
                    .eventTimestampNanos(eventTimeOf(record))
                    .sequence(++recordsRead)
                    .commit();
            emitted++;
        }
        return emitted;
    }

    /** A record's event time: its declared bin, or the scan's start when none was declared. */
    private long eventTimeOf(Record record) {
        if (eventTimeOrdinal < 0) {
            return scanStartedNanos;
        }
        Object value = record.bins.get(schema.field(eventTimeOrdinal).name());
        // A record whose event-time bin is absent or not a number falls back to the scan's time
        // rather than to zero. Zero would place it in the first window of 1970 and hold the
        // watermark of every partition down to it.
        return value instanceof Number number ? number.longValue() : scanStartedNanos;
    }

    /**
     * Runs one scan and buffers what it returns.
     *
     * <p>The watermark advances to the newest record <em>this scan saw</em>, and only after the scan
     * completes. Advancing it per record would mean a scan that failed halfway had already moved the
     * offset past records it never delivered.
     */
    private void scan() {
        ScanPolicy policy = new ScanPolicy();
        policy.filterExp = filter();
        policy.maxConcurrentNodes = 1;
        // Timeouts, because the client's defaults are "wait for ever". A scan that never returns
        // takes its lane thread with it and looks exactly like a hung engine: no log line, no
        // error, no progress, and the first useful diagnostic is a thread dump. Found by a test
        // that hung rather than failed, which is how this class of bug always announces itself.
        policy.socketTimeout = socketTimeoutMillis;
        policy.totalTimeout = totalTimeoutMillis;
        if (recordsPerSecond > 0) {
            // Protecting somebody's OLTP workload from this scan is the difference between a
            // strategy that is allowed to run in business hours and one that is not.
            policy.recordsPerSecond = recordsPerSecond;
        }
        List<Record> found = new ArrayList<>();
        long startedNanos = System.currentTimeMillis() * 1_000_000L;
        try {
            client.scanPartitions(
                    policy,
                    PartitionFilter.range(firstPartition, partitionCount),
                    namespace,
                    set,
                    (Key key, Record record) -> found.add(record));
        } catch (AerospikeException e) {
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "scan of " + namespace + "." + set + " partitions [" + firstPartition + ", "
                            + (firstPartition + partitionCount) + ") failed: " + e.getMessage(),
                    e);
        }
        buffered.addAll(found);
        // The watermark moves to when this scan *started*, not to when it finished. A record written
        // during the scan may or may not have been seen depending on which partition it landed in
        // and when the scan reached it; taking the start time re-reads that window next time, which
        // is a duplicate rather than a loss. Duplicates the engine survives; losses it cannot.
        scanStartedNanos = startedNanos;
        watermarkNanos = startedNanos;
        scans++;
    }

    /**
     * The server-side filter: last update at or after the watermark, plus whatever the engine asked
     * to push down.
     *
     * <p>Anything untranslatable is simply absent from the expression, never approximated. The
     * engine keeps its own filter regardless, so an unpushed predicate costs bandwidth; a wrongly
     * pushed one costs rows, and a missing row is indistinguishable from one that was never written.
     */
    private Expression filter() {
        List<Exp> conditions = new ArrayList<>();
        conditions.add(Exp.ge(Exp.lastUpdate(), Exp.val(watermarkNanos)));
        for (ReadRequest.Filter pushed : request.filters()) {
            Exp translated = AerospikeExpressions.translate(pushed, schema);
            if (translated != null) {
                conditions.add(translated);
            }
        }
        return Exp.build(conditions.size() == 1 ? conditions.get(0) : Exp.and(conditions.toArray(new Exp[0])));
    }

    @Override
    public SourceOffset position() {
        return new SourceOffset("lut=" + watermarkNanos);
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    long scanCount() {
        return scans;
    }

    @Override
    public void close() {
        buffered.clear();
    }
}
