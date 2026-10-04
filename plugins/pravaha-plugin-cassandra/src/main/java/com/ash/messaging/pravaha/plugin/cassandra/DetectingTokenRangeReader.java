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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.Row;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * {@code deletes: detect}: each full pass over the token range is compared, token by token, with
 * every row this reader has emitted, so a row that is gone is retracted, a row that changed is
 * retracted and re-inserted, and a row that did not change is not emitted again.
 *
 * <p>{@link TokenRangeScanReader} re-reads every row at {@code +1} on every pass, so a view over it
 * holds each row once per pass it has lived through, and a deleted row is simply not read again.
 * This reader turns the same passes into a changelog. Rows arrive in token order, and the rows it
 * holds are sorted by token, so the two are merged as the pass goes: when the scan moves past token
 * {@code t}, every held row with a token below {@code t} that the pass did not reach is gone, and
 * the rows of {@code t} itself are compared as a multiset -- a partition with clustering rows, or
 * two partitions whose keys collide on a token, is several rows under one token. Nothing but the
 * pass's current token is buffered.
 *
 * <p>For each token: held rows the pass did not return, at {@code -1} with the event time they were
 * inserted at; then rows the pass returned that were not held, at {@code +1}. An update to a row is
 * both. A row returned twice is counted twice, as the table holds it.
 *
 * <p><strong>The offset is not a token.</strong> It is {@code deletes=<reader>/<count>}: how many
 * rows this reader had emitted, backed by {@link EmittedRows}' files. A reader resumed from it holds
 * exactly the rows the restored view was built from and starts a new pass from the bottom of its
 * range. Re-reading the part of the range the interrupted pass had covered costs reads, and emits
 * nothing for rows that have not changed since.
 *
 * <p>A pass that fails emits nothing more, and never reads the rest of its range as deleted: the
 * sweep for a gap below a token runs only once the scan has reached that token.
 */
final class DetectingTokenRangeReader implements PartitionReader {

    /** What every offset this reader writes begins with. */
    static final String PREFIX = "deletes=";

    /** Opens one full pass over the range, in token order. */
    @FunctionalInterface
    interface PassSource {
        Iterator<Row> open();
    }

    static final EmittedRows.KeyCodec<Long> TOKENS = new EmittedRows.KeyCodec<>() {
        @Override
        public void write(DataOutput out, Long key) throws IOException {
            out.writeLong(key);
        }

        @Override
        public Long read(DataInput in) throws IOException {
            return in.readLong();
        }
    };

    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Change(long weight, long token, byte[] row, long eventTimeNanos) {}

    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Seen(byte[] row, long eventTimeNanos) {}

    private final PassSource passes;
    private final StreamSchema schema;
    private final boolean[] read;
    private final String eventTimeColumn;
    private final String range;
    private final int rowsPerPoll;
    private final long scanIntervalNanos;
    private final EmittedRows<Long> emitted;
    private final NavigableMap<Long, EmittedRows.Entry> held;
    private final RowRecorder recorder;
    private final ArrayDeque<Change> pending = new ArrayDeque<>();

    private @Nullable Iterator<Row> current;
    private long passStartedNanos;
    private long lastScanEndedNanos = Long.MIN_VALUE;
    private boolean passDrained;

    /** The token of the rows being gathered, or null before the pass's first row. */
    private @Nullable Long groupToken;

    private final List<Seen> group = new ArrayList<>();

    /** The last token whose rows have been compared, or null before the first. */
    private @Nullable Long compared;

    private long recordsRead;
    private long scans;
    private boolean paused;

    DetectingTokenRangeReader(
            PassSource passes,
            boolean[] read,
            StreamSchema schema,
            String eventTimeColumn,
            String range,
            int rowsPerPoll,
            int scanIntervalMillis,
            @Nullable SourceOffset resumeFrom,
            Path stateDir,
            long maxKeys) {
        this.passes = passes;
        this.schema = schema;
        this.read = read.clone();
        this.eventTimeColumn = eventTimeColumn;
        this.range = range;
        this.rowsPerPoll = Math.max(1, rowsPerPoll);
        this.scanIntervalNanos =
                Duration.ofMillis(Math.max(0, scanIntervalMillis)).toNanos();
        this.recorder = new RowRecorder(schema);
        TreeMap<Long, EmittedRows.Entry> rows = new TreeMap<>();
        this.held = rows;
        this.emitted = EmittedRows.open(
                rows,
                TOKENS,
                stateDir.resolve("t" + range.replaceAll("[\\[\\]()]", "").replace(',', '_')),
                resumeToken(resumeFrom),
                maxKeys,
                fingerprint(schema),
                new EmittedRows.Codes(CassandraErrors.DELETE_STATE_FULL, CassandraErrors.DELETE_STATE_FAILED),
                "cassandra source over token range " + range);
    }

    /** The stream's shape, which the state files must match. */
    static String fingerprint(StreamSchema schema) {
        StringBuilder shape = new StringBuilder("cassandra:");
        schema.fields()
                .forEach(field -> shape.append(field.name())
                        .append(' ')
                        .append(field.type().typeName())
                        .append(','));
        return shape.toString();
    }

