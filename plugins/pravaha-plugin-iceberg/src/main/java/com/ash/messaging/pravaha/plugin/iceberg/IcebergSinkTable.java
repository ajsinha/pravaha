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
package com.ash.messaging.pravaha.plugin.iceberg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotUpdate;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.parquet.ParquetUtil;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * One Iceberg table on the local filesystem, and the sink's files in it.
 *
 * <p>The catalog is Iceberg's own {@link HadoopTables} over the local filesystem: the table is a
 * directory, its metadata files are the catalog, and no metastore or REST service is involved.
 *
 * <p><strong>Staging is the table's own data directory.</strong> A transaction's Parquet files are
 * written to {@code <path>/data/_pravaha/<transaction.id>/<label>/} and are invisible to every
 * reader until a snapshot names them, which is exactly the durable-and-not-yet-visible state the
 * SPI's {@code prepare} wants: an Iceberg commit adds files that are already there. A file named
 * {@code delete-*} is an equality delete over the key columns, and every other one is data. Once a
 * label is committed its files belong to the table and are never removed by this sink.
 *
 * <p><strong>The table remembers what it committed.</strong> Every commit's snapshot summary carries
 * {@value #TXN_PROPERTY} and {@value #LABEL_PROPERTY}, so {@link #committedLabel} is read from the
 * table itself, and a commit repeated by a restore that cannot know the first one arrived is
 * detected and skipped -- delta-sink's {@code txn} action, in Iceberg's terms.
 */
final class IcebergSinkTable {

    static final String TXN_PROPERTY = "pravaha.transaction-id";
    static final String LABEL_PROPERTY = "pravaha.label";

    private static final String DELETE_PREFIX = "delete-";
    private static final String SUFFIX = ".parquet";

    private final String instanceName;
    private final Path root;
    private final Schema schema;
    private final boolean upsert;
    private final HadoopTables tables = new HadoopTables(new Configuration());
    private Table table;
    private long rowsApplied;

    IcebergSinkTable(String instanceName, Path root, Schema schema, boolean upsert) {
        this.instanceName = instanceName;
        this.root = root;
        this.schema = schema;
        this.upsert = upsert;
    }

    /** Refuses an existing table that is not the binding's; says nothing when there is no table yet. */
    void check() {
        if (tables.exists(location())) {
            refuseMismatch(tables.load(location()));
        }
    }

    /** Loads the table, creating it (format v2) when {@code create} allows. */
    void open(boolean create) {
        if (tables.exists(location())) {
            table = tables.load(location());
            refuseMismatch(table);
            return;
        }
        if (!create) {
            throw IcebergSinkSchema.mismatch(
                    instanceName, location(), "there is no Iceberg table there, and create is false");
        }
        table = tables.create(
                schema, PartitionSpec.unpartitioned(), Map.of(TableProperties.FORMAT_VERSION, "2"), location());
    }

    private void refuseMismatch(Table existing) {
        IcebergSinkSchema.refuseMismatch(instanceName, location(), schema, existing.schema());
        int version = ((HasTableOperations) existing).operations().current().formatVersion();
        if (upsert && version < 2) {
            throw IcebergSinkSchema.mismatch(
                    instanceName,
                    location(),
                    "the table is format version " + version + ", and upsert mode writes equality deletes, "
                            + "which need version 2");
        }
    }

    String location() {
        return root.toString();
    }

    Table table() {
        return table;
    }

    long rowsApplied() {
        return rowsApplied;
    }

    /** A record of the table's schema, for a caller to fill. */
    Record newRecord() {
        return GenericRecord.create(schema);
    }

    /** A record holding only the key columns, which is what an equality delete file holds. */
    Record newKeyRecord() {
        return GenericRecord.create(keySchema());
    }

    private Schema keySchema() {
        return schema.select(schema.identifierFieldNames());
    }

    /** Writes {@code rows} as one Parquet data file in {@code directory}. Nothing sees it until a commit. */
    void writeData(Path directory, String name, List<Record> rows) {
        String file = directory.resolve(name + SUFFIX).toString();
        try {
            Files.createDirectories(directory);
            DataWriter<Record> writer = Parquet.writeData(table.io().newOutputFile(file))
                    .forTable(table)
                    .createWriterFunc(GenericParquetWriter::buildWriter)
                    .overwrite()
                    .build();
            try (writer) {
                rows.forEach(writer::write);
            }
        } catch (IOException | RuntimeException e) {
            throw writeFailed("cannot write the data file " + file, e);
        }
    }

    /** Writes an equality delete file over the key columns in {@code directory}. */
    void writeDeletes(Path directory, String name, List<Record> keys) {
        String file = directory.resolve(DELETE_PREFIX + name + SUFFIX).toString();
        try {
            Files.createDirectories(directory);
            EqualityDeleteWriter<Record> writer = Parquet.writeDeletes(
                            table.io().newOutputFile(file))
                    .forTable(table)
                    .rowSchema(keySchema())
                    .equalityFieldIds(List.copyOf(schema.identifierFieldIds()))
                    .createWriterFunc(GenericParquetWriter::buildWriter)
                    .overwrite()
                    .buildEqualityWriter();
            try (writer) {
                keys.forEach(writer::write);
            }
        } catch (IOException | RuntimeException e) {
            throw writeFailed("cannot write the delete file " + file, e);
        }
    }

    /**
     * Commits the Parquet files in {@code directory} as one snapshot, labelled when {@code txn} is
     * not null. Data only is an append; any delete file makes it a row delta.
     */
    void commit(Path directory, String txn, long label) {
        List<Path> files = filesIn(directory);
        if (files.isEmpty()) {
            return;
        }
        List<DataFile> data = new ArrayList<>();
        List<DeleteFile> deletes = new ArrayList<>();
        MetricsConfig metricsConfig = MetricsConfig.forTable(table);
        for (Path file : files) {
            InputFile input = table.io().newInputFile(file.toString());
            Metrics metrics = ParquetUtil.fileMetrics(input, metricsConfig);
            if (file.getFileName().toString().startsWith(DELETE_PREFIX)) {
                deletes.add(FileMetadata.deleteFileBuilder(table.spec())
                        .ofEqualityDeletes(schema.identifierFieldIds().stream()
                                .mapToInt(Integer::intValue)
                                .sorted()
                                .toArray())
                        .withInputFile(input)
                        .withFormat(FileFormat.PARQUET)
                        .withMetrics(metrics)
                        .build());
            } else {
                data.add(DataFiles.builder(table.spec())
                        .withInputFile(input)
                        .withFormat(FileFormat.PARQUET)
                        .withMetrics(metrics)
                        .build());
            }
        }
        try {
            SnapshotUpdate<?> update;
            if (deletes.isEmpty()) {
                var append = table.newAppend();
                data.forEach(append::appendFile);
                update = append;
            } else {
                RowDelta delta = table.newRowDelta();
                data.forEach(delta::addRows);
                deletes.forEach(delta::addDeletes);
                update = delta;
            }
            if (txn != null) {
                update.set(TXN_PROPERTY, txn);
                update.set(LABEL_PROPERTY, Long.toString(label));
            }
            update.commit();
        } catch (RuntimeException e) {
            throw writeFailed("cannot commit " + files.size() + " files to the Iceberg table at " + location(), e);
        }
        rowsApplied += data.stream().mapToLong(DataFile::recordCount).sum();
    }

    /**
     * The newest label committed for {@code txn}, read from the current snapshot's ancestry: the
     * first snapshot carrying the transaction id is the newest, since labels only increase.
     */
    OptionalLong committedLabel(String txn) {
        table.refresh();
        Snapshot snapshot = table.currentSnapshot();
        while (snapshot != null) {
            Map<String, String> summary = snapshot.summary();
            if (txn.equals(summary.get(TXN_PROPERTY)) && summary.containsKey(LABEL_PROPERTY)) {
                return OptionalLong.of(Long.parseLong(summary.get(LABEL_PROPERTY)));
            }
            Long parent = snapshot.parentId();
            snapshot = parent == null ? null : table.snapshot(parent);
        }
        return OptionalLong.empty();
    }

    /** The labels that have a directory under {@code stagingRoot}, ascending. */
    static List<Long> labels(Path stagingRoot) {
        if (!Files.isDirectory(stagingRoot)) {
            return List.of();
        }
        List<Long> labels = new ArrayList<>();
        try (Stream<Path> listing = Files.list(stagingRoot)) {
            listing.forEach(entry -> {
                try {
                    labels.add(Long.parseLong(entry.getFileName().toString()));
                } catch (NumberFormatException notALabel) {
                    // Not this sink's directory; left alone.
                }
            });
        } catch (IOException e) {
            throw writeFailed("cannot list " + stagingRoot, e);
        }
        labels.sort(Comparator.naturalOrder());
        return labels;
    }

    /** Removes a directory of uncommitted files. The caller has made sure no snapshot names them. */
    static void discard(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            throw writeFailed("cannot remove the staged files under " + directory, e);
        }
    }

    private static List<Path> filesIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(directory)) {
            return listing.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw writeFailed("cannot list the staged files under " + directory, e);
        }
    }

    private static PravahaException writeFailed(String what, Exception cause) {
        return new PravahaException(IcebergErrors.SINK_WRITE_FAILED, what + ": " + cause.getMessage(), cause);
    }
}
