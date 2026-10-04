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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The rows already in the table when a registration starts, read so that together with the change
 * stream they are delivered exactly once -- including across a restart in the middle of the read.
 *
 * <p><strong>One snapshot, one point in the log.</strong> A temporary logical slot is created with
 * {@code EXPORT_SNAPSHOT}, and the table is read in a {@code REPEATABLE READ} transaction that imports
 * that snapshot ({@code SET TRANSACTION SNAPSHOT}). PostgreSQL guarantees the pairing: the exported
 * snapshot sees exactly the transactions that committed before the slot's consistent point {@code C},
 * and none after. So the table as this transaction reads it <em>is</em> the table at {@code C} -- no
 * watermarks, no de-duplication window, no reasoning about which commits a snapshot happened to see.
 * The temporary slot exists only to fix that pairing; the registration's own slot does the streaming,
 * and the temporary one is gone when its connection closes.
 *
 * <p><strong>The splice.</strong> The reader streams the registration's slot up to {@code C}, then
 * delivers the snapshot's rows in primary-key order, then carries on with the stream after {@code C}.
 * Every row committed before {@code C} arrives from the snapshot at {@code +1}; every change after it
 * arrives from the stream.
 *
 * <p><strong>What a checkpoint records, and why it can be resumed without the dead snapshot.</strong>
 * While the snapshot is unfinished, every position the reader reports ({@link CdcOffset}) is a pair
 * {@code (L, K)} and means one thing: the engine holds <em>the table's rows with key at or below
 * {@code K}, as of log position {@code L}</em>, and nothing else. A restart from {@code (L, K)}
 * takes a <em>new</em> exported snapshot at a new point {@code C'} and:
 *
 * <ol>
 *   <li>streams the slot from {@code L} to {@code C'}, delivering a change only when its row's key is
 *       at or below {@code K} ({@link TransactionAssembler.CatchUp}) -- which keeps the statement true,
 *       now as of {@code C'};
 *   <li>reads the rows keyed above {@code K} from the snapshot at {@code C'} -- which extends it,
 *       row by row, to larger {@code K}, still as of {@code C'};
 *   <li>and once the table is read, streams everything after {@code C'}.
 * </ol>
 *
 * A change above {@code K} is dropped in step 1 because step 2 reads the row as that change left it
 * (or does not read it, if the change deleted it). An update that moves a row's key across {@code K}
 * is two images, each judged by its own key: the old row's retraction is delivered when the old key is
 * at or below {@code K}, the new row's insertion when the new key is. Nothing about the original
 * snapshot is needed; only its effect, which {@code (L, K)} describes completely. That is what makes a
 * checkpoint mid-snapshot exact.
 *
 * <p>Every "at or below" is decided by PostgreSQL with the key's own type and collation ({@link
 * SnapshotKey}), the same comparison the chunk query uses.
 *
 * <p><strong>Bounded memory.</strong> The table is read in chunks of {@code snapshot.chunk.rows} by
 * keyset ({@code WHERE key > last ORDER BY key LIMIT n}), on a thread of its own, at most two chunks
 * ahead of the engine. The stream meanwhile holds at most {@code buffer.rows}.
 */
final class InitialSnapshot implements AutoCloseable {

    private final CdcOptions options;
    private final CdcSchema.Mapping mapping;
    private final SnapshotKey key;
    private final long consistentPoint;
    private final long eventNanos;
    private final long estimate;
    private final ConcurrentLinkedQueue<CdcTransaction.Change> rows = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong fetched = new AtomicLong();

    private @Nullable Connection connection;
    private List<String> lastFetched;
    private @Nullable Thread thread;
    private volatile boolean running = true;
    private volatile boolean exhausted;
    private volatile @Nullable PravahaException failure;

    private InitialSnapshot(
            CdcOptions options,
            CdcSchema.Mapping mapping,
            SnapshotKey key,
            Connection connection,
            long consistentPoint,
            long eventNanos,
            long estimate,
            List<String> after) {
        this.options = options;
        this.mapping = mapping;
        this.key = key;
        this.connection = connection;
        this.consistentPoint = consistentPoint;
        this.eventNanos = eventNanos;
        this.estimate = estimate;
        this.lastFetched = after;
    }

    /**
     * Pins a snapshot to a point in the log and opens a transaction reading it; nothing is read yet.
     * Blocks until PostgreSQL has created the temporary slot, which waits for every transaction
     * already running to end -- bounded by {@code start.timeout}.
     */
    static InitialSnapshot begin(CdcOptions options, CdcSchema.Mapping mapping, SnapshotKey key, List<String> after) {
        Connection reading = null;
        Connection replication = null;
        String slot = temporarySlotName(options.slot());
        boolean replicating = false;
        try {
            reading = PostgresCdcSourcePlugin.connect(options, true);
            try (Statement statement = reading.createStatement()) {
                // Held for the whole read: an engine that pauses under backpressure must not have
                // the server end the snapshot for being idle.
                statement.execute("SET idle_in_transaction_session_timeout = 0");
                statement.execute("SET statement_timeout = '10min'");
            }
            replicating = true;
            replication = CdcStream.connectForReplication(options);
            replicating = false;
            long point;
            String exported;
            try (Statement statement = replication.createStatement()) {
                statement.setQueryTimeout(
                        (int) Math.max(1, options.startTimeout().toSeconds()));
                statement.execute("CREATE_REPLICATION_SLOT " + slot + " TEMPORARY LOGICAL pgoutput EXPORT_SNAPSHOT");
                try (ResultSet created = statement.getResultSet()) {
                    created.next();
                    point = CdcOffset.parseLsn(created.getString("consistent_point"));
                    exported = created.getString("snapshot_name");
                }
            }
            try (Statement statement = reading.createStatement()) {
                statement.execute("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY");
                statement.execute("SET TRANSACTION SNAPSHOT '" + exported.replace("'", "''") + "'");
            }
            // Imported: the snapshot is this transaction's now, and the temporary slot has done its
            // one job. Closing its connection drops it.
            replication.close();
            replication = null;
            long eventNanos;
            long estimate;
            try (PreparedStatement statement = reading.prepareStatement(
                    "SELECT (extract(epoch FROM now()) * 1000000)::bigint, reltuples::bigint FROM pg_class "
                            + "WHERE oid = ?::regclass")) {
                statement.setString(1, options.quotedTable());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    eventNanos = result.getLong(1) * 1_000L;
                    estimate = result.getLong(2);
                }
            }
            return new InitialSnapshot(options, mapping, key, reading, point, eventNanos, estimate, after);
        } catch (SQLException e) {
            closeQuietly(replication);
            closeQuietly(reading);
            com.ash.messaging.pravaha.api.ConfigurationException privilege =
                    replicating ? Preflight.replicationRefused(options, e) : null;
            if (privilege != null) {
                throw privilege;
            }
            throw new PravahaException(
                    CdcErrors.SNAPSHOT_FAILED,
                    "plugin '" + options.instanceName() + "' cannot start the initial snapshot of "
                            + options.qualifiedTable() + ": " + e.getMessage() + ". The snapshot is pinned to the "
                            + "log by a temporary replication slot, which PostgreSQL creates only once every "
                            + "transaction already running has ended: a session idle in a transaction holds it up "
                            + "(SELECT pid, xact_start, state FROM pg_stat_activity WHERE backend_xid IS NOT NULL "
                            + "ORDER BY xact_start), and so does max_replication_slots with no room for one more. "
                            + "start.timeout (" + options.startTimeout().toSeconds() + "s) bounds the wait.",
                    e);
        }
    }

    /** {@code <slot>_snap_<random>}: unique per attempt, and within PostgreSQL's 63 characters. */
    static String temporarySlotName(String slot) {
        String prefix = slot.length() > 46 ? slot.substring(0, 46) : slot;
        return prefix + "_snap_"
                + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFL)
                        .toLowerCase(Locale.ROOT);
    }

    /** The log position the snapshot is exactly consistent with. */
    long consistentPoint() {
        return consistentPoint;
    }

    /** {@code pg_class.reltuples}: an estimate of the table's rows, or negative when never analysed. */
    long estimate() {
        return estimate;
    }

    /** Starts reading chunks ahead of the engine. */
    void start() {
        thread = Thread.ofPlatform()
                .daemon()
                .name("pgcdc-snapshot-" + options.slot())
                .start(this::run);
    }

    /** Waits, bounded, until the first chunk is in or the table turned out empty. */
    void awaitFirstChunk(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (rows.isEmpty() && !exhausted && failure == null && System.nanoTime() < deadline) {
            LockSupport.parkNanos(2_000_000L);
        }
    }

    /** The next row in key order, or null when none is ready. */
    CdcTransaction.Change peek() {
        return rows.peek();
    }

    void take() {
        rows.poll();
        queued.decrementAndGet();
    }

    /** True once every row has been read and taken. */
    boolean finished() {
        return exhausted && rows.isEmpty();
    }

    @Nullable
    PravahaException failure() {
        return failure;
    }

    long fetched() {
        return fetched.get();
    }

    private void run() {
        try {
            while (running && !exhausted) {
                if (queued.get() >= options.snapshotChunkRows()) {
                    LockSupport.parkNanos(2_000_000L);
                    continue;
                }
                fetchChunk();
            }
        } catch (SQLException | RuntimeException e) {
            if (running) {
                failure = new PravahaException(
                        CdcErrors.SNAPSHOT_FAILED,
                        "the initial snapshot of " + options.qualifiedTable() + " failed after " + fetched.get()
                                + " rows: " + e.getMessage() + ". Nothing was delivered past the last complete "
                                + "row; a restart from the last checkpoint resumes the snapshot exactly, from a new "
                                + "snapshot.",
                        e);
            }
        } finally {
            if (exhausted || failure != null) {
                endTransaction();
            }
        }
    }

    private void fetchChunk() throws SQLException {
        int limit = options.snapshotChunkRows();
        boolean fromStart = lastFetched.isEmpty();
        List<CdcTransaction.Change> chunk = new ArrayList<>();
        int fields = mapping.columnNames().size();
        try (PreparedStatement statement = Objects.requireNonNull(
                        connection, "the snapshot's transaction is open while it fetches")
                .prepareStatement(key.chunkSql(options, mapping.columnNames(), fromStart, limit))) {
            if (!fromStart) {
                for (int i = 0; i < key.size(); i++) {
                    statement.setString(i + 1, lastFetched.get(i));
                }
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String[] texts = new String[fields];
                    for (int field = 0; field < fields; field++) {
                        texts[field] = result.getString(field + 1);
                    }
                    List<String> rowKey = new ArrayList<>(key.size());
                    for (int part = 0; part < key.size(); part++) {
                        rowKey.add(result.getString(fields + part + 1));
                    }
                    chunk.add(CdcTransaction.Change.fromText(mapping, texts, +1, eventNanos, List.copyOf(rowKey)));
                }
            }
        }
        if (!chunk.isEmpty()) {
            lastFetched = Objects.requireNonNull(chunk.getLast().key(), "a snapshot row carries its key");
            rows.addAll(chunk);
            queued.addAndGet(chunk.size());
            fetched.addAndGet(chunk.size());
        }
        if (chunk.size() < limit) {
            exhausted = true;
        }
    }

    /** Ends the snapshot's transaction as soon as it is no longer needed: it holds back vacuum. */
    private synchronized void endTransaction() {
        closeQuietly(connection);
        connection = null;
    }

    private static void closeQuietly(@Nullable Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A read-only transaction on a connection being thrown away; nothing is owed to it.
        }
    }

    @SuppressWarnings("ReferenceEquality") // identity is the question here: a sentinel, a thread or the very object
    @Override
    public void close() {
        running = false;
        Thread current = thread;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        endTransaction();
        rows.clear();
    }

    /**
     * Filters the log before the snapshot's point by the key frontier the engine reached before a
     * restart, asking PostgreSQL on a connection of its own. Used only on the stream's thread.
     */
    static final class CatchUp implements TransactionAssembler.CatchUp, AutoCloseable {

        private final CdcOptions options;
        private final SnapshotKey key;
        private final long until;
        private final List<String> frontier;
        private @Nullable Connection connection;

        CatchUp(CdcOptions options, SnapshotKey key, long until, List<String> frontier) {
            this.options = options;
            this.key = key;
            this.until = until;
            this.frontier = List.copyOf(frontier);
        }

        @Override
        public long until() {
            return until;
        }

        @Override
        public List<String> keyColumns() {
            return key.names();
        }

        @Override
        public boolean[] atOrBelow(List<List<String>> keys) {
            if (frontier.isEmpty()) {
                return new boolean[keys.size()];
            }
            try {
                if (connection == null || connection.isClosed()) {
                    connection = PostgresCdcSourcePlugin.connect(options, false);
                }
                return key.atOrBelow(connection, keys, frontier);
            } catch (SQLException e) {
                closeQuietly(connection);
                connection = null;
                // Unchecked and not a PravahaException: the stream treats it as a dropped connection,
                // and decodes the transaction again from the last one it handed on.
                throw new IllegalStateException(
                        "cannot compare keys with the snapshot's frontier: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            closeQuietly(connection);
            connection = null;
        }
    }
}
