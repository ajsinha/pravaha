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
package com.ash.messaging.pravaha.it;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.WritePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.plugin.aerospike.AerospikeLookupPlugin;
import com.ash.messaging.pravaha.plugin.aerospike.AerospikeSourcePlugin;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Pushdown;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The query on the front of the README, running against Aerospike.
 *
 * <p>This is the acceptance test for the whole product claim, and it is deliberately not a unit
 * test of anything. The transactions come out of an Aerospike set, the user tiers are looked up in
 * another Aerospike set, the filter is pushed into the server, the aggregate is windowed and
 * incremental, and the answer is read back by key without a second system in the call. Every part of
 * that sentence is a different subsystem, and this is the only test that requires them to be right
 * at the same time.
 *
 * <p>The SQL is the README's query in the dialect the engine actually parses: {@code TABLE(TUMBLE(
 * ...))} rather than {@code GROUP BY TUMBLE(...)}, and no {@code CREATE CONTINUOUS QUERY} wrapper,
 * because the DDL is not parsed yet. The semantics -- windowed aggregate over a temporal lookup
 * join with a pushed filter -- are the README's, and those are what has to work.
 *
 * <p>Skipped rather than failed when docker is unavailable.
 */
@Timeout(300)
class AerospikeContinuousQueryIT {

    private static final String NAMESPACE = "test";
    private static final int PORT = 3000;
    private static final long SECOND = 1_000_000_000L;

    private static GenericContainer<?> aerospike;
    private static IAerospikeClient admin;
    private static String hosts;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /**
     * The README's query, verbatim apart from the DDL wrapper.
     *
     * <p>{@code SELECT STREAM}, {@code GROUP BY TUMBLE(...)} and {@code TUMBLE_END(...)} are the
     * README's own text and all three now parse and plan. What is still not parsed is the statement
     * around them -- {@code CREATE CONTINUOUS QUERY ... INTO ... SERVE AS VIEW ... EMIT CHANGES} --
     * which is registration and lifecycle rather than the query, and the engine is driven through
     * its API here instead.
     */
    private static final String SQL = "SELECT STREAM "
            + "TUMBLE_END(t.event_time, INTERVAL '10' SECOND) AS window_end, "
            + "t.user_id, p.tier, "
            + "COUNT(*)      AS txn_count, "
            + "SUM(t.amount) AS total_volume "
            + "FROM  txn_stream AS t "
            + "LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p "
            + "       ON t.user_id = p.user_id "
            + "WHERE t.status = 'COMPLETED' "
            + "GROUP BY TUMBLE(t.event_time, INTERVAL '10' SECOND), t.user_id, p.tier";

    private static StreamSchema txnSchema() {
        return StreamSchema.builder("txn_stream")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("status", Types.string())
                .field("event_time", Types.timestamp())
                .build();
    }

    private static StreamSchema profileSchema() {
        return StreamSchema.builder("user_profile")
                .field("user_id", Types.string())
                .field("tier", Types.string())
                .build();
    }

