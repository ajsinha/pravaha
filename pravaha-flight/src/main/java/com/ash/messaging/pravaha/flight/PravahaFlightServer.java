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
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.serving.ReadAdmission;
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
 * <p>{@code --sun-misc-unsafe-memory-access=allow} belongs beside them, or Java 25 warns about Arrow's
 * use of {@code sun.misc.Unsafe} (JEP 498). From 2.0, Java 25 only, {@code bin/pravaha-server} always
 * passes it; 1.x could not, because Java 21 refuses the option rather than ignoring it.
 *
 * <p>Without them the failure is an {@code InaccessibleObjectException} at class-load that mentions
 * neither Arrow nor Flight, so it is stated here rather than left to be rediscovered.
 */
public final class PravahaFlightServer implements AutoCloseable {

    private final ViewCatalog catalog;
    private final BufferAllocator allocator;
    private @Nullable TokenVerifier verifier;
    private SecurityPolicy policy = SecurityPolicy.PERMISSIVE;
    private AuditSink audit = AuditSink.NONE;
    private ReadAdmission admission = ReadAdmission.UNLIMITED;
    private com.ash.messaging.pravaha.registry.@Nullable QueryRegistry registry;

    /**
     * What has been dead-lettered, when a node has a {@code pravaha.dlq.directory} (B5).
     *
     * <p>Separate from the registry because it is a different thing a deployment may or may not
     * have configured: a node with a registry and no queue answers the dead-letter actions with an
     * empty queue and says the directory is not set, which is a different answer from "this query
     * has rejected nothing".
     */
    private com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore deadLetters =
            com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE;

    private java.io.@Nullable File certificateChain;
    private java.io.@Nullable File privateKey;
    private java.time.Duration readDeadline = java.time.Duration.ZERO;
    /**
     * The threads that serve calls, one virtual thread per call.
     *
     * <p>Flight's default is a cached pool of <em>platform</em> threads, and this server's most
     * expensive call parks rather than computes: a subscription sits in {@code
     * PravahaFlightSqlProducer.streamSubscription} polling a handover queue for the life of the
     * subscription, waking every 200ms to check whether the client is still entitled to read. One
     * platform thread per subscriber means a megabyte of stack each and a thousand subscribers
     * costing a gigabyte before a row has moved — for threads that are, almost always, parked.
     *
     * <p>That is what Loom is for, and the HTTP side of this node already uses it
     * ({@code server.threads.virtual.enabled}). The data plane did not, which left the engine's
     * cheapest-to-scale surface running on its most expensive threads.
     *
     * <p>Safe here specifically because nothing on the serving path blocks inside {@code
     * synchronized}: the handover is a {@code BlockingQueue}, so its {@code poll} parks on a
     * {@code ReentrantLock} and releases the carrier. A {@code synchronized} block around a
     * blocking call would pin the carrier instead and this would be worse than what it replaced.
     */
    private final java.util.concurrent.ExecutorService callThreads =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicReference<FlightServer> server = new AtomicReference<>();
    private final boolean ownsAllocator;
    private @Nullable Location location;

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

    /**
     * Serves over TLS, using a PEM certificate chain and its private key.
     *
     * <p>Without this the transport is {@code grpc+tcp} and every row, every credential and every
     * query travels in clear text. That is defensible on a loopback socket and nowhere else --
     * authentication over an unencrypted channel hands the bearer token to anyone on the path,
     * which makes the token a formality rather than a control.
     *
     * <p>Must be called before {@link #start}, because the transport is chosen when the server is
     * built and cannot be upgraded under a listening socket.
     */
    public PravahaFlightServer encryptedWith(java.io.File certificateChain, java.io.File privateKey) {
        requireNotStarted("TLS");
        // CFG-6(b). Null-checked before either readability branch, because both of those
        // dereference. A certificate with no key reached `privateKey.isFile()` and threw a
        // NullPointerException whose helpful text names `privateKey` -- a field of this class --
        // and never `pravaha.flight.tls.key`, which is the thing the operator has to set. The
        // ordering meant that message only ever reached operators who had got the certificate
        // right.
        if (certificateChain == null || privateKey == null) {
            throw new PravahaException(
                    FlightErrors.TLS_UNREADABLE,
                    "TLS needs both halves and got "
                            + (certificateChain == null ? "only a private key" : "only a " + "certificate chain")
                            + ". Set pravaha.flight.tls.certificate and pravaha.flight.tls.key together, or "
                            + "neither -- a node given one of them cannot serve TLS, and starting in plaintext "
                            + "because half a setting was missing is how a deployment that asked for encryption "
                            + "ends up without it.");
        }
        if (!certificateChain.isFile()) {
            throw new PravahaException(
                    FlightErrors.TLS_UNREADABLE,
                    "the TLS certificate " + certificateChain.getAbsolutePath() + " is not a readable file");
        }
        if (!privateKey.isFile()) {
            throw new PravahaException(
                    FlightErrors.TLS_UNREADABLE,
                    "the TLS private key " + privateKey.getAbsolutePath() + " is not a readable file");
        }
        this.certificateChain = certificateChain;
        this.privateKey = privateKey;
        return this;
    }

