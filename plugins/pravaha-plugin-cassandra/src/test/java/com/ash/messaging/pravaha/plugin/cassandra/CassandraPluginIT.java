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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.cassandra.CassandraContainer;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The Cassandra plugin against a real single-node cluster.
 *
 * <p>What is asserted here is what a full periodic token-range scan can genuinely do, and -- as with
 * {@code AerospikePluginIT} -- what it cannot: a test suite that only shows the happy path would
 * leave a reader believing this source sees deletes, which it does not (ADR-039 item 6).
 *
 * <p>Skipped rather than failed when docker is unavailable, for the same reason every other
 * container test here is: a build that goes red on a machine without docker teaches people to ignore
 * red builds.
 */
@Timeout(300)
class CassandraPluginIT {

    private static CassandraContainer cassandra;
    private static CqlSession admin;
    private static String contactPoints;
    private static String localDatacenter;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static final String SCHEMA = "id:INT64,status:STRING,amount:INT64,updated_at:TIMESTAMP";

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
        admin.execute("CREATE TABLE IF NOT EXISTS " + CassandraTestContainer.KEYSPACE
                + ".orders (id bigint PRIMARY KEY, status text, amount bigint, updated_at timestamp)");
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
    void clearTable() {
        admin.execute("TRUNCATE " + CassandraTestContainer.KEYSPACE + ".orders");
    }

    private static void put(long id, String status, long amount) {
        admin.execute(SimpleStatement.newInstance(
                "INSERT INTO " + CassandraTestContainer.KEYSPACE + ".orders (id, status, amount) VALUES (?, ?, ?)",
                id,
                status,
                amount));
    }

