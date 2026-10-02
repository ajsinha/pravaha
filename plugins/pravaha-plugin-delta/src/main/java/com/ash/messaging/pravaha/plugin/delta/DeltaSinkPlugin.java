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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.types.StructType;
import org.apache.hadoop.conf.Configuration;

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
import com.ash.messaging.pravaha.plugin.delta.DeltaSinkRows.Change;

/**
 * A continuous query's answer, maintained in a Delta Lake table.
 *
 * <p>Built on <strong>Delta Kernel, not Spark</strong>, like the {@code delta} source beside it: the
 * transaction log, the protocol versions and the Parquet writing are Kernel's, and no cluster is
 * required to keep a table up to date.
 *
 * <p><strong>Two modes.</strong> {@code mode: upsert} (the default) keeps the table equal to the
 * query's view: a row is written or replaced by its key, and a retraction -- a row with a negative
 * weight -- removes the record its key names. {@code key.columns} names the key, and the registry
 * refuses a registration whose keys differ (PRV-8010), because a sink keyed differently from the
 * view deletes the wrong record. {@code mode: changelog} appends every change as it is, with two
 * extra columns -- {@code _op} ({@code insert} or {@code delete}) and {@code _weight}, the Z-set
 * weight -- for a reader that folds the changes itself.
 *
 * <p>Upsert is the default because a query registered without saying anything wants its
 * <em>answer</em> in the table, not its history, and because it is what the other two maintained
 * sinks ({@code jdbc-sink}, {@code kafka-sink}) default to for the same reason. It is also the more
 * expensive of the two -- {@link DeltaSinkCommit} says exactly how -- so a high-volume query should
 * be bound to {@code changelog} deliberately.
 *
 * <p><strong>Exactly once, and how.</strong> With {@code transactional: true} (the default) the sink
 * implements the SPI's two-phase protocol. Delta's own commit protocol is the second phase, and the
 * first is a staging directory, because a Delta commit has no <em>prepared</em> state: a commit is
 * visible the instant its log entry lands.
 *
 * <ol>
 *   <li>{@link #write} does not commit anything. It encodes the batch and appends it to
 *       {@code staging.dir/transaction.id/<label>/}, durable and invisible to every reader of the
 *       table -- which is what {@link #prepare} promises, so prepare has only to name the label.
 *   <li>{@link #commit} reads back everything staged under the label and applies it as <em>one</em>
 *       Delta commit, carrying a Delta {@code txn} action ({@code transaction.id}, the label). So a
 *       reader sees all of a checkpoint's changes or none of them, and a commit sent twice -- by a
 *       restore that cannot know the first arrived -- finds the label already recorded in the table
 *       and does nothing. The idempotence is the table's, not a note this process keeps.
 *   <li>{@link #abortAfter} deletes everything staged under a label greater than the restored
 *       checkpoint, which the replay is about to write again.
 * </ol>
 *
 * <p><strong>The guarantee, stated precisely.</strong> On a node that checkpoints, every change the
 * engine commits reaches the table exactly once and each checkpoint's changes become visible
 * together. Without checkpoints there is nothing to tie a transaction to, so each view commit is its
 * own Delta commit: atomic per commit, and repeated after a restart -- effectively once in upsert
 * mode, at least once in changelog mode. {@code SinkDelivery.label} is what says which of those an
 * operator is promised (HLP-4); this sink only declares what it is.
 *
 * <p><strong>What it assumes:</strong> one writer per {@code transaction.id} (the binding's name by
 * default), and <strong>no other writer of the table at all</strong>. A concurrent commit is refused
 * with {@code PRV-5059} rather than retried; see {@link DeltaSinkCommit}.
 *
 * <p><strong>Writes are never per row.</strong> Nothing reaches Parquet until a commit, and a commit
 * writes one file for the rows it adds plus one for each file it had to rewrite. That is one or more
 * new files per checkpoint, for ever: the table accumulates small files and needs compaction, which
 * is Delta's {@code OPTIMIZE} and not this sink's -- Kernel has no compaction API, and this plugin
 * does not pretend to one. See {@code OPERATIONS.md}.
 *
 * <p>Configuration: {@code path} (required, the table root), {@code schema} (required,
 * {@code name:TYPE,...}), {@code mode} ({@code upsert} | {@code changelog}), {@code key.columns}
 * (required in upsert mode), {@code transactional} (default {@code true}), {@code transaction.id}
 * (default the binding's name), {@code staging.dir} (default {@code <path>/_pravaha_sink}),
 * {@code create} (default {@code true}), {@code partition.columns} (optional, the table's partition
 * columns in order; each must be one of the declared columns, and not {@code BYTES}).
 */
