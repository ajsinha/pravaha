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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.CoordinatorFactory;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
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
    private volatile ClusterCoordinator coordinator;
    private volatile boolean running;

    public PravahaNode(
            StreamCatalog streams,
            @Value("${pravaha.flight.enabled:true}") boolean flightEnabled,
            @Value("${pravaha.flight.host:0.0.0.0}") String flightHost,
            @Value("${pravaha.flight.port:8815}") int flightPort,
            @Value("${pravaha.registry.journal:}") String journal,
            @Value("${pravaha.cluster.mode:SINGLE}") String clusterMode,
            @Value("${pravaha.cluster.mechanism:single}") String clusterMechanism,
            @Value("${pravaha.node.id:pravaha-node-01}") String nodeId) {
        this.streams = streams;
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

        views = new ViewCatalog();
        registry = new QueryRegistry(
                views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, streams.all().toArray(new StreamSchema[0]));
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
            flight = new PravahaFlightServer(views).hosting(registry).start(flightHost, flightPort);
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
