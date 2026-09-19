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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.cassandra.CassandraContainer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * {@code deletes: detect} against a real Cassandra node, on a table with a clustering column, so
 * one partition -- one token -- holds several rows: an insert, an update, a row deleted, a whole
 * partition deleted, and a re-insert each reach the emitted rows exactly; restarts mid-pass and
 * between passes neither invent nor miss a retraction; the ceiling refuses; eight token ranges
 * together cover the ring; and a registered view over the source and an aggregate over it, through
 * the registry a node runs, equal the table across updates, deletes and a restart through it.
 */
@Timeout(300)
class CassandraDeleteDetectionIT {

    private static final String SCHEMA = "customer:INT64,id:INT64,status:STRING,amount:INT64";
    private static final StreamSchema ORDERS = CassandraSchemas.parse("orders", SCHEMA);
    private static final AtomicInteger TABLES = new AtomicInteger();

    private static CassandraContainer cassandra;
    private static CqlSession admin;
    private static String contactPoints;
    private static String localDatacenter;

    private final String table = "dd" + TABLES.incrementAndGet();
    private final List<QueryRegistry> registries = new ArrayList<>();

    @TempDir
    Path stateDir;

    @TempDir
    Path checkpoints;

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
    void createTable() {
        admin.execute("CREATE TABLE " + qualified() + " (customer bigint, id bigint, status text, amount bigint, "
                + "PRIMARY KEY ((customer), id))");
    }

    @AfterEach
    void closeRegistries() {
        registries.forEach(QueryRegistry::close);
    }

    private String qualified() {
        return CassandraTestContainer.KEYSPACE + "." + table;
    }

    private void put(long customer, long id, String status, long amount) {
        admin.execute(SimpleStatement.newInstance(
                "INSERT INTO " + qualified() + " (customer, id, status, amount) VALUES (?, ?, ?, ?)",
                customer,
                id,
                status,
                amount));
    }

    private void delete(long customer, long id) {
        admin.execute(SimpleStatement.newInstance(
                "DELETE FROM " + qualified() + " WHERE customer = ? AND id = ?", customer, id));
    }

    private void deleteCustomer(long customer) {
        admin.execute(SimpleStatement.newInstance("DELETE FROM " + qualified() + " WHERE customer = ?", customer));
    }

