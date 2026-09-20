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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.delta.kernel.DataWriteContext;
import io.delta.kernel.Operation;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.TransactionBuilder;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.ConcurrentTransactionException;
import io.delta.kernel.exceptions.ConcurrentWriteException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.SingleAction;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.delta.DeltaSinkRows.Change;

/**
 * One checkpoint's changes, turned into one Delta commit.
 *
 * <p><strong>Changelog mode is an append</strong>: the batch becomes Parquet files and the commit
 * adds them. <strong>Upsert mode is a copy-on-write merge</strong>, which is what a Delta table
 * without deletion vectors makes of a retraction:
 *
 * <ol>
 *   <li>The changes are collapsed by key -- of everything that happened to one key between
 *       checkpoints, only the last decides what the table holds.
 *   <li>The table's data files are read <em>through their key columns alone</em> to find the ones
 *       holding a key that is about to change. Parquet is columnar, so this reads the key column and
 *       not the rows.
 *   <li>Each such file is read in full and rewritten without those rows, and the commit
 *       <em>removes</em> the old file and adds the new one. Files holding no affected key are not
 *       touched, not read past their key column, and not rewritten.
 *   <li>The rows with a positive weight are written as one more file. A retraction contributes no
 *       row, so it leaves the table with the record gone.
 * </ol>
 *
 * <p><strong>What that costs, stated rather than hidden.</strong> A commit reads the key column of
 * every data file the table has, and rewrites whole files to delete a row from them. The cost of a
 * checkpoint is therefore proportional to the table, not to the number of changes -- which is the
 * price of exact upsert semantics on a format whose files are immutable. A view of modest size
 * maintained at a checkpoint's cadence is what this mode is for; changelog mode is what a high-volume
 * query should write, with the folding left to whoever reads it.
 *
 * <p><strong>Concurrency, exactly.</strong> A commit reads the table and then writes one log entry,
 * and another writer can land in either gap:
 *
 * <ul>
 *   <li><strong>Between the prepare and the start of this commit:</strong> nothing fails. This
 *       commit is computed against the snapshot that writer left, so their rows are in the table it
 *       merges onto -- and where they wrote a key this sink also holds, this sink's value wins,
 *       because that key is the query's answer and the sink's job is to maintain it.
 *   <li><strong>Between the start of this commit and its log entry:</strong> Delta refuses the
 *       commit, and so does this sink. Every transaction is built with {@code withMaxRetries(0)}:
 *       Kernel would otherwise resolve the conflict and try again, and its retry is safe for a blind
 *       append and not for this -- the file being removed may be one the other writer has just
 *       rewritten, and replaying the removal against their version undoes their change. So the
 *       commit fails with {@link DeltaErrors#SINK_COMMIT_CONFLICT}, the delivery detaches the sink
 *       (PRV-8009), and the checkpoint's changes are still staged. Nothing is written twice and
 *       nothing is written half.
 * </ul>
 *
 * <p>Both say the same thing about deployment: a Delta table maintained by a continuous query
 * should have no other writer.
 */
final class DeltaSinkCommit {

    private static final String ENGINE_INFO = "Pravaha-delta-sink/0.1.0";

    private final Engine engine;
    private final Table table;
    private final String instanceName;
    private final String path;
    private final StreamSchema schema;
    private final StructType deltaSchema;
    private final DeltaSinkRows rows;
    private final int[] keyOrdinals;
    private final boolean changelog;

    private long rowsApplied;
    private long filesRewritten;

    /**
     * The conflict window, held open by a test.
     *
     * <p>The window is real and narrow: it is the time between the snapshot this commit reads and
     * the log entry it writes. A test cannot hit it by racing two threads and be trusted, so it
     * arranges the other writer's commit here instead. Nothing in production sets it.
     */
    private Runnable conflictWindow = () -> {};

    DeltaSinkCommit(
            Engine engine,
            String instanceName,
            String path,
            StreamSchema schema,
            StructType deltaSchema,
            DeltaSinkRows rows,
            int[] keyOrdinals,
            boolean changelog) {
        this.engine = engine;
        this.table = Table.forPath(engine, path);
        this.instanceName = instanceName;
        this.path = path;
        this.schema = schema;
        this.deltaSchema = deltaSchema;
        this.rows = rows;
        this.keyOrdinals = keyOrdinals.clone();
        this.changelog = changelog;
    }