    private static CassandraSourcePlugin source(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "contact.points",
                contactPoints,
                "local.datacenter",
                localDatacenter,
                "keyspace",
                CassandraTestContainer.KEYSPACE,
                "table",
                "orders",
                "schema",
                SCHEMA,
                "partition.key",
                "id",
                "stream",
                "orders"));
        config.putAll(extra);
        CassandraSourcePlugin plugin = new CassandraSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        plugin.open();
        return plugin;
    }

    private static List<Object[]> drainAllPartitions(CassandraSourcePlugin plugin) {
        List<Object[]> rows = new ArrayList<>();
        for (SourcePartition partition : plugin.partitions("orders")) {
            Collector out = new Collector(plugin.schema());
            try (PartitionReader reader = plugin.createReader(partition, null)) {
                while (reader.poll(out, 100) > 0) {
                    // drain
                }
            }
            rows.addAll(out.rows);
        }
        return rows;
    }

    /**
     * ADR-039 item 6: a pushed projection is the CQL SELECT list, so Cassandra sends only those
     * columns. {@code status} was not asked for and is NOT NULL, so it holds the placeholder a
     * column nothing reads gets.
     */
    @Test
    void aPushedProjectionSelectsOnlyTheNamedColumns() {
        put(1, "NEW", 100);
        put(2, "DONE", 250);
        CassandraSourcePlugin plugin = source(Map.of());
        com.ash.messaging.pravaha.api.plugin.ReadRequest request =
                new com.ash.messaging.pravaha.api.plugin.ReadRequest(List.of(), List.of("id", "amount"), List.of());
        assertThat(CassandraSourcePlugin.projectedColumns(plugin.schema(), request, ""))
                .containsExactly("id", "amount");

        List<Object[]> rows = new ArrayList<>();
        for (SourcePartition partition : plugin.partitions("orders")) {
            Collector out = new Collector(plugin.schema());
            try (PartitionReader reader = plugin.createReader(partition, null, request)) {
                while (reader.poll(out, 100) > 0) {
                    // drain
                }
            }
            rows.addAll(out.rows);
        }

        assertThat(rows).hasSize(2);
        rows.sort(java.util.Comparator.comparing(row -> (Long) row[0]));
        assertThat(rows.get(0)[2]).isEqualTo(100L);
        assertThat(rows.get(1)[2]).isEqualTo(250L);
        assertThat(rows.get(0)[1]).as("status was not selected").isEqualTo("");
        plugin.close();
    }

    @Test
    void aScanReadsWhatIsInTheTable() {
        put(1, "NEW", 100);
        put(2, "DONE", 250);
        CassandraSourcePlugin plugin = source(Map.of());

        List<Object[]> rows = drainAllPartitions(plugin);

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row -> (Long) row[0]).sorted().toList()).containsExactly(1L, 2L);
        plugin.close();
    }

    @Test
    void resumingContinuesThePassFromThePartitionItStoppedInLosingNothing() {
        // The resumed read is the unread remainder plus the partition the first read stopped in,
        // read again from its first row (CASS-1): a stop inside a wide partition must not skip the
        // rest of it, and a re-read row lands at +1 on a keyed view like every row of every pass.
        // Each partition here is one row, so that is exactly one row read twice -- no more.
        for (long id = 1; id <= 6; id++) {
            put(id, "NEW", id * 10);
        }
        CassandraSourcePlugin plugin = source(Map.of());
        SourcePartition partition = plugin.partitions("orders").get(0);

        Collector first = new Collector(plugin.schema());
        SourceOffset midpoint;
        try (PartitionReader reader = plugin.createReader(partition, null)) {
            int taken = reader.poll(first, 3);
            assertThat(taken).isEqualTo(3);
            midpoint = reader.position();
        }

        Collector rest = new Collector(plugin.schema());
        try (PartitionReader reader = plugin.createReader(partition, midpoint)) {
            while (reader.poll(rest, 100) > 0) {
                // drain
            }
        }

        Set<Long> firstIds = first.rows.stream().map(row -> (Long) row[0]).collect(java.util.stream.Collectors.toSet());
        List<Long> restIds = rest.rows.stream().map(row -> (Long) row[0]).toList();
        long stoppedIn = (Long) first.rows.get(first.rows.size() - 1)[0];
        Set<Long> unread = new HashSet<>(Set.of(1L, 2L, 3L, 4L, 5L, 6L));
        unread.removeAll(firstIds);
        assertThat(firstIds).hasSize(3);
        assertThat(restIds)
                .as("the unread remainder, and the partition the first read stopped in again -- nothing "
                        + "missing, and no partition before it replayed")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(unread.stream(), java.util.stream.Stream.of(stoppedIn))
                                .toList());
        plugin.close();
    }

    @Test
    void multiplePartitionsTogetherCoverTheWholeTokenRingWithNoGapOrOverlap() {
        // TokenRangesTest proves the arithmetic in isolation; this proves it against real Murmur3
        // tokens, which is the thing a unit test of the arithmetic alone cannot see.
        Set<Long> written = new HashSet<>();
        for (long id = 1; id <= 60; id++) {
            put(id, "NEW", id);
            written.add(id);
        }
        CassandraSourcePlugin plugin = source(Map.of("partitions", "8"));

        List<Object[]> rows = drainAllPartitions(plugin);

        assertThat(rows).as("no partition boundary may drop or double a row").hasSize(60);
        assertThat(rows.stream().map(row -> (Long) row[0]).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(written);
        plugin.close();
    }

    @Test
    void aDeletedRowIsInvisibleAndTheCapabilitiesSaySo() {
        put(1, "NEW", 100);
        CassandraSourcePlugin plugin = source(Map.of());
        assertThat(drainAllPartitions(plugin)).hasSize(1);

        admin.execute("DELETE FROM " + CassandraTestContainer.KEYSPACE + ".orders WHERE id = 1");

        // A fresh reader always starts a pass immediately, so this is a second, independent scan.
        assertThat(drainAllPartitions(plugin))
                .as("a tombstone is not delivered to an ordinary read; absence is not an event")
                .isEmpty();
        assertThat(plugin.capabilities().emitsDeletes()).isFalse();
        assertThat(plugin.capabilities().emitsBeforeImage()).isFalse();
        assertThat(plugin.capabilities().guarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        assertThat(plugin.capabilities().pushdown())
                .as(
                        "a SELECT list and filters on the key: nothing needing ALLOW FILTERING, no partial over a full re-read")
                .containsExactlyInAnyOrder(
                        com.ash.messaging.pravaha.api.plugin.PushdownKind.PROJECT,
                        com.ash.messaging.pravaha.api.plugin.PushdownKind.FILTER);
        plugin.close();
    }

    @Test
    void anOverwrittenRowShowsOnlyItsFinalValue() {
        // "Two writes between scans are seen as one" -- the class javadoc's claim, checked rather
        // than trusted: a value written and then immediately overwritten never appears as an
        // intermediate row, because a scan has no way to see the write in between.
        put(1, "NEW", 100);
        put(1, "SHIPPED", 200);
        CassandraSourcePlugin plugin = source(Map.of());

        List<Object[]> rows = drainAllPartitions(plugin);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo("SHIPPED");
        plugin.close();
    }

    @Test
    void aFullPassRereadsAfterTheConfiguredIntervalRatherThanStoppingWhenExhausted() {
        // The defining difference from a one-shot bounded read: this source is a *periodic* scan.
        put(1, "NEW", 100);
        CassandraSourcePlugin plugin = source(Map.of("scan.interval.ms", "200"));
        SourcePartition partition = plugin.partitions("orders").get(0);

        try (PartitionReader reader = plugin.createReader(partition, null)) {
            Collector firstPass = new Collector(plugin.schema());
            while (reader.poll(firstPass, 100) > 0) {
                // drain the first pass
            }
            assertThat(firstPass.rows).hasSize(1);

            assertThat(reader.poll(new Collector(plugin.schema()), 100))
                    .as("the interval has not elapsed yet, so no second pass should have started")
                    .isZero();

            sleep(300);

            Collector secondPass = new Collector(plugin.schema());
            int total = 0;
            int polled;
            do {
                polled = reader.poll(secondPass, 100);
                total += polled;
            } while (polled > 0);
            assertThat(total)
                    .as("a full periodic scan reads everything again on its next pass")
                    .isEqualTo(1);
        }
        plugin.close();
    }

    @Test
    void theDeclaredEventTimeColumnIsUsedRatherThanTheScanTime() {
        Instant when = Instant.parse("2020-01-01T00:00:00Z");
        admin.execute(SimpleStatement.newInstance(
                "INSERT INTO " + CassandraTestContainer.KEYSPACE
                        + ".orders (id, status, amount, updated_at) VALUES (?, ?, ?, ?)",
                1L,
                "NEW",
                100L,
                when));
        CassandraSourcePlugin plugin = source(Map.of("event.time", "updated_at"));
        SourcePartition partition = plugin.partitions("orders").get(0);

        EventTimeCollector out = new EventTimeCollector(plugin.schema());
        try (PartitionReader reader = plugin.createReader(partition, null)) {
            while (reader.poll(out, 100) > 0) {
                // drain
            }
        }

        assertThat(out.eventTimestampsNanos).hasSize(1);
        long expectedNanos = when.getEpochSecond() * 1_000_000_000L + when.getNano();
        assertThat(out.eventTimestampsNanos.get(0))
                .as("a windowed query assigns rows by this value; falling back to the scan time drops "
                        + "every row as late against a watermark that never catches up")
                .isEqualTo(expectedNanos);
        plugin.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Collects rows as plain values. */
    private static final class Collector implements PartitionReader.RecordSink {
        private final List<Object[]> rows = new ArrayList<>();
        private final StreamSchema schema;

        Collector(StreamSchema schema) {
            this.schema = schema;
        }

        @Override
        public RowWriter beginRow() {
            return new ValueWriter(schema, rows::add);
        }
    }

    /** Collects both a row's values and the event timestamp it was written with. */
    private static final class EventTimeCollector implements PartitionReader.RecordSink {
        private final List<Long> eventTimestampsNanos = new ArrayList<>();
        private final StreamSchema schema;

        EventTimeCollector(StreamSchema schema) {
            this.schema = schema;
        }

        @Override
        public RowWriter beginRow() {
            List<Object[]> rows = new ArrayList<>();
            return new ValueWriter(schema, rows::add, eventTimestampsNanos::add);
        }
    }
}
