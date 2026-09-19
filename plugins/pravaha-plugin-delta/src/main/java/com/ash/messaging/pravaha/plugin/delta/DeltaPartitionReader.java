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
package com.ash.messaging.pravaha.plugin.delta;

import java.util.List;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Reads a Delta table as a stream of weighted rows.
 *
 * <p><strong>The idea this plugin turns on.</strong> Delta rewrites whole files: an {@code UPDATE}
 * or a {@code MERGE} removes the files it touched and adds replacements. So the difference between
 * version <em>n</em> and version <em>n+1</em> is exactly a set of removed files and a set of added
 * files -- and emitting the removed files' rows with weight {@code -1} and the added files' rows
 * with {@code +1} <em>is</em> the Z-set delta for that commit (design section 9.2). No update path,
 * no before-image plumbing: Delta's storage semantics and Pravaha's algebra already agree, and this
 * reader only has to notice.
 *
 * <p>It over-emits, and that is worth stating precisely rather than discovering. Rewriting a
 * 100 000-row file to change one row yields 100 000 retractions and 100 000 insertions, of which
 * 99 999 pairs are identical and annihilate under Z-set consolidation. The <em>answer</em> is right;
 * the <em>volume</em> is proportional to file size rather than to change size. Append-heavy tables --
 * most Delta tables -- have no removals and never pay it. For update-heavy tables this is the wrong
 * reader and the change data feed is the right one; Kernel 4.0 exposes no public API for CDF, which
 * is said here rather than found out later.
 *
 * <p>Two situations are refused rather than approximated: deletion vectors, which hide row-level
 * deletes from file diffing ({@link DeltaScanFiles}), and a file the log still references that has
 * been vacuumed away, whose retractions cannot be reconstructed from anywhere.
 */
final class DeltaPartitionReader implements PartitionReader {

    private final Engine engine;
    private final Table table;
    private final String streamName;
    private final long startVersion;

    private DeltaOffset offset;
    private boolean paused;

    /** The files of the version-and-phase being emitted, in the order the offset counts them. */
    private List<DeltaScanFiles.ScanFile> currentFiles = List.of();

    private boolean prepared;
    private Row scanState;
    private StructType logicalSchema;
    private List<DataType> columnTypes;

    /** The column whose value is each row's event time, or blank to stamp zero (HLP-6). */
    private String eventTimeColumn = "";

    /** {@link #eventTimeColumn}'s ordinal in {@link #logicalSchema}, or -1. */
    private int eventTimeOrdinal = -1;

    private CloseableIterator<FilteredColumnarBatch> openFile;
    /** The file {@link #openFile} reads, kept only so a failure on it can be reported by name. */
    private DeltaScanFiles.ScanFile openFileHandle;

    private ColumnarBatch batch;
    private int batchCursor;
    private long rowInFile;

    DeltaPartitionReader(Engine engine, Table table, String streamName, long startVersion, SourceOffset resumeFrom) {
        this.engine = engine;
        this.table = table;
        this.streamName = streamName;
        this.startVersion = startVersion;
        this.offset = DeltaOffset.parse(resumeFrom);
    }