    /**
     * Creates the table when it is not there, and checks it against the binding when it is.
     *
     * <p>Unlike {@code jdbc-sink}, this sink does create its target. A relational table carries
     * decisions this engine has no business making -- indexes, constraints, tablespaces, grants -- and
     * a Delta table carries none: it is a directory, a schema, and a log. Refusing to create one would
     * mean requiring Spark or another engine to run a {@code CREATE TABLE} that says nothing the
     * binding does not already say. {@code create: false} demands it exist anyway.
     */
    void openTable(boolean create) {
        Optional<SnapshotImpl> existing = snapshot();
        if (existing.isEmpty()) {
            if (!create) {
                throw new PravahaException(
                        DeltaErrors.SINK_TABLE_MISMATCH,
                        "plugin '" + instanceName + "' found no Delta table at " + path + " and create is false. "
                                + "A Delta table is a directory holding a _delta_log; create it, or set create: true "
                                + "and this sink will create it with the binding's schema.");
            }
            try {
                table.createTransactionBuilder(engine, ENGINE_INFO, Operation.CREATE_TABLE)
                        .withSchema(engine, deltaSchema)
                        .withMaxRetries(0)
                        .build(engine)
                        .commit(engine, CloseableIterable.emptyIterable());
            } catch (ConcurrentWriteException e) {
                throw conflict("create the table at " + path, e);
            } catch (RuntimeException e) {
                throw new PravahaException(
                        DeltaErrors.SINK_TABLE_MISMATCH,
                        "plugin '" + instanceName + "' cannot create a Delta table at " + path + ": " + e.getMessage(),
                        e);
            }
            return;
        }
        SnapshotImpl snapshot = existing.get();
        if (!snapshot.getPartitionColumnNames().isEmpty()) {
            throw new PravahaException(
                    DeltaErrors.SINK_TABLE_MISMATCH,
                    "the Delta table at " + path + " is partitioned by " + snapshot.getPartitionColumnNames()
                            + ", and this sink writes unpartitioned tables only. It would have to decide which "
                            + "partition each row belongs to and rewrite whole partitions to delete from them, "
                            + "and neither is something to do silently. Point the sink at an unpartitioned table.");
        }
        DeltaSinkSchema.refuseMismatch(instanceName, path, deltaSchema, snapshot.getSchema());
    }

    /** The table's latest version, for a message or a test. */
    long version() {
        return snapshot().map(Snapshot::getVersion).orElse(-1L);
    }

    /**
     * The transaction label this sink last committed under {@code appId}, as the table records it.
     *
     * <p>This is what makes {@code commit} idempotent, as the SPI requires: every commit carries a
     * Delta {@code txn} action naming this sink and the label, so a commit sent twice -- by a restore
     * that cannot know the first one arrived -- finds the label already in the table and does nothing.
     * It is the table's own record, not a note this process keeps, so it survives the process.
     */
    Optional<Long> committedLabel(String appId) {
        return snapshot().flatMap(s -> s.getLatestTransactionVersion(engine, appId));
    }

    private Optional<SnapshotImpl> snapshot() {
        try {
            return Optional.of((SnapshotImpl) table.getLatestSnapshot(engine));
        } catch (TableNotFoundException absent) {
            return Optional.empty();
        }
    }

    /**
     * Applies changes as one Delta commit.
     *
     * @param changes every change of the transaction, in the order the engine wrote them
     * @param appId the {@code txn} application id to record the label under, or null for a commit
     *     that carries no transaction identifier (the non-transactional mode, where each write is its
     *     own commit and there is no label to be idempotent about)
     * @param label the transaction label to record
     * @return the version committed, or empty when the label was already committed
     */
    Optional<Long> apply(List<Change> changes, String appId, long label) {
        Transaction txn;
        try {
            TransactionBuilder builder = table.createTransactionBuilder(engine, ENGINE_INFO, Operation.WRITE)
                    .withMaxRetries(0);
            if (appId != null) {
                builder = builder.withTransactionId(engine, appId, label);
            }
            txn = builder.build(engine);
        } catch (ConcurrentTransactionException alreadyCommitted) {
            // The table already holds a txn action for this app at this label or beyond: this
            // transaction was committed before, by this process or by the one that died. Nothing to do.
            return Optional.empty();
        } catch (TableNotFoundException e) {
            throw new PravahaException(
                    DeltaErrors.SINK_TABLE_MISMATCH,
                    "the Delta table at " + path + " is gone; the sink opened it and cannot commit to it now",
                    e);
        }
        Row txnState = txn.getTransactionState(engine);
        DataWriteContext context = Transaction.getWriteContext(engine, txnState, Map.of());
        List<Row> actions = new ArrayList<>();
        if (changelog) {
            appendChangelog(changes, txnState, context, actions);
        } else {
            merge(changes, txn.getReadTableVersion(), txnState, context, actions);
        }
        conflictWindow.run();
        try {
            return Optional.of(txn.commit(engine, CloseableIterable.inMemoryIterable(iterator(actions)))
                    .getVersion());
        } catch (ConcurrentWriteException e) {
            throw conflict("commit transaction " + label + " to " + path, e);
        }
    }