public final class DeltaSinkPlugin implements StreamSinkPlugin {

    /** Where a sink stages a checkpoint's changes, under the table root, unless told otherwise. */
    public static final String DEFAULT_STAGING_DIRECTORY = "_pravaha_sink";

    private static final int MAX_BATCH_ROWS = 1000;
    private static final String HANDLE_PREFIX = "delta-sink:v1:";

    private String instanceName = "delta-sink";
    private String path;
    private StreamSchema schema;
    private StructType deltaSchema;
    private List<String> keyNames = List.of();
    private int[] keyOrdinals = new int[0];
    private int[] partitionOrdinals = new int[0];
    private boolean changelog;
    private boolean transactional;
    private boolean create;
    private String transactionId;
    private String stagingDirectory;

    private Engine engine;
    private DeltaSinkRows rows;
    private DeltaSinkCommit commit;
    private DeltaSinkStaging staging;

    /** The open transaction's label, or -1 when none is open. */
    private long openLabel = -1;

    private int nextSeq;

    @Override
    public String name() {
        return "delta-sink";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.path = context.require("path").strip();
        this.schema = DeltaSinkSchema.parse(nameOf(path), context.require("schema"));

        String mode = context.get("mode", "upsert").strip().toLowerCase(Locale.ROOT);
        this.changelog = switch (mode) {
            case "upsert" -> false;
            case "changelog" -> true;
            default ->
                throw new ConfigurationException(
                        DeltaErrors.SINK_BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' mode '" + mode + "' is not upsert or changelog");
        };
        this.deltaSchema = DeltaSinkSchema.toDeltaSchema(instanceName, schema, changelog);
        readKeyColumns(context.get("key.columns", "").strip());
        this.partitionOrdinals =
                DeltaSinkPartitions.ordinalsOf(instanceName, schema, namesIn(context.get("partition.columns", "")));

        this.transactional =
                Boolean.parseBoolean(context.get("transactional", "true").strip());
        this.create = Boolean.parseBoolean(context.get("create", "true").strip());
        this.transactionId = context.get("transaction.id", instanceName).strip();
        if (!transactionId.matches("[A-Za-z0-9._-]{1,200}")) {
            throw new ConfigurationException(
                    DeltaErrors.SINK_BAD_CONFIGURATION,
                    "transaction.id '" + transactionId + "' must be 1 to 200 characters of letters, digits, dot, "
                            + "underscore or hyphen: it is both a Delta application id and a directory name");
        }
        this.stagingDirectory = context.get("staging.dir", "").strip();
    }

    private void readKeyColumns(String keys) {
        if (changelog && !keys.isEmpty()) {
            throw new ConfigurationException(
                    DeltaErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' is in changelog mode, which appends every change and removes "
                            + "nothing, so key.columns would mean nothing. Remove it, or use mode: upsert to keep the "
                            + "table equal to the query's view by key.");
        }
        if (!changelog && keys.isEmpty()) {
            throw new ConfigurationException(
                    DeltaErrors.SINK_BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' needs key.columns in upsert mode: a retraction removes the record "
                            + "its key names, and without one there is nothing to name. It must be the query's "
                            + "--keys.");
        }
        this.keyNames = namesIn(keys);
        this.keyOrdinals = new int[keyNames.size()];
        for (int k = 0; k < keyNames.size(); k++) {
            keyOrdinals[k] = keyOrdinal(keyNames.get(k));
        }
    }

    private static List<String> namesIn(String list) {
        List<String> names = new ArrayList<>();
        for (String part : list.split(",")) {
            if (!part.isBlank()) {
                names.add(part.strip());
            }
        }
        return List.copyOf(names);
    }

    private int keyOrdinal(String key) {
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (!schema.field(ordinal).name().equalsIgnoreCase(key)) {
                continue;
            }
            TypeName type = schema.field(ordinal).type().typeName();
            if (type == TypeName.FLOAT32 || type == TypeName.FLOAT64) {
                throw new ConfigurationException(
                        DeltaErrors.SINK_BAD_CONFIGURATION,
                        "key column '" + key + "' is " + type + ". A floating-point key makes two values that print "
                                + "alike two records, and a retraction that misses by a rounding error removes "
                                + "nothing.");
            }
            if (schema.field(ordinal).type().nullable()) {
                throw new ConfigurationException(
                        DeltaErrors.SINK_BAD_CONFIGURATION,
                        "key column '" + key + "' is declared nullable; a record cannot be keyed by nothing, and a "
                                + "retraction of a row whose key is null would name no record to remove");
            }
            return ordinal;
        }
        throw new ConfigurationException(
                DeltaErrors.SINK_BAD_CONFIGURATION,
                "key column '" + key + "' is not in the declared schema, which has "
                        + schema.fields().stream().map(f -> f.name()).toList());
    }