    private Map<String, String> options(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "contact.points",
                contactPoints,
                "local.datacenter",
                localDatacenter,
                "keyspace",
                CassandraTestContainer.KEYSPACE,
                "table",
                table,
                "schema",
                SCHEMA,
                "partition.key",
                "customer",
                "stream",
                "orders",
                "deletes",
                "detect",
                "deletes.state.dir",
                stateDir.toString(),
                "scan.interval.ms",
                "0"));
        config.putAll(extra);
        return config;
    }

    private CassandraSourcePlugin source(Map<String, String> extra) {
        CassandraSourcePlugin plugin = new CassandraSourcePlugin();
        plugin.configure(new Ctx("orders", options(extra)));
        plugin.open();
        return plugin;
    }

    private Map<List<Object>, Long> tableAsView() {
        Map<List<Object>, Long> view = new HashMap<>();
        for (Row row : admin.execute("SELECT customer, id, status, amount FROM " + qualified())) {
            view.merge(
                    java.util.Arrays.asList(
                            row.getLong("customer"), row.getLong("id"), row.getString("status"), row.getLong("amount")),
                    1L,
                    Long::sum);
        }
        return view;
    }

    private static List<Object> row(long customer, long id, String status, long amount) {
        return java.util.Arrays.asList(customer, id, status, amount);
    }

    @Test
    void insertUpdateDeleteAndReinsertEachReachTheEmittedRowsExactly() {
        put(1, 1, "NEW", 10);
        put(1, 2, "NEW", 20);
        put(2, 3, "NEW", 30);
        put(3, 4, "NEW", 40);
        CassandraSourcePlugin plugin = source(Map.of());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null)) {
            WeightedCollector out = new WeightedCollector(ORDERS).drain(reader);
            assertThat(out.rows).hasSize(4);
            out.clearRows();
            out.drain(reader);
            assertThat(out.rows)
                    .as("an unchanged table emits nothing on the next pass")
                    .isEmpty();

            put(1, 2, "SHIPPED", 25);
            delete(1, 1);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactlyInAnyOrder(
                            Tuple.tuple(row(1, 1, "NEW", 10), -1L),
                            Tuple.tuple(row(1, 2, "NEW", 20), -1L),
                            Tuple.tuple(row(1, 2, "SHIPPED", 25), 1L));

            out.clearRows();
            deleteCustomer(2);
            out.drain(reader);
            assertThat(out.rows)
                    .as("a partition tombstone retracts every row of the partition")
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(Tuple.tuple(row(2, 3, "NEW", 30), -1L));

            out.clearRows();
            put(2, 3, "AGAIN", 33);
            out.drain(reader);
            assertThat(out.rows)
                    .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                    .containsExactly(Tuple.tuple(row(2, 3, "AGAIN", 33), 1L));
            assertThat(out.view).isEqualTo(tableAsView());
        }
        plugin.close();
    }

    @Test
    void eightRangesTogetherRetractExactlyWhatWasDeleted() {
        for (long customer = 1; customer <= 40; customer++) {
            put(customer, customer, "NEW", customer);
        }
        CassandraSourcePlugin plugin = source(Map.of("partitions", "8"));
        List<PartitionReader> readers = new ArrayList<>();
        WeightedCollector out = new WeightedCollector(ORDERS);
        for (SourcePartition partition : plugin.partitions("orders")) {
            readers.add(plugin.createReader(partition, null));
        }
        readers.forEach(out::drain);
        assertThat(out.view).isEqualTo(tableAsView());
        for (long customer = 1; customer <= 40; customer += 3) {
            deleteCustomer(customer);
        }
        out.clearRows();
        readers.forEach(out::drain);
        assertThat(out.rows).hasSize(14).allMatch(e -> e.weight() == -1L);
        assertThat(out.view).isEqualTo(tableAsView());
        readers.forEach(PartitionReader::close);
        plugin.close();
    }

    @Test
    void aRestartMidPassAndOneBetweenPassesNeitherInventNorMissARetraction() {
        for (long customer = 1; customer <= 8; customer++) {
            put(customer, 1, "NEW", customer);
            put(customer, 2, "NEW", customer * 10);
        }
        CassandraSourcePlugin plugin = source(Map.of());
        SourcePartition partition = plugin.partitions("orders").get(0);
        WeightedCollector out = new WeightedCollector(ORDERS);
        SourceOffset midPass;
        try (PartitionReader reader = plugin.createReader(partition, null)) {
            assertThat(reader.poll(out, 5)).isPositive();
            midPass = reader.position();
        }
        deleteCustomer(3);
        delete(5, 2);
        put(7, 1, "DONE", 70);

        WeightedCollector resumed = WeightedCollector.restoredFrom(ORDERS, out.view);
        SourceOffset betweenPasses;
        try (PartitionReader reader = plugin.createReader(partition, midPass)) {
            resumed.drain(reader);
            betweenPasses = reader.position();
        }
        assertThat(resumed.view).isEqualTo(tableAsView());

        deleteCustomer(8);
        put(9, 1, "NEW", 9);
        WeightedCollector again = WeightedCollector.restoredFrom(ORDERS, resumed.view);
        try (PartitionReader reader = plugin.createReader(partition, betweenPasses)) {
            again.drain(reader);
        }
        assertThat(again.rows)
                .extracting(WeightedCollector.Emitted::values, WeightedCollector.Emitted::weight)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(row(8, 1, "NEW", 8), -1L),
                        Tuple.tuple(row(8, 2, "NEW", 80), -1L),
                        Tuple.tuple(row(9, 1, "NEW", 9), 1L));
        assertThat(again.view).isEqualTo(tableAsView());
        plugin.close();
    }

    @Test
    void aTableLargerThanTheCeilingIsRefusedByCode() {
        for (long customer = 1; customer <= 5; customer++) {
            put(customer, 1, "NEW", customer);
        }
        CassandraSourcePlugin plugin = source(Map.of("deletes.max.keys", "4"));
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null)) {
            WeightedCollector out = new WeightedCollector(ORDERS);
            assertThatThrownBy(() -> out.drain(reader))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e -> assertThat(((PravahaException) e).errorCode())
                            .isEqualTo(CassandraErrors.DELETE_STATE_FULL));
            assertThat(out.rows).hasSizeLessThanOrEqualTo(4);
        }
        plugin.close();
    }

    /** A checksum of the whole table one row can hold; see {@code AerospikeDeleteDetectionIT}. */
    private static final String CHECKSUM =
            "SELECT COUNT(*) AS n, SUM(amount) AS total, SUM(customer * 1000 + id * id + amount) AS mix FROM orders";

    private QueryRegistry registry() {
        return registry(Map.of());
    }

    private QueryRegistry registry(Map<String, String> extra) {
        Map<String, String> binding = new HashMap<>(Map.of("scan.interval.ms", "100"));
        binding.putAll(extra);
        PluginSourceFeeds feeds =
                new PluginSourceFeeds().bind(new SourceBinding("orders", "cassandra", options(binding)));
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), ORDERS)
                .feedingFrom(feeds)
                .checkpointingTo(
                        checkpoints,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "30s")
                                .build());
        registries.add(registry);
        return registry;
    }

    /**
     * Through the registry and source bindings a node runs, and across a restart through it: both
     * views are checkpointed, a delete is delivered after the checkpoint (so the restore must redo
     * it), the registry is closed, the table changes while nothing reads it, and the same two names
     * registered again restore from the checkpoint and are brought to the table by one pass.
     *
     * <p>This test first ran without the restart because a restored view's first commit was refused
     * as "frontier went backwards" (PRV-5092) -- the defect {@code 3b9d9a8} (LANE-4) fixed. It goes
     * on changing the table after the restore because that defect stopped the feed, not the view.
     */
    @Test
    void aRegisteredViewAndAggregateEqualTheTableAcrossUpdatesDeletesAndARestart() throws Exception {
        for (long customer = 1; customer <= 10; customer++) {
            put(customer, 1, "NEW", customer);
            put(customer, 2, "NEW", customer * 2);
        }
        QueryRegistry first = registry();
        RegisteredQuery rows = first.register(
                "orders_now", "SELECT customer, id, status, amount FROM orders", List.of(0, 1), Principal.ANONYMOUS);
        RegisteredQuery totals = first.register("orders_total", CHECKSUM, List.of(0), Principal.ANONYMOUS);
        awaitRows(rows);
        awaitChecksum(totals);

        deleteCustomer(3);
        delete(4, 2);
        put(5, 1, "CHANGED", 500);
        awaitRows(rows);
        awaitChecksum(totals);
        sleep(500);
        assertThat(checksum(totals))
                .as("still equal a few passes later: an unchanged row is not counted again")
                .isEqualTo(tableChecksum());

        checkpointerOf(rows).checkpointNow();
        checkpointerOf(totals).checkpointNow();
        deleteCustomer(6);
        awaitRows(rows); // delivered after the checkpoint, so the restore must redo it
        awaitChecksum(totals);
        first.close();
        registries.remove(first);

        // While nothing is reading.
        delete(7, 1);
        put(3, 1, "BACK", 3);
        put(11, 1, "NEW", 11);
        put(8, 2, "CHANGED", 800);

        QueryRegistry second = registry();
        RegisteredQuery rowsAgain = second.register(
                "orders_now", "SELECT customer, id, status, amount FROM orders", List.of(0, 1), Principal.ANONYMOUS);
        RegisteredQuery totalsAgain = second.register("orders_total", CHECKSUM, List.of(0), Principal.ANONYMOUS);
        awaitRows(rowsAgain);
        awaitChecksum(totalsAgain);
        assertThat(totalsAgain.rowsIn())
                .as("restored, so the reader sends the difference from the checkpoint, not the whole table")
                .isLessThan(tableRows().size());

        // Alive after the restore: the defect this replaces stopped the feed on its first commit.
        deleteCustomer(9);
        put(10, 1, "CHANGED", 1000);
        awaitRows(rowsAgain);
        awaitChecksum(totalsAgain);
        sleep(500);
        assertThat(viewRows(rowsAgain))
                .as("still equal a few passes later: nothing is added twice")
                .isEqualTo(tableRows());
        assertThat(checksum(totalsAgain)).isEqualTo(tableChecksum());
        assertThat(rowsAgain.failure()).isEmpty();
        assertThat(totalsAgain.failure()).isEmpty();
    }

    private Set<String> tableRows() {
        return tableAsView().keySet().stream().map(Object::toString).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> viewRows(RegisteredQuery query) {
        return query.view().scan().stream()
                .map(row -> java.util.Arrays.asList(row).toString())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private List<Long> tableChecksum() {
        long n = 0;
        long total = 0;
        long mix = 0;
        for (List<Object> row : tableAsView().keySet()) {
            long customer = (Long) row.get(0);
            long id = (Long) row.get(1);
            long amount = (Long) row.get(3);
            n++;
            total += amount;
            mix += customer * 1000 + id * id + amount;
        }
        return List.of(n, total, mix);
    }

    private static List<Long> checksum(RegisteredQuery query) {
        List<Object[]> scanned = query.view().scan();
        if (scanned.isEmpty()) {
            return List.of(0L, 0L, 0L);
        }
        Object[] row = scanned.get(0);
        return List.of(
                ((Number) row[0]).longValue(),
                row[1] == null ? 0L : ((Number) row[1]).longValue(),
                row[2] == null ? 0L : ((Number) row[2]).longValue());
    }

    private void awaitRows(RegisteredQuery rows) {
        await(
                () -> viewRows(rows).equals(tableRows()),
                () -> "the view holds " + viewRows(rows) + ", the table holds " + tableRows() + " (" + rows.state()
                        + ", feed " + rows.feed().describe() + ")");
    }

    private void awaitChecksum(RegisteredQuery totals) {
        await(
                () -> checksum(totals).equals(tableChecksum()),
                () -> "the view holds " + checksum(totals)
                        + ", the table holds " + tableChecksum() + " (" + totals.state() + ", feed "
                        + totals.feed().describe() + ")");
    }

    private static void await(java.util.function.BooleanSupplier done, java.util.function.Supplier<String> why) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(why.get());
            }
            sleep(50);
        }
    }

    /**
     * SCAN-1 against a real server. Over the default binding, {@code deletes: ignore}, every pass
     * emits every row again at +1 -- so an aggregate is refused at registration (PRV-2042) with
     * {@code deletes: detect} named as the fix, and a keyed view of the rows is admitted and holds
     * each row once, as the table does, however many copies arrive.
     */
    @Test
    void overTheDefaultBindingAnAggregateIsRefusedAndAKeyedViewStillEqualsTheTable() {
        for (long customer = 1; customer <= 5; customer++) {
            put(customer, 1, "NEW", customer);
            put(customer, 2, "NEW", customer * 2);
        }
        QueryRegistry registry = registry(Map.of("deletes", "ignore"));
        assertThatThrownBy(() -> registry.register("orders_total", CHECKSUM, List.of(0), Principal.ANONYMOUS))
                .isInstanceOf(PravahaException.class)
                .satisfies(e -> assertThat(((PravahaException) e).errorCode())
                        .isEqualTo(com.ash.messaging.pravaha.sql.SqlErrors.SOURCE_REPEATS_ROWS))
                .hasMessageContaining("`deletes: detect`");
        assertThat(registry.names()).isEmpty();

        RegisteredQuery rows = registry.register(
                "orders_now", "SELECT customer, id, status, amount FROM orders", List.of(0, 1), Principal.ANONYMOUS);
        awaitRows(rows);
        put(5, 1, "CHANGED", 500);
        put(6, 1, "NEW", 6);
        awaitRows(rows);
        sleep(1_000);
        assertThat(viewRows(rows))
                .as("each row once, with the values the table holds")
                .isEqualTo(tableRows());
        assertThat(rows.rowsIn())
                .as("and the copies did arrive: more rows in than the table holds")
                .isGreaterThan(tableRows().size());
        assertThat(rows.failure()).isEmpty();
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) throws ReflectiveOperationException {
        Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
        field.setAccessible(true);
        return (PeriodicCheckpointer) field.get(query);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