    /** Arranges the other writer's commit inside this one's window. See {@link #conflictWindow}. */
    void openConflictWindow(Runnable other) {
        this.conflictWindow = other;
    }

    private PravahaException conflict(String what, RuntimeException cause) {
        return new PravahaException(
                DeltaErrors.SINK_COMMIT_CONFLICT,
                "plugin '" + instanceName + "' could not " + what + ": another writer committed to the table while "
                        + "this commit was being built. "
                        + "This sink does not retry, and that is deliberate -- its commit removes the data files it "
                        + "read, and replaying those removals against whatever the other writer has just written "
                        + "would undo their change or double-apply this one. The sink is detached (PRV-8009) with "
                        + "this checkpoint's changes still staged. A Delta table maintained by a continuous query "
                        + "must have no other writer; drop the other one, then drop and re-register the query.",
                cause);
    }

    /** Changelog mode: every change becomes a row, and the commit adds the files. */
    private void appendChangelog(List<Change> changes, Row txnState, DataWriteContext context, List<Row> actions) {
        if (changes.isEmpty()) {
            return;
        }
        ColumnarBatch batch = rows.batchOf(changes, deltaSchema, true);
        actions.addAll(write(txnState, context, iterator(List.of(new FilteredColumnarBatch(batch, Optional.empty())))));
        rowsApplied += changes.size();
    }