    private static String nameOf(String tablePath) {
        Path asPath = Path.of(tablePath);
        return asPath.getFileName() == null ? "delta" : asPath.getFileName().toString();
    }

    @Override
    public void open() {
        // A Hadoop Configuration with no site XML on the classpath is the local-filesystem default,
        // which is what a local table wants; an S3 or ADLS table supplies its own through the
        // plugin's isolated classpath rather than through the engine's.
        this.engine = DefaultEngine.create(new Configuration());
        this.rows = new DeltaSinkRows(schema);
        this.commit = new DeltaSinkCommit(
                engine, instanceName, path, schema, deltaSchema, rows, keyOrdinals, changelog, partitionOrdinals);
        commit.openTable(create);
        Path stagingRoot = (stagingDirectory.isEmpty()
                        ? Path.of(path).resolve(DEFAULT_STAGING_DIRECTORY)
                        : Path.of(stagingDirectory))
                .resolve(transactionId);
        this.staging = new DeltaSinkStaging(stagingRoot);
    }

    @Override
    public Optional<StreamSchema> schema() {
        return Optional.ofNullable(schema);
    }

    @Override
    public List<String> keyColumns() {
        return keyNames;
    }

    /**
     * Upsert mode takes a revising changelog and is idempotent; changelog mode takes appends and
     * retractions and writes both, and is not -- a replay would append the same changes again.
     */
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

    /** Stages the batch in the open transaction, or -- not transactional -- commits it on its own. */
    @Override
    public int write(List<RowView> batch) {
        requireOpen();
        List<Change> changes = new ArrayList<>(batch.size());
        for (RowView row : batch) {
            changes.add(rows.read(row));
        }
        if (changes.isEmpty()) {
            return 0;
        }
        if (transactional && openLabel >= 0) {
            staging.stage(openLabel, nextSeq++, rows.encode(changes));
        } else {
            commit.apply(changes, null, -1L);
        }
        return changes.size();
    }

    /** Nothing is buffered: a staged batch is a file that is there, and everything else has committed. */
    @Override
    public void flush() {}

    /**
     * Opens transaction {@code checkpointId}, first discarding anything staged under that label.
     *
     * <p>Labels only increase, across restarts too, so a label being begun has never been prepared,
     * and whatever is staged under it is the remains of a process that died before preparing it.
     */
    @Override
    public void beginTransaction(long checkpointId) {
        if (!transactional) {
            return;
        }
        requireOpen();
        staging.discard(checkpointId);
        this.openLabel = checkpointId;
        this.nextSeq = 0;
    }

