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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.query.PartitionFilter;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * {@code deletes: detect}: a full scan every pass, compared against every row this reader has
 * emitted, so a record that is gone is retracted and a record that changed is retracted and
 * re-inserted.
 *
 * <p><strong>Why a full scan, not the last-update-time filter.</strong> {@link LutScanReader} asks
 * the server only for records written since the last scan, and a deleted record is never among them
 * -- it is not written, it is absent. Absence can only be seen by looking at everything: each pass
 * reads the whole partition range (pushed filters and projection still apply server-side), marks
 * each held row it finds, and retracts the rows nobody marked.
 *
 * <p><strong>What a pass emits.</strong> For each record, keyed by its 20-byte digest:
 *
 * <ul>
 *   <li>not held: the row at {@code +1};
 *   <li>held and identical, byte for byte as decoded: nothing;
 *   <li>held and different: the held row at {@code -1}, then the new row at {@code +1};
 * </ul>
 *
 * and for each held row the pass did not see, that row at {@code -1}, with the event time it was
 * inserted at, so it leaves the window it entered. A pass therefore emits exactly the difference
 * between the rows the engine holds from this reader and the rows the store holds -- never the
 * whole set again, which is what makes a registered view equal the store rather than a multiple of
 * it.
 *
 * <p><strong>The offset is not a place in a scan.</strong> It is {@code deletes=<reader>/<count>}:
 * how many rows this reader had emitted, backed by {@link EmittedRows}' files. A reader resumed from
 * it holds exactly the rows the restored view was built from and starts a new pass; see {@link
 * EmittedRows} for why that is exact, and the plugin's capabilities for what it lets this source
 * promise.
 *
 * <p>A scan that fails emits nothing. A partial scan must never be read as deletions, which is also
 * why the sweep for missing rows runs only after the scan call has returned normally.
 */
final class DetectingScanReader implements PartitionReader {

    /** What every offset this reader writes begins with. */
    static final String PREFIX = "deletes=";

    /** A record's digest as three primitives: 32 bytes of object rather than 56 with an array. */
    record Digest(long high, long middle, int low) {

        static Digest of(byte[] digest) {
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new Digest(bytes.getLong(), bytes.getLong(), bytes.getInt());
        }

        static final EmittedRows.KeyCodec<Digest> CODEC = new EmittedRows.KeyCodec<>() {
            @Override
            public void write(DataOutput out, Digest key) throws IOException {
                out.writeLong(key.high);
                out.writeLong(key.middle);
                out.writeInt(key.low);
            }

            @Override
            public Digest read(DataInput in) throws IOException {
                return new Digest(in.readLong(), in.readLong(), in.readInt());
            }
        };
    }

    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Change(long weight, Digest key, byte[] row, long eventTimeNanos) {}

    private final IAerospikeClient client;
    private final String namespace;
    private final String set;
    private final StreamSchema schema;
    private final int firstPartition;
    private final int partitionCount;
    private final int recordsPerSecond;
    private final int socketTimeoutMillis;
    private final int totalTimeoutMillis;
    private final long scanIntervalNanos;
    private final ReadRequest request;
    private final String[] binNames;
    private final boolean[] read;
    private final int eventTimeOrdinal;
    private final EmittedRows<Digest> emitted;
    private final RowRecorder recorder;
    private final ArrayDeque<Change> pending = new ArrayDeque<>();

    private long lastScanEndedNanos = Long.MIN_VALUE;
    private long scanStartedNanos;
    private int pass;
    private boolean passOpen;
    private long recordsRead;
    private long scans;
    private boolean paused;

    DetectingScanReader(
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
            ReadRequest request,
            Path stateDir,
            long maxKeys) {
        this.client = client;
        this.namespace = namespace;
        this.set = set;
        this.schema = schema;
        this.firstPartition = firstPartition;
        this.partitionCount = partitionCount;
        this.recordsPerSecond = recordsPerSecond;
        this.socketTimeoutMillis = socketTimeoutMillis;
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.scanIntervalNanos =
                java.time.Duration.ofMillis(Math.max(0, scanIntervalMillis)).toNanos();
        this.request = request == null ? ReadRequest.NOTHING : request;
        this.eventTimeOrdinal = schema.eventTimeOrdinal().orElse(-1);
        this.binNames = LutScanReader.projectedBins(schema, this.request, eventTimeOrdinal);
        this.read = new boolean[schema.fieldCount()];
        List<String> names = binNames == null ? null : List.of(binNames);
        for (int ordinal = 0; ordinal < read.length; ordinal++) {
            read[ordinal] =
                    names == null || names.contains(schema.field(ordinal).name());
        }
        this.recorder = new RowRecorder(schema);
        String where = namespace + "." + set + " partitions [" + firstPartition + ", "
                + (firstPartition + partitionCount) + ")";
        this.emitted = EmittedRows.open(
                new HashMap<>(),
                Digest.CODEC,
                stateDir.resolve("p" + firstPartition + "+" + partitionCount),
                resumeToken(resumeFrom),
                maxKeys,
                fingerprint(schema),
                new EmittedRows.Codes(AerospikeErrors.DELETE_STATE_FULL, AerospikeErrors.DELETE_STATE_FAILED),
                "aerospike source over " + where);
    }

