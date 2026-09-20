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
package com.ash.messaging.pravaha.server;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.bindings.ingest.PluginLookupSources;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.CoordinatorFactory;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.AuthenticatedOnlyPolicy;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * The parts that make this process a server rather than a library: the Flight endpoint clients
 * connect to, the registry of continuous queries, and the cluster coordinator.
 *
 * <p>Until this existed, every one of those was assembled only in tests. The engine started, the
 * HTTP surface came up, and nothing listened on the wire protocol that the SDKs and the CLI actually
 * speak -- so the product's own client tools could not talk to the product's own server without a
 * test harness standing it up. The pieces were all built and none of them were joined.
 *
 * <p>Startup order is deliberate and the reverse of what a reading order suggests.
 *
 * <ol>
 *   <li>The coordinator first, because whether this node may own partitions at all is a question to
 *       answer before it does any work, and the answer can be "no, refuse to start" ({@code
 *       PRV-9002}).
 *   <li>The registry next, and <em>recovered</em> before anything can reach it. A client that
 *       connects during recovery and is told "no such view" would re-register, and a duplicate
 *       registration of a query that was about to come back is a second computation of the same
 *       thing.
 *   <li>Flight last. Accepting connections is the last thing that happens, because a connection
 *       accepted before the views exist gets a wrong answer rather than no answer.
 * </ol>
 *
 * <p>Shutdown reverses it: stop accepting, then let go of the queries, then leave the cluster. A
 * node that left the cluster first would have its partitions reassigned while it was still serving
 * them.
 */
