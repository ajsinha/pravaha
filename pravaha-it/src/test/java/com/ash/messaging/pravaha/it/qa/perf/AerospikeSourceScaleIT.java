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
package com.ash.messaging.pravaha.it.qa.perf;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.WritePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.server.ingest.SourceBinding;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * What N continuous queries over one Aerospike set cost the Aerospike cluster.
 *
 * <p>This is ADR-036 §3 turned into a number, and it is the crux of the stated target. ADR-029 makes
 * the plugin scan-only, so the question "a thousand Aerospike-backed continuous queries on one
 * instance" is really the question "what does that do to the store". The ADR asserts one scan per
 * query from reading the code. Reading the code is not a measurement, and the load lands on somebody
 * else's cluster, so it is measured here against a real server: the numbers below are Aerospike's own
 * counters, read out of {@code namespace/test} and {@code statistics}, not the plugin's.
 *
 * <p>Two things are measured and both are per <em>registration</em> rather than per set:
 *
 * <ul>
 *   <li><strong>Scans.</strong> {@code pi_query_*} on the namespace counts primary-index queries,
 *       which is what {@code scanPartitions} is. If N queries over one set were one scan, this would
 *       not move with N.
 *   <li><strong>Connections.</strong> {@code client_connections} on the node. Since SRC-2 every
 *       {@code AerospikeSourcePlugin.open()} against one cluster and credential shares a single
 *       {@code AerospikeClient} -- one cluster object, one tend thread, one per-node pool. It was
 *       one of each per registration, and both assertions below used to demand exactly that.
 * </ul>
 *
 * <p>The queries deliberately have <strong>different SQL over the same set</strong>. Identical SQL
 * shares one computation by fingerprint and therefore one feed, which is well covered
 * elsewhere and is not the case a deployment is in. Different questions over the same data is the
 * case the target describes.
 *
 * <p>Skipped rather than failed when docker is unavailable -- but note that "skipped" means this
 * question has no answer on that machine, not that the answer is fine.
 */
@Timeout(600)
class AerospikeSourceScaleIT {

    private static final String NAMESPACE = "test";
    private static final String SET = "txn";
    private static final int PORT = 3000;

    /**
     * How many continuous queries to register over the one set.
     *
     * <p>Four, not a thousand. The shape of the answer -- proportional or shared -- is visible at
     * four and does not need a thousand to establish, and a thousand real scans against a
     * containerised single node in a test suite would measure the container's disk.
     */
    private static final int QUERIES = 4;

    /** How many records the set holds. Small: this measures scan *rate*, not scan throughput. */
    private static final int RECORDS = 200;

    /** How long to let the queries run while the cluster's counters are watched. */
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static GenericContainer<?> aerospike;
    private static IAerospikeClient admin;
    private static String hosts;

