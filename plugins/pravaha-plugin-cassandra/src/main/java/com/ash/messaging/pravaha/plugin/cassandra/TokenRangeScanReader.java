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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.time.Duration;
import java.util.Iterator;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Reads one token range by running one full pass over it, on an interval.
 *
 * <p>The whole strategy in one sentence: page through {@code WHERE token(pk) > ? AND token(pk) <=
 * ?} from the bottom of the assigned range to the top, then -- after waiting out {@code
 * scan.interval.ms} -- do it again. Unlike the Aerospike plugin's {@code lut-scan}, there is no
 * filter that makes a later pass cheaper than the first: CQL has no honest way to ask "what changed",
 * so every pass reads every row in the range (see {@link CassandraStrategy}).
 *
 * <p><strong>The offset is a token, and resuming continues the pass it was taken from.</strong> A
 * fresh reader with no resume offset starts a pass at the bottom of its assigned range; a reader
 * resumed from a token -- after a restart, or after a page of its own pass failed -- continues that
 * same pass with {@code token(pk) >= <resumed>}: the partition it stopped in is read again from its
 * first clustering row (CASS-1). It resumed with {@code >}, and a stop part way through a wide
 * partition skipped the rest of that partition until the next pass. Reading the partition's first
 * rows twice is what every pass of this reader does to every row anyway: it re-reads the whole range
 * at {@code +1} each time, which is why only a keyed view may be built over it. Once a pass
 * finishes, this reader goes back to the bottom of its range for the next one -- there is no partial
 * state between passes to resume into, because a full scan does not have one.
 *
 * <p>Iteration is lazy: the driver's {@link ResultSet} fetches pages on demand as {@link #poll}
 * consumes rows, rather than this reader materialising a whole pass into memory the way {@code
 * LutScanReader} materialises a whole filtered scan -- a token range on a real table can be far
 * larger than what mattered in one Aerospike scan interval.
 */
final class TokenRangeScanReader implements PartitionReader {

    /**
     * Opens a pass over {@code token(pk) > floor} (or {@code >=}, when {@code inclusive}) up to the
     * range's top: the one call to the cluster, so a test can stand in for it.
     */
    interface PassOpener {
        Iterator<Row> open(long floor, boolean inclusive);
    }

    private final PassOpener opener;
    private final StreamSchema schema;

    /** Per schema ordinal, whether the statements select it -- false only under a projection. */
    private final boolean[] read;

    private final String eventTimeColumn;
    private final long lowerBound;
    private final long upperBound;
    private final boolean inclusiveLower;
    private final long scanIntervalNanos;

    private Iterator<Row> current;
    private long passStartedNanos;
    private long lastScanEndedNanos = Long.MIN_VALUE;
    private long recordsRead;
    private long scans;
    private boolean paused;

    /** The last token consumed in the current pass, or {@code null} before the pass's first row. */
    private Long lastConsumedToken;

    TokenRangeScanReader(
            CqlSession session,
            PreparedStatement greaterThan,
            PreparedStatement greaterOrEqual,
            boolean[] read,
            StreamSchema schema,
            String eventTimeColumn,
            long lowerBound,
            long upperBound,
            boolean inclusiveLower,
            int fetchSize,
            ConsistencyLevel consistencyLevel,
            Duration timeout,
            int scanIntervalMillis,
            SourceOffset resumeFrom) {
        this(
                (floor, inclusive) -> execute(
                        session,
                        (inclusive ? greaterOrEqual : greaterThan)
                                .boundStatementBuilder(floor, upperBound)
                                .setPageSize(fetchSize)
                                .setConsistencyLevel(consistencyLevel)
                                .setTimeout(timeout)
                                .build(),
                        floor,
                        upperBound),
                read,
                schema,
                eventTimeColumn,
                lowerBound,
                upperBound,
                inclusiveLower,
                scanIntervalMillis,
                resumeFrom);
    }

    TokenRangeScanReader(
            PassOpener opener,
            boolean[] read,
            StreamSchema schema,
            String eventTimeColumn,
            long lowerBound,
            long upperBound,
            boolean inclusiveLower,
            int scanIntervalMillis,
            SourceOffset resumeFrom) {
        this.opener = opener;
        this.schema = schema;
        this.read = read.clone();
        this.eventTimeColumn = eventTimeColumn;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.inclusiveLower = inclusiveLower;
        this.scanIntervalNanos =
                Duration.ofMillis(Math.max(0, scanIntervalMillis)).toNanos();
        this.lastConsumedToken = parse(resumeFrom);
    }

