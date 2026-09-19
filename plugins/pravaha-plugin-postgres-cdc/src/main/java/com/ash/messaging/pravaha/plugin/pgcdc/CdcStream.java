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

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The replication connection, read on a thread of its own into a bounded queue of whole
 * transactions.
 *
 * <p><strong>Why a thread.</strong> {@code PartitionReader.poll} must not block, and a replication
 * connection must keep talking even when the engine is not asking for rows: PostgreSQL ends a
 * walsender that has not heard from its client for {@code wal_sender_timeout}, and a backpressured
 * query can go that long without polling. So this thread owns the socket -- it decodes, assembles
 * transactions, confirms positions, and writes heartbeats -- and {@code poll} only ever drains the
 * queue. Every call on {@link PGReplicationStream} happens on this thread; the driver's stream is not
 * safe to share.
 *
 * <p><strong>Confirming.</strong> {@link #requestAck} records the newest position a durable
 * checkpoint holds; this thread sets it as both the flushed and the applied LSN and reports it. That
 * is the slot's {@code confirmed_flush_lsn}, the point before which PostgreSQL may discard WAL. It is
 * never set from what was merely received or delivered: the driver's automatic flush is turned off.
 *
 * <p><strong>Heartbeats.</strong> Every {@code heartbeat.interval} this writes a non-transactional
 * logical message ({@code pg_logical_emit_message}) into the WAL. It comes back through the slot
 * behind every transaction committed before it, so when it arrives nothing before it is undelivered,
 * and its LSN is a position the reader can move to -- and the engine checkpoint, and the slot confirm
 * -- even when the captured table has not changed for a week. Without it, a quiet table on a busy
 * database holds the slot still while WAL piles up behind it.
 *
 * <p><strong>A dropped connection is reconnected</strong>, from the end of the last transaction
 * queued, with backoff and for as long as it takes; errors that no retry can fix -- the slot is gone,
 * invalidated, or the role lost its privileges -- end the stream, and the reader reports them.
 */
final class CdcStream implements AutoCloseable {

    /** SQLSTATEs that another attempt cannot fix. */
    private static final Set<String> PERMANENT = Set.of("42704", "55000", "42501", "28000", "28P01", "3D000");

    private final CdcOptions options;
    private final TransactionAssembler assembler;
    private final ConcurrentLinkedQueue<CdcTransaction> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedRows = new AtomicInteger();
    private final AtomicLong ackRequested = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();

    private volatile boolean running = true;
    private volatile PravahaException failure;
    private volatile String syncSeen = "";
    private volatile String lastProblem = "";
    private volatile long confirmed;
    private volatile boolean markerRequested;

    private Connection replication;
    private PGReplicationStream stream;
    private Connection control;
    private long acked;
    private long heartbeats;
    private Thread thread;

    CdcStream(CdcOptions options, CdcSchema.Mapping mapping, int tableOid, CdcOffset resume) {
        this(options, mapping, tableOid, resume, null);
    }

    /** @param catchUp the key frontier an unfinished initial snapshot filters the log by, or null */
    CdcStream(
            CdcOptions options,
            CdcSchema.Mapping mapping,
            int tableOid,
            CdcOffset resume,
            TransactionAssembler.CatchUp catchUp) {
        this.options = options;
        this.assembler =
                new TransactionAssembler(options, mapping, tableOid, resume, this::enqueue, this::sawMessage, catchUp);
    }

    /** Asks for a position marker now rather than at the next heartbeat. */
    void requestMarker() {
        markerRequested = true;
    }

    /** Connects and starts reading. Throws when the stream cannot be started at all. */
    void start() {
        try {
            open(assembler.resumeLsn(), Duration.ofSeconds(15));
        } catch (SQLException e) {
            closeQuietly();
            throw new PravahaException(
                    CdcErrors.STREAM_FAILED,
                    "cannot start streaming slot '" + options.slot() + "': " + e.getMessage(),
                    e);
        }
        thread = Thread.ofPlatform().daemon().name("pgcdc-" + options.slot()).start(this::run);
    }

    /**
     * Waits until everything in the WAL when this was called has been read, the buffer is full, or
     * {@code timeout} passes. A reader opens caught up with the log as it stood, so its first polls
     * see what was already there rather than whatever the socket happened to have delivered.
     */
    void awaitCaughtUp(Duration timeout) {
        String nonce = options.slot() + ":sync:" + UUID.randomUUID();
        try {
            emit(nonce);
        } catch (SQLException e) {
            lastProblem = "could not write the start marker: " + e.getMessage();
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!nonce.equals(syncSeen)
                && failure == null
                && queuedRows.get() < options.bufferRows()
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(2_000_000L);
        }
    }

    CdcTransaction peek() {
        return queue.peek();
    }

    void remove(int rows) {
        queue.poll();
        queuedRows.addAndGet(-rows);
    }

    void consumed(int rows) {
        queuedRows.addAndGet(-rows);
    }

    PravahaException failure() {
        return failure;
    }

    /** Confirms {@code lsn} to the slot, on this stream's thread, once and only ever forwards. */
    void requestAck(long lsn) {
        ackRequested.accumulateAndGet(lsn, Math::max);
    }

    /** The newest LSN confirmed to the slot on this connection. */
    long confirmed() {
        return confirmed;
    }

    long carriedForward() {
        return assembler.carriedForward();
    }

    long reconnects() {
        return reconnects.get();
    }

    String lastProblem() {
        return lastProblem;
    }

    private void enqueue(CdcTransaction transaction) {
        queue.add(transaction);
        queuedRows.addAndGet(transaction.size());
    }

    private void sawMessage(String content) {
        syncSeen = content;
    }

    private void run() {
        long statusNanos = options.statusInterval().toNanos();
        long heartbeatNanos = options.heartbeat().toNanos();
        long lastHeartbeat = System.nanoTime();
        long lastStatus = System.nanoTime();
        long backoffMillis = 500;
        while (running) {
            try {
                long now = System.nanoTime();
                acknowledge();
                if ((heartbeatNanos > 0 && now - lastHeartbeat >= heartbeatNanos) || markerRequested) {
                    markerRequested = false;
                    lastHeartbeat = now;
                    heartbeat();
                }
                if (queuedRows.get() >= options.bufferRows()) {
                    // Full: stop reading, keep talking, or the server hangs up on a quiet client.
                    if (now - lastStatus >= statusNanos / 2) {
                        stream.forceUpdateStatus();
                        lastStatus = now;
                    }
                    LockSupport.parkNanos(5_000_000L);
                    continue;
                }
                ByteBuffer message = stream.readPending();
                if (message == null) {
                    LockSupport.parkNanos(5_000_000L);
                    continue;
                }
                assembler.accept(PgOutput.decode(message));
                backoffMillis = 500;
            } catch (PravahaException e) {
                failure = e;
                break;
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    break;
                }
                if (e instanceof SQLException sql && PERMANENT.contains(sql.getSQLState())) {
                    failure = new PravahaException(
                            CdcErrors.STREAM_FAILED,
                            "the replication stream of slot '" + options.slot() + "' failed and cannot be resumed: "
                                    + e.getMessage() + ". If the slot was dropped or invalidated (pg_replication_slots."
                                    + "wal_status = 'lost'), the WAL the engine needs is gone: drop the registration's "
                                    + "checkpoints and register the query again.",
                            e);
                    break;
                }
                lastProblem = "reconnecting after: " + e.getMessage();
                reconnects.incrementAndGet();
                closeQuietly();
                assembler.connectionLost();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(backoffMillis));
                backoffMillis = Math.min(backoffMillis * 2, 30_000);
                try {
                    open(assembler.resumeLsn(), Duration.ZERO);
                    lastProblem = "";
                } catch (SQLException again) {
                    lastProblem = "reconnecting after: " + again.getMessage();
                }
            }
        }
        closeQuietly();
    }

    private void acknowledge() throws SQLException {
        long wanted = ackRequested.get();
        if (wanted > acked && stream != null) {
            LogSequenceNumber lsn = LogSequenceNumber.valueOf(wanted);
            stream.setFlushedLSN(lsn);
            stream.setAppliedLSN(lsn);
            stream.forceUpdateStatus();
            acked = wanted;
            confirmed = wanted;
        }
    }

    private void heartbeat() {
        try {
            emit(options.slot() + ":heartbeat:" + (++heartbeats));
        } catch (SQLException e) {
            // Not fatal: the stream is fine, only the slot's advance on a quiet table is not.
            lastProblem = "heartbeat failed: " + e.getMessage();
            closeControl();
        }
    }

    /** Writes a logical message this stream will read back; synchronised with the heartbeat. */
    private synchronized void emit(String content) throws SQLException {
        if (control == null || control.isClosed()) {
            control = PostgresCdcSourcePlugin.connect(options, false);
        }
        try (PreparedStatement statement = control.prepareStatement("SELECT pg_logical_emit_message(false, ?, ?)")) {
            statement.setString(1, CdcOptions.MESSAGE_PREFIX);
            statement.setString(2, content);
            statement.execute();
        }
    }

    private void open(long startLsn, Duration waitForSlot) throws SQLException {
        long deadline = System.nanoTime() + waitForSlot.toNanos();
        while (true) {
            try {
                replication = connectForReplication(options);
                ChainedLogicalStreamBuilder builder = replication
                        .unwrap(PGConnection.class)
                        .getReplicationAPI()
                        .replicationStream()
                        .logical()
                        .withSlotName(options.slot())
                        .withSlotOption("proto_version", 1)
                        .withSlotOption("publication_names", options.publication())
                        .withSlotOption("messages", true)
                        .withStatusInterval((int) options.statusInterval().toMillis(), TimeUnit.MILLISECONDS)
                        // Never confirm what was merely received: only a checkpointed position.
                        .withAutomaticFlush(false);
                if (startLsn != 0L) {
                    builder = builder.withStartPosition(LogSequenceNumber.valueOf(startLsn));
                }
                stream = builder.start();
                acked = 0L;
                return;
            } catch (SQLException e) {
                closeQuietly();
                // 55006: the slot is still held by a walsender that has not noticed its client left
                // -- the previous reader of this slot, a moment ago. It lets go within a second or two.
                if (!"55006".equals(e.getSQLState()) || System.nanoTime() > deadline) {
                    throw e;
                }
                LockSupport.parkNanos(200_000_000L);
            }
        }
    }

    static Connection connectForReplication(CdcOptions options) throws SQLException {
        Properties properties = PostgresCdcSourcePlugin.credentials(options);
        PGProperty.REPLICATION.set(properties, "database");
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, "9.4");
        PGProperty.PREFER_QUERY_MODE.set(properties, "simple");
        return DriverManager.getConnection(options.url(), properties);
    }

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
        } else {
            closeQuietly();
        }
        closeControl();
    }

    private void closeQuietly() {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (SQLException | RuntimeException ignored) {
            // Closing a stream whose socket is already gone; nothing is owed to it.
        }
        stream = null;
        try {
            if (replication != null) {
                replication.close();
            }
        } catch (SQLException | RuntimeException ignored) {
            // As above.
        }
        replication = null;
    }

    private synchronized void closeControl() {
        try {
            if (control != null) {
                control.close();
            }
        } catch (SQLException ignored) {
            // A broken heartbeat connection is replaced on the next heartbeat.
        }
        control = null;
    }
}
