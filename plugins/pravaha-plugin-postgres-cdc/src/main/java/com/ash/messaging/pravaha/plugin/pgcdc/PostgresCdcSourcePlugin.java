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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * {@code postgres-cdc}: change data capture from one PostgreSQL table through native logical
 * replication (ADR-041).
 *
 * <p>The first source that is a changelog rather than an approximation of one. An insert arrives at
 * {@code +1}, a delete as the whole old row at {@code -1}, an update as both -- so a revising
 * aggregate over it walks {@code silver} from 900 to 899 when a customer moves to gold, which no
 * polling source can do. {@code emitsDeletes} and {@code emitsBeforeImage}, in the SPI since it was
 * written and declared by no connector until this one, are both true.
 *
 * <p><strong>What open refuses, each naming its fix</strong> ({@code PRV-5112}): a server before
 * PostgreSQL 14; {@code wal_level} other than {@code logical}; a table that is not {@code REPLICA
 * IDENTITY FULL} (a {@code DEFAULT} identity's before-image is the key alone, so a retraction would
 * retract nothing and the view would stay wrong for ever -- the ADR's central trap); a publication
 * that does not publish updates and deletes or does not include the table; a slot that is invalidated
 * or belongs to another output plugin or database.
 *
 * <p><strong>The guarantee is {@code EXACTLY_ONCE}</strong>, and it rests on three things. The
 * position is a commit LSN and replay from it is deterministic, so a restore re-delivers exactly what
 * the checkpoint does not hold ({@link CdcOffset}). The slot is confirmed only at positions a durable
 * checkpoint recorded ({@link PartitionReader#checkpointed}), so PostgreSQL never discards what a
 * restore needs. And a restore that asks for a position the slot has already released -- a fallback
 * to an older checkpoint, a slot recreated by hand -- is refused ({@code PRV-5115}) rather than
 * silently resumed from wherever the slot now is. What it does not survive is the slot being
 * dropped or invalidated; that is detected and refused too, never papered over.
 *
 * <p><strong>The slot is the operational story.</strong> It retains WAL until confirmed, so a node
 * that stops reading fills the database's disk. {@link #health()} reports the retained WAL and goes
 * {@code DEGRADED} past {@code slot.lag.warn.bytes}; a heartbeat keeps the slot moving on a quiet
 * table; {@code docs/operations/OPERATIONS.md} says how to drop a slot nobody will read again.
 *
 * <p>One partition, one slot, one reader at a time: a replication slot has one consumer. Not shared
 * between queries ({@code EXACTLY_ONCE} and ordered sources never are), and the slot is the
 * binding's, not the query's: one query reads a binding. A second, different query over it is
 * refused at registration ({@code PRV-8028}, through {@link #secondReaderRefusal}) rather than left
 * to wait for the slot and fail {@code PRV-5117} (CDCREPL-2); a second question about the table is a
 * second binding with a {@code slot} of its own.
 *
 * <p><strong>Rows already in the table</strong> are delivered first when {@code snapshot.mode} is
 * {@code initial}: read under a snapshot pinned to a point in the log, spliced into the stream at
 * that point, and resumable exactly from a checkpoint taken half-way through ({@link
 * InitialSnapshot}). With {@code never}, the default, a registration sees changes from the moment
 * its slot was created -- what this source did before snapshots existed, kept as the default so that
 * no existing binding starts reading whole tables, or starts being refused for a table without a
 * primary key, without having asked to.
 */
public final class PostgresCdcSourcePlugin implements StreamSourcePlugin {

    private static final String DRIVER = "org.postgresql.Driver";

    private CdcOptions options;
    private Connection control;
    private CdcSchema.Mapping mapping;
    private int tableOid;
    private SnapshotKey snapshotKey;
    private volatile HealthStatus cachedHealth;
    private volatile long cachedAt;

    /** Readers this plugin opened, so health can say when one is reconnecting or has failed. */
    private final List<PostgresCdcReader> readers = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "postgres-cdc";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.options = CdcOptions.from(context);
    }

    @Override
    public void open() {
        requireConfigured();
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            throw new PravahaException(
                    CdcErrors.CONNECT_FAILED,
                    "plugin '" + options.instanceName()
                            + "' needs the PostgreSQL JDBC driver (org.postgresql:postgresql, "
                            + "42.7 or later), and it is not on the classpath. Like the jdbc source, this plugin uses "
                            + "the driver the deployment supplies -- its replication API is what streams the slot.",
                    e);
        }
        try {
            control = connect(options, true);
            Preflight.check(control, options);
            tableOid = Preflight.tableOid(control, options);
            mapping = CdcSchema.resolve(options, CdcSchema.load(control, options));
            if (options.snapshotInitial()) {
                snapshotKey = SnapshotKey.load(control, options);
            }
            Preflight.ensurePublication(control, options);
            Preflight.ensureSlot(control, options);
        } catch (SQLException e) {
            closeControl();
            throw new PravahaException(
                    CdcErrors.CONNECT_FAILED,
                    "plugin '" + options.instanceName() + "' cannot prepare " + options.qualifiedTable() + " at "
                            + options.url() + " for capture: " + e.getMessage(),
                    e);
        } catch (RuntimeException e) {
            closeControl();
            throw e;
        }
    }

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                // The slot keeps the WAL from the last confirmed position, and only a checkpointed
                // position is ever confirmed: any offset this source handed out can be resumed.
                true,
                // One slot, one reader, commit order.
                true,
                // The point of the connector. Under REPLICA IDENTITY FULL, which open insists on, a
                // delete carries the whole old row and an update carries both.
                true,
                true,
                // Commit LSNs are deterministic positions; the reader drops what a restore already
                // holds, and a position the slot has released is refused rather than skipped past.
                DeliveryGuarantee.EXACTLY_ONCE,
                // The publication chooses the table, not the rows; a pushed filter would have to be a
                // publication row filter, which is the operator's to set (PostgreSQL 15 and later).
                EnumSet.noneOf(PushdownKind.class),
                // As fast as the log is read and a transaction commits: tens of milliseconds.
                Duration.ofMillis(50),
                // Never repeats: an update is a retraction of the old row and an insertion of the new,
                // and the initial snapshot hands over to the slot at one LSN.
                false);
    }

    /**
     * A replacement's backfill or a debug fork cannot read this binding beside the query reading it
     * (CDCREPL-1).
     *
     * <p>Both would stream the same slot, and PostgreSQL streams a slot to one connection at a time:
     * the second reader waited for the slot and failed with {@code PRV-5117} ("is active for PID").
     * Nor would it have anything to replay if it could start: "from the beginning" is the slot's
     * confirmed position, and everything before it is released. A slot of the replacement's own
     * would stream, but it starts now and holds none of the history the running version has read,
     * so its backfill would start from empty state and call itself caught up.
     */
    @Override
    public Optional<String> secondReaderRefusal() {
        requireConfigured();
        return Optional.of("streams replication slot '" + options.slot() + "', which PostgreSQL streams to one "
                + "connection at a time -- the running version holds it -- and which keeps no WAL from before "
                + "its confirmed position, so a second reader could neither start beside the running version nor "
                + "replay what it has already read. Replace it by dropping it and registering the new version "
                + "(with snapshot.mode: initial to count the rows already in the table), or register the new "
                + "version under another name on a binding with a slot of its own");
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        requireOpen();
        return List.of(mapping.schema());
    }

    @Override
    public List<SourcePartition> partitions(String stream) {
        requireConfigured();
        // One: a slot is one ordered stream with one consumer.
        return List.of(new SourcePartition(stream, 0, Map.of("slot", options.slot())));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        requireOpen();
        CdcOffset requested = CdcOffset.parse(resumeFrom);
        CdcOffset start;
        SnapshotKey key = snapshotKey;
        try {
            start = Preflight.requireResumable(control, options, requested);
            if (requested.isBeginning() && options.snapshotInitial()) {
                // From nothing: the rows already there first.
                start = start.withSnapshot(CdcOffset.Snapshot.START);
            }
            if (start.inSnapshot() && key == null) {
                // A checkpoint taken mid-snapshot is finished as a snapshot whatever snapshot.mode now
                // says: the engine holds part of the table, and only the rest of it makes that whole.
                key = SnapshotKey.load(control, options);
            }
        } catch (SQLException e) {
            throw new PravahaException(
                    CdcErrors.CONNECT_FAILED,
                    "cannot read the state of slot '" + options.slot() + "': " + e.getMessage(),
                    e);
        }
        PostgresCdcReader reader = PostgresCdcReader.open(options, mapping, tableOid, start, key);
        readers.removeIf(PostgresCdcReader::isClosed);
        readers.add(reader);
        return reader;
    }

    /**
     * The slot as PostgreSQL reports it, for an operator and for {@link #health()}.
     *
     * @return empty when the slot does not exist
     */
    public Optional<SlotStatus> slotStatus() {
        requireOpen();
        try {
            return Preflight.slotStatus(control, options);
        } catch (SQLException e) {
            throw new PravahaException(
                    CdcErrors.CONNECT_FAILED,
                    "cannot read the state of slot '" + options.slot() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * Healthy while the slot exists and retains less than {@code slot.lag.warn.bytes} of WAL.
     *
     * <p>Asked of the database rather than remembered, because the number that matters is the one on
     * the database's disk: how much WAL the slot is holding. A Pravaha node that has stopped reading
     * cannot report that, so {@code docs/operations/OPERATIONS.md} also gives the query to alert on from the
     * database side. Cached for a second; the engine polls this often.
     */
    @Override
    public HealthStatus health() {
        if (control == null) {
            return HealthStatus.unhealthy("not open");
        }
        long now = System.nanoTime();
        HealthStatus cached = cachedHealth;
        if (cached != null && now - cachedAt < 1_000_000_000L) {
            return cached;
        }
        HealthStatus fresh;
        try {
            Optional<SlotStatus> status = Preflight.slotStatus(control, options);
            if (status.isEmpty()) {
                fresh = HealthStatus.unhealthy("replication slot '" + options.slot() + "' does not exist; changes are "
                        + "not being captured");
            } else {
                SlotStatus slot = status.get();
                String detail = slot.describe();
                if ("lost".equals(slot.walStatus())) {
                    fresh = HealthStatus.unhealthy(detail + ". The slot is invalidated: the WAL it needed is gone");
                } else if (slot.retainedBytes() >= options.lagWarnBytes() || "unreserved".equals(slot.walStatus())) {
                    fresh = HealthStatus.degraded(detail + ", past slot.lag.warn.bytes=" + options.lagWarnBytes()
                            + ". PostgreSQL keeps this WAL until the slot is confirmed; check that checkpoints are "
                            + "being taken and the reader is running, or drop the slot if nothing will read it again");
                } else {
                    fresh = new HealthStatus(HealthStatus.State.HEALTHY, detail);
                }
                String trouble = readerTrouble();
                if (!trouble.isEmpty() && fresh.state() == HealthStatus.State.HEALTHY) {
                    fresh = HealthStatus.degraded(detail + ". " + trouble);
                }
                String progress = snapshotProgress();
                if (!progress.isEmpty()) {
                    fresh = new HealthStatus(fresh.state(), fresh.detail() + ". " + progress);
                }
            }
        } catch (SQLException e) {
            fresh = HealthStatus.unhealthy("cannot read the slot's state: " + e.getMessage());
        }
        cachedHealth = fresh;
        cachedAt = now;
        return fresh;
    }

    @Override
    public void close() {
        if (control != null && options != null && options.dropSlotOnClose()) {
            try {
                Preflight.dropSlot(control, options);
            } catch (SQLException e) {
                closeControl();
                throw new PravahaException(
                        CdcErrors.CONNECT_FAILED,
                        "drop.slot.on.close is set and slot '" + options.slot() + "' could not be dropped: "
                                + e.getMessage() + ". Drop it by hand -- SELECT pg_drop_replication_slot('"
                                + options.slot() + "') -- or it will retain WAL until the disk is full.",
                        e);
            }
        }
        closeControl();
    }

    /** What a reader of this plugin reports about its replication connection, or nothing. */
    private String readerTrouble() {
        for (PostgresCdcReader reader : readers) {
            if (reader.isClosed()) {
                continue;
            }
            if (reader.stream().failure() != null) {
                return "The reader has stopped: " + reader.stream().failure().getMessage();
            }
            if (!reader.stream().lastProblem().isEmpty()) {
                return "The reader is " + reader.stream().lastProblem() + " ("
                        + reader.stream().reconnects() + " reconnects so far)";
            }
        }
        return "";
    }

    /** How far an open reader's initial snapshot has got, or empty. */
    private String snapshotProgress() {
        for (PostgresCdcReader reader : readers) {
            if (!reader.isClosed() && !reader.snapshotProgress().isEmpty()) {
                return reader.snapshotProgress();
            }
        }
        return "";
    }

    /** The stream this plugin exposes, once open. */
    public StreamSchema schema() {
        requireOpen();
        return mapping.schema();
    }

    private void closeControl() {
        if (control != null) {
            try {
                control.close();
            } catch (SQLException ignored) {
                // Closing; nothing is owed to a connection that is already gone.
            } finally {
                control = null;
            }
        }
    }

    private void requireConfigured() {
        if (options == null) {
            throw new ConfigurationException(CdcErrors.BAD_CONFIGURATION, "postgres-cdc is not configured");
        }
    }

    private void requireOpen() {
        requireConfigured();
        if (control == null) {
            throw new IllegalStateException("postgres-cdc plugin '" + options.instanceName() + "' is not open");
        }
    }

    static Properties credentials(CdcOptions options) {
        Properties properties = new Properties();
        if (!options.user().isBlank()) {
            properties.setProperty("user", options.user());
        }
        if (!options.password().isBlank()) {
            properties.setProperty("password", options.password());
        }
        return properties;
    }

    /** An ordinary (not replication) connection, autocommit. */
    static Connection connect(CdcOptions options, boolean named) throws SQLException {
        Properties properties = credentials(options);
        properties.setProperty("ApplicationName", named ? "pravaha-cdc " + options.slot() : "pravaha-cdc heartbeat");
        Connection connection = DriverManager.getConnection(options.url(), properties);
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            // Health queries must not hang a caller behind a lock; nothing here takes long.
            statement.execute("SET statement_timeout = '30s'");
        }
        return connection;
    }

    /** What {@code pg_replication_slots} says about a slot. */
    public record SlotStatus(
            String slot,
            boolean active,
            long retainedBytes,
            long confirmedLagBytes,
            String confirmedFlushLsn,
            String walStatus) {

        String describe() {
            return "slot '" + slot + "' " + (active ? "active" : "INACTIVE") + ", retaining " + retainedBytes
                    + " bytes of WAL, confirmed position " + confirmedFlushLsn + " (" + confirmedLagBytes
                    + " bytes behind), wal_status " + walStatus;
        }
    }

    /** Package-private for tests: the slot's confirmed position, parsed. */
    long confirmedFlush() throws SQLException {
        try (PreparedStatement statement = control.prepareStatement(
                "SELECT confirmed_flush_lsn::text FROM pg_replication_slots WHERE slot_name = ?")) {
            statement.setString(1, options.slot());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getString(1) != null ? CdcOffset.parseLsn(rows.getString(1)) : 0L;
            }
        }
    }

    CdcOptions options() {
        return options;
    }
}