    private static StreamSchema txn() {
        return StreamSchema.builder("txn_stream")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    @BeforeAll
    static void startAerospike() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the cost of N queries to an Aerospike cluster cannot be measured "
                        + "without an Aerospike cluster, and reasoning about it is not the same answer")
                .isTrue();
        assumeThat(portIsFree(PORT))
                .as("something is already listening on port %d; host networking needs it free", PORT)
                .isTrue();
        aerospike = new GenericContainer<>(DockerImageName.parse("aerospike/aerospike-server:latest"))
                // Host networking: an Aerospike client routes by partition map, and a bridged node
                // advertises its container-internal address, so reads through a mapped port connect
                // and then hang. See AerospikeContinuousQueryIT, which paid for that discovery.
                .withNetworkMode("host")
                .withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withUlimits(new com.github.dockerjava.api.model.Ulimit[] {
                            new com.github.dockerjava.api.model.Ulimit("nofile", 16_000L, 16_000L)
                        }))
                .waitingFor(Wait.forLogMessage(".*migrations: complete.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
        aerospike.start();
        hosts = "127.0.0.1:" + PORT;

        ClientPolicy policy = new ClientPolicy();
        policy.failIfNotConnected = true;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                admin = new AerospikeClient(policy, new Host("127.0.0.1", PORT));
                load();
                return;
            } catch (RuntimeException e) {
                last = e;
                sleep(1000);
            }
        }
        throw last;
    }

    private static void load() {
        for (int i = 0; i < RECORDS; i++) {
            admin.put(
                    new WritePolicy(),
                    new Key(NAMESPACE, SET, i),
                    new Bin("txn_id", (long) i),
                    new Bin("user_id", "u" + (i % 17)),
                    new Bin("amount", 100L + i));
        }
    }

    @AfterAll
    static void stopAerospike() {
        if (admin != null) {
            admin.close();
        }
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    @Test
    void whatNQueriesOverOneSetCostTheCluster() {
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn_stream",
                        "aerospike",
                        Map.of(
                                "hosts", hosts,
                                "namespace", NAMESPACE,
                                "set", SET,
                                "stream", "txn_stream",
                                "schema", "txn_id:INT64,user_id:STRING,amount:INT64")));

        ViewCatalog views = new ViewCatalog();
        long connectionsIdle = clientConnections();
        long threadsIdle = platformThreads();
        Map<String, Long> threadNamesIdle = platformThreadNames();
        long clusterCpuIdle = clusterCpuPercent();

        try (QueryRegistry registry = new QueryRegistry(views, txn()).feedingFrom(feeds)) {

            // One query first, so the per-query rate is measured rather than divided out of a total.
            Sample beforeOne = sample();
            registry.register("q0", "SELECT user_id, amount FROM txn_stream WHERE amount > 0", List.of(0), DANA);
            long connectionsOne = clientConnections();
            long threadsOne = platformThreads();
            sleep(WINDOW.toMillis());
            Sample afterOne = sample();
            long clusterCpuOne = clusterCpuPercent();

            // Then the rest, each a different question over the same set: a different plan, a
            // different fingerprint, and therefore nothing shared with the first.
            for (int i = 1; i < QUERIES; i++) {
                registry.register(
                        "q" + i, "SELECT user_id, amount FROM txn_stream WHERE amount > " + i, List.of(0), DANA);
            }
            long connectionsAll = clientConnections();
            long threadsAll = platformThreads();
            // A multiset, because the names repeat: four lanes are four threads called
            // pravaha-query-0, and four Aerospike clients are four threads sharing one name. A set
            // difference showed one of each and hid exactly the per-query threads being counted.
            Map<String, Long> added = new java.util.TreeMap<>();
            platformThreadNames()
                    .forEach((name, count) -> added.put(name, count - threadNamesIdle.getOrDefault(name, 0L)));
            added.values().removeIf(count -> count <= 0);
            Sample beforeMany = sample();
            sleep(WINDOW.toMillis());
            Sample afterMany = sample();
            long clusterCpuAll = clusterCpuPercent();

            double scansPerSecondOne = afterOne.scansSince(beforeOne) / (double) WINDOW.toSeconds();
            double scansPerSecondMany = afterMany.scansSince(beforeMany) / (double) WINDOW.toSeconds();

            System.out.printf(
                    "%nAEROSPIKE SOURCE SCALE: %d continuous queries, different SQL, one set (%s.%s, %d records)%n"
                            + "  scans of the set, from the cluster's own pi_query counters:%n"
                            + "    1 query   : %6d scans in %ds = %8.1f scans/s  (%8.1f per query)%n"
                            + "    %d queries : %6d scans in %ds = %8.1f scans/s  (%8.1f per query)%n"
                            + "  client connections on the node:%n"
                            + "    no queries: %3d      1 query: %3d (+%d)      %d queries: %3d (+%d)%n"
                            + "  platform threads in this JVM:%n"
                            + "    no queries: %3d      1 query: %3d (+%d)      %d queries: %3d (+%d)%n"
                            + "  the cluster's own cpu, as it reports it:%n"
                            + "    no queries: %3d%%      1 query: %3d%%              %d queries: %3d%%%n"
                            + "%n  Registrations now share one AerospikeClient per cluster per credential%n"
                            + "  (SRC-2): one cluster object, one partition map, one tend thread, one pool for%n"
                            + "  all of them. It was one of each per registration -- so the tend thread was a%n"
                            + "  *platform* thread that scaled with the query count, and maxConnsPerNode's%n"
                            + "  default of 100 was a per-query ceiling rather than a node-wide one.%n"
                            + "%n  The scan rate is now bounded by scan.interval.ms, one second by default.%n"
                            + "  It was bounded by nothing: LutScanReader.scan() ran whenever poll() found its%n"
                            + "  buffer empty and PumpingFeed polls every millisecond, so one query alone took%n"
                            + "  the cluster from 1%% to 200-310%% CPU. Scans still scale with registrations%n"
                            + "  rather than with sets, which is what ADR-036 section 3's shared scan is for.%n",
                    QUERIES,
                    NAMESPACE,
                    SET,
                    RECORDS,
                    afterOne.scansSince(beforeOne),
                    WINDOW.toSeconds(),
                    scansPerSecondOne,
                    scansPerSecondOne,
                    QUERIES,
                    afterMany.scansSince(beforeMany),
                    WINDOW.toSeconds(),
                    scansPerSecondMany,
                    scansPerSecondMany / QUERIES,
                    connectionsIdle,
                    connectionsOne,
                    connectionsOne - connectionsIdle,
                    QUERIES,
                    connectionsAll,
                    connectionsAll - connectionsIdle,
                    threadsIdle,
                    threadsOne,
                    threadsOne - threadsIdle,
                    QUERIES,
                    threadsAll,
                    threadsAll - threadsIdle,
                    clusterCpuIdle,
                    clusterCpuOne,
                    QUERIES,
                    clusterCpuAll);

            // Named, because "N threads a query" is a claim and the names are the evidence for what
            // they are. Printed raw: a stem-merge hid the very distinction that matters here.
            System.out.printf("  the platform threads %d Aerospike-backed queries added:%n", QUERIES);
            added.forEach((name, count) -> System.out.printf("    %-32s x%d%n", name, count));
            System.out.println();

            assertThat(afterOne.scansSince(beforeOne))
                    .as("one continuous query over an Aerospike set must produce scans of it, or the plugin is "
                            + "not reading and every other number here is meaningless")
                    .isPositive();

            // The scan interval, which this test is what found the absence of. LutScanReader started
            // a scan whenever poll() found its buffer empty and the pump polls every millisecond, so
            // one query ran 43-153 scans a second of a set nobody was writing to and took the
            // cluster from 1% to 200-310% CPU. That is a defect at one query, not at a thousand.
            //
            // Bounded by scan.interval.ms, one second by default. Generously asserted -- three
            // scans a second against a configured one -- because the first scan does not wait and a
            // slow scan pushes the next one out rather than in. What it refuses is the old
            // behaviour, which was two orders of magnitude above this line.
            assertThat(scansPerSecondOne)
                    .as(
                            "one query produced %.1f scans/s. With scan.interval.ms at its %dms default the rate "
                                    + "is bounded by the interval rather than by how fast the cluster can answer",
                            scansPerSecondOne, 1000)
                    .isLessThan(3.0);

            // The measurement ADR-036 section 3 asserts from the code. Recorded as an assertion so
            // that the day a shared scan exists, this test is what says so.
            assertThat(scansPerSecondMany)
                    .as(
                            "%d queries over one set produced %.1f scans/s against %.1f scans/s for one. Scans "
                                    + "scale with the number of registrations, not with the number of sets: a "
                                    + "thousand continuous queries over one set is a thousand scans of it, and "
                                    + "that load is on the store. This assertion is the one that changes when "
                                    + "ADR-036 section 3's shared scan exists",
                            QUERIES, scansPerSecondMany, scansPerSecondOne)
                    .isGreaterThan(scansPerSecondOne * 1.5);

            // SRC-2 fixed: one client per cluster per credential, shared by every registration.
            // This assertion used to demand the opposite -- connections >= QUERIES -- and it was
            // right to, because that was the behaviour. It is inverted rather than deleted so the
            // day sharing regresses, this is what says so.
            assertThat(connectionsAll - connectionsIdle)
                    .as(
                            "%d registrations opened %d client connections. They share one client, so the "
                                    + "connection count follows the work in flight rather than the number of "
                                    + "registrations -- the ceiling is one pool's maxConnsPerNode, not a "
                                    + "thousand of them. See SRC-2",
                            QUERIES, connectionsAll - connectionsIdle)
                    .isLessThan(QUERIES);

            // The number ADR-036 does not have. NodeScaleTest measures one platform thread per
            // query -- the lane -- against a registry whose queries are fed by nobody. An
            // Aerospike-backed query is the workload the target actually names, and it pays a
            // second one: `tend` is the Aerospike client's cluster thread, it is a platform thread,
            // and there is one client per registration because AerospikeSourcePlugin.open()
            // constructs one and nothing shares it.
            //
            // Named rather than counted in the aggregate, because the aggregate also contains the
            // virtual scheduler's carriers and the JDK's NIO pollers, which are bounded pools and
            // do not scale with the query count. These two do.
            // The lane's threads are reported and not asserted, because W9-4 is in the middle of
            // changing what that number is: lanes can now share a thread, so "one per query" is a
            // fact about a particular build rather than about the engine. NodeScaleTest owns that
            // ratchet and states it as a bound on cores.
            System.out.printf(
                    "  lane threads for %d queries: %d (reported, not asserted -- W9-4 shares them)%n",
                    QUERIES, added.getOrDefault("pravaha-query-0", 0L));

            // This one is asserted, and it is the point. `tend` is the Aerospike client's cluster
            // thread: one per client, one client per registration, because AerospikeSourcePlugin
            // .open() constructs its own and nothing shares it. It is a *platform* thread and it
            // scales with the query count, which is exactly the property W9-2 and W9-4 removed from
            // everything else a query owns.
            //
            // Named rather than counted in the aggregate, because the aggregate also holds the
            // virtual scheduler's carriers and the JDK's NIO pollers, which are bounded pools.
            assertThat(added.getOrDefault("tend", 0L))
                    .as(
                            "%d Aerospike-backed registrations started %d `tend` threads. One client per cluster "
                                    + "per credential means one cluster thread for all of them, however many "
                                    + "register -- the last platform thread that scaled with the query count. "
                                    + "See SRC-2",
                            QUERIES, added.getOrDefault("tend", 0L))
                    .isLessThanOrEqualTo(1L);
        }
    }

    private static long platformThreads() {
        return Thread.getAllStackTraces().keySet().size();
    }

    /** Every platform thread, by name, <em>with multiplicity</em>. The multiplicity is the point. */
    private static Map<String, Long> platformThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Thread::getName, java.util.TreeMap::new, java.util.stream.Collectors.counting()));
    }

    /** What this scanning is costing the cluster, as the cluster sees it. */
    private static long clusterCpuPercent() {
        return info("statistics").getOrDefault("process_cpu_pct", -1L);
    }

    // -----------------------------------------------------------------------------------------
    // The cluster's own counters
    // -----------------------------------------------------------------------------------------

    /** Every {@code pi_query_*} counter on the namespace, which is what a partition scan increments. */
    private record Sample(Map<String, Long> counters) {

        long scans() {
            return counters.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("pi_query_"))
                    .filter(entry -> entry.getKey().endsWith("_complete")
                            || entry.getKey().endsWith("_error")
                            || entry.getKey().endsWith("_abort")
                            || entry.getKey().endsWith("_timeout"))
                    .mapToLong(Map.Entry::getValue)
                    .sum();
        }

        long scansSince(Sample earlier) {
            return scans() - earlier.scans();
        }
    }

    private static Sample sample() {
        return new Sample(info("namespace/" + NAMESPACE));
    }

    private static long clientConnections() {
        return info("statistics").getOrDefault("client_connections", -1L);
    }

    private static Map<String, Long> info(String command) {
        Node node = admin.getNodes()[0];
        String response = com.aerospike.client.Info.request(null, node, command);
        Map<String, Long> values = new LinkedHashMap<>();
        for (String pair : response.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                values.put(pair.substring(0, equals), Long.parseLong(pair.substring(equals + 1)));
            } catch (NumberFormatException notANumber) {
                // Plenty of these are strings -- cluster keys, node ids, booleans. Only the counters
                // matter here and a string is simply not one.
            }
        }
        return values;
    }

    private static boolean portIsFree(int port) {
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return false;
        } catch (java.io.IOException refused) {
            return true;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
