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
import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.CoordinatorFactory;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.server.ingest.SourceBinding;
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
    private final boolean flightEnabled;
    private final String flightHost;
    private final int flightPort;
    private final Optional<Path> journalPath;
    private final Configuration clusterConfiguration;
    private final String nodeId;

    private volatile ViewCatalog views;
    private volatile QueryRegistry registry;
    private volatile PravahaFlightServer flight;
    private final SourceBindingProperties sources;
    private final StreamDeclarationProperties declaredStreams;
    private final SecurityProperties security;
    private final File tlsCertificate;
    private final File tlsKey;
    private final Duration watermarkIdleAfter;
    private final Duration watermarkTick;
    private volatile PluginSourceFeeds feeds;
    private final Optional<Path> checkpointPath;
    private final Configuration checkpointConfiguration;
    private volatile ClusterCoordinator coordinator;
    private volatile boolean running;

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
            @Value("${pravaha.node.id:pravaha-node-01}") String nodeId) {
        this.streams = streams;
        this.sources = sources;
        this.declaredStreams = declaredStreams;
        this.security = security;
        this.tlsCertificate = tlsCertificate == null || tlsCertificate.isBlank() ? null : new File(tlsCertificate);
        this.tlsKey = tlsKey == null || tlsKey.isBlank() ? null : new File(tlsKey);
        this.watermarkIdleAfter = watermarkIdleAfter;
        this.watermarkTick = watermarkTick;
        this.nodeId = nodeId;
        this.flightEnabled = flightEnabled;
        this.flightHost = flightHost;
        this.flightPort = flightPort;
        this.journalPath = persistence.journalPath();
        this.checkpointPath = persistence.checkpointPath();
        this.checkpointConfiguration = persistence.checkpointConfiguration();
        this.clusterConfiguration = Configuration.builder()
                .set("pravaha.cluster.mode", clusterMode)
                .set("pravaha.cluster.mechanism", clusterMechanism)
                .build();
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
        // An open server is one that serves data to callers it has not identified. That is true
        // whenever the policy admits anonymous callers, regardless of whether a token mechanism also
        // exists -- my first version required authentication to be off entirely, which made
        // allow-anonymous silently dead on exactly the configuration a team reaches by hardening dev.
        boolean open = !(securityPolicy() instanceof AuthenticatedOnlyPolicy);
        if (open && !security.isAllowAnonymous()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    "this node is configured to accept unauthenticated callers and serve them every view "
                            + "(pravaha.security.authentication=none, policy=" + security.getPolicy() + "). That "
                            + "is a reasonable way to run an engine behind a boundary that has already "
                            + "authenticated the caller, and a bad way to run one on a network. Set "
                            + "pravaha.security.authentication=token with pravaha.security.tokens.*, or set "
                            + "pravaha.security.policy=authenticated, or -- if open really is what you want -- "
                            + "set pravaha.security.allow-anonymous=true to say so on purpose.");
        }
        if (!security.authenticates() && securityPolicy() instanceof AuthenticatedOnlyPolicy) {
            // A contradiction, and a dangerous one rather than a merely silly one. The policy says
            // only authenticated callers see anything and the node offers no way to authenticate, so
            // Flight correctly refuses everybody -- while the HTTP surface, which has no filter
            // because authentication is off and does not consult the policy, keeps serving stream
            // schemas and accepting stream registrations from anyone who can reach the port. The
            // configuration reads as locked down and leaves a door open.
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
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
        if (declaration.getEventTime() == null || declaration.getEventTime().isBlank()) {
            return parsed;
        }
        StreamSchema.Builder builder = StreamSchema.builder(name);
        parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
        String column = declaration.getEventTime().strip();
        if (!parsed.hasField(column)) {
            throw new PravahaException(
                    SqlErrors.VALIDATION_FAILED,
                    "stream '" + name + "' declares '" + column + "' as its event time and has no such column. "
                            + "Its columns are "
                            + parsed.fields().stream()
                                    .map(com.ash.messaging.pravaha.api.data.Field::name)
                                    .toList()
                            + ".");
        }
        builder.eventTime(column);
        if (declaration.getOutOfOrderness() != null) {
            builder.outOfOrderness(declaration.getOutOfOrderness());
        }
        return builder.build();
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
            case "authenticated", "authenticated-only" -> new AuthenticatedOnlyPolicy();
            default ->
                throw new PravahaException(
                        SecurityErrors.FORBIDDEN,
                        "pravaha.security.policy is '" + configured + "', which is not a policy this node "
                                + "knows. Use 'permissive' or 'authenticated', or implement SecurityPolicy "
                                + "for rules of your own.");
        };
    }

    /** The policy the registry was actually built with, so the two halves cannot disagree. */
    private static SecurityPolicy securityPolicyOf(QueryRegistry registry) {
        return registry.policy();
    }

    private AuditSink auditSink() {
        String configured =
                security.getAudit() == null ? "none" : security.getAudit().trim();
        return switch (configured.toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> AuditSink.NONE;
            case "memory" -> audit == null ? (audit = new AuditSink.InMemory()) : audit;
            default ->
                throw new PravahaException(
                        SecurityErrors.FORBIDDEN,
                        "pravaha.security.audit is '" + configured + "'; use 'none' or 'memory'.");
        };
    }

    private AuditSink.InMemory audit;

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
        coordinator = CoordinatorFactory.create(clusterConfiguration);
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
        log.info(
                "security: authentication={}, policy={}, audit={}, flight transport={}",
                security.authenticates() ? "token" : "none",
                // The configured name, not the object: SecurityPolicy.PERMISSIVE is an anonymous
                // class, and "SecurityPolicy$1@7657d90b" in the one line an operator reads to check
                // how a node is secured is worse than not logging it.
                security.getPolicy(),
                security.getAudit(),
                tlsCertificate == null ? "PLAINTEXT" : "TLS");

        // Before recovery, and that ordering is the point: a recovered query is registered the same
        // way a fresh one is, so a factory attached afterwards would feed everything registered
        // from now on and nothing the journal brought back -- queries that look identical in every
        // listing and differ only in whether rows arrive.
        // Before the feed factory, so that a query is checkpointed from its first row rather than
        // from whenever the next interval happens to land.
        checkpointPath.ifPresentOrElse(
                path -> {
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
        sources.toBindings().forEach(binding -> feeds.bind(withDeclaredEventTime(binding)));
        registry.feedingFrom(feeds);
        if (feeds.bindings().isEmpty()) {
            log.info("no sources are bound, so registered queries receive rows only from clients that push "
                    + "them; bind one under pravaha.sources.<stream>");
        } else {
            log.info("sources bound: {}", feeds.bindings().values());
        }

        journalPath.ifPresent(path -> {
            registry.journalTo(new RegistryJournal(path));
            QueryRegistry.Recovery recovery = registry.recover(this::principalNamed);
            log.info(
                    "registry recovered {} of {} queries from {}",
                    recovery.recovered().size(),
                    recovery.recovered().size() + recovery.refused().size(),
                    path);
            // The refused list is the one that matters: each entry is a view some client expects to
            // find and will not, so it is logged per query rather than counted.
            recovery.refused().forEach(refusal -> log.warn("registration not recovered -- {}", refusal));
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
                    .hosting(registry);
            TokenVerifier verifier = security.verifier();
            if (verifier != null) {
                server.authenticatedBy(verifier);
            }
            if (tlsCertificate != null) {
                server.encryptedWith(tlsCertificate, tlsKey);
            }
            flight = server.start(flightHost, flightPort);
            log.info("Flight SQL listening on {}:{}", flightHost, flight.port());
        } else {
            log.info("Flight SQL disabled (pravaha.flight.enabled=false); this node serves HTTP only");
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        // Reverse of startup: stop accepting, then let go of the queries, then leave the cluster.
        closeQuietly("Flight server", flight);
        closeQuietly("registry", registry);
        closeQuietly("cluster coordinator", coordinator);
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

    public Optional<QueryRegistry> registry() {
        return Optional.ofNullable(registry);
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
                "journal: " + journalPath.map(Path::toString).orElse("none (queries are lost on restart)"));
    }

    /** How long shutdown may take before Spring stops waiting. */
    public Duration shutdownTimeout() {
        return Duration.ofSeconds(30);
    }
}