    /** Whether this server is serving over TLS. */
    public boolean isEncrypted() {
        return certificateChain != null;
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
     * Bounds how many reads run at once and how long one may take.
     *
     * <p>ADR-030 made this load-bearing rather than optional. Once one engine answers both
     * continuous queries and request/response, an unbounded read path is how a client with a loop
     * stops a continuous query from keeping up with its input -- and the continuous query is the
     * one with a service level.
     */
    public PravahaFlightServer admitting(ReadAdmission admission, java.time.Duration readDeadline) {
        requireNotStarted("admission control");
        this.admission = java.util.Objects.requireNonNull(admission, "admission");
        this.readDeadline = java.util.Objects.requireNonNull(readDeadline, "readDeadline");
        return this;
    }

    /** Lets clients read and replay this node's dead letters (B5). */
    public PravahaFlightServer withDeadLetters(com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore store) {
        requireNotStarted("a dead-letter store");
        this.deadLetters = store == null ? com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE : store;
        return this;
    }

    /**
     * Reports every call, and every subscription the engine ends for entitlement, to {@code observation}:
     * the server's spans and meters. {@link FlightObservation#NONE} -- the default -- observes nothing and
     * adds nothing to a call.
     */
    public PravahaFlightServer observedBy(FlightObservation observation) {
        requireNotStarted("an observation");
        this.observation = observation == null ? FlightObservation.NONE : observation;
        return this;
    }

    private FlightObservation observation = FlightObservation.NONE;

    /**
     * Hosts a registry, so clients can register, list, drop and subscribe to continuous queries.
     *
     * <p>Optional, and the two states are meant to be visible. A server without one serves views
     * that something else maintains, and tells a client that asks to register so, rather than
     * offering an operation that quietly does nothing.
     */
    public PravahaFlightServer hosting(com.ash.messaging.pravaha.registry.QueryRegistry registry) {
        requireNotStarted("a registry");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Refuses a deployment where the server and the registry authorize against different policies.
     *
     * <p>There are two policy holders and until now nothing said so. A deployment that configured
     * one and not the other got a server where registering was judged by one set of rules and
     * reading by another -- silently, and in the direction of whichever was more permissive. It was
     * found by writing a test that configured the registry and not the server, and watching a
     * subscription that should have been refused succeed.
     *
     * <p>Identity rather than equality, deliberately: two policies that behave the same today are
     * still two objects somebody can change independently tomorrow.
     *
     * <p>Called from {@link #start}, so a caller may configure the registry and the policy in
     * either order.
     */
    @SuppressWarnings("ReferenceEquality") // identity is the question here: a sentinel, a thread or the very object
    private void requireOnePolicy() {
        if (registry == null || policy == null) {
            return;
        }
        if (registry.policy() != policy) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    "this server and the registry it hosts authorize against different SecurityPolicy "
                            + "instances. Registering would be judged by one and reading by the other, which "
                            + "is a split authorization model nobody chose -- and the more permissive of the "
                            + "two would decide. Pass the same policy to both, or pass it to neither and let "
                            + "both default.");
        }
    }

    /**
     * Binds and starts.
     *
     * @param port the port to listen on, or zero to let the operating system choose -- which is what
     *     a test wants, and the reason {@link #port()} exists
     */
    @SuppressWarnings("ReferenceEquality") // identity is the question here: a sentinel, a thread or the very object
    public PravahaFlightServer start(String host, int port) {
        // Checked here, where configuration is finished, rather than in each setter.
        //
        // Run per setter it was order-dependent and wrong about it: this server starts with a
        // default policy, so hosting(registry) compared a registry's real policy against that
        // default and refused -- before authorizedBy had been reached. Configuring in the order the
        // javadoc shows threw, and the only way to succeed was to call authorizedBy first, which
        // nothing said. The authenticated test fixture hit it and could not start, so five
        // authentication tests skipped every run reporting "the Pravaha server did not start; is
        // the module built?" -- an environmental-sounding message for a configuration refusal.
        requireOnePolicy();
        if (certificateChain != null) {
            // SX-17. Before anything is built, and before the startup summary can say
            // `transport=TLS`: a certificate and a key that are each valid and are not a pair used
            // to start a healthy-looking node that every client then failed to reach. See
            // FlightTlsPair.
            FlightTlsPair.requireMatching(
                    certificateChain, java.util.Objects.requireNonNull(privateKey, "set with the chain"));
        }
        Location requested =
                certificateChain == null ? Location.forGrpcInsecure(host, port) : Location.forGrpcTls(host, port);
        PravahaFlightSqlProducer producer = new PravahaFlightSqlProducer(
                        catalog, allocator, requested, policy, audit, admission, readDeadline)
                .withRegistry(registry)
                .withDeadLetters(deadLetters)
                .observedBy(observation);
        try {
            @SuppressWarnings(
                    "ReferenceEquality") // identity is the question here: a sentinel, a thread or the very object
            FlightServer.Builder builder = FlightServer.builder(
                    allocator,
                    requested,
                    // Inside the observer, so a refused request is still an observed (failed) call.
                    observation == FlightObservation.NONE
                            ? new RequestShapeGuard(producer)
                            : new ObservedFlightProducer(new RequestShapeGuard(producer), observation));
            if (observation != FlightObservation.NONE) {
                builder.middleware(ObservedFlightProducer.KEY, new ObservedFlightProducer.HeadersFactory());
            }
            if (verifier != null) {
                builder.middleware(PrincipalMiddleware.KEY, new PrincipalMiddleware.Factory(verifier));
            }
            if (certificateChain != null) {
                builder.useTls(certificateChain, privateKey);
            }
            builder.executor(callThreads);
            FlightServer started = builder.build().start();
            server.set(started);
            // SX-16, both halves.
            //
            // The scheme was `forGrpcInsecure` unconditionally, so a node genuinely serving TLS
            // reported its own address as `grpc+tcp://` -- a client following it dials plaintext at
            // a port that speaks TLS. And the producer was built from `requested`, whose port is
            // the one that was *asked for*: with `--pravaha.flight.port=0`, meaning "ask the OS",
            // `getFlightInfo` handed clients an endpoint at port 0. Both are the same mistake --
            // describing the server by what was requested rather than by what happened -- so both
            // are fixed in the one place that knows the difference.
            this.location = certificateChain == null
                    ? Location.forGrpcInsecure(host, started.getPort())
                    : Location.forGrpcTls(host, started.getPort());
            producer.servedFrom(this.location);
            return this;
        } catch (IOException e) {
            throw new PravahaException(
                    RuntimeErrors.LANE_FAILED,
                    "cannot start the Flight SQL server on " + host + ":" + port + ": " + e.getMessage(),
                    e);
        } catch (PravahaException alreadyDiagnosed) {
            throw alreadyDiagnosed;
        } catch (RuntimeException uncoded) {
            // SX-17's other half. The catch above was IOException-only, and the transport builder
            // throws IllegalArgumentException for a certificate/key file it cannot parse -- swapped
            // files, most often -- so that reached the operator as a raw Java exception with no
            // PRV code and no help URL. A TLS node says so in its own vocabulary.
            throw new PravahaException(
                    certificateChain == null ? RuntimeErrors.LANE_FAILED : FlightErrors.TLS_UNREADABLE,
                    "cannot start the Flight SQL server on " + host + ":" + port
                            + (certificateChain == null
                                    ? ""
                                    : " with the certificate " + certificateChain.getAbsolutePath() + " and the key "
                                            + java.util.Objects.requireNonNull(privateKey, "set with the chain")
                                                    .getAbsolutePath())
                            + ": " + uncoded.getMessage(),
                    uncoded);
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

    /**
     * How long {@link #close()} gives in-flight calls to release their buffers before it closes the
     * root allocator anyway. Long enough for a call that is already unwinding, short enough that a
     * shutdown is still a shutdown.
     */
    private static final java.time.Duration SHUTDOWN_DRAIN = java.time.Duration.ofSeconds(5);

    /** The URI a client connects to. */
    public String uri() {
        return java.util.Objects.requireNonNull(location, "set when the server starts")
                .getUri()
                .toString();
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
        // After the transport has stopped accepting, before the allocator: a call still unwinding
        // holds Arrow buffers, and awaitInFlightCalls below is what waits for it to let go.
        callThreads.shutdownNow();
        // Whether or not this server owns the allocator. An owner closes it next, and so does a
        // caller that lent it: either way the call threads must have let go of their buffers
        // first. Waiting only when owning left the lent case racing -- SubscriptionOverflowTest
        // shares one allocator between client and server, closed it the moment close() returned,
        // and failed a loaded gate twice with a live 80 KiB subscription batch while passing alone.
        awaitInFlightCalls();
        if (ownsAllocator) {
            allocator.close();
        }
    }

    /**
     * Waits, briefly, for the calls that were in flight to release their buffers.
     *
     * <p>Flight gives each call a child allocator. {@code FlightServer.close()} returning means the
     * transport has stopped accepting work, not that every call thread has finished unwinding and
     * released what it held -- so closing the root immediately after it reports the outstanding
     * child as leaked. It is a shutdown ordering problem wearing a leak's clothes, and it surfaced
     * as {@code JavaSdkQueryTest} failing under a loaded full-reactor build and passing alone.
     *
     * <p>Bounded, and it closes regardless when the bound expires: a real leak must still be
     * reported. Waiting for ever to avoid an accusation would be how a real one gets hidden.
     */
    private void awaitInFlightCalls() {
        long deadline = System.nanoTime() + SHUTDOWN_DRAIN.toNanos();
        while (System.nanoTime() < deadline) {
            if (allocator.getChildAllocators().isEmpty() && allocator.getAllocatedMemory() == 0L) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
