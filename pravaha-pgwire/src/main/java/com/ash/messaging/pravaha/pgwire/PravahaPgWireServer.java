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
package com.ash.messaging.pravaha.pgwire;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.serving.ReadAdmission;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * A PostgreSQL wire protocol server over Pravaha's views. Read half only.
 *
 * <p>Gate P6 is "drive a real SQL client", and Arrow Flight SQL has never passed it -- not because
 * it is a bad protocol but because almost nobody has a client for it installed. Everybody has
 * {@code psql}. This is the same engine, the same planner and the same authorization, behind a
 * socket that {@code psql}, DBeaver, Grafana, every ORM and every dbt project can already open.
 *
 * <p>Built the same way as {@code PravahaFlightServer} and on purpose: a catalogue, then fluent
 * calls that add a verifier, a policy and admission, then {@code start}. Two gateways that are
 * configured differently get configured differently by mistake.
 *
 * <pre>{@code
 * try (PravahaPgWireServer server = new PravahaPgWireServer(catalog)
 *         .authenticatedBy(verifier)
 *         .authorizedBy(policy, audit)
 *         .admitting(admission, Duration.ofSeconds(30))
 *         .encryptedWith(certificateChain, privateKey)
 *         .start("127.0.0.1", 5432)) {
 *     // psql "host=127.0.0.1 port=5432 user=alice sslmode=require" -c 'SELECT * FROM user_volume'
 * }
 * }</pre>
 *
 * <p>What this server does <em>not</em> do is listed in this package's {@code package-info}, in one
 * place, with a reason each. The short version: no write path -- there being no write path in the
 * engine to expose -- and no cursors, {@code COPY} or binary wire format. Neither TLS nor the
 * extended query protocol is one of the absences any more; see {@link #encryptedWith}/{@link PgTls}
 * and {@link PgExtendedSession} respectively.
 */
public final class PravahaPgWireServer implements AutoCloseable {

    /**
     * The version this server claims to be.
     *
     * <p>A real-looking PostgreSQL version because clients gate features on it -- a driver that
     * reads "0.1.0-SNAPSHOT" here takes a compatibility path written for PostgreSQL 6, or refuses
     * to connect. The suffix says what it actually is, in the same string, so that a person reading
     * {@code SHOW server_version} or a connection banner is not misled about what they are talking
     * to.
     *
     * <p><strong>9.4.26, not the newest, and lower than slice 1's 14.0 -- deliberately, for slice
     * 2.</strong> {@code psql}'s {@code \d} and {@code \dt} do not send one fixed query: {@code
     * libpq} reads this string back as {@code server_version} and {@code psql} builds its {@code
     * pg_catalog} introspection SQL to match what a server of that version actually has -- a real
     * {@code psql} pointed at a real PostgreSQL 9.4 asks a materially smaller question than the same
     * {@code psql} pointed at a real PostgreSQL 16. Row-level security (9.5), declarative
     * partitioning, statistics objects and logical replication publications (all 10) each add a join
     * or a whole extra round-trip to {@code \d}'s query, none of which this engine's views have
     * anything to say about -- a partition never exists, a publication never exists, so answering
     * would mean inventing empty machinery for features Pravaha does not have, rather than reporting
     * that a version old enough not to ask about them is the honest match. 9.4 is the newest version
     * that still predates every one of those: {@link PgCatalogShim} implements exactly the {@code
     * pg_catalog} query shapes a 9.4-era client sends, no more, and if a future slice teaches it the
     * newer shapes too, this constant can move up to meet it. The SCRAM concern the slice 1 comment
     * raised does not bind this choice either way: this server only ever offers cleartext or {@code
     * AuthenticationOk} (see {@code PgWireConnection.authenticate}), so a client never attempts
     * SCRAM regardless of the version announced here.
     */
    private static final String SERVER_VERSION = "9.4.26 (Pravaha)";

    /** How long {@link #close()} waits for in-flight sessions before giving up on them. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ViewCatalog catalog;
    private SecurityPolicy policy = SecurityPolicy.PERMISSIVE;
    private AuditSink audit = AuditSink.NONE;
    private ReadAdmission admission = ReadAdmission.UNLIMITED;
    private Duration readDeadline = Duration.ZERO;
    private TokenVerifier verifier;
    private volatile PgTls tls;
    private PgWireLimits limits = PgWireLimits.DEFAULTS;
    private volatile PgConnections connections;

    /**
     * Stable object identifiers for {@code pg_catalog.pg_class}, minted once per view and never
     * reused for the life of this server.
     *
     * <p>Held here, not per-connection: {@code psql}'s {@code \d} resolves a name to an oid in one
     * query and looks the oid back up in the next, on whatever connection it happens to be. A
     * registry that started over per session would hand back a different oid for the second query
     * than the first returned, and {@code psql} would report "not found".
     */
    private final PgOidRegistry oids = new PgOidRegistry();

    private volatile ServerSocket listener;
    private ExecutorService sessions;
    private ScheduledExecutorService deadlines;
    private Thread acceptor;
    private volatile boolean closing;

    public PravahaPgWireServer(ViewCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Requires every connection to present a credential this verifier accepts.
     *
     * <p>The credential arrives as the PostgreSQL password -- in the clear unless {@link
     * #encryptedWith} is also called, in which case it crosses the wire inside the TLS session this
     * server negotiated before authentication ran. See {@code PgWireConnection.authenticate} for why
     * cleartext is the only PostgreSQL authentication method whose shape matches {@link
     * TokenVerifier}, and see {@link #encryptedWith} for the case where "in the clear" still applies:
     * a deployment with password authentication configured and no certificate.
     *
     * <p>Without this call the server does not authenticate and every session runs as {@code
     * Principal.ANONYMOUS}. That is for an engine already behind its own boundary, and it is a
     * deliberate two-state choice rather than a default that quietly downgrades a configured one.
     */
    public PravahaPgWireServer authenticatedBy(TokenVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        return this;
    }

    /**
     * Serves over TLS, using a PEM certificate chain and its unencrypted PKCS#8 private key.
     *
     * <p>Without this call an {@code SSLRequest} is declined with {@code 'N'} exactly as slice 1
     * did, and every credential and every row crosses the wire in clear text -- acceptable on
     * loopback and nowhere else. With it, {@code PgWireConnection} answers {@code 'S'} instead and
     * layers TLS onto the same socket before authentication runs, so a cleartext {@code
     * PasswordMessage} never leaves this process unencrypted.
     *
     * <p>Loaded and validated immediately, not deferred to {@link #start}: a certificate this server
     * cannot read is a configuration mistake worth finding at the call that names it, not three
     * sentences later at whatever line calls {@code start}, and not at the first client connection
     * either -- see {@link PgTls#load} and, for why half a pair is refused rather than silently
     * ignored, this class's own package's CFG-6 note.
     *
     * @throws PravahaException {@link PgWireErrors#TLS_UNREADABLE} if either file is missing, only
     *     one of the pair is given, or the key is not an unencrypted PKCS#8 PEM key
     */
    public PravahaPgWireServer encryptedWith(java.io.File certificateChain, java.io.File privateKey) {
        this.tls = PgTls.load(certificateChain, privateKey);
        return this;
    }

    /** Whether this server is serving over TLS. */
    public boolean isEncrypted() {
        return tls != null;
    }

    /**
     * The policy every query is authorized against, and where its decisions are recorded.
     *
     * <p>Handed straight to {@link ViewQuery}, which is where enforcement happens. Nothing in this
     * module reads the policy itself; if it did, there would be two places a rule could be changed
     * and one of them would be missed.
     */
    public PravahaPgWireServer authorizedBy(SecurityPolicy policy, AuditSink audit) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
        return this;
    }

    /**
     * Bounds how many reads run at once and how long one may take.
     *
     * <p>The same {@link ReadAdmission} instance should be shared with every other gateway on the
     * same engine. A pgwire port with its own separate budget would not be a limit -- it would be a
     * way round the one Flight is subject to.
     */
    public PravahaPgWireServer admitting(ReadAdmission admission, Duration readDeadline) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.readDeadline = Objects.requireNonNull(readDeadline, "readDeadline");
        return this;
    }

    /**
     * Bounds how many connections this server holds, how long a handshake may take, how large a
     * message may be and how long a signed-in connection may sit idle (PGPREAUTH-1). Without this
     * call {@link PgWireLimits#DEFAULTS} applies -- there is no unlimited setting.
     */
    public PravahaPgWireServer limitedBy(PgWireLimits limits) {
        if (listener != null) {
            throw new IllegalStateException("limits are set before start, not on a server already accepting");
        }
        this.limits = Objects.requireNonNull(limits, "limits");
        return this;
    }

    /** The limits this server enforces. */
    public PgWireLimits limits() {
        return limits;
    }

    /** Connections open now, signed in or not; for health and tests. */
    public int openConnections() {
        PgConnections counted = connections;
        return counted == null ? 0 : counted.open();
    }

    /**
     * Binds the port and starts accepting.
     *
     * @param port the port, or 0 to let the operating system choose one -- which is what a test
     *     should do, so that two tests running at once do not fight over 5432
     */
    public PravahaPgWireServer start(String host, int port) {
        if (listener != null) {
            throw new IllegalStateException("this server is already started on port " + port());
        }
        // AVG of an integer as numeric, as a PostgreSQL client expects (AVGINT-1).
        ViewQuery queries = new ViewQuery(catalog, policy, audit, admission, readDeadline).withNumericAverages();
        PgCatalogShim catalogShim = new PgCatalogShim(catalog, policy, oids, SERVER_VERSION);
        try {
            ServerSocket bound = new ServerSocket();
            // Reuse, so that a restart does not have to wait out TIME_WAIT on a fixed port -- the
            // difference between an operator restarting a server and an operator waiting two
            // minutes wondering whether it is broken.
            bound.setReuseAddress(true);
            bound.bind(new InetSocketAddress(InetAddress.getByName(host), port));
            this.listener = bound;
        } catch (IOException e) {
            throw new PravahaException(
                    PgWireErrors.PROTOCOL_VIOLATION,
                    "could not listen on " + host + ":" + port + " -- " + e.getMessage(),
                    e);
        }
        this.sessions = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-pgwire-session");
            // Daemon, so an embedded engine that forgets to close this server does not keep a JVM
            // alive on an idle psql window.
            thread.setDaemon(true);
            return thread;
        });
        this.deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-pgwire-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.connections = new PgConnections(limits);
        this.acceptor = new Thread(() -> accept(queries, catalogShim), "pravaha-pgwire-accept");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
        return this;
    }

    private void accept(ViewQuery queries, PgCatalogShim catalogShim) {
        while (!closing) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException stopped) {
                // Either close() shut the listener, or the accept failed. Both end the loop; the
                // former is normal and the latter has already closed the socket underneath us.
                return;
            }
            try {
                // A query result is written as one small message per row, and Nagle would hold the
                // last partial segment of a small result set waiting for more -- so a two-row
                // answer arrives 40ms after it was ready. That delay is indistinguishable from a
                // slow engine to whoever is watching the prompt.
                client.setTcpNoDelay(true);
            } catch (IOException broken) {
                closeQuietly(client);
                continue;
            }
            PgConnections.Ticket ticket;
            try {
                // Counted here, on the acceptor, before a thread is spent: a connection past a limit
                // costs this server one small write and a close, whoever is sending them.
                ticket = connections.admit();
            } catch (PravahaException full) {
                refuseAtOnce(client, full);
                continue;
            }
            try {
                sessions.execute(new PgWireConnection(
                        client, queries, catalogShim, verifier, tls, SERVER_VERSION, limits, ticket, deadlines));
            } catch (RuntimeException rejected) {
                ticket.close();
                closeQuietly(client);
            }
        }
    }

    /**
     * Answers {@code FATAL 53300} without reading anything, and closes.
     *
     * <p>Before the startup packet, deliberately: reading it would mean a thread, or a wait, for a
     * connection this server has already decided not to hold. libpq, pgjdbc and Npgsql each read an
     * {@code ErrorResponse} that arrives in place of the answer to {@code SSLRequest} or to the
     * startup packet and report its message, as they do PostgreSQL's own "too many clients".
     */
    private void refuseAtOnce(Socket client, PravahaException full) {
        try {
            PgBackend backend = new PgBackend(new java.io.BufferedOutputStream(client.getOutputStream()));
            backend.errorResponse(
                    "FATAL",
                    PgWireErrors.sqlStateFor(full),
                    full.getMessage(),
                    full.errorCode().name());
            // Half-closed now and fully closed a moment later, off this thread: closing at once with
            // the client's startup packet still unread would reset the connection, and a reset can
            // discard the ErrorResponse before the client reads it -- leaving "connection reset"
            // where "too many clients" should be.
            client.shutdownOutput();
            deadlines.schedule(() -> closeQuietly(client), 1, TimeUnit.SECONDS);
        } catch (IOException | RuntimeException gone) {
            // The peer has already gone, or this server is closing; nothing is owed to it.
            closeQuietly(client);
        }
    }

    /** The port actually bound, which is the one to connect to when {@code start} was given 0. */
    public int port() {
        ServerSocket bound = listener;
        if (bound == null) {
            throw new IllegalStateException("this server has not been started");
        }
        return bound.getLocalPort();
    }

    /** The views this server serves. */
    public ViewCatalog catalog() {
        return catalog;
    }

    /** Whether a credential is required. False means every session runs as anonymous. */
    public boolean isAuthenticating() {
        return verifier != null;
    }

    @Override
    public void close() {
        closing = true;
        ServerSocket bound = listener;
        if (bound != null) {
            closeQuietly(bound);
        }
        if (sessions != null) {
            // Interrupted rather than waited out politely: a session thread is blocked reading from
            // a socket that may never say anything again, and an orderly shutdown that waits for an
            // idle psql window is a shutdown that does not happen.
            sessions.shutdownNow();
            try {
                sessions.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (deadlines != null) {
            deadlines.shutdownNow();
        }
        if (acceptor != null) {
            try {
                acceptor.join(SHUTDOWN_GRACE.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        listener = null;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing a socket that is already broken is the normal case, not an event.
        }
    }
}
