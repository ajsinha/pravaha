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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.network.SSLMode;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Reads the binary log on a thread of its own and queues whole transactions for the reader.
 *
 * <p>The thread owns the replication connection: it registers as a replica from a transaction
 * boundary, assembles events into transactions ({@link TransactionAssembler}) and queues them, at
 * most {@code buffer.rows} rows ahead of the engine. When the connection drops it reconnects from
 * the end of the last transaction it queued -- never from the middle of one, which would lose the
 * table map its rows need -- with backoff, and gives up with {@code PRV-5157} after ten failures in
 * a row. An event the library cannot decode is refused ({@code PRV-5156}) rather than skipped, as
 * the library would otherwise do.
 */
final class BinlogStream implements AutoCloseable {

    private static final int MAX_FAILURES = 10;

    private final MySqlCdcOptions options;
    private final MySqlSchema.Mapping mapping;
    private final ArrayDeque<BinlogTransaction> queue = new ArrayDeque<>();
    private int queuedRows;
    private String resumeFile;
    private long resumePosition;
    private int resumeSkip;
    /** GTID mode: the executed set the last queued transaction left, and the partial one's GTID. */
    private String resumeGtids;

    private String resumeSkipGtid;
    private int failures;
    private volatile boolean running = true;
    private volatile boolean halted;
    private volatile boolean connected;
    /** Set once the first connection is made, and never reset: a refusal may close it at once. */
    private volatile boolean everConnected;

    private volatile PravahaException failure;
    private volatile String lastProblem = "";
    private volatile long reconnects;
    private volatile BinaryLogClient client;
    private Thread thread;

    BinlogStream(MySqlCdcOptions options, MySqlSchema.Mapping mapping, BinlogOffset start) {
        this.options = options;
        this.mapping = mapping;
        this.resumeFile = start.file();
        this.resumePosition = start.position();
        this.resumeSkip = (int) Math.min(Integer.MAX_VALUE, start.partial());
        this.resumeGtids = start.gtidSet();
        this.resumeSkipGtid = start.partialGtid();
    }

    void start() {
        thread = Thread.ofPlatform()
                .daemon()
                .name("pravaha-mysql-cdc-" + options.instanceName())
                .start(this::run);
    }

