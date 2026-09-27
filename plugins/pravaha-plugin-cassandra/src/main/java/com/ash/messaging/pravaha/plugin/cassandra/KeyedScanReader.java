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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.Row;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Reads the partitions a query's filters name, on an interval, instead of the whole token range.
 *
 * <p>Each pass runs one statement per partition read ({@link CassandraPushdown.KeyRead}) -- the
 * whole partition key by equality, and any clustering restrictions -- and keeps the rows whose
 * token falls in this reader's range: every reader of the binding runs the same reads, and the
 * range is what stops two of them emitting one row. Otherwise it is {@link TokenRangeScanReader}:
 * every row of a pass at {@code +1}, a pass every {@code scan.interval.ms}, at-least-once.
 *
 * <p><strong>The position is always the beginning.</strong> A pass here reads a handful of
 * partitions rather than a range, so there is no token to continue from that would be worth the
 * bookkeeping; a restore -- or a shared reader replaced at this one's position -- starts the pass
 * again, which repeats rows this source already declares it repeats. A token position a range scan
 * wrote is accepted and does the same.
 */
final class KeyedScanReader implements PartitionReader {

    /** Runs one read, returning its rows in the order Cassandra gives them. */
    @FunctionalInterface
    interface ReadRunner {
        Iterator<Row> run(CassandraPushdown.KeyRead read);
    }

    private final List<CassandraPushdown.KeyRead> reads;
    private final ReadRunner runner;
    private final StreamSchema schema;
    private final boolean[] read;
    private final String eventTimeColumn;
    private final long lowerBound;
    private final long upperBound;
    private final boolean inclusiveLower;
    private final long scanIntervalNanos;
    private final String range;

    private Iterator<Row> current;
    private long passStartedNanos;
    private long lastScanEndedNanos = Long.MIN_VALUE;
    private long recordsRead;
    private long passes;
    private boolean paused;

    KeyedScanReader(
            List<CassandraPushdown.KeyRead> reads,
            ReadRunner runner,
            boolean[] read,
            StreamSchema schema,
            String eventTimeColumn,
            long lowerBound,
            long upperBound,
            boolean inclusiveLower,
            int scanIntervalMillis,
            SourceOffset resumeFrom) {
        // Validated as a range scan's would be: an offset from deletes: detect or another plugin is
        // still refused by name, and a token is accepted and restarts the pass.
        TokenRangeScanReader.parse(resumeFrom);
        this.reads = List.copyOf(reads);
        this.runner = runner;
        this.read = read.clone();
        this.schema = schema;
        this.eventTimeColumn = eventTimeColumn;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.inclusiveLower = inclusiveLower;
        this.scanIntervalNanos =
                Duration.ofMillis(Math.max(0, scanIntervalMillis)).toNanos();
        this.range = (inclusiveLower ? "[" : "(") + lowerBound + ", " + upperBound + "]";
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        if (current == null) {
            if (!readyToScan()) {
                return 0;
            }
            passStartedNanos = System.currentTimeMillis() * 1_000_000L;
            current = inRange(reads, runner, lowerBound, upperBound, inclusiveLower, range);
        }
        int emitted = 0;
        while (emitted < maxRecords) {
            boolean more;
            try {
                more = current.hasNext();
            } catch (DriverException e) {
                current = null;
                throw new PravahaException(
                        CassandraErrors.OPERATION_FAILED,
                        "reading the pushed partitions in token range " + range + " failed: " + e.getMessage(),
                        e);
            } catch (PravahaException e) {
                // A read that could not start: the next poll starts the pass again rather than going
                // on from the read after it.
                current = null;
                throw e;
            }
            if (!more) {
                current = null;
                lastScanEndedNanos = System.nanoTime();
                passes++;
                break;
            }
            Row row = current.next();
            RowWriter writer = sink.beginRow();
            CassandraSchemas.copyInto(row, schema, writer, read);
            writer.weight(1L)
                    .eventTimestampNanos(eventTimeOf(row))
                    .sequence(++recordsRead)
                    .commit();
            emitted++;
        }
        return emitted;
    }

    /**
     * The rows of {@code reads}, one read after another, kept only where their token is in the range.
     * Shared with {@code deletes: detect}, whose pass is one read of one partition.
     */
    static Iterator<Row> inRange(
            List<CassandraPushdown.KeyRead> reads,
            ReadRunner runner,
            long lowerBound,
            long upperBound,
            boolean inclusiveLower,
            String range) {
        Function<CassandraPushdown.KeyRead, Iterator<Row>> open = keyRead -> {
            try {
                return runner.run(keyRead);
            } catch (DriverException e) {
                throw new PravahaException(
                        CassandraErrors.OPERATION_FAILED,
                        "reading a pushed partition in token range " + range + " failed: " + e.getMessage(),
                        e);
            }
        };
        return new Iterator<>() {
            private final Iterator<CassandraPushdown.KeyRead> remaining = reads.iterator();
            private Iterator<Row> rows = Collections.emptyIterator();
            private Row next;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (rows.hasNext()) {
                        Row candidate = rows.next();
                        long token = candidate.getLong(CassandraSourcePlugin.TOKEN_ALIAS);
                        boolean above = inclusiveLower ? token >= lowerBound : token > lowerBound;
                        if (above && token <= upperBound) {
                            next = candidate;
                        }
                    } else if (remaining.hasNext()) {
                        rows = open.apply(remaining.next());
                    } else {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Row next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Row row = next;
                next = null;
                return row;
            }
        };
    }

    private boolean readyToScan() {
        if (scanIntervalNanos <= 0 || lastScanEndedNanos == Long.MIN_VALUE) {
            return true;
        }
        return System.nanoTime() - lastScanEndedNanos >= scanIntervalNanos;
    }

    private long eventTimeOf(Row row) {
        if (eventTimeColumn.isBlank() || row.isNull(eventTimeColumn)) {
            return passStartedNanos;
        }
        return CassandraSchemas.timestampNanos(row, eventTimeColumn);
    }

    @Override
    public SourceOffset position() {
        return SourceOffset.BEGINNING;
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
        return passes;
    }

    @Override
    public void close() {
        current = null;
    }
}
