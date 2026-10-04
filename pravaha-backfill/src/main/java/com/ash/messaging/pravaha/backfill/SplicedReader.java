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
package com.ash.messaging.pravaha.backfill;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.state.RowStore;

/**
 * Joins a snapshot of history to a live change feed, without losing or duplicating anything.
 *
 * <p>Design section 16.1. A query that needs three years of history and the present has to read
 * both, and the seam between them is where streaming rollouts quietly go wrong: read the snapshot
 * first and every change made while it was running is lost; start the feed first and those changes
 * arrive twice. Neither failure announces itself -- the numbers are merely a little off.
 *
 * <p>The order here is the one the design prescribes, and each step is doing something specific.
 *
 * <ol>
 *   <li><strong>The change feed starts first</strong>, and its rows are buffered rather than
 *       emitted. Nothing that happens from this moment on can be missed, whatever the snapshot
 *       takes.
 *   <li><strong>The snapshot is read</strong>, and a row is emitted only if the buffer does not
 *       already hold a change to that key at or after the snapshot's version. Where it does, the
 *       buffered change is the newer truth and the snapshot row would be an older value arriving
 *       after it.
 *   <li><strong>The buffer is replayed</strong> in arrival order. Every change made during the
 *       snapshot is now applied exactly once, in the order the store applied it.
 *   <li><strong>The feed becomes the only input.</strong> Nothing is buffered and nothing is
 *       compared; the seam is behind us and the cost with it.
 * </ol>
 *
 * <p>What makes this bounded is that the deduplication is keyed on <em>changed</em> keys, not on
 * every key in the snapshot. A three-year backfill of a hundred million rows against a few thousand
 * changes during the scan holds a few thousand entries. Inverting it -- remembering every snapshot
 * row so changes can be checked against it -- would need the whole table in memory, which is the
 * obvious implementation and the reason people say this is hard.
 *
 * <p>Buffered rows are held off-heap in a {@link RowStore}, bounded, and a backfill that overruns
 * the bound fails saying so. An unbounded buffer here is a heap exhaustion whose cause is a scan
 * that took longer than somebody expected.
 */
public final class SplicedReader implements PartitionReader {

    private final PartitionReader snapshot;
    private final PartitionReader changes;
    private final SpliceSpec spec;
    private final StreamSchema schema;
    private final RowLayout layout;
    private final RowStore buffer;
    private final ArrayDeque<Long> buffered = new ArrayDeque<>();
    private final BinaryRowView bufferedRow;
    private final BinaryRowWriter bufferWriter;
    private final int maxBufferedRows;

    /**
     * The rate limit on reading history, or null for no limit.
     *
     * <p>It governs the snapshot and nothing else. Throttling the live feed would make the query
     * fall behind the present to protect the store from the past, which is backwards: the change
     * feed is the load the store is already carrying, and the backfill is the load being added.
     */
    private final BackfillThrottle throttle;

    /**
     * The newest version buffered for each changed key.
     *
     * <p>Bounded by the number of keys that changed during the scan, which is what makes this
     * survivable on a table nobody could hold in memory.
     */
    private final Map<Key, Long> changedDuringSnapshot = new HashMap<>();

    /**
     * The window a throttle budget is expressed over.
     *
     * <p>A tenth of a second: long enough that the arithmetic is not dominated by rounding, short
     * enough that a backoff takes effect within the two seconds the design asks for rather than
     * after the current batch, however large that was.
     */
    private long pollWindowNanos = 100_000_000L;

    private BackfillPhase phase = BackfillPhase.SNAPSHOT;
    private long snapshotRowsRead;
    private long snapshotRowsEmitted;
    private long snapshotRowsSuperseded;
    private long bufferedRows;
    private long replayedRows;
    private boolean paused;

