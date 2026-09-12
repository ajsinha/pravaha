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
import java.util.Optional;
import java.util.Set;

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
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.AuthenticatedOnlyPolicy;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.serving.ViewCatalog;

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
    private final SecurityProperties security;
    private final File tlsCertificate;
    private final File tlsKey;
    private volatile PluginSourceFeeds feeds;
    private volatile ClusterCoordinator coordinator;
    private volatile boolean running;

    public PravahaNode(
            StreamCatalog streams,
            SourceBindingProperties sources,
            SecurityProperties security,
            @Value("${pravaha.flight.tls.certificate:}") String tlsCertificate,
            @Value("${pravaha.flight.tls.key:}") String tlsKey,
            @Value("${pravaha.flight.enabled:true}") boolean flightEnabled,
            @Value("${pravaha.flight.host:0.0.0.0}") String flightHost,
            @Value("${pravaha.flight.port:9090}") int flightPort,
            @Value("${pravaha.registry.journal:}") String journal,
            @Value("${pravaha.cluster.mode:SINGLE}") String clusterMode,
            @Value("${pravaha.cluster.mechanism:single}") String clusterMechanism,
            @Value("${pravaha.node.id:pravaha-node-01}") String nodeId) {
        this.streams = streams;
        this.sources = sources;
        this.security = security;
        this.tlsCertificate = tlsCertificate == null || tlsCertificate.isBlank() ? null : new File(tlsCertificate);
        this.tlsKey = tlsKey == null || tlsKey.isBlank() ? null : new File(tlsKey);
        this.nodeId = nodeId;
        this.flightEnabled = flightEnabled;
        this.flightHost = flightHost;
        this.flightPort = flightPort;
        this.journalPath = journal == null || journal.isBlank() ? Optional.empty() : Optional.of(Path.of(journal));
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
        boolean open = !security.authenticates() && !(securityPolicy() instanceof AuthenticatedOnlyPolicy);
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
        if (security.authenticates() && tlsCertificate == null) {
            // Not fatal: a sidecar or a service mesh may be terminating TLS in front of this. It is
            // logged at warn because a bearer token on a plaintext socket is handed to anyone on
            // the path, which makes the token a formality rather than a control.
            log.warn("authentication is on and Flight is serving plaintext, so credentials travel in the "
                    + "clear; set pravaha.flight.tls.certificate and .key unless something in front of "
                    + "this node is terminating TLS");
        }
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

        views = new ViewCatalog();
        SecurityPolicy policy = securityPolicy();
        AuditSink audit = auditSink();
        registry = new QueryRegistry(views, policy, audit, streams.all().toArray(new StreamSchema[0]));
        log.info(
                "security: authentication={}, policy={}, audit={}, flight transport={}",
                security.authenticates() ? "token" : "none",
                policy,
                security.getAudit(),
                tlsCertificate == null ? "PLAINTEXT" : "TLS");

        // Before recovery, and that ordering is the point: a recovered query is registered the same
        // way a fresh one is, so a factory attached afterwards would feed everything registered
        // from now on and nothing the journal brought back -- queries that look identical in every
        // listing and differ only in whether rows arrive.
        feeds = new PluginSourceFeeds();
        sources.toBindings().forEach(feeds::bind);
        registry.feedingFrom(feeds);
        if (feeds.bindings().isEmpty()) {
            log.info("no sources are bound, so registered queries receive rows only from clients that push "
                    + "them; bind one under pravaha.sources.<stream>");
        } else {
            log.info("sources bound: {}", feeds.bindings().values());
        }

        journalPath.ifPresent(path -> {
            registry.journalTo(new RegistryJournal(path));
            QueryRegistry.Recovery recovery = registry.recover(PravahaNode::principalNamed);
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
            PravahaFlightServer server = new PravahaFlightServer(views)
                    .hosting(registry)
                    // The same policy object the registry authorizes against. The Flight server
                    // refuses a mismatch rather than letting two halves of one deployment disagree
                    // about who may read what.
                    .authorizedBy(securityPolicyOf(registry), auditSink());
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
    private static Optional<Principal> principalNamed(String id) {
        return id == null || id.isBlank()
                ? Optional.empty()
                : Optional.of(new Principal(id, "unknown", Set.of(), java.util.Map.of()));
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
