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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.iceberg.data.Record;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * A continuous query's answer, maintained in an Apache Iceberg table: iceberg-core and
 * iceberg-parquet, not Spark, over a table on the local filesystem ({@link IcebergSinkTable}).
 *
 * <p><strong>Two modes.</strong> {@code mode: upsert} (the default) keeps the table equal to the
 * query's view by {@code key.columns}: a checkpoint's changes are collapsed by key (the last change
 * to a key decides), and the commit writes one equality delete file naming every affected key plus
 * one data file of the rows that survive. Iceberg applies an equality delete only to data older than
 * it, so the commit replaces each key's row without reading or rewriting the table -- the cost is
 * the change, not the table, unlike delta-sink. The table is format version 2 with the key columns as
 * its identifier fields. {@code mode: changelog} appends every change with {@code _op} and the Z-set
 * weight in {@code _weight}.
 *
 * <p><strong>Exactly once.</strong> With {@code transactional: true} (the default) a checkpoint is one
 * Iceberg snapshot. Changelog mode writes each batch as a Parquet file under the transaction's
 * directory at {@link #write}; upsert mode collapses in memory and writes its two files at {@link
 * #prepare}. Either way the files are durable and unreferenced -- invisible -- once prepare returns.
 * {@link #commit} adds them in one snapshot whose summary carries {@code transaction.id} and the
 * label; a commit whose label the table already records is skipped, so a restore that repeats it
 * changes nothing. {@link #abortAfter} removes every uncommitted label after the restored checkpoint.
 *
 * <p>Without checkpoints each write is its own snapshot: atomic, and at least once after a restart.
 *
 * <p><strong>What it assumes:</strong> one writer per {@code transaction.id}, a table on the local
 * filesystem, and no other engine expiring the sink's newest snapshot. Compaction and snapshot
 * expiry belong to the table's own engine.
 *
 * <p>Configuration: {@code path} (required, the table directory), {@code schema} (required,
 * {@code name:TYPE,...}), {@code mode} ({@code upsert} | {@code changelog}), {@code key.columns}
 * (required in upsert mode), {@code transactional} (default {@code true}), {@code transaction.id}
 * (default the binding's name), {@code create} (default {@code true}).
 */
public final class IcebergSinkPlugin implements StreamSinkPlugin {

    private static final int MAX_BATCH_ROWS = 1000;
    private static final String HANDLE_PREFIX = "iceberg-sink:v1:";

    private String instanceName = "iceberg-sink";
    private String path;
    private StreamSchema schema;
    private List<String> keyNames = List.of();
    private int[] keyOrdinals = new int[0];
    private boolean changelog;
    private boolean transactional;
    private boolean create;
    private String transactionId;
    private Path stagingRoot;

    private IcebergSinkTable table;

    /** Upsert mode: the open transaction's changes, collapsed by key, in the order of each key's last change. */
    private final Map<List<Object>, Change> pending = new LinkedHashMap<>();

    private long openLabel = -1;
    private int nextSeq;

    private record Change(Object[] values, long weight) {}

    @Override
    public String name() {
        return "iceberg-sink";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.path = context.get("path", "").strip();
        if (path.isEmpty()) {
            throw new ConfigurationException(
                    IcebergErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' needs 'path', the directory of the Iceberg table on the local "
                            + "filesystem. It is created there when create is true (the default).");
        }
        if (path.contains("://")) {
            throw new ConfigurationException(
                    IcebergErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' path '" + path + "' is not a local directory; this sink writes "
                            + "tables on the local filesystem only");
        }
        String spec = context.get("schema", "").strip();
        if (spec.isEmpty()) {
            throw new ConfigurationException(
                    IcebergErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' needs 'schema', the query's columns as name:TYPE,...");
        }
        Path root = Path.of(path).toAbsolutePath().normalize();
        this.schema = IcebergSinkSchema.parse(
                root.getFileName() == null ? "iceberg" : root.getFileName().toString(), spec);

        String mode = context.get("mode", "upsert").strip().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "upsert" -> this.changelog = false;
            case "changelog" -> this.changelog = true;
            default ->
                throw new ConfigurationException(
                        IcebergErrors.SINK_BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' mode '" + mode + "' is not upsert or changelog");
        }
        readKeyColumns(context.get("key.columns", "").strip());
        this.transactional =
                Boolean.parseBoolean(context.get("transactional", "true").strip());
        this.create = Boolean.parseBoolean(context.get("create", "true").strip());
        this.transactionId = context.get("transaction.id", instanceName).strip();
        if (!transactionId.matches("[A-Za-z0-9._-]{1,200}")) {
            throw new ConfigurationException(
                    IcebergErrors.SINK_BAD_CONFIGURATION,
                    "transaction.id '" + transactionId + "' must be 1 to 200 letters, digits, dots, underscores or "
                            + "hyphens: it names a directory and is recorded in every snapshot this sink commits");
        }
        this.stagingRoot = root.resolve("data").resolve("_pravaha").resolve(transactionId);
        this.table = new IcebergSinkTable(
                instanceName, root, IcebergSinkSchema.toIceberg(instanceName, schema, changelog, keyNames), !changelog);
        table.check();
    }

    private void readKeyColumns(String keys) {
        if (changelog && !keys.isEmpty()) {
            throw new ConfigurationException(
                    IcebergErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' is in changelog mode, which appends every change, so "
                            + "key.columns would mean nothing. Remove it, or use mode: upsert.");
        }
        if (!changelog && keys.isEmpty()) {
            throw new ConfigurationException(
                    IcebergErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' needs key.columns in upsert mode: an equality delete names the "
                            + "record it removes by key. It must be the query's --keys.");
        }
        List<String> names = new ArrayList<>();
        for (String part : keys.split(",")) {
            if (!part.isBlank()) {
                names.add(part.strip());
            }
        }
        this.keyNames = List.copyOf(names);
        this.keyOrdinals = new int[keyNames.size()];
        for (int k = 0; k < keyNames.size(); k++) {
            keyOrdinals[k] = keyOrdinal(keyNames.get(k));
        }
    }

    private int keyOrdinal(String key) {
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (!schema.field(ordinal).name().equalsIgnoreCase(key)) {
                continue;
            }
            TypeName type = schema.field(ordinal).type().typeName();
            if (type == TypeName.FLOAT32
                    || type == TypeName.FLOAT64
                    || schema.field(ordinal).type().nullable()) {
                throw new ConfigurationException(
                        IcebergErrors.SINK_BAD_CONFIGURATION,
                        "key column '" + key + "' is "
                                + (schema.field(ordinal).type().nullable() ? "nullable" : type)
                                + "; an Iceberg identifier field must be required and not floating-point");
            }
            return ordinal;
        }
        throw new ConfigurationException(
                IcebergErrors.SINK_BAD_CONFIGURATION,
                "key column '" + key + "' is not in the declared schema, which has "
                        + schema.fields().stream().map(f -> f.name()).toList());
    }

    @Override
    public void open() {
        table.open(create);
    }

    @Override
    public Optional<StreamSchema> schema() {
        return Optional.ofNullable(schema);
    }

    @Override
    public List<String> keyColumns() {
        return keyNames;
    }

    @Override
    public SinkCapabilities capabilities() {
        return new SinkCapabilities(
                changelog
                        ? EnumSet.of(EmitMode.APPEND, EmitMode.RETRACT)
                        : EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT),
                transactional,
                !changelog,
                MAX_BATCH_ROWS);
    }

    @Override
    public int write(List<RowView> batch) {
        requireOpen();
        if (batch.isEmpty()) {
            return 0;
        }
        List<Change> changes = new ArrayList<>(batch.size());
        for (RowView row : batch) {
            changes.add(new Change(IcebergSinkSchema.read(schema, row), row.weight()));
        }
        boolean inTransaction = transactional && openLabel >= 0;
        if (changelog) {
            Path directory = inTransaction ? labelDirectory(openLabel) : directDirectory();
            table.writeData(directory, String.format("data-%06d", nextSeq++), changelogRecords(changes));
            if (!inTransaction) {
                table.commit(directory, null, -1L);
            }
        } else {
            changes.forEach(this::collapse);
            if (!inTransaction) {
                Path directory = directDirectory();
                writeCollapsed(directory);
                table.commit(directory, null, -1L);
            }
        }
        return changes.size();
    }

    private void collapse(Change change) {
        List<Object> key = new ArrayList<>(keyOrdinals.length);
        for (int ordinal : keyOrdinals) {
            if (change.values()[ordinal] == null) {
                throw new PravahaException(
                        IcebergErrors.SINK_WRITE_FAILED,
                        "key column '" + schema.field(ordinal).name() + "' is null; a record cannot be keyed by "
                                + "nothing");
            }
            key.add(change.values()[ordinal]);
        }
        pending.remove(key);
        pending.put(List.copyOf(key), change);
    }

    /** Writes the collapsed changes as one delete file of every key and one data file of the survivors. */
    private void writeCollapsed(Path directory) {
        if (pending.isEmpty()) {
            return;
        }
        List<Record> keys = new ArrayList<>(pending.size());
        List<Record> rows = new ArrayList<>(pending.size());
        for (Change change : pending.values()) {
            Record key = table.newKeyRecord();
            for (int ordinal : keyOrdinals) {
                key.setField(schema.field(ordinal).name(), change.values()[ordinal]);
            }
            keys.add(key);
            if (change.weight() > 0) {
                rows.add(record(change));
            }
        }
        pending.clear();
        table.writeDeletes(directory, "000000", keys);
        if (!rows.isEmpty()) {
            table.writeData(directory, "data-000000", rows);
        }
    }

    private List<Record> changelogRecords(List<Change> changes) {
        List<Record> records = new ArrayList<>(changes.size());
        for (Change change : changes) {
            Record record = record(change);
            record.set(schema.fieldCount(), change.weight() > 0 ? "insert" : "delete");
            record.set(schema.fieldCount() + 1, change.weight());
            records.add(record);
        }
        return records;
    }

    private Record record(Change change) {
        Record record = table.newRecord();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            record.set(ordinal, change.values()[ordinal]);
        }
        return record;
    }

    /** Nothing is buffered outside a transaction: every non-transactional write has committed. */
    @Override
    public void flush() {}

    /** Opens transaction {@code checkpointId}, first removing anything a dead process left under it. */
    @Override
    public void beginTransaction(long checkpointId) {
        if (!transactional) {
            return;
        }
        requireOpen();
        if (checkpointId > table.committedLabel(transactionId).orElse(Long.MIN_VALUE)) {
            IcebergSinkTable.discard(labelDirectory(checkpointId));
        }
        pending.clear();
        this.openLabel = checkpointId;
        this.nextSeq = 0;
    }

    /** Writes what upsert mode holds in memory; after this every file of the label is durable. */
    @Override
    public String prepare(long checkpointId) {
        if (!transactional) {
            return "";
        }
        if (openLabel < 0) {
            throw new PravahaException(
                    IcebergErrors.SINK_WRITE_FAILED,
                    "prepare(" + checkpointId + ") for the Iceberg table at " + path + " with no transaction begun");
        }
        if (!changelog) {
            writeCollapsed(labelDirectory(openLabel));
        }
        String handle = HANDLE_PREFIX + openLabel + ":" + transactionId;
        openLabel = -1;
        return handle;
    }

    /**
     * Commits the handle's files as one snapshot, unless the table already records the label: that
     * check is what makes a commit repeated after a restore a no-op.
     */
    @Override
    public void commit(String handle) {
        if (!transactional || handle == null || handle.isEmpty()) {
            return;
        }
        requireOpen();
        long label = labelOf(handle);
        if (table.committedLabel(transactionId).orElse(Long.MIN_VALUE) >= label) {
            return;
        }
        table.commit(labelDirectory(label), transactionId, label);
    }

    @Override
    public void abort(String handle) {
        if (!transactional || handle == null || handle.isEmpty()) {
            return;
        }
        requireOpen();
        long label = labelOf(handle);
        if (label > table.committedLabel(transactionId).orElse(Long.MIN_VALUE)) {
            IcebergSinkTable.discard(labelDirectory(label));
        }
        if (label == openLabel) {
            openLabel = -1;
            pending.clear();
        }
    }

    /** Removes every label after the restored checkpoint that the table does not record as committed. */
    @Override
    public void abortAfter(long checkpointId) {
        if (!transactional) {
            return;
        }
        requireOpen();
        long committed = table.committedLabel(transactionId).orElse(Long.MIN_VALUE);
        for (long label : IcebergSinkTable.labels(stagingRoot)) {
            if (label > checkpointId && label > committed) {
                IcebergSinkTable.discard(labelDirectory(label));
            }
        }
    }

    private Path labelDirectory(long label) {
        return stagingRoot.resolve(String.format("%016d", label));
    }

    private Path directDirectory() {
        return stagingRoot.resolve("direct-" + UUID.randomUUID());
    }

    private long labelOf(String handle) {
        if (handle.startsWith(HANDLE_PREFIX)) {
            String rest = handle.substring(HANDLE_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon > 0) {
                String owner = rest.substring(colon + 1);
                if (!owner.equals(transactionId)) {
                    throw new PravahaException(
                            IcebergErrors.SINK_WRITE_FAILED,
                            "handle '" + handle + "' belongs to transaction.id '" + owner + "', and this sink is '"
                                    + transactionId + "'; keep transaction.id the same across restarts of one binding");
                }
                try {
                    return Long.parseLong(rest.substring(0, colon));
                } catch (NumberFormatException ignored) {
                    // Falls through to the refusal.
                }
            }
        }
        throw new PravahaException(IcebergErrors.SINK_WRITE_FAILED, "'" + handle + "' is not a handle this sink wrote");
    }

    private void requireOpen() {
        if (table == null || table.table() == null) {
            throw new PravahaException(
                    IcebergErrors.SINK_WRITE_FAILED, "sink '" + instanceName + "' is not open; call open() first");
        }
    }

    /** Data rows this instance has committed to the table. */
    public long rowsApplied() {
        return table == null ? 0L : table.rowsApplied();
    }

    /** The table, once open: for tests that read it back through Iceberg. */
    org.apache.iceberg.Table icebergTable() {
        return table.table();
    }

    @Override
    public HealthStatus health() {
        return table == null || table.table() == null
                ? HealthStatus.unhealthy("the Iceberg sink is not open")
                : HealthStatus.healthy();
    }

    /** Lets go of the table. A transaction's files stay where a restore finds them. */
    @Override
    public void close() {
        openLabel = -1;
        pending.clear();
        table = null;
    }
}