    /**
     * @param snapshot reads history and then returns zero for ever
     * @param changes the live feed, already positioned at or before the snapshot's start
     * @param maxBufferedRows how many changes may accumulate during the snapshot. Reached means the
     *     scan is slower than the change rate can be held for; the backfill fails rather than the
     *     process
     */
    public SplicedReader(
            PartitionReader snapshot,
            PartitionReader changes,
            StreamSchema schema,
            SpliceSpec spec,
            int maxBufferedRows) {
        this(snapshot, changes, schema, spec, maxBufferedRows, null);
    }

    /**
     * @param throttle limits how fast history is read, and backs off when the store suffers. Null
     *     means read as fast as the source allows, which is the right default for a snapshot of
     *     something nothing else is using and the wrong one for a production cluster
     */
    public SplicedReader(
            PartitionReader snapshot,
            PartitionReader changes,
            StreamSchema schema,
            SpliceSpec spec,
            int maxBufferedRows,
            BackfillThrottle throttle) {
        this.throttle = throttle;
        this.snapshot = snapshot;
        this.changes = changes;
        this.schema = schema;
        this.spec = spec;
        this.maxBufferedRows = maxBufferedRows;
        spec.validate(schema);
        this.layout = RowLayout.of(schema);
        this.buffer = new RowStore(MemoryAccess.best(), 1 << 20, 256);
        this.bufferedRow = new BinaryRowView(layout);
        this.bufferWriter = new BinaryRowWriter(layout);
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        return switch (phase) {
            case SNAPSHOT -> pollSnapshot(sink, maxRecords);
            case CATCH_UP -> replay(sink, maxRecords);
            case LIVE -> changes.poll(sink, maxRecords);
        };
    }

    /**
     * Reads history, taking in whatever changed while it was reading.
     *
     * <p>The change feed is drained before the snapshot rows are read, not after. A change that
     * arrives between reading a snapshot row and deciding whether to emit it would otherwise be
     * compared against nothing and the older row would win.
     */
    private int pollSnapshot(RecordSink sink, int maxRecords) {
        // Changes are absorbed at full speed whatever the throttle says. They are the store's own
        // traffic arriving; refusing to read them does not reduce the store's load, it only makes
        // the buffer the thing that overflows.
        absorbChanges(maxRecords);

        int budget = throttle == null ? maxRecords : Math.min(maxRecords, throttle.budgetFor(pollWindowNanos));
        int emitted = snapshot.poll(new FilteringSink(sink), budget);
        if (emitted == 0 && snapshot.poll(new FilteringSink(sink), budget) == 0) {
            // Twice, because a source is entitled to return zero once and more later; asking again
            // costs one empty poll and avoids ending a backfill early on a source that paused for
            // a moment. A source that is genuinely done says so both times.
            phase = BackfillPhase.CATCH_UP;
        }
        return emitted;
    }

    /** Pulls whatever the feed has, into the buffer, without emitting any of it. */
    private void absorbChanges(int maxRecords) {
        changes.poll(new BufferingSink(), maxRecords);
    }

    private int replay(RecordSink sink, int maxRecords) {
        int emitted = 0;
        while (emitted < maxRecords && !buffered.isEmpty()) {
            long handle = buffered.poll();
            RowView row = bufferedRow.wrap(buffer.regionOf(handle), buffer.offsetOf(handle));
            copyInto(sink.beginRow(), row);
            buffer.release(handle);
            emitted++;
            replayedRows++;
        }
        if (buffered.isEmpty()) {
            // The seam is behind us: nothing is buffered, nothing is compared, and the map of
            // changed keys is released. Holding it would be a leak whose size is "however busy the
            // table was during a scan that finished hours ago".
            changedDuringSnapshot.clear();
            phase = BackfillPhase.LIVE;
        }
        return emitted;
    }

    /** Which phase and where in it, so a crash mid-backfill resumes in the same place. */
    @Override
    public SourceOffset position() {
        return new SourceOffset(phase.name() + ":" + snapshot.position().token() + ":"
                + changes.position().token());
    }

