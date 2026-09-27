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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.cassandra.CassandraContainer;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.ReadRequest.Comparison;
import com.ash.messaging.pravaha.api.plugin.ReadRequest.Filter;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Filters on a table's key pushed to a real Cassandra: the partitions a filter names are read and
 * nothing else, clustering restrictions slice them, none of it needs {@code ALLOW FILTERING} -- the
 * server would refuse the statement otherwise -- and a filter on anything else reads the table as
 * before.
 */
@Timeout(300)
class CassandraPushdownIT {

    private static final String SCHEMA = "tenant:STRING,id:INT64,day:INT32,ts:TIMESTAMP,status:STRING,amount:INT64";

    private static final StreamSchema EVENTS = CassandraSchemas.parse("events", SCHEMA);

    private static CassandraContainer cassandra;
    private static CqlSession admin;
    private static String contactPoints;
    private static String localDatacenter;

    @TempDir
    Path stateDir;

    private final List<CassandraSourcePlugin> plugins = new ArrayList<>();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @BeforeAll
    static void startServer() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the Cassandra tests need a real server")
                .isTrue();
        cassandra = CassandraTestContainer.create();
        cassandra.start();
        contactPoints = cassandra.getContactPoint().getHostString() + ":"
                + cassandra.getContactPoint().getPort();
        localDatacenter = cassandra.getLocalDatacenter();
        admin = CqlSession.builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(localDatacenter)
                .build();
        admin.execute("CREATE KEYSPACE IF NOT EXISTS " + CassandraTestContainer.KEYSPACE
                + " WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
        admin.execute("CREATE TABLE IF NOT EXISTS " + CassandraTestContainer.KEYSPACE + ".events (tenant text, "
                + "id bigint, day int, ts timestamp, status text, amount bigint, "
                + "PRIMARY KEY ((tenant, id), day, ts))");
    }