    /** Upsert mode: the copy-on-write merge described on this class. */
    private void merge(
            List<Change> changes, long readVersion, Row txnState, DataWriteContext context, List<Row> actions) {
        Map<List<Object>, Change> last = collapse(changes);
        if (last.isEmpty()) {
            return;
        }
        Set<List<Object>> affected = new LinkedHashSet<>(last.keySet());
        Snapshot snapshot = table.getSnapshotAsOfVersion(engine, readVersion);
        List<DeltaScanFiles.ScanFile> touched = filesHolding(snapshot, affected);
        if (!touched.isEmpty()) {
            Scan scan = snapshot.getScanBuilder().build();
            Row scanState = scan.getScanState(engine);
            List<DataType> types = DeltaTypes.columnTypes(deltaSchema);
            for (DeltaScanFiles.ScanFile file : touched) {
                actions.addAll(write(txnState, context, survivors(scanState, file, affected, types)));
                actions.add(SingleAction.createRemoveFileSingleAction(
                        new AddFile(file.row().getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL))
                                .toRemoveFileRow(true, Optional.of(System.currentTimeMillis()))));
                filesRewritten++;
            }
        }
        List<Change> inserted = new ArrayList<>();
        for (Change change : last.values()) {
            if (change.weight() > 0) {
                inserted.add(change);
            }
        }
        if (!inserted.isEmpty()) {
            ColumnarBatch batch = rows.batchOf(inserted, deltaSchema, false);
            actions.addAll(
                    write(txnState, context, iterator(List.of(new FilteredColumnarBatch(batch, Optional.empty())))));
        }
        rowsApplied += last.size();
    }

    /**
     * Keeps each key's last change, in order.
     *
     * <p>An upsert replaces a whole record and a retraction removes it, so of everything that happens
     * to one key only the last change decides what the table holds, and changes to different keys
     * touch different records and commute. A key that changed a hundred times between checkpoints is
     * therefore written once.
     */
    private Map<List<Object>, Change> collapse(List<Change> changes) {
        Map<List<Object>, Change> last = new LinkedHashMap<>();
        for (Change change : changes) {
            List<Object> key = rows.keyOf(change.values(), keyOrdinals);
            // Removed first, so a key that changes again moves to the back and the order stays the
            // order of each key's final change.
            last.remove(key);
            last.put(key, change);
        }
        return last;
    }

    /**
     * The data files holding a key that is about to change, found by reading the key columns alone.
     *
     * <p>Two scans of one snapshot: one with a read schema of the key columns, to decide; the other
     * with the whole schema, to rewrite. Both are listed by {@link DeltaScanFiles#listFiles}, which
     * sorts by path, so the two lists are the same files in the same order and an index in one names
     * the same file in the other.
     */
    private List<DeltaScanFiles.ScanFile> filesHolding(Snapshot snapshot, Set<List<Object>> affected) {
        StructType keySchema = new StructType();
        for (int ordinal : keyOrdinals) {
            keySchema = keySchema.add(deltaSchema.at(ordinal));
        }
        int[] inKeyRead = new int[keyOrdinals.length];
        for (int i = 0; i < inKeyRead.length; i++) {
            inKeyRead[i] = i;
        }
        List<DataType> keyTypes = DeltaTypes.columnTypes(keySchema);
        Scan keyScan = snapshot.getScanBuilder().withReadSchema(keySchema).build();
        Row keyState = keyScan.getScanState(engine);
        List<DeltaScanFiles.ScanFile> keyFiles = DeltaScanFiles.listFiles(engine, keyScan);
        List<DeltaScanFiles.ScanFile> full =
                DeltaScanFiles.listFiles(engine, snapshot.getScanBuilder().build());
        List<DeltaScanFiles.ScanFile> touched = new ArrayList<>();
        for (int i = 0; i < keyFiles.size(); i++) {
            if (holdsAffectedKey(keyState, keyFiles.get(i), affected, inKeyRead, keyTypes)) {
                touched.add(full.get(i));
            }
        }
        return touched;
    }

    private boolean holdsAffectedKey(
            Row scanState,
            DeltaScanFiles.ScanFile file,
            Set<List<Object>> affected,
            int[] ordinals,
            List<DataType> types) {
        try (CloseableIterator<FilteredColumnarBatch> batches = DeltaScanFiles.readFile(engine, scanState, file)) {
            while (batches.hasNext()) {
                ColumnarBatch batch = batches.next().getData();
                for (int rowId = 0; rowId < batch.getSize(); rowId++) {
                    if (affected.contains(DeltaSinkRows.keyOf(batch, rowId, ordinals, types))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED, "cannot read the keys of " + file.path() + ": " + e, e);
        }
    }

    /** One file's rows minus the ones whose keys this commit changes, as a selection over its batches. */
    private CloseableIterator<FilteredColumnarBatch> survivors(
            Row scanState, DeltaScanFiles.ScanFile file, Set<List<Object>> affected, List<DataType> types) {
        CloseableIterator<FilteredColumnarBatch> batches = DeltaScanFiles.readFile(engine, scanState, file);
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return batches.hasNext();
            }

            @Override
            public FilteredColumnarBatch next() {
                ColumnarBatch batch = batches.next().getData();
                boolean[] keep = new boolean[batch.getSize()];
                for (int rowId = 0; rowId < keep.length; rowId++) {
                    keep[rowId] = !affected.contains(DeltaSinkRows.keyOf(batch, rowId, keyOrdinals, types));
                }
                return new FilteredColumnarBatch(batch, Optional.of(DeltaSinkRows.selection(keep)));
            }

            @Override
            public void close() throws IOException {
                batches.close();
            }
        };
    }

    /** Writes logical rows as Parquet and returns the {@code add} actions naming the files. */
    private List<Row> write(Row txnState, DataWriteContext context, CloseableIterator<FilteredColumnarBatch> logical) {
        try (CloseableIterator<FilteredColumnarBatch> physical =
                Transaction.transformLogicalData(engine, txnState, logical, Map.of())) {
            CloseableIterator<DataFileStatus> written = engine.getParquetHandler()
                    .writeParquetFiles(context.getTargetDirectory(), physical, context.getStatisticsColumns());
            List<Row> actions = new ArrayList<>();
            try (CloseableIterator<Row> appends =
                    Transaction.generateAppendActions(engine, txnState, written, context)) {
                while (appends.hasNext()) {
                    actions.add(appends.next());
                }
            }
            return actions;
        } catch (IOException e) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED, "cannot write the table's data files under " + path + ": " + e, e);
        }
    }

    /** Rows this instance has applied to the table, by a commit or written straight. */
    long rowsApplied() {
        return rowsApplied;
    }

    /** Data files this instance has rewritten to delete rows from them. */
    long filesRewritten() {
        return filesRewritten;
    }

    /** The schema the sink writes, for a message that has to show it. */
    StreamSchema schema() {
        return schema;
    }

    private static <T> CloseableIterator<T> iterator(List<T> values) {
        Iterator<T> iterator = values.iterator();
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public T next() {
                return iterator.next();
            }

            @Override
            public void close() {}
        };
    }
}
