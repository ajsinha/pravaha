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
 *         .start("127.0.0.1", 5432)) {
 *     // psql -h 127.0.0.1 -p 5432 -U alice -c 'SELECT * FROM user_volume'
 * }
 * }</pre>
 *
 * <p>What this server does <em>not</em> do is listed in this package's {@code package-info}, in one
 * place, with a reason each. The short version: the simple query protocol and nothing else, no TLS,
 * and no write path -- there being no write path in the engine to expose.
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
     * <p>14.0 rather than the newest: it is old enough that no client takes a path Pravaha cannot
     * follow, and new enough to be past the protocol changes that matter (SCRAM, {@code Infinity}
     * as a float text literal).
     */
    private static final String SERVER_VERSION = "14.0 (Pravaha)";

    /** How long {@link #close()} waits for in-flight sessions before giving up on them. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ViewCatalog catalog;
    private SecurityPolicy policy = SecurityPolicy.PERMISSIVE;
    private AuditSink audit = AuditSink.NONE;
    private ReadAdmission admission = ReadAdmission.UNLIMITED;
    private Duration readDeadline = Duration.ZERO;
    private TokenVerifier verifier;

    private volatile ServerSocket listener;
    private ExecutorService sessions;
    private Thread acceptor;
    private volatile boolean closing;

    public PravahaPgWireServer(ViewCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Requires every connection to present a credential this verifier accepts.
     *
     * <p>The credential arrives as the PostgreSQL password, in the clear, because this slice has no
     * TLS. See {@code PgWireConnection.authenticate} for why cleartext is the only PostgreSQL
     * authentication method whose shape matches {@link TokenVerifier}, and for the standing warning
     * that goes with it.
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
     * Binds the port and starts accepting.
     *
     * @param port the port, or 0 to let the operating system choose one -- which is what a test
     *     should do, so that two tests running at once do not fight over 5432
     */
    public PravahaPgWireServer start(String host, int port) {
        if (listener != null) {
            throw new IllegalStateException("this server is already started on port " + port());
        }
        ViewQuery queries = new ViewQuery(catalog, policy, audit, admission, readDeadline);
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
        this.acceptor = new Thread(() -> accept(queries), "pravaha-pgwire-accept");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
        return this;
    }

    private void accept(ViewQuery queries) {
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
                sessions.execute(new PgWireConnection(client, queries, verifier, SERVER_VERSION));
            } catch (IOException | RuntimeException rejected) {
                closeQuietly(client);
            }
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