    @Override
    public void pause() {
        paused = true;
        snapshot.pause();
        changes.pause();
    }

    @Override
    public void resume() {
        paused = false;
        snapshot.resume();
        changes.resume();
    }

    @Override
    public void close() {
        snapshot.close();
        changes.close();
        buffer.close();
    }

    public BackfillPhase phase() {
        return phase;
    }

    public long snapshotRowsRead() {
        return snapshotRowsRead;
    }

    /** History rows that reached the query. */
    public long snapshotRowsEmitted() {
        return snapshotRowsEmitted;
    }

    /** History rows dropped because a change made during the scan already covers them. */
    public long snapshotRowsSuperseded() {
        return snapshotRowsSuperseded;
    }

    public long bufferedRows() {
        return bufferedRows;
    }

    public long replayedRows() {
        return replayedRows;
    }

    /** The rate history is being read at, or -1 when nothing is limiting it. */
    public long rowsPerSecond() {
        return throttle == null ? -1 : throttle.rowsPerSecond();
    }

    /** How full the change buffer is, from 0 to 1. What an operator watches during a long scan. */
    public double bufferFill() {
        return (double) buffered.size() / maxBufferedRows;
    }

    private Key keyOf(RowView row) {
        Object[] values = new Object[spec.keyOrdinals().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = valueOf(row, spec.keyOrdinals().get(i));
        }
        return new Key(values);
    }

    private long versionOf(RowView row) {
        int ordinal = spec.versionOrdinal();
        if (row.isNull(ordinal)) {
            throw new PravahaException(
                    BackfillErrors.MISSING_VERSION,
                    "row has no value in version column '"
                            + schema.field(ordinal).name()
                            + "'. The splice decides which of two rows is newer by comparing versions; a null "
                            + "one cannot be compared, and guessing would drop live changes.");
        }
        return switch (schema.field(ordinal).type().typeName()) {
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            default -> row.getLong(ordinal);
        };
    }

