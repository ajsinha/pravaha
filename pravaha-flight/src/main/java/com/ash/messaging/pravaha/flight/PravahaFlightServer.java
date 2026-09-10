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
package com.ash.messaging.pravaha.flight;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.serving.ViewCatalog;

/**
 * A Flight SQL endpoint in front of a set of maintained views.
 *
 * <p>Deliberately plain Java with no Spring: an embedded deployment starts this inside somebody
 * else's process (ADR-019), and a gateway that dragged in a framework would forfeit that.
 *
 * <p><strong>Arrow needs JVM flags.</strong> It allocates off-heap through {@code java.nio}
 * internals the module system closes by default, so a process hosting this needs:
 *
 * <pre>
 * --add-opens=java.base/java.nio=ALL-UNNAMED
 * --add-opens=java.base/java.lang=ALL-UNNAMED
 * </pre>
 *
 * <p>On Java 24 and later, {@code --sun-misc-unsafe-memory-access=allow} is needed as well. It is
 * <em>not</em> a valid option on 21 -- the JVM refuses to start rather than ignoring it -- so it
 * cannot simply be added to every command line.
 *
 * <p>Without them the failure is an {@code InaccessibleObjectException} at class-load that mentions
 * neither Arrow nor Flight, so it is stated here rather than left to be rediscovered.
 */
public final class PravahaFlightServer implements AutoCloseable {

    private final ViewCatalog catalog;
    private final BufferAllocator allocator;
    private TokenVerifier verifier;
    private SecurityPolicy policy = SecurityPolicy.PERMISSIVE;
    private AuditSink audit = AuditSink.NONE;
    private final AtomicReference<FlightServer> server = new AtomicReference<>();
    private final boolean ownsAllocator;
    private Location location;

    /** A server on its own allocator, which is what a standalone process wants. */
    public PravahaFlightServer(ViewCatalog catalog) {
        this(catalog, new RootAllocator(Long.MAX_VALUE), true);
    }

    /**
     * @param allocator shared with the host when embedded, so Arrow's memory shows up in the host's
     *     accounting rather than in a second pool nobody is watching
     */
    public PravahaFlightServer(ViewCatalog catalog, BufferAllocator allocator) {
        this(catalog, allocator, false);
    }

    private PravahaFlightServer(ViewCatalog catalog, BufferAllocator allocator, boolean ownsAllocator) {
        this.catalog = catalog;
        this.allocator = allocator;
        this.ownsAllocator = ownsAllocator;
    }

    /**
     * Requires every call to present a bearer credential this verifier accepts.
     *
     * <p>Must be called before {@link #start}. A server started without it accepts every call as
     * {@link com.ash.messaging.pravaha.security.Principal#ANONYMOUS}, which is correct for an
     * engine embedded inside a process that has already authenticated its caller and wrong for
     * anything listening on a network anybody else can reach.
     */
    public PravahaFlightServer authenticatedBy(TokenVerifier verifier) {
        requireNotStarted("authentication");
        this.verifier = java.util.Objects.requireNonNull(verifier, "verifier");
        return this;
    }

    /** Enforces {@code policy} on every read, recording each decision in {@code audit}. */
    public PravahaFlightServer authorizedBy(SecurityPolicy policy, AuditSink audit) {
        requireNotStarted("authorization");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
        return this;
    }

    private void requireNotStarted(String what) {
        if (server.get() != null) {
            throw new IllegalStateException(
                    "cannot configure " + what + " on a server that is already accepting calls; "
                            + "the calls in flight would be the ones running under the old rules");
        }
    }

    /**
     * Binds and starts.
     *
     * @param port the port to listen on, or zero to let the operating system choose -- which is what
     *     a test wants, and the reason {@link #port()} exists
     */
    public PravahaFlightServer start(String host, int port) {
        Location requested = Location.forGrpcInsecure(host, port);
        try {
            FlightServer.Builder builder = FlightServer.builder(
                    allocator, requested, new PravahaFlightSqlProducer(catalog, allocator, requested, policy, audit));
            if (verifier != null) {
                builder.middleware(PrincipalMiddleware.KEY, new PrincipalMiddleware.Factory(verifier));
            }
            FlightServer started = builder.build().start();
            server.set(started);
            this.location = Location.forGrpcInsecure(host, started.getPort());
            return this;
        } catch (IOException e) {
            throw new PravahaException(
                    RuntimeErrors.LANE_FAILED,
                    "cannot start the Flight SQL server on " + host + ":" + port + ": " + e.getMessage(),
                    e);
        }
    }

    /** The port actually bound, which differs from the requested one when that was zero. */
    public int port() {
        FlightServer running = server.get();
        if (running == null) {
            throw new IllegalStateException("the server has not been started");
        }
        return running.getPort();
    }

    /** The URI a client connects to. */
    public String uri() {
        return location.getUri().toString();
    }

    public ViewCatalog catalog() {
        return catalog;
    }

    @Override
    public void close() {
        FlightServer running = server.getAndSet(null);
        if (running != null) {
            try {
                running.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // A server already gone is not a shutdown failure.
            }
        }
        if (ownsAllocator) {
            allocator.close();
        }
    }
}