    @AfterAll
    static void stopServer() {
        if (admin != null) {
            admin.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @BeforeEach
    void fill() {
        admin.execute("TRUNCATE " + CassandraTestContainer.KEYSPACE + ".events");
        for (String tenant : List.of("acme", "zeta")) {
            for (long id = 1; id <= 3; id++) {
                for (int day = 1; day <= 3; day++) {
                    for (long ms = 1_000; ms <= 3_000; ms += 1_000) {
                        put(tenant, id, day, ms, "S" + day, id * 100 + day);
                    }
                }
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    void close() {
        plugins.forEach(CassandraSourcePlugin::close);
    }

    private static void put(String tenant, long id, int day, long millis, String status, long amount) {
        admin.execute(SimpleStatement.newInstance(
                "INSERT INTO " + CassandraTestContainer.KEYSPACE
                        + ".events (tenant, id, day, ts, status, amount) VALUES (?, ?, ?, ?, ?, ?)",
                tenant,
                id,
                day,
                Instant.ofEpochMilli(millis),
                status,
                amount));
    }

    private CassandraSourcePlugin source(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "contact.points", contactPoints,
                "local.datacenter", localDatacenter,
                "keyspace", CassandraTestContainer.KEYSPACE,
                "table", "events",
                "schema", SCHEMA,
                "partition.key", "tenant,id",
                "stream", "events",
                "partitions", "4",
                "scan.interval.ms", "600000"));
        config.putAll(extra);
        CassandraSourcePlugin plugin = new CassandraSourcePlugin();
        plugin.configure(new Ctx("events", config));
        plugin.open();
        plugins.add(plugin);
        return plugin;
    }

    /** Every reader of the binding, one pass each: what the engine would be handed. */
    private static List<List<Object>> read(CassandraSourcePlugin plugin, ReadRequest request) {
        List<List<Object>> rows = new ArrayList<>();
        for (SourcePartition partition : plugin.partitions("events")) {
            WeightedCollector out = new WeightedCollector(EVENTS);
            try (PartitionReader reader = plugin.createReader(partition, null, request)) {
                out.drain(reader);
            }
            out.rows.forEach(row -> rows.add(row.values()));
        }
        return rows;
    }

    private static Filter f(String column, Comparison comparison, Object value) {
        return new Filter(column, comparison, value);
    }

    @Test
    void theWholePartitionKeyReadsThatPartitionAndNoOther() {
        CassandraSourcePlugin plugin = source(Map.of());
        assertThat(plugin.capabilities().pushdown()).contains(PushdownKind.FILTER, PushdownKind.PROJECT);
        ReadRequest request = new ReadRequest(List.of(f("tenant", Comparison.EQ, "acme"), f("id", Comparison.EQ, 2L)));

        List<List<Object>> rows = read(plugin, request);

        assertThat(rows)
                .hasSize(9)
                .allSatisfy(row -> assertThat(row.subList(0, 2)).containsExactly("acme", 2L));
        assertThat(plugin.describePushdown(request))
                .isEqualTo("pushed to Cassandra: partition key tenant = 'acme' and id = 2");
    }

    @Test
    void clusteringRestrictionsSliceThePartitionWithoutAllowFiltering() {
        CassandraSourcePlugin plugin = source(Map.of());
        ReadRequest request = new ReadRequest(List.of(
                f("tenant", Comparison.EQ, "zeta"),
                f("id", Comparison.EQ, 3L),
                f("day", Comparison.EQ, 2),
                f("ts", Comparison.GT, 1_000_000_000L),
                f("status", Comparison.EQ, "ignored by Cassandra")));

        List<List<Object>> rows = read(plugin, request);

        assertThat(rows)
                .as("day 2, ts 2s and 3s; ts 1s is at the widened bound and the engine drops it")
                .extracting(row -> row.get(2), row -> row.get(3))
                .containsExactlyInAnyOrder(
                        Tuple.tuple(2, 1_000_000_000L), Tuple.tuple(2, 2_000_000_000L), Tuple.tuple(2, 3_000_000_000L));
        assertThat(plugin.describePushdown(request)).contains("clustering day = 2 and ts >= 1970-01-01T00:00:01Z");
    }

    @Test
    void anOrOfKeysReadsEachOnce() {
        CassandraSourcePlugin plugin = source(Map.of());
        ReadRequest shared = new ReadRequest(
                List.of(f("tenant", Comparison.EQ, "acme")),
                List.of(),
                List.of(),
                List.of(
                        List.of(f("id", Comparison.EQ, 1L)),
                        List.of(f("id", Comparison.EQ, 3L), f("day", Comparison.GE, 3))));

        List<List<Object>> rows = read(plugin, shared);

        assertThat(rows).hasSize(9 + 3);
        assertThat(rows).extracting(row -> row.get(1)).containsOnly(1L, 3L);
        assertThat(plugin.describePushdown(shared))
                .isEqualTo("pushed to Cassandra: 2 partitions by key [tenant, id], with clustering restrictions");
    }

    @Test
    void aFilterOffTheKeyReadsTheTableAsBefore() {
        CassandraSourcePlugin plugin = source(Map.of());
        ReadRequest request =
                new ReadRequest(List.of(f("status", Comparison.EQ, "S1"), f("tenant", Comparison.EQ, "acme")));

        assertThat(read(plugin, request)).hasSize(54);
        assertThat(plugin.describePushdown(request)).startsWith("no filter pushed to Cassandra");
    }

    @Test
    void deleteDetectionOverOnePartitionRetractsWhatIsDeletedThere() {
        CassandraSourcePlugin plugin = source(Map.of(
                "deletes",
                "detect",
                "deletes.state.dir",
                stateDir.toString(),
                "scan.interval.ms",
                "0",
                "partitions",
                "1"));
        ReadRequest request = new ReadRequest(List.of(f("tenant", Comparison.EQ, "acme"), f("id", Comparison.EQ, 1L)));

        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("events").get(0), null, request)) {
            WeightedCollector out = new WeightedCollector(EVENTS).drain(reader);
            assertThat(out.rows).hasSize(9);
            out.clearRows();

            admin.execute(SimpleStatement.newInstance(
                    "DELETE FROM " + CassandraTestContainer.KEYSPACE
                            + ".events WHERE tenant = ? AND id = ? AND day = ? AND ts = ?",
                    "acme",
                    1L,
                    1,
                    Instant.ofEpochMilli(1_000)));
            admin.execute(SimpleStatement.newInstance(
                    "DELETE FROM " + CassandraTestContainer.KEYSPACE + ".events WHERE tenant = ? AND id = ?",
                    "zeta",
                    1L));
            out.drain(reader);

            assertThat(out.rows)
                    .as("the row deleted in the pushed partition, and nothing for another partition")
                    .extracting(WeightedCollector.Emitted::weight)
                    .containsExactly(-1L);
            assertThat(out.rows.get(0).values().subList(0, 3)).containsExactly("acme", 1L, 1);
        }
    }
}