    /**
     * Names the open transaction. Everything it holds is already durable in the staging directory
     * and invisible in the table, so there is nothing else to do.
     */
    @Override
    public String prepare(long checkpointId) {
        if (!transactional) {
            return "";
        }
        if (openLabel < 0) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED,
                    "prepare(" + checkpointId + ") for the Delta table at " + path + " with no transaction begun");
        }
        String handle = HANDLE_PREFIX + openLabel + ":" + transactionId;
        openLabel = -1;
        return handle;
    }

    /**
     * Applies what the handle's transaction staged, as one Delta commit, and forgets the staging.
     *
     * <p>Idempotent three times over: the table's own {@code txn} action says the label has been
     * committed, the staging directory is empty once it has, and a commit that fails leaves both
     * untouched for the next attempt.
     */
    @Override
    public void commit(String handle) {
        if (!transactional || handle == null || handle.isEmpty()) {
            return;
        }
        requireOpen();
        long label = labelOf(handle);
        if (commit.committedLabel(transactionId).orElse(Long.MIN_VALUE) >= label) {
            staging.discard(label);
            return;
        }
        List<Change> changes = new ArrayList<>();
        for (byte[] payload : staging.staged(label)) {
            changes.addAll(rows.decode(payload));
        }
        if (!changes.isEmpty()) {
            commit.apply(changes, transactionId, label);
        }
        staging.discard(label);
    }

    @Override
    public void abort(String handle) {
        if (!transactional || handle == null || handle.isEmpty()) {
            return;
        }
        requireOpen();
        long label = labelOf(handle);
        staging.discard(label);
        if (label == openLabel) {
            openLabel = -1;
        }
    }

    /**
     * Discards everything staged under a label after the restored checkpoint: the open transaction
     * of the process that died, and anything prepared at a checkpoint that never became durable. The
     * replay writes all of it again.
     */
    @Override
    public void abortAfter(long checkpointId) {
        if (!transactional) {
            return;
        }
        requireOpen();
        staging.discardAfter(checkpointId);
    }

    private long labelOf(String handle) {
        if (handle.startsWith(HANDLE_PREFIX)) {
            String rest = handle.substring(HANDLE_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon > 0) {
                String owner = rest.substring(colon + 1);
                if (!owner.equals(transactionId)) {
                    throw new PravahaException(
                            DeltaErrors.SINK_WRITE_FAILED,
                            "handle '" + handle + "' belongs to transaction.id '" + owner + "', and this sink is '"
                                    + transactionId + "'. Committing it here would apply another sink's changes to "
                                    + "this table; keep transaction.id the same across restarts of one binding.");
                }
                try {
                    return Long.parseLong(rest.substring(0, colon));
                } catch (NumberFormatException ignored) {
                    // Falls through to the refusal.
                }
            }
        }
        throw new PravahaException(DeltaErrors.SINK_WRITE_FAILED, "'" + handle + "' is not a handle this sink wrote");
    }

    private void requireOpen() {
        if (commit == null) {
            throw new PravahaException(
                    DeltaErrors.SINK_WRITE_FAILED,
                    "sink '" + instanceName + "' is not open; call open() before writing");
        }
    }

    /** Rows this instance has applied to the table. */
    public long rowsApplied() {
        return commit == null ? 0L : commit.rowsApplied();
    }

    /** Data files this instance has rewritten to remove rows from them, in upsert mode. */
    public long filesRewritten() {
        return commit == null ? 0L : commit.filesRewritten();
    }

    /** The table's version, once open. */
    long tableVersion() {
        return commit.version();
    }

    /** The commit machinery, for the test that has to hold its conflict window open. */
    DeltaSinkCommit commits() {
        return commit;
    }

    @Override
    public HealthStatus health() {
        if (commit == null) {
            return HealthStatus.unhealthy("the Delta sink is not open");
        }
        return commit.version() < 0 ? HealthStatus.unhealthy("no Delta table at " + path) : HealthStatus.healthy();
    }

    /**
     * Lets go of the table. Nothing is pending: an open transaction's changes are already staged,
     * where a restore's {@code abortAfter} or a later commit finds them.
     */
    @Override
    public void close() {
        openLabel = -1;
        commit = null;
        engine = null;
    }
}