    static Long parse(SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return null;
        }
        String token = offset.token();
        if (token.startsWith(DetectingTokenRangeReader.PREFIX)) {
            throw new PravahaException(
                    CassandraErrors.BAD_CONFIGURATION,
                    "offset '" + token + "' was written with deletes: detect, and this source now has deletes: "
                            + "ignore. The restored view holds each row once, and this reader would add every row "
                            + "again on every pass; switch deletes back, or drop and re-register the query so it "
                            + "starts afresh.");
        }
        if (!token.startsWith("token=")) {
            throw new PravahaException(
                    CassandraErrors.MALFORMED_OFFSET,
                    "offset '" + token + "' was not written by this plugin, which writes 'token=<value>'. "
                            + "Resuming from another plugin's offset would read from an arbitrary point.");
        }
        try {
            return Long.parseLong(token.substring("token=".length()));
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    CassandraErrors.MALFORMED_OFFSET, "offset '" + token + "' does not hold a number", e);
        }
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        if (current == null) {
            if (!readyToScan()) {
                // Nothing in flight and too soon to start the next pass. Zero means idle to the
                // pump, which naps -- rather than this reader opening a new query per poll.
                return 0;
            }
            current = startPass();
        }
        int emitted = 0;
        while (emitted < maxRecords) {
            boolean more;
            try {
                // hasNext() is where a page boundary is crossed, so a mid-pass failure -- the node
                // this pass was reading from going away, a timeout on a later page -- surfaces here
                // and not only from the statement that opened the pass.
                more = current.hasNext();
            } catch (DriverException e) {
                current = null;
                throw new PravahaException(
                        CassandraErrors.OPERATION_FAILED,
                        "scan of token range (" + lowerBound + ", " + upperBound + "] failed while paging: "
                                + e.getMessage(),
                        e);
            }
            if (!more) {
                // The pass is over. The next one starts at the bottom of the range again -- a full
                // scan has no partial state between passes to resume into.
                current = null;
                lastConsumedToken = null;
                lastScanEndedNanos = System.nanoTime();
                scans++;
                break;
            }
            Row row = current.next();
            RowWriter writer = sink.beginRow();
            CassandraSchemas.copyInto(row, schema, writer, read);
            writer.weight(1L)
                    .eventTimestampNanos(eventTimeOf(row))
                    .sequence(++recordsRead)
                    .commit();
            lastConsumedToken = row.getLong(CassandraSourcePlugin.TOKEN_ALIAS);
            emitted++;
        }
        return emitted;
    }

    /** Whether enough time has passed since the last pass ended to start another. */
    private boolean readyToScan() {
        if (scanIntervalNanos <= 0 || lastScanEndedNanos == Long.MIN_VALUE) {
            return true; // no interval configured, or nothing scanned yet
        }
        return System.nanoTime() - lastScanEndedNanos >= scanIntervalNanos;
    }

    /** A row's event time: its declared column, or the moment this pass began when none was declared. */
    private long eventTimeOf(Row row) {
        if (eventTimeColumn.isBlank() || row.isNull(eventTimeColumn)) {
            return passStartedNanos;
        }
        return CassandraSchemas.timestampNanos(row, eventTimeColumn);
    }

    private Iterator<Row> startPass() {
        passStartedNanos = System.currentTimeMillis() * 1_000_000L;
        // Resuming: from the partition it stopped in, inclusive, so the rest of it is not skipped
        // (CASS-1). Starting: from the range's own lower bound, as the range says.
        boolean inclusive = lastConsumedToken != null || inclusiveLower;
        long floor = lastConsumedToken != null ? lastConsumedToken : lowerBound;
        return opener.open(floor, inclusive);
    }

    private static Iterator<Row> execute(CqlSession session, BoundStatement bound, long floor, long upperBound) {
        try {
            ResultSet resultSet = session.execute(bound);
            return resultSet.iterator();
        } catch (DriverException e) {
            throw new PravahaException(
                    CassandraErrors.OPERATION_FAILED,
                    "scan of token range (" + floor + ", " + upperBound + "] failed: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public SourceOffset position() {
        return lastConsumedToken == null ? SourceOffset.BEGINNING : new SourceOffset("token=" + lastConsumedToken);
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    long passCount() {
        return scans;
    }

    @Override
    public void close() {
        current = null;
    }
}
