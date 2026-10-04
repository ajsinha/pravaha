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
 * <p>A scan is a batch, not a stream. A pass over the partitions starts when the last one has been
 * drained and the scan interval has passed. That is why the latency is the scan interval and not the
 * write latency, and why this strategy is a fallback rather than the recommendation.
 *
 * <p><strong>A pass is read a page at a time, and a page is what one poll may emit</strong> (SRC-7).
 * The pass used to be read whole into a list and then handed on {@code maxRecords} at a time, so the
 * first pass of a fresh registration -- which matches every record in the set -- put the entire set
 * on the heap before the first row reached a lane: ten million records, ten million {@code Record}s.
 * Each page is now a {@code scanPartitions} call with {@link ScanPolicy#maxRecords} set to the poll's
 * own {@code maxRecords}, over a {@link PartitionFilter} the client keeps its place in, so the next
 * page resumes where this one stopped and the buffer never holds more than one poll can take.
 *
 * <p>Chosen over a bounded hand-over from a scanning thread because it has no thread: nothing blocks
 * on a full queue, so {@link #close} has nothing to unblock and cannot deadlock, and a failure is
 * thrown on the lane that polled rather than parked in another thread for someone to collect. What it
 * costs is a round trip per page, which at a page of a poll's size is noise beside a scan.
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

    /**
     * The pass in progress, which the client updates as each page is read so the next page resumes
     * from it; null between passes.
     */
    private PartitionFilter pass;

    /** How many empty pages one poll reads before answering zero with the pass still open. */
    private static final int EMPTY_PAGES_PER_POLL = 16;

    /** The largest the buffer has been, for the test that holds it to one page. */
    private int peakBuffered;

    private long pages;

    private long watermarkNanos;
    private long scanStartedNanos;

    /**
     * The least time between one scan finishing and the next starting.
     *
     * <p>There was none, and the cost was not subtle: {@link #poll} starts a scan whenever the
     * buffer is empty, and the ingest pump polls about once a millisecond, so scans ran back to back
     * for as long as a query was registered. Against a real Community node, one query took the
     * cluster from 1% to 200-310% CPU and 43-153 scans a second of a set nobody was writing to.
     *
     * <p>This class's own javadoc already called the scan interval the source of this strategy's
     * latency. It was describing something that did not exist.
     */
    private final long scanIntervalNanos;

    /** When the last scan finished, so the next one can be made to wait. */
    private long lastScanEndedNanos = Long.MIN_VALUE;

    private long recordsRead;
    private long scans;
    private boolean paused;

    /** The ordinal of the event-time column, or -1 when the deployment named none. */
    private final int eventTimeOrdinal;

    /**
     * The bins a pushed projection asks the server for, or null for every bin. Always includes the
     * event-time bin, which this reader reads for itself whatever the engine needs.
     */
    private final String[] binNames;

    /** Per schema ordinal, whether that bin was read -- false only under a pushed projection. */
    private final boolean[] read;

    LutScanReader(
            IAerospikeClient client,
            String namespace,
            String set,
            StreamSchema schema,
            int firstPartition,
            int partitionCount,
            int recordsPerSecond,
            int scanIntervalMillis,
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
        this.scanIntervalNanos =
                java.time.Duration.ofMillis(Math.max(0, scanIntervalMillis)).toNanos();
        this.socketTimeoutMillis = socketTimeoutMillis;
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.request = request == null ? ReadRequest.NOTHING : request;
        this.watermarkNanos = parse(resumeFrom);
        // The column the schema marked as event time, if any. Resolved once here rather than per
        // record: a name lookup on the ingest path is the sort of thing that does not show up until
        // the throughput graph does.
        this.eventTimeOrdinal = schema.eventTimeOrdinal().orElse(-1);
        this.binNames = projectedBins(schema, this.request, eventTimeOrdinal);
        this.read = new boolean[schema.fieldCount()];
        List<String> names = binNames == null ? null : List.of(binNames);
        for (int ordinal = 0; ordinal < read.length; ordinal++) {
            read[ordinal] =
                    names == null || names.contains(schema.field(ordinal).name());
        }
    }

    /**
     * The bins to name on the scan, or null to read them all: the server then sends only those
     * bins of each record, which is bytes off the wire and decode work off the lane.
     *
     * <p>A requested column the schema does not declare means the request is about something else,
     * and reading every bin is the safe answer to that.
     */
    static String[] projectedBins(StreamSchema schema, ReadRequest request, int eventTimeOrdinal) {
        if (request.columns().isEmpty()) {
            return null;
        }
        java.util.Set<String> bins = new java.util.LinkedHashSet<>();
        for (String column : request.columns()) {
            if (!schema.hasField(column)) {
                return null;
            }
            bins.add(column);
        }
        if (eventTimeOrdinal >= 0) {
            bins.add(schema.field(eventTimeOrdinal).name());
        }
        return bins.size() >= schema.fieldCount() ? null : bins.toArray(new String[0]);
    }

    private static long parse(SourceOffset offset) {
        if (offset == null || offset.token() == null || offset.token().isBlank()) {
            return 0;
        }
        String token = offset.token();
        if (token.startsWith(DetectingScanReader.PREFIX)) {
            throw new PravahaException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "offset '" + token + "' was written with deletes: detect, and this source now has deletes: "
                            + "ignore. The restored view holds rows this reader cannot tell apart from new ones; "
                            + "switch deletes back, or drop and re-register the query so it starts afresh.");
        }
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
        if (maxRecords <= 0) {
            return 0;
        }
        if (buffered.isEmpty()) {
            if (pass == null) {
                if (!readyToScan()) {
                    // Nothing buffered and too soon to ask again. Zero means idle to the pump, which
                    // naps -- rather than this reader spinning a scan against the cluster per poll.
                    return 0;
                }
                beginPass();
            }
            // A page can come back empty before the pass is done -- the partitions it reached held
            // nothing that matched -- and an empty poll reads to the pump as "caught up". So a few
            // more pages are tried in the same poll before answering zero; bounded, because a
            // server that kept answering empty and not-done must not hold the lane.
            for (int attempt = 0; attempt < EMPTY_PAGES_PER_POLL && buffered.isEmpty() && !pass.isDone(); attempt++) {
                readPage(maxRecords);
            }
            if (buffered.isEmpty() && pass.isDone()) {
                finishPass();
                return 0;
            }
        }
        int emitted = 0;
        while (emitted < maxRecords && !buffered.isEmpty()) {
            Record record = buffered.poll();
            RowWriter writer = sink.beginRow();
            AerospikeSchemas.copyInto(record, schema, writer, read);
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
        // Drained: only now may the offset move past this scan. While anything remains buffered,
        // position() keeps reporting the previous watermark, so a checkpoint taken mid-drain resumes
        // by re-scanning this window -- re-delivering the records already handed over, which is what
        // AT_LEAST_ONCE means and what the engine's weights absorb, instead of stepping over the
        // ones it never handed over at all.
        if (buffered.isEmpty() && pass != null && pass.isDone()) {
            finishPass();
        }
        return emitted;
    }

    /** Whether enough time has passed since the last scan ended for another to be worth running. */
    private boolean readyToScan() {
        if (scanIntervalNanos <= 0 || lastScanEndedNanos == Long.MIN_VALUE) {
            return true; // no interval configured, or nothing scanned yet
        }
        return System.nanoTime() - lastScanEndedNanos >= scanIntervalNanos;
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
     * Starts a pass over this reader's partitions.
     *
     * <p>The watermark advances to when the pass <em>started</em>, and only after it has been read to
     * the end and drained. Advancing it earlier would mean a pass that failed halfway, or a checkpoint
     * taken mid-drain, had already moved the offset past records it never delivered.
     */
    private void beginPass() {
        pass = PartitionFilter.range(firstPartition, partitionCount);
        scanStartedNanos = System.currentTimeMillis() * 1_000_000L;
        scans++;
    }

    /**
     * Reads the pass's next page -- at most {@code maxRecords} records -- into the buffer.
     *
     * <p>The filter is the same for every page of a pass, because the watermark it reads does not
     * move until the pass is finished; the client's {@link PartitionFilter} carries where each
     * partition stopped.
     */
    @SuppressWarnings(
            "deprecation") // Aerospike deprecates scans for query(); moving is a reader rewrite, proven only against a
    // server
    private void readPage(int maxRecords) {
        ScanPolicy policy = new ScanPolicy();
        policy.filterExp = filter();
        policy.maxConcurrentNodes = 1;
        policy.maxRecords = maxRecords;
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
        try {
            // binNames null reads every bin; otherwise only the projection's. The client's varargs
            // treat an absent array and a null one alike. The callback runs on this thread with
            // maxConcurrentNodes = 1, so the buffer is filled here and nowhere else.
            client.scanPartitions(
                    policy, pass, namespace, set, (Key key, Record record) -> buffered.add(record), binNames);
        } catch (AerospikeException e) {
            // The pass is abandoned whole: the watermark has not moved, so the next pass re-reads
            // from where this one began, which is a duplicate rather than a loss.
            pass = null;
            buffered.clear();
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "scan of " + namespace + "." + set + " partitions [" + firstPartition + ", "
                            + (firstPartition + partitionCount) + ") failed: " + e.getMessage(),
                    e);
        }
        pages++;
        peakBuffered = Math.max(peakBuffered, buffered.size());
    }

    /**
     * Ends a pass that has been read to the end and drained: only now may the offset move past it.
     *
     * <p>To when the pass <em>started</em>, not when it finished. A record written during the pass may
     * or may not have been seen depending on which partition it landed in and when the pass reached
     * it; taking the start time re-reads that window next time, which is a duplicate rather than a
     * loss. Duplicates the engine survives; losses it cannot.
     *
     * <p>And not at the end of the read, either. Advancing it there made position() report
     * "everything up to this scan" while records from that scan were still sitting unread in the
     * buffer -- so a checkpoint taken mid-drain recorded an offset past rows nobody had been given,
     * and a reader resumed from it filtered on a time strictly after them. Found by
     * AerospikeSourceTckIT, which polls two of five records and then resumes.
     */
    private void finishPass() {
        watermarkNanos = scanStartedNanos;
        lastScanEndedNanos = System.nanoTime();
        pass = null;
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
        return withPushedFilters(conditions, request, schema);
    }

    /**
     * {@code conditions} and whatever of {@code request}'s filters translates, as one expression, or
     * null when there is nothing to filter on. Shared with {@link DetectingScanReader}, whose scan
     * has no last-update condition and must push exactly what this one pushes.
     */
    static Expression withPushedFilters(List<Exp> conditions, ReadRequest request, StreamSchema schema) {
        for (ReadRequest.Filter pushed : request.filters()) {
            Exp translated = AerospikeExpressions.translate(pushed, schema);
            if (translated != null) {
                conditions.add(translated);
            }
        }
        Exp anyOf = AerospikeExpressions.anyOf(request.alternatives(), schema);
        if (anyOf != null) {
            conditions.add(anyOf);
        }
        if (conditions.isEmpty()) {
            return null;
        }
        return Exp.build(conditions.size() == 1 ? conditions.get(0) : Exp.and(conditions.toArray(new Exp[0])));
    }

    /** The bins this reader asks the server for, or null for all of them. For tests. */
    String[] binNames() {
        return binNames == null ? null : binNames.clone();
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

    /** Passes started -- what the scan interval bounds. */
    long scanCount() {
        return scans;
    }

    /** Pages read, across every pass. For tests. */
    long pageCount() {
        return pages;
    }

    /** The most records the buffer has held at once. For tests. */
    int peakBuffered() {
        return peakBuffered;
    }

    @Override
    public void close() {
        buffered.clear();
        pass = null;
    }
}