    private static @Nullable String resumeToken(@Nullable SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return null;
        }
        String token = offset.token();
        if (token.startsWith("token=")) {
            throw new PravahaException(
                    CassandraErrors.BAD_CONFIGURATION,
                    "offset '" + token + "' was written with deletes: ignore, and this source now has deletes: "
                            + "detect. Nothing records which rows the restored view holds, so no pass could say "
                            + "which of them are gone; drop and re-register the query so it starts afresh.");
        }
        if (!token.startsWith(PREFIX)) {
            throw new PravahaException(
                    CassandraErrors.MALFORMED_OFFSET,
                    "offset '" + token + "' was not written by this plugin, which writes '" + PREFIX
                            + "<reader>/<count>' under deletes: detect");
        }
        return token.substring(PREFIX.length());
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        int written = drain(sink, maxRecords);
        if (written == maxRecords) {
            return written;
        }
        if (current == null) {
            if (!readyToScan()) {
                return written;
            }
            startPass();
        }
        // Bounded, so a pass over a large unchanged range -- which emits nothing -- still returns to
        // the pump between pages rather than holding the lane's feed for the whole of it.
        int scanned = 0;
        while (current != null && scanned < rowsPerPoll && pending.size() < maxRecords - written) {
            boolean more;
            try {
                more = current.hasNext();
            } catch (DriverException e) {
                current = null;
                throw new PravahaException(
                        CassandraErrors.OPERATION_FAILED,
                        "scan of token range " + range + " failed while paging, and nothing past the last "
                                + "compared token was emitted: " + e.getMessage(),
                        e);
            }
            if (!more) {
                endPass();
                break;
            }
            Row row = current.next();
            long token = row.getLong(CassandraSourcePlugin.TOKEN_ALIAS);
            if (groupToken != null && token != groupToken) {
                compareGroup();
            }
            CassandraSchemas.copyInto(row, schema, recorder.reset(), read);
            group.add(new Seen(recorder.toBytes(), eventTimeOf(row)));
            groupToken = token;
            scanned++;
        }
        written += drain(sink, maxRecords - written);
        if (passDrained && pending.isEmpty()) {
            passDrained = false;
            emitted.passEnded();
        }
        return written;
    }

    private int drain(RecordSink sink, int limit) {
        int written = 0;
        while (written < limit && !pending.isEmpty()) {
            Change change = pending.peek();
            if (change.weight() > 0) {
                // The ceiling bounds the rows held at every moment, so a pass that inserts before it
                // reaches the rows it will retract can meet it on the way.
                emitted.refuseBeyondCeiling(emitted.size() + 1);
            }
            pending.poll();
            RowWriter writer = sink.beginRow();
            RowRecorder.replay(change.row(), writer);
            writer.weight(change.weight())
                    .eventTimestampNanos(change.eventTimeNanos())
                    .sequence(++recordsRead)
                    .commit();
            emitted.emitted(change.weight(), change.token(), change.row(), change.eventTimeNanos());
            written++;
        }
        return written;
    }

    private void startPass() {
        passStartedNanos = System.currentTimeMillis() * 1_000_000L;
        groupToken = null;
        group.clear();
        compared = null;
        try {
            current = passes.open();
        } catch (DriverException e) {
            throw new PravahaException(
                    CassandraErrors.OPERATION_FAILED, "scan of token range " + range + " failed: " + e.getMessage(), e);
        }
    }

    /** The pass reached the top of the range: the last token's rows, then every held row above it. */
    private void endPass() {
        compareGroup();
        Map<Long, EmittedRows.Entry> above = compared == null ? held : held.tailMap(compared, false);
        retractAll(above);
        current = null;
        lastScanEndedNanos = System.nanoTime();
        passDrained = true;
        scans++;
    }

    /**
     * Compares the gathered rows of one token with the held ones, after retracting every held token
     * between the last one compared and this one: the pass went past them without a row.
     */
    private void compareGroup() {
        if (groupToken == null) {
            return;
        }
        long token = groupToken;
        retractAll(compared == null ? held.headMap(token, false) : held.subMap(compared, false, token, false));
        List<EmittedRows.Entry> old = new ArrayList<>();
        for (EmittedRows.Entry at = held.get(token); at != null; at = at.next) {
            old.add(at);
        }
        List<Seen> added = new ArrayList<>();
        for (Seen seen : group) {
            boolean matched = false;
            for (int index = 0; index < old.size(); index++) {
                if (Arrays.equals(old.get(index).row, seen.row())) {
                    old.remove(index);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                added.add(seen);
            }
        }
        for (EmittedRows.Entry gone : old) {
            pending.add(new Change(-1, token, gone.row, gone.eventTimeNanos));
        }
        for (Seen seen : added) {
            pending.add(new Change(1, token, seen.row(), seen.eventTimeNanos()));
        }
        compared = token;
        groupToken = null;
        group.clear();
    }

    private void retractAll(Map<Long, EmittedRows.Entry> tokens) {
        for (Map.Entry<Long, EmittedRows.Entry> each : tokens.entrySet()) {
            for (EmittedRows.Entry at = each.getValue(); at != null; at = at.next) {
                pending.add(new Change(-1, each.getKey(), at.row, at.eventTimeNanos));
            }
        }
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
        return new SourceOffset(PREFIX + emitted.token());
    }

    @Override
    public void checkpointed(SourceOffset offset) {
        if (offset != null && offset.token().startsWith(PREFIX)) {
            emitted.checkpointed(offset.token().substring(PREFIX.length()));
        }
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

    /** Whether a pass is being read or its changes are still to be emitted. For tests. */
    boolean passInProgress() {
        return current != null || !pending.isEmpty();
    }

    /** The held rows, for tests. */
    EmittedRows<Long> emittedRows() {
        return emitted;
    }

    @Override
    public void close() {
        current = null;
        pending.clear();
        emitted.close();
    }
}