    /** The stream's shape, which the state files must match. */
    static String fingerprint(StreamSchema schema) {
        StringBuilder shape = new StringBuilder("aerospike:");
        schema.fields()
                .forEach(field -> shape.append(field.name())
                        .append(' ')
                        .append(field.type().typeName())
                        .append(','));
        return shape.toString();
    }

    private static String resumeToken(SourceOffset offset) {
        if (offset == null || offset.token() == null || offset.token().isBlank()) {
            return null;
        }
        String token = offset.token();
        if (token.startsWith("lut=")) {
            throw new PravahaException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "offset '" + token + "' was written with deletes: ignore, and this source now has deletes: "
                            + "detect. Nothing records which rows the restored view holds, so no pass could say "
                            + "which of them are gone; drop and re-register the query so it starts afresh.");
        }
        if (!token.startsWith(PREFIX)) {
            throw new PravahaException(
                    AerospikeErrors.MALFORMED_OFFSET,
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
        if (pending.isEmpty()) {
            endPassIfDrained();
            if (!readyToScan()) {
                return 0;
            }
            scan();
            lastScanEndedNanos = System.nanoTime();
        }
        int written = 0;
        while (written < maxRecords && !pending.isEmpty()) {
            Change change = pending.poll();
            RowWriter writer = sink.beginRow();
            RowRecorder.replay(change.row(), writer);
            writer.weight(change.weight())
                    .eventTimestampNanos(change.eventTimeNanos())
                    .sequence(++recordsRead)
                    .commit();
            emitted.emitted(change.weight(), change.key(), change.row(), change.eventTimeNanos());
            written++;
        }
        endPassIfDrained();
        return written;
    }

    private void endPassIfDrained() {
        if (passOpen && pending.isEmpty()) {
            passOpen = false;
            emitted.passEnded();
        }
    }

    private boolean readyToScan() {
        if (scanIntervalNanos <= 0 || lastScanEndedNanos == Long.MIN_VALUE) {
            return true;
        }
        return System.nanoTime() - lastScanEndedNanos >= scanIntervalNanos;
    }

    /** One full scan, compared against the held rows. Emits nothing itself; fills {@link #pending}. */
    @SuppressWarnings(
            "deprecation") // Aerospike deprecates scans for query(); moving is a reader rewrite, proven only against a
    // server
    private void scan() {
        int thisPass = ++pass;
        scanStartedNanos = System.currentTimeMillis() * 1_000_000L;
        List<Change> changed = new ArrayList<>();
        Set<Digest> fresh = new HashSet<>();
        ScanPolicy policy = new ScanPolicy();
        policy.filterExp = LutScanReader.withPushedFilters(new ArrayList<>(), request, schema);
        policy.maxConcurrentNodes = 1;
        policy.socketTimeout = socketTimeoutMillis;
        policy.totalTimeout = totalTimeoutMillis;
        if (recordsPerSecond > 0) {
            policy.recordsPerSecond = recordsPerSecond;
        }
        try {
            client.scanPartitions(
                    policy,
                    PartitionFilter.range(firstPartition, partitionCount),
                    namespace,
                    set,
                    (Key key, Record record) -> compare(key, record, thisPass, changed, fresh),
                    binNames);
        } catch (AerospikeException e) {
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "scan of " + namespace + "." + set + " partitions [" + firstPartition + ", "
                            + (firstPartition + partitionCount) + ") failed, and nothing from it was emitted: "
                            + e.getMessage(),
                    e);
        }
        List<Change> gone = new ArrayList<>();
        emitted.forEach((digest, entry) -> {
            if (entry.seenPass != thisPass) {
                gone.add(new Change(-1, digest, entry.row, entry.eventTimeNanos));
            }
        });
        emitted.refuseBeyondCeiling(emitted.size() + fresh.size() - gone.size());
        pending.addAll(changed);
        pending.addAll(gone);
        passOpen = true;
        scans++;
    }

    /** One record against the held rows. Synchronized: the client may call back from its own threads. */
    private synchronized void compare(Key key, Record record, int thisPass, List<Change> changed, Set<Digest> fresh) {
        AerospikeSchemas.copyInto(record, schema, recorder.reset(), read);
        byte[] row = recorder.toBytes();
        Digest digest = Digest.of(key.digest);
        EmittedRows.Entry held = emitted.get(digest);
        if (held == null) {
            if (fresh.add(digest)) {
                changed.add(new Change(1, digest, row, eventTimeOf(record)));
            }
            return;
        }
        if (held.seenPass == thisPass) {
            return; // a record the scan returned twice
        }
        held.seenPass = thisPass;
        if (!Arrays.equals(held.row, row)) {
            changed.add(new Change(-1, digest, held.row, held.eventTimeNanos));
            changed.add(new Change(1, digest, row, eventTimeOf(record)));
        }
    }

    private long eventTimeOf(Record record) {
        if (eventTimeOrdinal < 0) {
            return scanStartedNanos;
        }
        Object value = record.bins == null
                ? null
                : record.bins.get(schema.field(eventTimeOrdinal).name());
        return value instanceof Number number ? number.longValue() : scanStartedNanos;
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

    long scanCount() {
        return scans;
    }

    /** The held rows, for tests. */
    EmittedRows<Digest> emittedRows() {
        return emitted;
    }

    @Override
    public void close() {
        pending.clear();
        emitted.close();
    }
}