    /** Waits, bounded, for the replica connection; refuses when it does not come. */
    void awaitConnected(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!everConnected && failure == null && System.nanoTime() < deadline) {
            sleep(20);
        }
        if (failure != null) {
            throw failure;
        }
        if (!everConnected) {
            throw new PravahaException(
                    MySqlCdcErrors.CONNECT_FAILED,
                    "plugin '" + options.instanceName() + "' could not open a binlog connection to " + options.host()
                            + ":" + options.port() + " within start.timeout (" + timeout.toSeconds() + "s)"
                            + (lastProblem.isEmpty() ? "" : ": " + lastProblem));
        }
    }

    synchronized BinlogTransaction peek() {
        return queue.peekFirst();
    }

    /** Removes {@code head}, when it is still the head, and lets the reading thread go on. */
    synchronized void remove(BinlogTransaction head) {
        if (queue.peekFirst() == head) {
            queue.pollFirst();
            queuedRows -= head.size();
            notifyAll();
        }
    }

    PravahaException failure() {
        return failure;
    }

    String lastProblem() {
        return lastProblem;
    }

    long reconnects() {
        return reconnects;
    }

    private void run() {
        try {
            readUntilClosed();
        } catch (RuntimeException | LinkageError e) {
            failure = new PravahaException(
                    MySqlCdcErrors.STREAM_FAILED,
                    "plugin '" + options.instanceName() + "': the binlog reader stopped: " + e,
                    e);
        }
    }

    private void readUntilClosed() {
        long backoff = 500;
        while (running && !halted) {
            BinaryLogClient current = newClient();
            client = current;
            try {
                current.connect();
            } catch (IOException | RuntimeException e) {
                lastProblem = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            connected = false;
            if (!running || halted) {
                break;
            }
            synchronized (this) {
                failures++;
                if (failures > MAX_FAILURES) {
                    failure = new PravahaException(
                            MySqlCdcErrors.STREAM_FAILED,
                            "plugin '" + options.instanceName() + "': the binlog connection to " + options.host() + ":"
                                    + options.port() + " failed " + MAX_FAILURES + " times in a row at "
                                    + resumeFile + ":" + resumePosition + "; last: " + lastProblem
                                    + ". If the file was purged, the checkpoint cannot be resumed exactly.");
                    break;
                }
            }
            reconnects++;
            sleep(backoff);
            backoff = Math.min(backoff * 2, 5_000);
        }
    }

    private synchronized BinaryLogClient newClient() {
        BinaryLogClient created =
                new BinaryLogClient(options.host(), options.port(), options.user(), options.password());
        created.setServerId(options.serverId());
        created.setBinlogFilename(resumeFile);
        created.setBinlogPosition(resumePosition);
        if (resumeGtids != null) {
            // Every transaction not in the set, from whichever file holds the first of them: the
            // position that survives a failover (MYC-2). Never gtid_purged as a fallback.
            created.setGtidSet(resumeGtids);
            created.setGtidSetFallbackToPurged(false);
        }
        created.setKeepAlive(false);
        created.setBlocking(true);
        created.setSSLMode(SSLMode.DISABLED);
        created.setConnectTimeout(Math.max(1_000L, options.startTimeout().toMillis()));
        if (!options.heartbeat().isZero()) {
            created.setHeartbeatInterval(options.heartbeat().toMillis());
        }
        EventDeserializer deserializer = new EventDeserializer();
        deserializer.setCompatibilityMode(
                EventDeserializer.CompatibilityMode.DATE_AND_TIME_AS_LONG_MICRO,
                EventDeserializer.CompatibilityMode.CHAR_AND_BINARY_AS_BYTE_ARRAY);
        created.setEventDeserializer(deserializer);
        TransactionAssembler assembler = new TransactionAssembler(
                options, mapping, resumeFile, resumeSkip, resumeGtids, resumeSkip > 0 ? resumeSkipGtid : null);
        created.registerEventListener(event -> {
            if (!halted) {
                BinlogTransaction done = assembler.accept(event);
                if (done != null) {
                    enqueue(done);
                }
            }
        });
        created.registerLifecycleListener(new BinaryLogClient.AbstractLifecycleListener() {
            @Override
            public void onConnect(BinaryLogClient connectedClient) {
                connected = true;
                everConnected = true;
                lastProblem = "";
            }

            @Override
            public void onCommunicationFailure(BinaryLogClient failed, Exception e) {
                lastProblem = "reconnecting after: " + e.getMessage();
            }

            @Override
            public void onEventDeserializationFailure(BinaryLogClient failed, Exception e) {
                String at;
                synchronized (BinlogStream.this) {
                    at = resumeFile + ":" + resumePosition;
                }
                enqueue(BinlogTransaction.refused(
                        resumeFile,
                        resumePosition,
                        new PravahaException(
                                MySqlCdcErrors.UNREPRESENTABLE_CHANGE,
                                "an event in the transaction after " + at + " cannot be decoded: " + e.getMessage()
                                        + ". Everything before it was delivered.")));
            }
        });
        return created;
    }

    private synchronized void enqueue(BinlogTransaction transaction) {
        if (halted) {
            return;
        }
        while (running && !queue.isEmpty() && queuedRows + transaction.size() > options.bufferRows()) {
            try {
                wait(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!running) {
            return;
        }
        boolean marker =
                transaction.size() == 0 && transaction.failure() == null && transaction.alreadyDelivered() == 0;
        BinlogTransaction last = queue.peekLast();
        if (marker && last != null && last.size() == 0 && last.failure() == null && queue.size() > 1) {
            // Consecutive markers: only the newest position matters. The head is left alone, as the
            // reader may be looking at it.
            queue.pollLast();
        }
        queue.addLast(transaction);
        queuedRows += transaction.size();
        if (transaction.failure() == null) {
            resumeFile = transaction.file();
            resumePosition = transaction.endPosition();
            if (transaction.executedGtids() != null) {
                resumeGtids = transaction.executedGtids();
            }
            if (!marker) {
                resumeSkip = 0;
                resumeSkipGtid = null;
            }
        }
        failures = 0;
        if (transaction.failure() != null) {
            halted = true;
            disconnectQuietly();
        }
    }

    private void disconnectQuietly() {
        BinaryLogClient current = client;
        if (current != null) {
            Thread.ofVirtual().start(() -> {
                try {
                    current.disconnect();
                } catch (IOException ignored) {
                    // Stopping; the connection is being abandoned either way.
                }
            });
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        synchronized (this) {
            notifyAll();
        }
        BinaryLogClient current = client;
        if (current != null) {
            try {
                current.disconnect();
            } catch (IOException ignored) {
                // Closing.
            }
        }
        if (thread != null) {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