@Component
public class PravahaNode implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PravahaNode.class);

    private final StreamCatalog streams;
    private final boolean pgwireEnabled;
    private final String pgwireHost;
    private final int pgwirePort;
    private final File pgwireTlsCertificate;
    private final File pgwireTlsKey;

    private final boolean flightEnabled;
    private final String flightHost;
    private final int flightPort;

    /**
     * Claims on the directories this node writes durable state into, held for its lifetime.
     *
     * <p>Two nodes on one checkpoint root prune each other's checkpoints; two on one registry
     * journal replay each other's registrations and each comes up running queries it never
     * registered (CFG-13, CFG-14). Both are reachable from two lines of YAML and neither reports
     * anything, so the claim is taken before either path is handed to the registry.
     */
    private final java.util.List<com.ash.messaging.pravaha.common.io.StateOwnership> stateClaims =
            new java.util.ArrayList<>();

    private final Optional<Path> journalPath;
    private final Configuration clusterConfiguration;
    private final java.util.Optional<java.nio.file.Path> dlqPath;

    /** What bounds the dead-letter files, read whether or not a directory is configured (B5). */
    private final com.ash.messaging.pravaha.runtime.dlq.DeadLetterRetention dlqRetention;

    private final String nodeId;
    private final boolean allowSharedState;
    private final boolean standby;
    private final com.ash.messaging.pravaha.server.ingest.LaneProperties lanes;
    private com.ash.messaging.pravaha.server.state.StandbyWatch standbyWatch;

    private volatile ViewCatalog views;
    private volatile QueryRegistry registry;
    private volatile PravahaFlightServer flight;

    private volatile com.ash.messaging.pravaha.pgwire.PravahaPgWireServer pgwire;
    private final SourceBindingProperties sources;
    private final SinkBindingProperties sinks;

    private final com.ash.messaging.pravaha.server.state.StateSpillProperties stateSpill;

    /**
     * {@code pravaha.metrics.operators}: whether queries compiled on this node count rows, state
     * and time per operator (B6).
     *
     * <p>Off by default, and the default is a measurement rather than a preference --
     * {@code OperatorMetricsOverheadIT} puts the wrappers at more than a few percent of a narrow
     * query's throughput on the reference machine, which is over the bar for something everybody
     * pays for. Read once, at start-up, for the same reason the spill tier is: a lane that has
     * already compiled its stages cannot be given counters afterwards.
     */
    private final boolean measureOperators;

    private volatile com.ash.messaging.pravaha.bindings.egress.PluginSinks pluginSinks;

    private PluginLookupSources lookupSources;

    /** The dimension tables' schemas, for anything that has to plan SQL outside the registry. */
    private List<StreamSchema> lookupSchemas = List.of();

    private final StreamDeclarationProperties declaredStreams;
    private final SecurityProperties security;
    private final File tlsCertificate;
    private final File tlsKey;
    private final Duration watermarkIdleAfter;
    private final Duration watermarkTick;
    private volatile PluginSourceFeeds feeds;
    private final Optional<Path> checkpointPath;
    private final Configuration checkpointConfiguration;

    /** The debugger's bounds, pravaha.debug.* (ADR-048). */
    private final Configuration debugConfiguration;

    private volatile ClusterCoordinator coordinator;
    private volatile boolean running;

    /**
     * A node built by naming what differs from the defaults.
     *
     * <p>Spring keeps the constructor: {@code @Value} injection needs one, and a framework that
     * binds configuration to parameters cannot be handed a builder. What the constructor is bad at
     * is <em>everything else</em> — it carries twenty-three positional parameters, of which a test
     * typically cares about two, and the rest are {@code null}, {@code false} and {@code 0} standing
     * in a row where nothing says which is which.
     *
     * <p><strong>That is not a style complaint; it broke this build three times in one day.</strong>
     * Each new parameter meant editing fifteen call sites across seven files, and twice a call site
     * at a different indent was missed and found by a compile error, once after the tree had already
     * been pushed. A builder moves that cost to one place: a new parameter changes the constructor
     * and this class, and every caller that does not care about it stays as it was.
     *
     * <p>The defaults here are the {@code @Value} defaults, in one place, so a test that says
     * {@code PravahaNode.builder().withCatalog(c).build()} gets the same node the configuration file
     * would have produced with nothing set.
     */
    public static final class Builder {

        private StreamCatalog streams = new StreamCatalog();
        private SourceBindingProperties sources = new SourceBindingProperties();
        private StreamDeclarationProperties declaredStreams = new StreamDeclarationProperties();
        private SecurityProperties security = new SecurityProperties();
        private String tlsCertificate;
        private String tlsKey;
        private Duration watermarkIdleAfter = Duration.ofSeconds(30);
        private Duration watermarkTick = Duration.ofSeconds(1);
        private boolean flightEnabled;
        private String flightHost = "127.0.0.1";
        private int flightPort;
        private PersistenceProperties persistence = new PersistenceProperties();
        private String clusterMode = "SINGLE";
        private String clusterMechanism = "single";
        private String nodeId = "pravaha-node-01";
        private boolean allowSharedState = true;
        private boolean standby;
        private com.ash.messaging.pravaha.server.ingest.LaneProperties lanes;
        private SinkBindingProperties sinks;
        private com.ash.messaging.pravaha.server.state.StateSpillProperties stateSpill;
        private boolean measureOperators;
        private boolean pgwireEnabled;
        private String pgwireHost = "127.0.0.1";
        private int pgwirePort;
        private String pgwireTlsCertificate;
        private String pgwireTlsKey;

        public Builder withCatalog(StreamCatalog streams) {
            this.streams = streams;
            return this;
        }

        public Builder withSources(SourceBindingProperties sources) {
            this.sources = sources;
            return this;
        }

        public Builder withDeclaredStreams(StreamDeclarationProperties declaredStreams) {
            this.declaredStreams = declaredStreams;
            return this;
        }

        public Builder withSecurity(SecurityProperties security) {
            this.security = security;
            return this;
        }

        /** Both halves together, because half a pair is refused rather than ignored (CFG-6). */
        public Builder withFlightTls(String certificate, String key) {
            this.tlsCertificate = certificate;
            this.tlsKey = key;
            return this;
        }

        public Builder withWatermark(Duration idleAfter, Duration tick) {
            this.watermarkIdleAfter = idleAfter;
            this.watermarkTick = tick;
            return this;
        }

        /** Port zero lets the operating system pick, so tests do not fight over a fixed one. */
        public Builder withFlight(boolean enabled, String host, int port) {
            this.flightEnabled = enabled;
            this.flightHost = host;
            this.flightPort = port;
            return this;
        }

        public Builder withPgWire(boolean enabled, String host, int port) {
            this.pgwireEnabled = enabled;
            this.pgwireHost = host;
            this.pgwirePort = port;
            return this;
        }

        /** Both halves together, refused together when only one is set, as for Flight. */
        public Builder withPgWireTls(String certificate, String key) {
            this.pgwireTlsCertificate = certificate;
            this.pgwireTlsKey = key;
            return this;
        }

        public Builder withPersistence(PersistenceProperties persistence) {
            this.persistence = persistence;
            return this;
        }

        public Builder withCluster(String mode, String mechanism) {
            this.clusterMode = mode;
            this.clusterMechanism = mechanism;
            return this;
        }

        public Builder withNodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder allowingSharedState(boolean allow) {
            this.allowSharedState = allow;
            return this;
        }

        public Builder asStandby(boolean standby) {
            this.standby = standby;
            return this;
        }

        public Builder withLanes(com.ash.messaging.pravaha.server.ingest.LaneProperties lanes) {
            this.lanes = lanes;
            return this;
        }

        public Builder withSinks(SinkBindingProperties sinks) {
            this.sinks = sinks;
            return this;
        }

        public Builder withStateSpill(com.ash.messaging.pravaha.server.state.StateSpillProperties spill) {
            this.stateSpill = spill;
            return this;
        }

        /** {@code pravaha.metrics.operators}: per-operator rows, state and sampled time. Off by default. */
        public Builder measuringOperators(boolean measure) {
            this.measureOperators = measure;
            return this;
        }

        public PravahaNode build() {
            return new PravahaNode(
                    streams,
                    sources,
                    declaredStreams,
                    security,
                    tlsCertificate,
                    tlsKey,
                    watermarkIdleAfter,
                    watermarkTick,
                    flightEnabled,
                    flightHost,
                    flightPort,
                    persistence,
                    clusterMode,
                    clusterMechanism,
                    nodeId,
                    allowSharedState,
                    standby,
                    lanes,
                    sinks,
                    stateSpill,
                    pgwireEnabled,
                    pgwireHost,
                    pgwirePort,
                    pgwireTlsCertificate,
                    pgwireTlsKey,
                    measureOperators);
        }
    }

    /** A node built by naming what differs from the defaults. See {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    public PravahaNode(
            StreamCatalog streams,
            SourceBindingProperties sources,
            StreamDeclarationProperties declaredStreams,
            SecurityProperties security,
            @Value("${pravaha.flight.tls.certificate:}") String tlsCertificate,
            @Value("${pravaha.flight.tls.key:}") String tlsKey,
            @Value("${pravaha.watermark.idle-after:30s}") Duration watermarkIdleAfter,
            @Value("${pravaha.watermark.tick:1s}") Duration watermarkTick,
            @Value("${pravaha.flight.enabled:true}") boolean flightEnabled,
            @Value("${pravaha.flight.host:0.0.0.0}") String flightHost,
            @Value("${pravaha.flight.port:9090}") int flightPort,
            PersistenceProperties persistence,
            @Value("${pravaha.cluster.mode:SINGLE}") String clusterMode,
            @Value("${pravaha.cluster.mechanism:single}") String clusterMechanism,
            @Value("${pravaha.node.id:pravaha-node-01}") String nodeId,
            @Value("${pravaha.state.allow-shared:false}") boolean allowSharedState,
            @Value("${pravaha.standby.enabled:false}") boolean standby,
            com.ash.messaging.pravaha.server.ingest.LaneProperties lanes,
            SinkBindingProperties sinks,
            com.ash.messaging.pravaha.server.state.StateSpillProperties stateSpill,
            @Value("${pravaha.pgwire.enabled:false}") boolean pgwireEnabled,
            @Value("${pravaha.pgwire.host:0.0.0.0}") String pgwireHost,
            @Value("${pravaha.pgwire.port:5432}") int pgwirePort,
            @Value("${pravaha.pgwire.tls.certificate:}") String pgwireTlsCertificate,
            @Value("${pravaha.pgwire.tls.key:}") String pgwireTlsKey,
            @Value("${pravaha.metrics.operators:false}") boolean measureOperators) {
        this.streams = streams;
        this.sources = sources;
        // Defaults to empty if no bean is supplied, so the existing test call sites that construct
        // a node directly -- none of which cares about sinks -- keep working, exactly as `lanes`
        // does above.
        this.sinks = sinks == null ? new SinkBindingProperties() : sinks;
        this.stateSpill =
                stateSpill == null ? new com.ash.messaging.pravaha.server.state.StateSpillProperties() : stateSpill;
        this.declaredStreams = declaredStreams;
        this.security = security;
        this.tlsCertificate = tlsCertificate == null || tlsCertificate.isBlank() ? null : new File(tlsCertificate);
        this.tlsKey = tlsKey == null || tlsKey.isBlank() ? null : new File(tlsKey);
        this.watermarkIdleAfter = watermarkIdleAfter;
        this.watermarkTick = watermarkTick;
        this.nodeId = nodeId;
        this.allowSharedState = allowSharedState;
        this.standby = standby;
        // Defaults to the library's if no bean is supplied, so the fourteen test call sites that
        // construct a node directly keep working and keep meaning the same thing.
        this.lanes = lanes == null ? new com.ash.messaging.pravaha.server.ingest.LaneProperties() : lanes;
        this.pgwireEnabled = pgwireEnabled;
        this.pgwireHost = pgwireHost;
        this.pgwirePort = pgwirePort;
        this.pgwireTlsCertificate =
                pgwireTlsCertificate == null || pgwireTlsCertificate.isBlank() ? null : new File(pgwireTlsCertificate);
        this.pgwireTlsKey = pgwireTlsKey == null || pgwireTlsKey.isBlank() ? null : new File(pgwireTlsKey);
        this.flightEnabled = flightEnabled;
        this.flightHost = flightHost;
        this.flightPort = flightPort;
        this.journalPath = persistence.journalPath();
        this.dlqPath = persistence.dlqPath();
        this.dlqRetention = persistence.dlqRetention();
        this.checkpointPath = persistence.checkpointPath();
        this.checkpointConfiguration = persistence.checkpointConfiguration();
        this.debugConfiguration = persistence.debugConfiguration();
        this.clusterConfiguration = Configuration.builder()
                .set("pravaha.cluster.mode", clusterMode)
                .set("pravaha.cluster.mechanism", clusterMechanism)
                .build();
        this.measureOperators = measureOperators;
    }

    /**
     * Refuses to start a server that serves everything to everybody unless somebody said so.
     *
     * <p>This is the guard that would have caught the state this node shipped in: no
     * authentication, {@code PERMISSIVE}, no audit, no TLS, and nothing anywhere saying that was the
     * intent. The failure has to be at startup. A warning in a log is read by whoever is watching
     * the log on the day, and an open server outlives that person's attention.
     *
     * <p>Not a refusal to run open at all -- plenty of deployments sit behind something that has
     * already authenticated the caller, and that is the embedded case this engine is built for. The
     * refusal is of running open <em>by default</em>, which is the only version of it nobody chose.
     */
    private void refuseAccidentalOpenServer() {
        // An open server is one that serves data to callers it has not identified, and that needs
        // BOTH halves to be true: an unauthenticated caller has to get in, and the policy has to
        // hand them everything once they are.
        //
        // CFG-9/SX-12. This was computed from the policy alone, and the comment it replaces records
        // the correction before it -- the version before that required authentication to be off
        // entirely, which made allow-anonymous dead under `token`. One term keeps failing in one
        // direction or the other, which is the signal that the question needs two.
        //
        // What the one-term version did: a node with authentication=token, a real token table,
        // policy=permissive and allow-anonymous=false -- where BearerTokenFilter refuses every
        // unauthenticated caller with a 401, which is exactly the posture an operator sets out to
        // configure -- could not start. The only way to start it was allow-anonymous=true, which is
        // a lie about the node.
        boolean unauthenticatedCallersGetIn = !security.authenticates() || security.isAllowAnonymous();
        boolean policyServesThemEverything = !(securityPolicy() instanceof AuthenticatedOnlyPolicy);
        boolean open = unauthenticatedCallersGetIn && policyServesThemEverything;
        if (open && !security.isAllowAnonymous()) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "this node is configured to accept unauthenticated callers and serve them every view "
                            + "(pravaha.security.authentication=" + security.getAuthentication() + ", policy="
                            + security.getPolicy() + "). That is a reasonable way to run an engine behind a "
                            + "boundary that has already authenticated the caller, and a bad way to run one on "
                            + "a network. Set pravaha.security.authentication=token with "
                            + "pravaha.security.tokens.*, or set pravaha.security.policy=authenticated, or -- "
                            + "if open really is what you want -- set pravaha.security.allow-anonymous=true to "
                            + "say so on purpose.");
        }
        if (!security.authenticates() && securityPolicy() instanceof AuthenticatedOnlyPolicy) {
            // A contradiction, and a dangerous one rather than a merely silly one. The policy says
            // only authenticated callers see anything and the node offers no way to authenticate, so
            // Flight correctly refuses everybody -- while the HTTP surface, which has no filter
            // because authentication is off and does not consult the policy, keeps serving stream
            // schemas and accepting stream registrations from anyone who can reach the port. The
            // configuration reads as locked down and leaves a door open.
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.security.policy=authenticated with pravaha.security.authentication=none is a "
                            + "node nobody can use: the policy serves only verified callers and nothing here "
                            + "can verify one. Set pravaha.security.authentication=token and configure "
                            + "pravaha.security.tokens, or choose a policy that admits anonymous callers.");
        }
        if (security.authenticates() && tlsCertificate == null) {
            // Not fatal: a sidecar or a service mesh may be terminating TLS in front of this. It is
            // logged at warn because a bearer token on a plaintext socket is handed to anyone on
            // the path, which makes the token a formality rather than a control.
            log.warn("authentication is on and Flight is serving plaintext, so credentials travel in the "
                    + "clear; set pravaha.flight.tls.certificate and .key unless something in front of "
                    + "this node is terminating TLS");
        }
    }

    /**
     * Puts configured stream schemas in the catalog, before the registry is built from it.
     *
     * <p>The registry takes a snapshot of the catalog's schemas when it is constructed, so a stream
     * declared after that point is not one any query can plan against. Registering here is the
     * difference between a node a file can describe and one that needs an HTTP call after every
     * restart before it will answer anything.
     */
    private void registerDeclaredStreams() {
        declaredStreams.getStreams().forEach((name, declaration) -> {
            if (declaration.getSchema() == null || declaration.getSchema().isBlank()) {
                throw new PravahaException(
                        SqlErrors.VALIDATION_FAILED,
                        "stream '" + name + "' is declared under pravaha.streams with no schema. A stream "
                                + "is a name and a shape; the name alone cannot be planned against.");
            }
            StreamSchema parsed = FilesystemSourcePlugin.parseSchema(name, declaration.getSchema());
            streams.register(withEventTime(name, parsed, declaration));
        });
        if (!declaredStreams.getStreams().isEmpty()) {
            log.info(
                    "streams declared in configuration: {}",
                    declaredStreams.getStreams().keySet());
        }
    }

    /**
     * Marks the event-time column, and the stream's own lateness, if the declaration named them.
     *
     * <p>Rebuilt rather than mutated because a schema is immutable: a running query keeps the
     * version it was planned against, which is what stops a re-declaration changing the meaning of a
     * query already in flight.
     */
    private static StreamSchema withEventTime(
            String name, StreamSchema parsed, StreamDeclarationProperties.Declaration declaration) {
        if ((declaration.getEventTime() == null || declaration.getEventTime().isBlank())
                && declaration.getAllowedLateness() == null) {
            return parsed;
        }
        // One implementation, shared with POST /api/v1/streams, so a stream declared by file and one
        // declared over HTTP cannot disagree about what an event-time declaration means. An allowed
        // lateness with no event time reaches it, and is refused there by name.
        return com.ash.messaging.pravaha.server.catalog.StreamCatalog.withEventTime(
                parsed, declaration.getEventTime(), declaration.getOutOfOrderness(), declaration.getAllowedLateness());
    }

    /**
     * Passes the stream's declared event-time column down to its source.
     *
     * <p>So it is declared once. The engine's schema and the plugin's are built from different
     * places -- {@code pravaha.streams.<n>.schema} and the binding's own {@code schema} option --
     * and only the first carried the event-time marker. The plugin then decoded rows with a schema
     * that had none and stamped them all with event time zero, which no watermark can advance past
     * into a window in the present. Making the operator write the column twice would have worked and
     * would have been one more thing to get out of step.
     */
    private SourceBinding withDeclaredEventTime(SourceBinding binding) {
        StreamDeclarationProperties.Declaration declaration =
                declaredStreams.getStreams().get(binding.streamName());
        if (declaration == null
                || declaration.getEventTime() == null
                || declaration.getEventTime().isBlank()
                || binding.options().containsKey("event.time")) {
            return binding;
        }
        Map<String, String> options = new java.util.LinkedHashMap<>(binding.options());
        options.put("event.time", declaration.getEventTime().strip());
        return new SourceBinding(binding.streamName(), binding.plugin(), options);
    }

    private SecurityPolicy securityPolicy() {
        String configured = security.getPolicy() == null
                ? "permissive"
                : security.getPolicy().trim();
        return switch (configured.toLowerCase(java.util.Locale.ROOT)) {
            case "permissive" -> SecurityPolicy.PERMISSIVE;
            case "authenticated", "authenticated-only" -> new AuthenticatedOnlyPolicy(security.getAuditReaders());
            default ->
                throw new PravahaException(
                        SecurityErrors.MISCONFIGURED,
                        "pravaha.security.policy is '" + configured + "', which is not a policy this node "
                                + "knows. Use 'permissive' or 'authenticated', or implement SecurityPolicy "
                                + "for rules of your own.");
        };
    }

    /** The policy the registry was actually built with, so the two halves cannot disagree. */
    private static SecurityPolicy securityPolicyOf(QueryRegistry registry) {
        return registry.policy();
    }

    /**
     * The one audit sink for this node, built from {@code pravaha.security.audit}.
     *
     * <p>Package-private rather than private because the HTTP half of the node has to get the
     * <em>same instance</em>. CFG-5: {@code HttpAuthorizer} took its sink from a Spring bean that
     * returned {@code AuditSink.NONE} unconditionally, so a deployment configured with
     * {@code audit: memory} recorded every Flight read and no HTTP read, no HTTP stream declaration
     * and no HTTP refusal -- with nothing at startup saying so.
     *
     * <p>Resolving the key again in that bean would not have fixed it. {@code memory} builds an
     * {@code InMemory} sink, and a second one is a sink nobody can reach: the HTTP events would
     * still be invisible, and the configuration would now look right. Sharing the instance is the
     * fix; the cache below is what makes sharing safe to ask for before {@link #start()}.
     */
    AuditSink auditSink() {
        String configured =
                security.getAudit() == null ? "none" : security.getAudit().trim();
        return switch (configured.toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> AuditSink.NONE;
            case "memory" -> audit == null ? (audit = readable(memorySink(), "memory")) : audit;
            // CFG-23. The setting that produces a trail an operator can read after the fact, and
            // the reason it is a file: an endpoint listing who-read-what is a disclosure surface
            // needing an authorization this codebase's policy SPI cannot express, while a file's
            // readers are already decided by the operating system. See FileAuditSink.
            case "file" -> audit == null ? (audit = readable(fileSink(), "file")) : audit;
            default ->
                throw new PravahaException(
                        SecurityErrors.MISCONFIGURED,
                        "pravaha.security.audit is '" + configured + "'; use 'none', 'memory' or 'file'. "
                                + "'file' writes JSON Lines to pravaha.security.audit-file and is the only "
                                + "one of the three that leaves a record anybody can read.");
        };
    }

    /**
     * The in-process sink, and the warning that it is not an audit trail.
     *
     * <p>CFG-23: {@code memory} accepts every decision and exposes them to nobody -- nothing in any
     * {@code src/main} reads {@code events()}. It is genuinely useful to tests, which hold the sink
     * object, and to support reading a heap dump. A deployment that set it believing otherwise has
     * no record at all, which is the same outcome as {@code none} arrived at from the other end, so
     * the node says so once rather than letting the configuration file look reassuring.
     */
    private AuditSink.InMemory memorySink() {
        log.warn("pravaha.security.audit=memory keeps recent decisions in this process and exposes them to "
                + "nothing: no endpoint, no log, no file. It is for tests and for support reading a heap "
                + "dump. Use audit=file for a trail that outlives the process and that an operator can read.");
        return new AuditSink.InMemory();
    }

    private com.ash.messaging.pravaha.security.FileAuditSink fileSink() {
        com.ash.messaging.pravaha.security.FileAuditSink sink = new com.ash.messaging.pravaha.security.FileAuditSink(
                java.nio.file.Path.of(security.getAuditFile()),
                security.getAuditRotateBytes(),
                security.getAuditKeep(),
                // Through the node's log rather than standard error: a write failure here
                // means decisions are being made and not recorded, which is exactly the
                // state CFG-23 is about, and it belongs where the operator is already
                // looking.
                message -> log.error("audit: {}", message));
        log.info(
                "audit trail: {} (owner-readable only, JSON Lines, rotating at {} bytes, keeping {})",
                sink.path(),
                security.getAuditRotateBytes(),
                security.getAuditKeep());
        return sink;
    }

    private AuditSink audit;

    /**
     * The configured sink, wrapped so the most recent decisions can be read back over
     * {@code GET /api/v1/audit}.
     *
     * <p>A bounded ring beside the durable sink rather than the durable sink read back: the file is
     * written asynchronously, rotates, and is for the operator's own tools, while the ring is
     * recorded on the same call as the decision and costs O(1). {@link AuditTrail} explains the
     * trade and the read API reports the bound. The wrapper also swallows a failing delegate, so
     * auditing cannot fail the call it audits.
     */
    private AuditSink readable(AuditSink durable, String kind) {
        int capacity = security.getAuditRecent();
        if (capacity < 1) {
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    "pravaha.security.audit-recent is " + capacity + "; it is how many recent decisions stay "
                            + "readable over /api/v1/audit and must be at least 1.");
        }
        return new AuditTrail(durable, kind, capacity);
    }

    /**
     * The readable trail, when this node audits at all; empty under {@code audit: none}, where there is
     * nothing to read and the read API says so rather than showing an empty trail as if nobody had
     * asked for anything.
     */
    public Optional<AuditTrail> auditTrail() {
        return auditSink() instanceof AuditTrail trail ? Optional.of(trail) : Optional.empty();
    }

    @Override
    public int getPhase() {
        // After the engine (Integer.MAX_VALUE - 1000), so the engine is up before anything is served.
        return Integer.MAX_VALUE - 500;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (standby && standbyWatch == null) {
            java.util.Optional<java.nio.file.Path> watched = checkpointPath;
            if (watched.isEmpty()) {
                throw new PravahaException(
                        SqlErrors.VALIDATION_FAILED,
                        "pravaha.standby.enabled=true needs pravaha.checkpoint.directory set: a standby waits on "
                                + "the ownership marker in the directory it would take over, and with no such "
                                + "directory there is nothing to wait on and nothing to resume from.");
            }
            // Returns immediately. The node holds no lanes, serves nothing and reports itself not
            // running until the primary's claim goes unrefreshed -- so an orchestrator's readiness
            // check keeps it out of rotation, which is what a standby is for.
            standbyWatch = new com.ash.messaging.pravaha.server.state.StandbyWatch(
                    watched.get(),
                    nodeId,
                    com.ash.messaging.pravaha.common.io.StateOwnership.DEFAULT_LEASE,
                    com.ash.messaging.pravaha.server.state.StandbyWatch.DEFAULT_POLL,
                    takeover -> {
                        log.warn("{}", takeover.describe());
                        startNow();
                    });
            standbyWatch.start();
            return;
        }
        startNow();
    }

    /**
     * Refuses to serve in {@code PARTITIONED} mode, because this node would not partition anything.
     *
     * <p>S-3, a second time. {@code CoordinatorFactory} used to refuse the mode outright; ADR-039 item
     * 8's first slice removed that once membership produced a real assignment, which is true of the
     * <em>cluster</em> layer and a good reason for the factory to build the coordinator. It is not
     * true of this node: nothing here constructs a {@code PartitionAssigner} or takes a partition
     * lease before reading, so a node started {@code PARTITIONED} reads and serves every partition
     * while reporting itself partitioned -- and two of them, with a sink attached, would each write
     * the whole answer to it. The refusal lives here rather than in the factory because this is
     * where the claim would be made: the coordinator can be built and tested as a library, and a
     * node may not serve on its strength until something here consumes ownership.
     */
    private void refusePartitionedServing() {
        if (CoordinatorFactory.modeOf(clusterConfiguration)
                != com.ash.messaging.pravaha.cluster.ClusterMode.PARTITIONED) {
            return;
        }
        com.ash.messaging.pravaha.cluster.ClusterCoordinator built = coordinator;
        coordinator = null;
        closeQuietly("cluster coordinator", built);
        throw new PravahaException(
                com.ash.messaging.pravaha.cluster.ClusterErrors.INSUFFICIENT_GUARANTEE,
                "cluster mode PARTITIONED cannot be served by this node: membership and partition "
                        + "assignment are built, but nothing in the node asks which partitions it owns before "
                        + "reading, so it would read and serve every partition while reporting itself "
                        + "partitioned -- and two such nodes would each write the whole answer to any sink. "
                        + "Use SINGLE, or REPLICATED for more than one node if duplicated work is acceptable. "
                        + "This refusal lifts when a node consumes partition ownership (ADR-039 item 8, S-3).");
    }

    private void startNow() {
        if (running) {
            return;
        }
        coordinator = CoordinatorFactory.create(clusterConfiguration);
        // S-3. After the factory, so PARTITIONED on a mechanism that cannot exclude split-brain keeps
        // its more specific diagnosis; before start, so a refused node never joins the cluster.
        refusePartitionedServing();
        // Advertised on the Flight port: that is the address other nodes would have to reach this
        // one on, and advertising an address nobody can connect to is a cluster that forms and
        // cannot work.
        coordinator.start(new com.ash.messaging.pravaha.cluster.Member(nodeId, flightHost, flightPort));
        log.info("{}", CoordinatorFactory.describe(clusterConfiguration, coordinator));

        refuseAccidentalOpenServer();
        registerDeclaredStreams();

        views = new ViewCatalog();
        SecurityPolicy policy = securityPolicy();
        AuditSink audit = auditSink();
        registry = new QueryRegistry(views, policy, audit, streams.all().toArray(new StreamSchema[0]));
        // The knobs eleven error messages have been telling operators to turn (PF-3). Nothing on this
        // path ever called executingWith, so every query on every node ran with the library's sizes
        // -- chosen for one high-throughput query, and paid for by each of a thousand small ones.
        registry.executingWith(lanes.toLaneConfig(), com.ash.messaging.pravaha.common.memory.MemoryAccess.best());
        // W9-8. The registry could host queries on shared lanes and no node ever asked it to.
        registry.multiplexingLanes(
                lanes.getMultiplex().effectiveLanes(), lanes.getMultiplex().getMaxQueriesPerLane());
        // The debugger's bounds (ADR-048): how many forks this node will hold, how long an
        // abandoned one lives, and how far a step will read. Given before anything can fork.
        registry.configuredWith(debugConfiguration);
        // Said once at startup, because the ceiling it names is the one a node holding many sources
        // reaches first -- and reaches with an error that blames the network (SRC-4).
        com.ash.messaging.pravaha.common.io.FileDescriptors.usage()
                .ifPresent(fds -> log.info(
                        "file descriptors: {} -- one bound source costs about one, so this is roughly the "
                                + "ceiling on how many sources this node can hold",
                        fds));
        log.info(
                "lane sizing: batch={}, inbox={}x{}B, arena={}B x{}, wait={} -- about {} KiB held per idle query",
                lanes.getBatchSize(),
                lanes.getInbox().getCells(),
                lanes.getInbox().getCellBytes(),
                lanes.getArena().getSlabBytes(),
                lanes.getArena().getMaxSlabs(),
                lanes.getWaitStrategy(),
                lanes.idleBytesPerQuery() / 1024);
        log.info("{}", laneSharing());
        log.info(
                "security: authentication={}, policy={}, audit={}, flight transport={}",
                security.authenticates() ? "token" : "none",
                // The configured name, not the object: SecurityPolicy.PERMISSIVE is an anonymous
                // class, and "SecurityPolicy$1@7657d90b" in the one line an operator reads to check
                // how a node is secured is worse than not logging it.
                security.getPolicy(),
                security.getAudit(),
                tlsCertificate == null ? "PLAINTEXT" : "TLS");
        // CFG-10(b). SecurityProperties.verifier() returns TokenVerifier.rejectAll() for an empty
        // token table and its own comment says why the operator should learn this at startup
        // "rather than in a support ticket about 401s" -- and then said it nowhere until the first
        // call. A node that refuses every caller looked identical, in the log, to one that accepts
        // the right ones.
        security.unusableTokenTable().ifPresent(log::warn);

        // Before recovery, and that ordering is the point: a recovered query is registered the same
        // way a fresh one is, so a factory attached afterwards would feed everything registered
        // from now on and nothing the journal brought back -- queries that look identical in every
        // listing and differ only in whether rows arrive.
        // Before the feed factory, so that a query is checkpointed from its first row rather than
        // from whenever the next interval happens to land.
        checkpointPath.ifPresentOrElse(
                path -> {
                    claimState(path, "checkpoint directory");
                    registry.checkpointingTo(path, checkpointConfiguration);
                    log.info("checkpointing registered queries under {}", path);
                },
                () -> log.warn("pravaha.checkpoint.directory is not set, so registered queries keep no "
                        + "checkpoints: a restart recovers their definitions from the journal and none of "
                        + "their accumulated state"));

        // Switched on, which it never was. pravaha.watermark.* was read by nothing on this server:
        // QueryExecution.generatingWatermarks is what arms every bound in the engine -- a window
        // closing, a join evicting, a view forgetting -- and the registry only arms it when asked.
        // Unasked, a windowed query ingested every row and emitted nothing for ever, and the
        // documentation assured operators the bounds were enforced.
        // Validated here, where one bad value is one startup failure, rather than at registration
        // where it is every query failing separately. `idle-after: 1h` is outside the tracker's
        // bounds: the node started, logged the setting as in force, recovered 0 of 3 queries and
        // reported itself healthy. A typo in one duration silently emptied the node.
        try {
            // Constructing one is the validation: the bounds live in the tracker and are enforced
            // in its constructor, so checking them here by hand would be a second copy to drift.
            new com.ash.messaging.pravaha.runtime.time.WatermarkTracker(watermarkIdleAfter.toNanos());
        } catch (RuntimeException e) {
            throw new PravahaException(
                    SqlErrors.VALIDATION_FAILED,
                    "pravaha.watermark.idle-after is " + watermarkIdleAfter + ", which this engine will not "
                            + "accept: " + e.getMessage() + " Left as configured, every registration on this "
                            + "node would fail and the node would look healthy.");
        }
        registry.generatingWatermarks(watermarkIdleAfter, watermarkTick);
        log.info("watermarks: idle-after={}, tick={}", watermarkIdleAfter, watermarkTick);

        feeds = new PluginSourceFeeds();
        // TIME-4/W8-11. Without this the server has no way to switch the dead-letter path on, so
        // every node ran the unguarded one: a single undecodable field ended the poll and stopped
        // the source, taking every other row in the file with it, with the query still RUNNING and
        // nothing in any log. `pravaha run --dlq` had this and a deployment did not.
        dlqPath.ifPresent(directory -> {
            feeds.deadLetteringTo(directory).retainingDeadLetters(dlqRetention);
            log.info(
                    "dead-lettering undecodable records to {} (pravaha.dlq.directory), keeping {}",
                    directory,
                    dlqRetention.describe());
        });
        sources.toBindings().forEach(binding -> feeds.bind(withDeclaredEventTime(binding)));
        registry.feedingFrom(feeds);

        // Dimension tables, opened at start-up and handed to the registry. Nothing in shipped code
        // discovered a LookupSourcePlugin before this, so a registration had no dimension table to
        // plan against and every lookup join was planned as a stream-to-stream join -- waiting for
        // rows a dimension table never sends. The feature was implemented, optimised, tested and
        // documented, and no deployment could reach it.
        lookupSources = new PluginLookupSources();
        sources.toLookupBindings().forEach(lookupSources::bind);
        List<com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin> dimensions = lookupSources.open();
        dimensions.forEach(registry::lookingUp);
        if (!dimensions.isEmpty()) {
            log.info("dimension tables: {}", lookupSources.bindings().values());
            lookupSchemas = dimensions.stream()
                    .map(com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin::schema)
                    .toList();
            // The REST surface plans too. Without this, POST /queries/validate on a lookup query
            // reports the table as not found for a query the registry would accept.
            lookupSchemas.forEach(streams::registerLookup);
        }
        if (feeds.bindings().isEmpty()) {
            log.info("no sources are bound, so registered queries receive rows only from clients that push "
                    + "them; bind one under pravaha.sources.<stream>");
        } else {
            log.info("sources bound: {}", feeds.bindings().values());
        }

        // W8-13 (ADR-039 item 5). Recorded here so a sink is discoverable and its configuration is
        // validated at startup rather than the first time something tries to use it -- exactly the
        // reasoning `refuseAccidentalOpenServer` gives for failing early elsewhere in this method.
        // Deliberately not opened: nothing yet resolves a registered query's output against one of
        // these names (that is a QueryRegistry change, tracked separately), so opening a connection
        // or a file handle here would hold a resource for a use that cannot yet happen.
        // ADR-037 B2. Process-wide rather than per-query, and set before any query compiles a
        // pipeline: a lane that has already built its join or window state cannot be told later
        // that an overflow tier exists. Off unless configured, because a spill tier is a real cost
        // and a deployment chooses it -- the same reasoning as pravaha.pgwire.enabled.
        com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.configureSpill(stateSpill.toSpillSettings());
        // B6, and set here for the same reason and in the same breath: the counters are built into
        // a pipeline's stages when it compiles, so a query that has already started cannot be
        // given them. Off unless configured -- the wrappers cost throughput and a deployment that
        // has not asked for per-operator detail should not pay for it.
        com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.measureOperators(measureOperators);
        if (measureOperators) {
            log.info("pravaha.metrics.operators is on: queries registered from now on count rows, rows out, "
                    + "state bytes and a sampled self time per operator, which GET /api/v1/queries/"
                    + "{{name}}/plan returns beside each node. It costs throughput; see docs/OPERATIONS.md");
        }
        if (stateSpill.resolvedEnabled()) {
            log.info(
                    "state spills to {} past its memory tier, up to {} overflow slabs per store and {} on the "
                            + "node, compacted once {} of a store's overflow is free; a query that outgrows "
                            + "memory degrades instead of being refused",
                    stateSpill.getDirectory(),
                    stateSpill.getMaxOverflowSlabs(),
                    stateSpill.getMaxBytes().toBytes() == 0 ? "no byte quota" : stateSpill.getMaxBytes(),
                    stateSpill.getCompactionThreshold());
        }
        pluginSinks = new com.ash.messaging.pravaha.bindings.egress.PluginSinks();
        sinks.toBindings().forEach(pluginSinks::bind);
        if (pluginSinks.bindings().isEmpty()) {
            log.info("no sinks are bound; bind one under pravaha.sinks.<name> once a query needs to write "
                    + "somewhere other than its own view");
        } else {
            // Configuration is validated now rather than left to be discovered when a query first
            // resolves this name -- the same reason sources are opened at start-up rather than lazily.
            pluginSinks.bindings().keySet().forEach(pluginSinks::capabilitiesOf);
            log.info("sinks bound: {}", pluginSinks.bindings().values());
        }
        // Before recovery, so a journalled registration that writes to a sink comes back writing to
        // it -- and one whose sink is no longer bound is refused by name rather than recovered
        // without it.
        registry.writingTo(pluginSinks);

        journalPath.ifPresent(path -> {
            // The journal's directory, not the file: a claim is about the place a node writes state,
            // and the marker has to live beside the journal rather than inside it.
            java.nio.file.Path journalDirectory = path.toAbsolutePath().getParent();
            if (journalDirectory != null) {
                claimState(journalDirectory, "registry journal directory");
            }
            registry.journalTo(new RegistryJournal(path));
            QueryRegistry.Recovery recovery = registry.recover(this::principalNamed);
            log.info(
                    "registry recovered {} of {} queries from {}",
                    recovery.recovered().size(),
                    recovery.recovered().size() + recovery.refused().size(),
                    path);
            // The refused list is the one that matters: each entry is a view some client expects to
            // find and will not, so it is logged per query rather than counted. The code, when the
            // refusal has one, is logged alongside the text rather than folded into it, so an
            // operator can grep for PRV-8007 without parsing prose.
            recovery.refused()
                    .forEach(refusal -> log.warn(
                            "registration not recovered -- {}{}",
                            refusal,
                            refusal.code().map(code -> " [" + code.code() + "]").orElse("")));
        });
        if (journalPath.isEmpty()) {
            log.warn("pravaha.registry.journal is not set, so registered queries live only in memory and "
                    + "a restart will lose them without saying so");
        }

        if (flightEnabled) {
            // The same policy object the registry authorizes against, and set BEFORE hosting().
            //
            // The order is not stylistic. hosting() runs requireOnePolicy(), which compares the
            // server's policy with the registry's -- so hosting first meant comparing the
            // constructor's PERMISSIVE default against the registry's real policy, and any
            // non-permissive policy threw at startup blaming the operator for a mismatch they had
            // not configured. `policy: authenticated`, one of the three documented ways to close
            // this server, could never start a node with Flight enabled.
            PravahaFlightServer server = new PravahaFlightServer(views)
                    .authorizedBy(securityPolicyOf(registry), auditSink())
                    // B5. The same files the HTTP endpoints read, so `pravaha dlq` and the REST
                    // API cannot disagree about what is in the queue.
                    .withDeadLetters(feeds.deadLetters())
                    .hosting(registry);
            TokenVerifier verifier = security.verifier();
            if (verifier != null) {
                server.authenticatedBy(verifier);
            }
            // CFG-6(a). This was `if (tlsCertificate != null)`, which handled the two halves of one
            // setting asymmetrically: a key configured without a certificate was read into a File,
            // held in a field and never used. The node started in plaintext, grpc:// connected, rows
            // were readable on the wire -- and under `authentication: token` it simultaneously
            // advised the operator to "set pravaha.flight.tls.certificate and .key", advice they had
            // already half-taken, with no acknowledgement of the half they took and nothing anywhere
            // saying a private key had been configured and ignored.
            if (tlsCertificate != null || tlsKey != null) {
                server.encryptedWith(tlsCertificate, tlsKey);
            }
            flight = server.start(flightHost, flightPort);
            log.info("Flight SQL listening on {}:{}", flightHost, flight.port());
        } else {
            log.info("Flight SQL disabled (pravaha.flight.enabled=false); this node serves HTTP only");
        }

        if (pgwireEnabled) {
            // The PostgreSQL wire protocol, read path only, so that psql, DBeaver, Grafana and every
            // ORM can reach a maintained view without a Flight SQL driver -- which almost nothing
            // ships, and which is why Gate P6's "DBeaver connects" criterion has never been tried.
            //
            // Same policy object and same audit sink as Flight and the registry: this is a transport,
            // and it must not become a second, weaker way to the data. PgWireConnection calls
            // ViewQuery.execute(sql, principal) exactly as PravahaFlightSqlProducer does.
            //
            // OFF BY DEFAULT. Without pravaha.pgwire.tls.certificate and .key the password and every
            // row cross the wire in the clear, so an operator turns it on knowing that, on loopback
            // or behind a terminator; nobody gets it by not reading the configuration file.
            com.ash.messaging.pravaha.pgwire.PravahaPgWireServer server =
                    new com.ash.messaging.pravaha.pgwire.PravahaPgWireServer(views)
                            .authorizedBy(securityPolicyOf(registry), auditSink());
            TokenVerifier pgVerifier = security.verifier();
            if (pgVerifier != null) {
                server.authenticatedBy(pgVerifier);
            }
            // HLP-5. The gateway has had TLS since its third slice, and application.yaml and
            // CONNECTOR_TLS.md documented pravaha.pgwire.tls.*, but nothing here read those keys:
            // a deployment that configured them was served plaintext, and told so only by the log
            // line below. Either half set hands both to PgTls.load, which refuses the missing one
            // by name (CFG-6's rule), rather than ignoring half a pair.
            if (pgwireTlsCertificate != null || pgwireTlsKey != null) {
                server.encryptedWith(pgwireTlsCertificate, pgwireTlsKey);
            }
            pgwire = server.start(pgwireHost, pgwirePort);
            if (server.isEncrypted()) {
                log.info("PostgreSQL wire protocol listening on {}:{} over TLS", pgwireHost, pgwire.port());
            } else {
                log.info(
                        "PostgreSQL wire protocol listening on {}:{} -- NO TLS, the credential crosses "
                                + "the wire in the clear; set pravaha.pgwire.tls.certificate and .key, "
                                + "or use loopback or a terminator",
                        pgwireHost,
                        pgwire.port());
            }
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        // First: a standby promoting itself while the rest of this method runs would start a node
        // that is being shut down.
        closeQuietly("standby watch", standbyWatch);
        standbyWatch = null;
        // Reverse of startup: stop accepting, then let go of the queries, then leave the cluster.
        closeQuietly("Flight server", flight);
        closeQuietly("PostgreSQL wire server", pgwire);
        closeQuietly("registry", registry);
        // After the registry, because a running query may still be looking rows up in one.
        closeQuietly("dimension tables", lookupSources);
        // After the registry, which has already let go of every sink its registrations held; this
        // closes anything left, and must still run before the coordinator gives up its partitions.
        closeQuietly("sinks", pluginSinks);
        closeQuietly("cluster coordinator", coordinator);
        // Last: a claim is released only once nothing is still writing under it, or the next start
        // finds the directory free while this one is still finishing a checkpoint.
        stateClaims.forEach(claim -> closeQuietly("state claim on " + claim.directory(), claim));
        stateClaims.clear();
        // After everything that could still record a decision, so the last refusal a node made is
        // in the file rather than in a queue nobody drains.
        if (audit instanceof AutoCloseable closeable) {
            closeQuietly("audit sink", closeable);
            audit = null;
        }
    }

    /**
     * Claims {@code directory} for this node, or refuses to start and says who holds it.
     *
     * <p>The path is namespaced by node id rather than by host and port, and the reasoning is in
     * {@code StateOwnership}: an address identifies a location, a node id identifies a node, and
     * recovery needs the second. A node restarting on a new pod IP must still find its own
     * checkpoints. The address goes in the marker, which is where it answers the question the node
     * id cannot -- whether the other owner is still alive.
     */
    private void claimState(java.nio.file.Path directory, String what) {
        com.ash.messaging.pravaha.common.io.StateOwnership.Owner owner =
                com.ash.messaging.pravaha.common.io.StateOwnership.Owner.current(nodeId, flightHost, flightPort);
        stateClaims.add(com.ash.messaging.pravaha.common.io.StateOwnership.claim(
                directory, owner, com.ash.messaging.pravaha.common.io.StateOwnership.DEFAULT_LEASE, allowSharedState));
        log.info("claimed the {} {} for node '{}'", what, directory, nodeId);
    }

    private void closeQuietly(String what, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception failure) {
            // One component refusing to shut down must not stop the others from trying.
            log.warn("{} did not shut down cleanly: {}", what, failure.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The port Flight is listening on, which is worth knowing when the configured port was zero. */
    public Optional<Integer> flightPort() {
        return flight == null ? Optional.empty() : Optional.of(flight.port());
    }

    /** The port the PostgreSQL wire gateway is listening on; empty when it is not enabled. */
    public Optional<Integer> pgwirePort() {
        return pgwire == null ? Optional.empty() : Optional.of(pgwire.port());
    }

    public Optional<QueryRegistry> registry() {
        return Optional.ofNullable(registry);
    }

    /** The sink bindings this node resolved at start, for describing them; empty before it starts. */
    public Optional<com.ash.messaging.pravaha.bindings.egress.PluginSinks> sinks() {
        return Optional.ofNullable(pluginSinks);
    }

    /**
     * The source bindings this node reads, for striking their option values out of a stopped feed's
     * message before it leaves the node (FEED-1); empty before it starts.
     */
    /**
     * What has been dead-lettered on this node, readable (B5).
     *
     * <p>{@link com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore#NONE} until the node is
     * running and while no {@code pravaha.dlq.directory} is set -- a surface then answers "no
     * queue is configured", which is a different thing from "this query has rejected nothing" and
     * is said differently.
     */
    public com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore deadLetters() {
        PluginSourceFeeds open = feeds;
        return open == null ? com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE : open.deadLetters();
    }

    public Optional<PluginSourceFeeds> sources() {
        return Optional.ofNullable(feeds);
    }

    public Optional<ClusterCoordinator> coordinator() {
        return Optional.ofNullable(coordinator);
    }

    /**
     * Resolves a journalled owner id back to a principal for recovery.
     *
     * <p>Deliberately minimal, and deliberately not a lookup that invents authority: it reconstructs
     * the identity the registration was made under and lets the policy decide, rather than recovering
     * everything as an administrator. A deployment with a real directory should replace this, which
     * is why recovery takes the resolver as an argument rather than doing it itself.
     */
    private Optional<Principal> principalNamed(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<Principal> configured = security.principalFor(id);
        if (configured.isPresent()) {
            return configured;
        }
        if (security.authenticates()) {
            // An owner this node cannot identify is one whose entitlements it cannot check, so the
            // registration is refused and named in the recovery report rather than resurrected under
            // an invented identity. Refusing is visible; inventing is not.
            return Optional.empty();
        }
        // No identity source configured at all, so there is nothing to reconstruct from and nothing
        // that could be checked against it either. The anonymous principal is honest about that,
        // where a fabricated one with a made-up tenant was not.
        return Optional.of(Principal.ANONYMOUS);
    }

    /** For a status endpoint: what this node is currently doing. */
    public List<String> describe() {
        return List.of(
                "cluster: "
                        + (coordinator == null
                                ? "not started"
                                : coordinator.guarantees().name()),
                "flight: " + (flight == null ? "disabled" : flightHost + ":" + flight.port()),
                "registry: " + (registry == null ? "not started" : registry.size() + " queries"),
                laneSharing(),
                "journal: " + journalPath.map(Path::toString).orElse("none (queries are lost on restart)"));
    }

    /**
     * Whether queries share lanes, and how full each shared lane is (W9-8).
     *
     * <p>The line an operator reads to know what {@code pravaha.lane.multiplex.*} is doing: per
     * shared lane, how many queries it carries against its ceiling, and how many queries could not
     * be placed and hold a lane -- and an inbox -- of their own.
     */
    private String laneSharing() {
        QueryRegistry current = registry;
        if (current == null) {
            return "lanes: not started";
        }
        if (current.maxQueriesPerSharedLane() == 0) {
            return "lanes: one per query (pravaha.lane.multiplex.enabled is false)";
        }
        return "lanes: shared, queries per lane " + current.pipelinesPerSharedLane() + " of at most "
                + current.maxQueriesPerSharedLane() + "; " + current.queriesOnOwnLanes()
                + " on lanes of their own";
    }

    /** How long shutdown may take before Spring stops waiting. */
    public Duration shutdownTimeout() {
        return Duration.ofSeconds(30);
    }
}
