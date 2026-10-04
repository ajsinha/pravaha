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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

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
import io.delta.kernel.internal.deletionvectors.Base85Codec;
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

    /** {@link #SCHEMA} with a third column, {@code ts}, a Delta TIMESTAMP (microseconds). */
    static final StructType TIMED_SCHEMA = SCHEMA.add("ts", io.delta.kernel.types.TimestampType.TIMESTAMP);

    private final Engine engine;
    private final String path;
    private boolean created;
    private boolean deletionVectors;

    /** Each file's add action as Kernel first wrote it, by path, for the deletion-vector commits. */
    private final Map<String, String> addActions = new HashMap<>();

    /** Each file's current deletion vector, as the log records it, by path. */
    private final Map<String, String> vectors = new HashMap<>();

    /** The row indexes each file's current deletion vector deletes, by path. */
    private final Map<String, TreeSet<Long>> deleted = new HashMap<>();

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
        return append(new SimpleBatch(ids, names, null));
    }

    /** Appends rows with a {@code ts} column, in microseconds since the epoch as Delta stores it. */
    long appendTimed(List<Long> ids, List<String> names, List<Long> micros) {
        return append(new SimpleBatch(ids, names, micros));
    }

    private long append(SimpleBatch batch) {
        Table table = Table.forPath(engine, path);
        TransactionBuilder builder = table.createTransactionBuilder(
                engine, "Pravaha-Test", created ? Operation.WRITE : Operation.CREATE_TABLE);
        if (!created) {
            builder = builder.withSchema(engine, batch.getSchema());
            if (deletionVectors) {
                builder = builder.withTableProperties(engine, Map.of("delta.enableDeletionVectors", "true"));
            }
        }
        Transaction txn = builder.build(engine);
        Row txnState = txn.getTransactionState(engine);

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

    /** Two columns, or three with times, held as boxed lists because a fixture's job is to be obviously correct. */
    private record SimpleBatch(List<Long> ids, List<String> names, List<Long> micros) implements ColumnarBatch {

        @Override
        public StructType getSchema() {
            return micros == null ? SCHEMA : TIMED_SCHEMA;
        }

        @Override
        public ColumnVector getColumnVector(int ordinal) {
            return switch (ordinal) {
                case 0 -> new LongVector(ids, LongType.LONG);
                case 1 -> new StringVector(names);
                default -> new LongVector(micros, io.delta.kernel.types.TimestampType.TIMESTAMP);
            };
        }

        @Override
        public int getSize() {
            return ids.size();
        }
    }

    private record LongVector(List<Long> values, DataType type) implements ColumnVector {
        @Override
        public DataType getDataType() {
            return type;
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

    /**
     * Creates the table, on the first append, with the {@code deletionVectors} table feature: the
     * protocol and property a table needs before a deletion vector may appear in its log.
     */
    DeltaTableFixture withDeletionVectors() {
        this.deletionVectors = true;
        return this;
    }

    /**
     * Deletes rows from the file version {@code version} added, the way a {@code DELETE} does on a
     * table with deletion vectors: the file stays, and a commit replaces its log entry with one
     * naming a deletion vector that marks the rows deleted.
     *
     * <p>The vector is real, written to the Delta protocol's own format: a
     * {@code deletion_vector_<uuid>.bin} file in the table root holding a format-version byte, the
     * vector's size, a portable {@code RoaringBitmapArray} behind its magic number, and a CRC-32 of
     * it. Kernel's own reader loads it and checks the size and the checksum. Kernel 4.0 has no API
     * for writing one, which is why this is here; the {@code remove} and {@code add} pair is what
     * Spark's {@code DELETE} commits, and each new vector replaces the last and holds every row
     * deleted from the file so far.
     *
     * @param rowIndexes row positions within the file, counted from zero
     * @return the version of the commit that performed the deletion
     */
    long deleteRows(long version, long... rowIndexes) {
        try {
            Path log = Path.of(path, "_delta_log");
            String add = addActionOf(log, version);
            String file = field(add, "path");
            TreeSet<Long> rows = deleted.computeIfAbsent(file, k -> new TreeSet<>());
            for (long row : rowIndexes) {
                rows.add(row);
            }
            String vector = writeVector(rows);
            String previous = vectors.put(file, vector);
            long next = highestVersion(log) + 1;
            String remove = "{\"remove\":{\"path\":\"" + file + "\",\"deletionTimestamp\":" + System.currentTimeMillis()
                    + ",\"dataChange\":true,\"extendedFileMetadata\":true,\"partitionValues\":{},\"size\":"
                    + field(add, "size") + (previous == null ? "" : ",\"deletionVector\":" + previous) + "}}\n";
            String replaced = "{\"add\":{\"deletionVector\":" + vector + "," + add.substring("{\"add\":{".length());
            Files.writeString(log.resolve(String.format("%020d.json", next)), remove + replaced + "\n");
            return next;
        } catch (IOException e) {
            throw new IllegalStateException("could not write a deletion vector for " + path, e);
        }
    }

    private String addActionOf(Path log, long version) throws IOException {
        String commit = Files.readString(log.resolve(String.format("%020d.json", version)));
        for (String line : commit.lines().toList()) {
            if (line.startsWith("{\"add\":{")) {
                return addActions.computeIfAbsent(field(line, "path"), k -> line);
            }
        }
        throw new IllegalStateException("version " + version + " added no file: " + commit);
    }

    private static String field(String action, String name) {
        Matcher matcher =
                Pattern.compile("\"" + name + "\":(\"([^\"]*)\"|([0-9]+))").matcher(action);
        if (!matcher.find()) {
            throw new IllegalStateException("no " + name + " in " + action);
        }
        return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
    }

    /** Writes the vector's file and returns its descriptor, as the log's JSON. */
    private String writeVector(NavigableSet<Long> rows) throws IOException {
        byte[] data = portableBitmapArray(rows);
        UUID uuid = UUID.randomUUID();
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteBuffer file = ByteBuffer.allocate(1 + 4 + data.length + 4);
        file.put((byte) 1).putInt(data.length).put(data).putInt((int) crc.getValue());
        Files.write(Path.of(path, "deletion_vector_" + uuid + ".bin"), file.array());
        return "{\"storageType\":\"u\",\"pathOrInlineDv\":\"" + Base85Codec.encodeUUID(uuid)
                + "\",\"offset\":1,\"sizeInBytes\":" + data.length + ",\"cardinality\":" + rows.size() + "}";
    }

    /**
     * The protocol's serialised deletion vector: the portable format's magic number, then a
     * {@code RoaringBitmapArray} of one 32-bit bitmap in RoaringBitmap's portable format, all
     * little-endian. Row indexes below 65 536 fit one array container, which is all a test needs.
     */
    private static byte[] portableBitmapArray(NavigableSet<Long> rows) {
        if (rows.isEmpty() || rows.last() >= 65_536 || rows.size() > 4096) {
            throw new IllegalArgumentException("the fixture writes one array container: " + rows);
        }
        ByteBuffer out = ByteBuffer.allocate(4 + 8 + 4 + 16 + 2 * rows.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(1681511377); // the portable RoaringBitmapArray's magic number
        out.putLong(1L); // one bitmap
        out.putInt(0); // for the row indexes whose high 32 bits are zero
        out.putInt(12346); // RoaringBitmap's cookie for a bitmap with no run containers
        out.putInt(1); // one container
        out.putShort((short) 0).putShort((short) (rows.size() - 1)); // its key, and its cardinality less one
        out.putInt(16); // its offset: past the cookie, the count, the one key pair and the one offset
        for (long row : rows) {
            out.putShort((short) row);
        }
        return out.array();
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