    /** Stamps each row with this column's value rather than zero; blank keeps zero. */
    DeltaPartitionReader stampingEventTimeFrom(String column) {
        this.eventTimeColumn = column == null ? "" : column;
        return this;
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused) {
            return 0;
        }
        int emitted = 0;
        while (emitted < maxRecords) {
            if (!ensureRows()) {
                return emitted; // the table has not moved on; polling again later is the whole loop
            }
            emitted += emitRows(sink, maxRecords - emitted);
        }
        return emitted;
    }

    /** Ensures a batch with unread rows is open, advancing files and versions as needed. */
    private boolean ensureRows() {
        while (true) {
            if (batch != null && batchCursor < batch.getSize()) {
                return true;
            }
            if (openFile != null && hasNextRow()) {
                batch = nextRow().getData();
                batchCursor = 0;
                continue;
            }
            closeOpenFile();
            if (prepared && offset.fileIndex() < currentFiles.size()) {
                openCurrentFile();
                continue;
            }
            if (!advance()) {
                return false;
            }
        }
    }

    /**
     * Moves to the next thing to emit.
     *
     * <p>Order within a commit is additions then retractions. Either order is correct under Z-set
     * semantics -- the sum is the same -- but a fixed one makes replay byte-comparable, and a
     * differential test that has to sort its input before comparing is a weaker test.
     *
     * @return {@code false} when there is nothing new, which is the ordinary idle case
     */
    private boolean advance() {
        if (!prepared) {
            loadCurrentPhase();
            prepared = true;
            if (offset.fileIndex() < currentFiles.size()) {
                return true;
            }
        }
        return switch (offset.phase()) {
            case SNAPSHOT, REMOVES -> nextVersion();
            case ADDS -> {
                // The additions of this commit are done; its retractions are the same commit.
                offset = offset.at(offset.version(), DeltaOffset.Phase.REMOVES);
                currentFiles = removedFilesOf(offset.version());
                yield !currentFiles.isEmpty() || nextVersion();
            }
        };
    }

    /** Rebuilds the file list for whatever version and phase the offset names. */
    private void loadCurrentPhase() {
        if (offset.isBeginning()) {
            Snapshot snapshot = startVersion < 0
                    ? table.getLatestSnapshot(engine)
                    : table.getSnapshotAsOfVersion(engine, startVersion);
            useSnapshot(snapshot);
            offset = new DeltaOffset(snapshot.getVersion(), DeltaOffset.Phase.SNAPSHOT, 0, 0L);
            return;
        }
        switch (offset.phase()) {
            case SNAPSHOT -> useSnapshot(table.getSnapshotAsOfVersion(engine, offset.version()));
            case ADDS -> {
                useSnapshot(table.getSnapshotAsOfVersion(engine, offset.version()));
                currentFiles = addedFilesOf(offset.version());
            }
            case REMOVES -> {
                useSnapshot(table.getSnapshotAsOfVersion(engine, offset.version()));
                currentFiles = removedFilesOf(offset.version());
            }
        }
    }

    /** Steps to the next commit, if the table has one. */
    private boolean nextVersion() {
        long latest = table.getLatestSnapshot(engine).getVersion();
        if (latest <= offset.version()) {
            return false;
        }
        long next = offset.version() + 1;
        useSnapshot(table.getSnapshotAsOfVersion(engine, next));
        offset = offset.at(next, DeltaOffset.Phase.ADDS);
        currentFiles = addedFilesOf(next);
        if (!currentFiles.isEmpty()) {
            return true;
        }
        offset = offset.at(next, DeltaOffset.Phase.REMOVES);
        currentFiles = removedFilesOf(next);
        // A commit that changed no data files at all -- a property change, say -- is skipped rather
        // than treated as end of stream, or the reader would stall on it forever.
        return !currentFiles.isEmpty() || nextVersion();
    }

    private List<DeltaScanFiles.ScanFile> addedFilesOf(long version) {
        List<String> before = pathsAt(version - 1);
        return filesAt(version).stream().filter(f -> !before.contains(f.path())).toList();
    }

    private List<DeltaScanFiles.ScanFile> removedFilesOf(long version) {
        List<String> after = pathsAt(version);
        // Read through the previous version's listing: a removed file is not in this version's scan,
        // so its rows -- and the log row describing it -- only exist there.
        return filesAt(version - 1).stream()
                .filter(f -> !after.contains(f.path()))
                .toList();
    }

    private List<DeltaScanFiles.ScanFile> filesAt(long version) {
        if (version < 0) {
            return List.of();
        }
        Snapshot snapshot = table.getSnapshotAsOfVersion(engine, version);
        return DeltaScanFiles.listFiles(engine, snapshot.getScanBuilder().build());
    }

    private List<String> pathsAt(long version) {
        return filesAt(version).stream().map(DeltaScanFiles.ScanFile::path).toList();
    }

    /** Records the scan state and schema of a snapshot, and its files as the current list. */
    private void useSnapshot(Snapshot snapshot) {
        Scan scan = snapshot.getScanBuilder().build();
        scanState = scan.getScanState(engine);
        currentFiles = DeltaScanFiles.listFiles(engine, scan);
        if (logicalSchema == null) {
            logicalSchema = snapshot.getSchema();
            columnTypes = DeltaTypes.columnTypes(logicalSchema);
            eventTimeOrdinal = eventTimeColumn.isEmpty() ? -1 : logicalSchema.indexOf(eventTimeColumn);
        }
    }

    /** Opens the file the offset points at, skipping the rows it says are already emitted. */
    private void openCurrentFile() {
        DeltaScanFiles.ScanFile file = currentFiles.get(offset.fileIndex());
        openFileHandle = file;
        openFile = DeltaScanFiles.readFile(engine, scanState, file);
        rowInFile = 0;
        batch = null;
        batchCursor = 0;
        long skip = offset.rowIndex();
        while (skip > 0 && hasNextRow()) {
            ColumnarBatch next = nextRow().getData();
            if (skip >= next.getSize()) {
                skip -= next.getSize();
                rowInFile += next.getSize();
            } else {
                batch = next;
                batchCursor = (int) skip;
                rowInFile += skip;
                skip = 0;
            }
        }
    }

    /**
     * {@code openFile.hasNext()}, with Kernel's own failure translated.
     *
     * <p>{@link DeltaScanFiles#readFile} returns its iterator lazily -- Kernel's default Parquet
     * handler does not open a file until the first {@code hasNext()}/{@code next()} call reaches it
     * -- so a file the log still references but that has been removed from disk is discovered here,
     * not at {@code readFile}'s own {@code catch (IOException)}, which never runs for this case.
     * Kernel reports it as an unchecked {@link io.delta.kernel.exceptions.KernelEngineException}
     * wrapping the {@link java.io.FileNotFoundException}, which would otherwise pass straight
     * through this reader with no PRV code at all.
     */
    private boolean hasNextRow() {
        try {
            return openFile.hasNext();
        } catch (io.delta.kernel.exceptions.KernelEngineException e) {
            throw vacuumOrReadFailure(e);
        }
    }

    /** {@code openFile.next()}, with the same translation as {@link #hasNextRow()}. */
    private FilteredColumnarBatch nextRow() {
        try {
            return openFile.next();
        } catch (io.delta.kernel.exceptions.KernelEngineException e) {
            throw vacuumOrReadFailure(e);
        }
    }

    private PravahaException vacuumOrReadFailure(io.delta.kernel.exceptions.KernelEngineException e) {
        String path = openFileHandle == null ? "<unknown>" : openFileHandle.path();
        if (e.getCause() instanceof java.io.FileNotFoundException) {
            return new PravahaException(
                    DeltaErrors.FILE_VACUUMED,
                    "data file " + path + " is still referenced by the Delta log but is no longer on disk "
                            + "-- typically removed by VACUUM. Its retractions cannot be reconstructed, and "
                            + "the rows it removed would otherwise keep being served as though still live.",
                    e);
        }
        return new PravahaException(DeltaErrors.READ_FAILED, "cannot read data file " + path + ": " + e, e);
    }

    /** Copies rows out of the open batch, up to {@code limit}. */
    private int emitRows(RecordSink sink, int limit) {
        long weight = offset.phase() == DeltaOffset.Phase.REMOVES ? -1L : 1L;
        int emitted = 0;
        while (emitted < limit && batchCursor < batch.getSize()) {
            RowWriter writer = sink.beginRow();
            for (int column = 0; column < columnTypes.size(); column++) {
                ColumnVector vector = batch.getColumnVector(column);
                DeltaTypes.copyValue(vector, batchCursor, writer, column, columnTypes.get(column));
            }
            // No event time is invented: deriving one from the commit timestamp would silently move
            // window boundaries to whenever the table happened to be written. The stream's declared
            // column is used when there is one; stamping zero regardless kept the watermark in
            // 1970, and no event-time window over a table ever closed (HLP-6).
            long eventTime = 0L;
            if (eventTimeOrdinal >= 0) {
                ColumnVector times = batch.getColumnVector(eventTimeOrdinal);
                if (!times.isNullAt(batchCursor)) {
                    eventTime = Math.multiplyExact(times.getLong(batchCursor), 1_000L);
                }
            }
            writer.weight(weight)
                    .eventTimestampNanos(eventTime)
                    .sequence(rowInFile)
                    .commit();
            batchCursor++;
            rowInFile++;
            emitted++;
            offset = offset.withRow(rowInFile);
        }
        if (batchCursor >= batch.getSize()) {
            batch = null;
            if (openFile == null || !openFile.hasNext()) {
                closeOpenFile();
                offset = offset.nextFile();
            }
        }
        return emitted;
    }

    private void closeOpenFile() {
        if (openFile != null) {
            try {
                openFile.close();
            } catch (Exception e) {
                throw new PravahaException(
                        DeltaErrors.READ_FAILED, "failed to close a data file of stream " + streamName + ": " + e, e);
            } finally {
                openFile = null;
                openFileHandle = null;
                batch = null;
                batchCursor = 0;
            }
        }
    }

    @Override
    public SourceOffset position() {
        return offset.toSourceOffset();
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
        closeOpenFile();
    }
}
