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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.delta.kernel.DataWriteContext;
import io.delta.kernel.Operation;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.TransactionBuilder;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import org.apache.hadoop.conf.Configuration;

/**
 * Builds real Delta tables for the tests, using Kernel's own write path.
 *
 * <p>Real tables rather than checked-in fixtures, and that is a deliberate cost. A committed table
 * is a snapshot of one protocol version that stops exercising the reader the moment Kernel changes
 * anything; a table written by Kernel is whatever Kernel currently produces, so the test keeps
 * telling the truth after an upgrade. It also means the reader is tested against files nobody hand
 * -tuned to make it pass.
 *
 * <p>The column vectors here are as simple as the interface allows: two supported types, no nulls
 * unless asked. Test scaffolding that grows features nothing needs is how a test suite acquires its
 * own bugs.
 */
final class DeltaTableFixture {

    static final StructType SCHEMA = new StructType().add("id", LongType.LONG).add("name", StringType.STRING);

    private final Engine engine;
    private final String path;
    private boolean created;

    DeltaTableFixture(String path) {
        this.engine = DefaultEngine.create(new Configuration());
        this.path = path;
    }

    Engine engine() {
        return engine;
    }

    String path() {
        return path;
    }

    /** Appends one commit's worth of rows, creating the table on the first call. */
    long append(List<Long> ids, List<String> names) {
        Table table = Table.forPath(engine, path);
        TransactionBuilder builder = table.createTransactionBuilder(
                engine, "Pravaha-Test", created ? Operation.WRITE : Operation.CREATE_TABLE);
        if (!created) {
            builder = builder.withSchema(engine, SCHEMA);
        }
        Transaction txn = builder.build(engine);
        Row txnState = txn.getTransactionState(engine);

        ColumnarBatch batch = new SimpleBatch(ids, names);
        CloseableIterator<FilteredColumnarBatch> logical = closeable(
                List.of(new FilteredColumnarBatch(batch, Optional.empty())).iterator());
        CloseableIterator<FilteredColumnarBatch> physical =
                Transaction.transformLogicalData(engine, txnState, logical, Map.of());
        DataWriteContext context = Transaction.getWriteContext(engine, txnState, Map.of());

        try {
            CloseableIterator<DataFileStatus> written = engine.getParquetHandler()
                    .writeParquetFiles(context.getTargetDirectory(), physical, context.getStatisticsColumns());
            CloseableIterator<Row> actions = Transaction.generateAppendActions(engine, txnState, written, context);
            long version = txn.commit(engine, CloseableIterable.inMemoryIterable(actions))
                    .getVersion();
            created = true;
            return version;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not write the fixture table at " + path, e);
        }
    }

    private static <T> CloseableIterator<T> closeable(Iterator<T> iterator) {
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

    /** Two columns, held as boxed lists because a fixture's job is to be obviously correct. */
    private record SimpleBatch(List<Long> ids, List<String> names) implements ColumnarBatch {

        @Override
        public StructType getSchema() {
            return SCHEMA;
        }

        @Override
        public ColumnVector getColumnVector(int ordinal) {
            return ordinal == 0 ? new LongVector(ids) : new StringVector(names);
        }

        @Override
        public int getSize() {
            return ids.size();
        }
    }

    private record LongVector(List<Long> values) implements ColumnVector {
        @Override
        public DataType getDataType() {
            return LongType.LONG;
        }

        @Override
        public int getSize() {
            return values.size();
        }

        @Override
        public void close() {}

        @Override
        public boolean isNullAt(int rowId) {
            return values.get(rowId) == null;
        }

        @Override
        public long getLong(int rowId) {
            return values.get(rowId);
        }
    }

    private record StringVector(List<String> values) implements ColumnVector {
        @Override
        public DataType getDataType() {
            return StringType.STRING;
        }

        @Override
        public int getSize() {
            return values.size();
        }

        @Override
        public void close() {}

        @Override
        public boolean isNullAt(int rowId) {
            return values.get(rowId) == null;
        }

        @Override
        public String getString(int rowId) {
            return values.get(rowId);
        }
    }

    /**
     * Writes a commit that removes the data file added by version {@code version}.
     *
     * <p>Hand-written, because Kernel 4.0's public write API appends and does not delete, and the
     * retraction path is the interesting half of this connector. A Delta commit is a JSON file of
     * actions, so a {@code remove} is a legitimate fixture rather than a trick -- it is exactly what
     * an {@code UPDATE} or a {@code DELETE} writes.
     *
     * @return the version of the commit that performed the removal
     */
    long removeFileAddedBy(long version) {
        try {
            java.nio.file.Path log = java.nio.file.Path.of(path, "_delta_log");
            String commit = java.nio.file.Files.readString(log.resolve(String.format("%020d.json", version)));
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\"add\":\\{\"path\":\"([^\"]+)\"")
                    .matcher(commit);
            if (!matcher.find()) {
                throw new IllegalStateException("version " + version + " added no file: " + commit);
            }
            String removedPath = matcher.group(1);
            long next = highestVersion(log) + 1;
            String action = "{\"remove\":{\"path\":\"" + removedPath + "\",\"deletionTimestamp\":"
                    + System.currentTimeMillis() + ",\"dataChange\":true}}\n";
            java.nio.file.Files.writeString(log.resolve(String.format("%020d.json", next)), action);
            return next;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not write a remove commit for " + path, e);
        }
    }

    private static long highestVersion(java.nio.file.Path log) throws java.io.IOException {
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(log)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && n.length() == 25)
                    .mapToLong(n -> Long.parseLong(n.substring(0, 20)))
                    .max()
                    .orElseThrow();
        }
    }

    /** Convenience: {@code n} rows named {@code prefix-i}, starting at {@code from}. */
    static DeltaTableFixture withRows(String path, long from, int count, String prefix) {
        DeltaTableFixture fixture = new DeltaTableFixture(path);
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(from + i);
            names.add(prefix + "-" + (from + i));
        }
        fixture.append(ids, names);
        return fixture;
    }
}