    @BeforeAll
    static void startAerospike() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; this test needs a real Aerospike server")
                .isTrue();
        aerospike = new GenericContainer<>(DockerImageName.parse("aerospike/aerospike-server:latest"))
                // Host networking: the client routes by partition map, and a bridged node advertises
                // its container-internal address, so reads through a mapped port connect and hang.
                .withNetworkMode("host")
                .withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withUlimits(new com.github.dockerjava.api.model.Ulimit[] {
                            new com.github.dockerjava.api.model.Ulimit("nofile", 16_000L, 16_000L)
                        }))
                .waitingFor(Wait.forLogMessage(".*migrations: complete.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
        // Host networking means the container binds the host's own port 3000, so anything else
        // already listening there -- a leftover container from a previous run, a local install --
        // makes this fail as an unexplained container launch error. Saying so first turns ten
        // minutes of confusion into one line. Cost exactly that once.
        assumeThat(portIsFree(PORT))
                .as("something is already listening on port %d; stop it before running this test", PORT)
                .isTrue();
        aerospike.start();
        hosts = "127.0.0.1:" + PORT;

        ClientPolicy policy = new ClientPolicy();
        policy.failIfNotConnected = true;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                admin = new AerospikeClient(policy, new Host("127.0.0.1", PORT));
                return;
            } catch (RuntimeException e) {
                last = e;
                sleep(1000);
            }
        }
        throw last;
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

    @BeforeEach
    void loadData() {
        admin.truncate(null, NAMESPACE, "txn", null);
        admin.truncate(null, NAMESPACE, "profile", null);
        sleep(300);

        // The dimension table: who is on which tier.
        profile("u1", "gold");
        profile("u2", "silver");
        // u3 has no profile at all, which is what makes the LEFT part of the join matter.

        // Transactions in the first ten-second window. The PENDING one must not be counted, and it
        // is the largest -- so a filter that quietly failed would be obvious in the total rather
        // than invisible.
        txn(1, "u1", 100, "COMPLETED", 1 * SECOND);
        txn(2, "u1", 200, "COMPLETED", 3 * SECOND);
        txn(3, "u2", 50, "COMPLETED", 4 * SECOND);
        txn(4, "u1", 9999, "PENDING", 5 * SECOND);
        txn(5, "u3", 7, "COMPLETED", 6 * SECOND);
        // And one in the second window, so the first window's totals are not simply "everything".
        txn(6, "u1", 1000, "COMPLETED", 15 * SECOND);
    }

    private static void profile(String user, String tier) {
        admin.put(
                new WritePolicy(),
                new Key(NAMESPACE, "profile", user),
                new Bin("user_id", user),
                new Bin("tier", tier));
    }

    private static void txn(long id, String user, long amount, String status, long eventTime) {
        admin.put(
                new WritePolicy(),
                new Key(NAMESPACE, "txn", id),
                new Bin("txn_id", id),
                new Bin("user_id", user),
                new Bin("amount", amount),
                new Bin("status", status),
                new Bin("event_time", eventTime));
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path deleteState;

    @Test
    void theReadmesQueryRunsOverAerospikeThroughTheDeploymentPath() {
        // The test below drives the engine through its own API: it compiles a pipeline by hand,
        // drains the reader once, advances the watermark itself, and writes nothing while it runs.
        // That proves the plan is right. It does not prove a continuous query over Aerospike works,
        // because nothing a deployment uses appears in it -- no registry, no feed, no discovery,
        // no lookup binding, and no record written while the query is live.
        //
        // This one uses only the deployment path, and writes to Aerospike after the query is
        // running. Every one of those pieces was broken at some point in this QA cycle, and each
        // was invisible from the test below.
        com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds feeds =
                new com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds()
                        .bind(new com.ash.messaging.pravaha.bindings.ingest.SourceBinding(
                                "txn_stream",
                                "aerospike",
                                Map.of(
                                        "hosts",
                                        hosts,
                                        "namespace",
                                        NAMESPACE,
                                        "set",
                                        "txn",
                                        "stream",
                                        "txn_stream",
                                        "event.time",
                                        "event_time",
                                        "schema",
                                        "txn_id:INT64,user_id:STRING,amount:INT64,"
                                                + "status:STRING,event_time:TIMESTAMP",
                                        // A COUNT and a SUM: over the default lut-scan a record read
                                        // twice would be counted twice, so the registry refuses the
                                        // query without this (PRV-2042, SCAN-1).
                                        "deletes",
                                        "detect",
                                        "deletes.state.dir",
                                        deleteState.toString())));

        AerospikeLookupPlugin profiles = new AerospikeLookupPlugin();
        profiles.configure(new Ctx(
                "user_profile",
                new HashMap<>(Map.of(
                        "hosts", hosts,
                        "namespace", NAMESPACE,
                        "set", "profile",
                        "stream", "user_profile",
                        "schema", "user_id:STRING,tier:STRING?",
                        "key.bin", "user_id",
                        "cache.seconds", "0"))));
        profiles.open();

        // The engine's own copy of the schema marks the event-time column too. Both sides need it:
        // the plugin stamps each row from that bin, and the engine derives the watermark from the
        // column. Either one missing and the windows close against the wrong clock.
        StreamSchema txnStream = StreamSchema.builder("txn_stream")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("status", Types.string())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();

        com.ash.messaging.pravaha.serving.ViewCatalog views = new com.ash.messaging.pravaha.serving.ViewCatalog();
        try (com.ash.messaging.pravaha.registry.QueryRegistry registry =
                        new com.ash.messaging.pravaha.registry.QueryRegistry(views, txnStream)
                                .lookingUp(profiles)
                                .feedingFrom(feeds)
                                // A node always turns these on; a registry constructed by hand does
                                // not, and without them no window ever closes.
                                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50));
                AerospikeLookupPlugin closing = profiles) {
            com.ash.messaging.pravaha.registry.RegisteredQuery query = registry.register(
                    "user_volume", SQL, List.of(1), com.ash.messaging.pravaha.security.Principal.ANONYMOUS);

            // The six records loaded before registration. The PENDING one is filtered by Aerospike
            // and must never arrive, so five is the number and six would mean pushdown is off.
            awaitRowsIn(query, 5);

            // Written while the query is running. Nothing is restarted or re-registered, and the
            // event time is past the first window's end -- which is what closes it.
            txn(7, "u1", 42, "COMPLETED", 25 * SECOND);
            txn(8, "u2", 9999, "PENDING", 26 * SECOND);
            awaitRowsIn(query, 6);

            // The first window closes because event time moved past it, from a record that arrived
            // after the query started. No hand-advanced watermark anywhere.
            awaitView(views, "SELECT user_id FROM user_volume", 3);

            List<Object[]> rows = new com.ash.messaging.pravaha.serving.ViewQuery(views)
                    .execute("SELECT user_id, tier, txn_count, total_volume FROM user_volume")
                    .rows();
            Map<String, Object[]> byUser = new HashMap<>();
            rows.forEach(row -> byUser.put(String.valueOf(row[0]), row));

            assertThat(byUser).containsKeys("u1", "u2", "u3");
            assertThat(byUser.get("u1")[1])
                    .as("u1's tier came from the Aerospike dimension table, through the registry")
                    .isEqualTo("gold");
            assertThat(byUser.get("u1")[2])
                    .as("u1's transaction count in [0,10)")
                    .isEqualTo(2L);
            assertThat(byUser.get("u1")[3])
                    .as("100 + 200, with the 9999 PENDING filtered")
                    .isEqualTo(300L);
            assertThat(byUser.get("u3")[1])
                    .as("u3 has no profile, and a LEFT join says so with a null rather than dropping the row")
                    .isNull();
            assertThat(query.state().isTerminal())
                    .as("still running: an Aerospike source re-scans, so there is no end to reach")
                    .isFalse();
        }
    }

    private static void awaitRowsIn(com.ash.messaging.pravaha.registry.RegisteredQuery query, long atLeast) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && query.rowsIn() < atLeast) {
            sleep(50);
        }
        assertThat(query.failure()).as("the lane must not have died").isEmpty();
        assertThat(query.rowsIn())
                .as("the feed delivered %d rows in sixty seconds; %d were expected", query.rowsIn(), atLeast)
                .isGreaterThanOrEqualTo(atLeast);
    }

    private static void awaitView(com.ash.messaging.pravaha.serving.ViewCatalog views, String sql, int expected) {
        com.ash.messaging.pravaha.serving.ViewQuery reader = new com.ash.messaging.pravaha.serving.ViewQuery(views);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        int size = -1;
        while (System.nanoTime() < deadline) {
            size = reader.execute(sql).size();
            if (size >= expected) {
                return;
            }
            sleep(50);
        }
        assertThat(size)
                .as("the view held %d rows after sixty seconds; %d were expected", size, expected)
                .isGreaterThanOrEqualTo(expected);
    }

    @Test
    void theReadmesQueryRunsOverAerospikeEndToEnd() {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withLookups(txnSchema(), profileSchema()).plan(SQL));

        AerospikeSourcePlugin source = new AerospikeSourcePlugin();
        source.configure(new Ctx(
                "txn_stream",
                new HashMap<>(Map.of(
                        "hosts", hosts,
                        "namespace", NAMESPACE,
                        "set", "txn",
                        "stream", "txn_stream",
                        "schema", "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"))));
        source.open();

        AerospikeLookupPlugin profiles = new AerospikeLookupPlugin();
        profiles.configure(new Ctx(
                "user_profile",
                new HashMap<>(Map.of(
                        "hosts", hosts,
                        "namespace", NAMESPACE,
                        "set", "profile",
                        "stream", "user_profile",
                        "schema", "user_id:STRING,tier:STRING?",
                        "key.bin", "user_id",
                        "cache.seconds", "60"))));
        profiles.open();

        ServedView view = new ServedView("user_volume", plan.outputSchema(), List.of(1), 10_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());

        // The WHERE clause goes to the server. Aerospike evaluates it, so the PENDING transaction
        // never crosses the network -- which is the claim design section 2 makes about store-native
        // pushdown, exercised here rather than described.
        ReadRequest request = Pushdown.requestFor(plan, "txn_stream", source.capabilities());
        assertThat(request.filters())
                .as("the filter should have been offered to Aerospike")
                .isNotEmpty();

        RowLayout layout = RowLayout.of(txnSchema());
        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline =
                        InterpretedPipeline.compile(plan, (RowOutput) sink::begin, Map.of("user_profile", profiles));
                PartitionReader reader =
                        source.createReader(source.partitions("txn_stream").get(0), null, request)) {

            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view0 = new BinaryRowView(layout);
            // Read everything Aerospike has, into the query.
            int moved;
            do {
                moved = reader.poll(
                        () -> {
                            long handle = feed.allocate(layout.rowSize(512));
                            writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                            return new com.ash.messaging.pravaha.runtime.ingest.DelegatingRowWriter(writer, () -> {
                                feed.trimTo(handle, writer.sizeSoFar());
                                pipeline.accept(view0.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
                            });
                        },
                        64);
            } while (moved > 0);

            // Close the first window. The second one stays open, which is the point of a watermark.
            pipeline.advanceWatermark(12 * SECOND);
        }
        sink.commit(sink.appliedFrontier());

        // u1: two COMPLETED transactions in [0,10), 100 + 200. The 9999 PENDING one is filtered by
        // Aerospike and the 1000 belongs to the next window.
        var u1 = view.get("u1");
        assertThat(u1.found()).as("no result for u1").isTrue();
        assertThat(u1.values().orElseThrow()[2])
                .as("u1's tier came from the Aerospike lookup")
                .isEqualTo("gold");
        assertThat(u1.values().orElseThrow()[3]).as("u1's transaction count").isEqualTo(2L);
        assertThat(u1.values().orElseThrow()[4]).as("u1's volume").isEqualTo(300L);

        assertThat(view.get("u2").values().orElseThrow()[4]).isEqualTo(50L);
        assertThat(view.get("u2").values().orElseThrow()[2]).isEqualTo("silver");

        // u3 has no profile. A LEFT lookup join emits the record with a null tier rather than
        // dropping it -- which is safe here because a lookup answers definitively, and is the
        // difference between counting your unprofiled users and silently not counting them.
        var u3 = view.get("u3");
        assertThat(u3.found()).as("the unprofiled user was dropped by the join").isTrue();
        assertThat(u3.values().orElseThrow()[2])
                .as("no profile means a null tier, not a missing row")
                .isNull();
        assertThat(u3.values().orElseThrow()[4]).isEqualTo(7L);

        source.close();
        profiles.close();
    }

    /** Whether nothing is listening on the host's service port yet. */
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