    private Object valueOf(RowView row, int ordinal) {
        if (row.isNull(ordinal)) {
            return null;
        }
        return switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case STRING -> row.getString(ordinal);
            default ->
                throw new PravahaException(
                        BackfillErrors.UNSUPPORTED_KEY,
                        "cannot use a " + schema.field(ordinal).type().typeName() + " column as a splice key");
        };
    }

    private void copyInto(RowWriter writer, RowView row) {
        for (int ordinal = 0; ordinal < schema.fields().size(); ordinal++) {
            if (row.isNull(ordinal)) {
                writer.setNull(ordinal);
                continue;
            }
            switch (schema.field(ordinal).type().typeName()) {
                case BOOLEAN -> writer.setBoolean(ordinal, row.getBoolean(ordinal));
                case INT8 -> writer.setByte(ordinal, row.getByte(ordinal));
                case INT16 -> writer.setShort(ordinal, row.getShort(ordinal));
                case INT32, DATE -> writer.setInt(ordinal, row.getInt(ordinal));
                case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, row.getLong(ordinal));
                case FLOAT32 -> writer.setFloat(ordinal, row.getFloat(ordinal));
                case FLOAT64 -> writer.setDouble(ordinal, row.getDouble(ordinal));
                case DECIMAL -> writer.setDecimal(ordinal, row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal));
                default -> writer.setString(ordinal, row.getString(ordinal));
            }
        }
        writer.weight(row.weight())
                .eventTimestampNanos(row.eventTimestampNanos())
                .sequence(row.sequence())
                .commit();
    }

    /** A key by value, so it can be a map key. */
    @SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the array's contents
    private record Key(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    /**
     * Takes the change feed's rows into the buffer instead of the query.
     *
     * <p>Rows are copied off-heap because they outlive the poll that produced them -- by the whole
     * remaining duration of the snapshot, which is the point.
     */
    private final class BufferingSink implements RecordSink {
        @Override
        public RowWriter beginRow() {
            return new StagedRow(row -> {
                if (buffered.size() >= maxBufferedRows) {
                    throw new PravahaException(
                            BackfillErrors.BUFFER_FULL,
                            "the backfill's change buffer is full at " + maxBufferedRows + " rows. The snapshot "
                                    + "is taking longer than the change rate can be held for: raise the buffer, "
                                    + "raise backfill parallelism, or narrow the backfill window.");
                }
                Key key = keyOf(row);
                long version = versionOf(row);
                changedDuringSnapshot.merge(key, version, Math::max);

                int length = ((BinaryRowView) row).length();
                long handle = buffer.allocate(length);
                buffer.regionOf(handle)
                        .copyFrom(
                                buffer.offsetOf(handle),
                                ((BinaryRowView) row).region(),
                                ((BinaryRowView) row).offset(),
                                length);
                buffered.add(handle);
                bufferedRows++;
            });
        }
    }

    /**
     * Passes a snapshot row on unless a change made during the scan already covers it.
     *
     * <p>The comparison is against the newest buffered version for that key. Equal counts as
     * covered: the change feed will deliver that same version, and emitting both would apply it
     * twice.
     */
    private final class FilteringSink implements RecordSink {
        private final RecordSink downstream;

        FilteringSink(RecordSink downstream) {
            this.downstream = downstream;
        }

        @Override
        public RowWriter beginRow() {
            return new StagedRow(row -> {
                snapshotRowsRead++;
                Long changed = changedDuringSnapshot.get(keyOf(row));
                if (changed != null && changed >= versionOf(row)) {
                    snapshotRowsSuperseded++;
                    return;
                }
                copyInto(downstream.beginRow(), row);
                snapshotRowsEmitted++;
            });
        }
    }

    /**
     * A writer that builds a row in a scratch buffer and hands it to a decision on commit.
     *
     * <p>Both sinks need the finished row before deciding what to do with it -- which key, which
     * version -- and a plugin writes a row a field at a time. So it is staged, then judged.
     *
     * <p>One at a time: the underlying writer is shared, so a source that began a second row before
     * committing the first would overwrite it. That is the same assumption the ingest pump makes and
     * the same one every source already satisfies, because a row is written and committed inside a
     * single call.
     */
    private final class StagedRow implements RowWriter {
        private final java.util.function.Consumer<RowView> onCommit;
        private final long handle;

        StagedRow(java.util.function.Consumer<RowView> onCommit) {
            this.onCommit = onCommit;
            this.handle = buffer.allocate(layout.rowSize(4096));
            bufferWriter.begin(buffer.regionOf(handle), buffer.offsetOf(handle));
        }

        @Override
        public StreamSchema schema() {
            return schema;
        }

        @Override
        public RowWriter setNull(int ordinal) {
            bufferWriter.setNull(ordinal);
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            bufferWriter.setBoolean(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            bufferWriter.setByte(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            bufferWriter.setShort(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            bufferWriter.setInt(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            bufferWriter.setLong(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            bufferWriter.setFloat(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            bufferWriter.setDouble(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            bufferWriter.setDecimal(ordinal, high, low);
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            bufferWriter.setString(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            bufferWriter.setBytes(ordinal, value);
            return this;
        }

        @Override
        public RowWriter weight(long weight) {
            bufferWriter.weight(weight);
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            bufferWriter.eventTimestampNanos(nanos);
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            bufferWriter.sequence(sequence);
            return this;
        }

        @Override
        public int commit() {
            bufferWriter.commit();
            try {
                onCommit.accept(bufferedRow.wrap(buffer.regionOf(handle), buffer.offsetOf(handle)));
            } finally {
                buffer.release(handle);
            }
            return 0;
        }

        @Override
        public void abort() {
            bufferWriter.abort();
            buffer.release(handle);
        }
    }
}
