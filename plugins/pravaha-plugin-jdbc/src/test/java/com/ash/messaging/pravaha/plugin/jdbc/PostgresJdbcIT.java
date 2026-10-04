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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.testkit.tck.ArenaRowCollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The JDBC plugin against a real PostgreSQL, not H2.
 *
 * <p>H2 is a fine stand-in for the shape of JDBC and a poor one for a dialect. It folds unquoted
 * identifiers to upper case where Postgres folds them to lower; it accepts SQL Postgres rejects; its
 * type metadata is its own. A plugin proven only against H2 is proven against H2, and the first real
 * database it meets is the customer's.
 *
 * <p>Skipped rather than failed when docker is unavailable, so a machine without it still gets a
 * green build rather than a red one everybody learns to ignore.
 */
@Timeout(300)
class PostgresJdbcIT {

    private static PostgreSQLContainer<?> postgres;
    private static Connection admin;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @BeforeAll
    static void startDatabase() throws SQLException {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the PostgreSQL tests need a real database")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void stopDatabase() throws SQLException {
        if (admin != null) {
            admin.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void createTable() throws SQLException {
        execute("DROP TABLE IF EXISTS orders");
        execute("CREATE TABLE orders ("
                + "id BIGINT NOT NULL PRIMARY KEY, "
                + "name VARCHAR(64), "
                + "amount DOUBLE PRECISION, "
                + "updated_at BIGINT NOT NULL)");
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void insert(long id, @Nullable String name, Double amount, long updatedAt) throws SQLException {
        execute("INSERT INTO orders VALUES (" + id + ", " + (name == null ? "NULL" : "'" + name + "'") + ", "
                + (amount == null ? "NULL" : amount) + ", " + updatedAt + ")");
    }

    /**
     * Lower-case column names, which is the point.
     *
     * <p>Postgres folds unquoted identifiers to lower case and H2 folds them to upper. A plugin that
     * assumed either would work against one and fail against the other, and the failure is "column
     * not found" for a column that plainly exists.
     */
    private static JdbcSourcePlugin open(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "url", postgres.getJdbcUrl(),
                "user", postgres.getUsername(),
                "password", postgres.getPassword(),
                "table", "orders",
                "watermark.column", "updated_at",
                "key.column", "id",
                "stream", "orders"));
        config.putAll(extra);
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        plugin.open();
        return plugin;
    }

    private static List<RowView> drain(PartitionReader reader, ArenaRowCollector collector) {
        while (reader.poll(collector, 64) > 0) {
            // each poll returns what is ready
        }
        return collector.rows();
    }

    @Test
    void theSchemaComesFromPostgresOwnMetadata() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());

        assertThat(plugin.schema().fields().stream().map(f -> f.name()).toList())
                .as("Postgres folds unquoted identifiers to lower case; H2 folds them to upper")
                .containsExactly("id", "name", "amount", "updated_at");
        plugin.close();
    }

    @Test
    void rowsArriveInWatermarkOrderAndResumeExactly() throws SQLException {
        for (long id = 1; id <= 10; id++) {
            insert(id, "n" + id, (double) id, id);
        }
        JdbcSourcePlugin plugin = open(Map.of());
        ArenaRowCollector first = new ArenaRowCollector(plugin.schema());

        com.ash.messaging.pravaha.api.plugin.SourceOffset midpoint;
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null)) {
            reader.poll(first, 4);
            midpoint = reader.position();
        }
        assertThat(first.rows().stream().map(row -> row.getLong(0)).toList()).containsExactly(1L, 2L, 3L, 4L);

        ArenaRowCollector rest = new ArenaRowCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), midpoint)) {
            assertThat(drain(reader, rest).stream().map(row -> row.getLong(0)).toList())
                    .as("keyset pagination gives a total order, so the resume is exact")
                    .containsExactly(5L, 6L, 7L, 8L, 9L, 10L);
        }
        plugin.close();
    }

    @Test
    void aPushedFilterBecomesAWhereClausePostgresEvaluates() throws SQLException {
        for (long id = 1; id <= 50; id++) {
            insert(id, "n" + id, (double) id, id);
        }
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(List.of(new ReadRequest.Filter("id", ReadRequest.Comparison.GT, 45L)));

        ArenaRowCollector collector = new ArenaRowCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, request)) {
            assertThat(drain(reader, collector).stream()
                            .map(row -> row.getLong(0))
                            .toList())
                    .containsExactly(46L, 47L, 48L, 49L, 50L);
        }
        assertThat(plugin.pollQueryFor(request)).contains("id > ?");
        plugin.close();
    }

    /** ADR-039 item 6: the SELECT list Postgres evaluates, lower-case names and all. */
    @Test
    void aPushedProjectionSelectsOnlyTheNamedColumns() throws SQLException {
        insert(1, "ann", 5.5, 10);
        insert(2, "bob", 6.5, 11);
        JdbcSourcePlugin plugin = open(Map.of());
        ReadRequest request = new ReadRequest(List.of(), List.of("amount"), List.of());
        assertThat(plugin.pollQueryFor(request)).startsWith("SELECT id, amount, updated_at FROM orders");

        ArenaRowCollector collector = new ArenaRowCollector(plugin.schema());
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null, request)) {
            List<RowView> rows = drain(reader, collector);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(1).getDouble(2)).isEqualTo(6.5);
            assertThat(rows.get(0).isNull(1)).as("name was not selected").isTrue();
        }
        plugin.close();
    }

    /**
     * ADR-039 item 6: a COUNT/SUM partial per keyset page, computed by Postgres -- whose SUM over a
     * BIGINT is a NUMERIC, which is the one dialect difference the reader has to survive -- summed
     * across pages and a resume, against Postgres's own answer for the whole table.
     */
    @Test
    void partialAggregatesPerPageSumToPostgresOwnAnswer() throws SQLException {
        execute("DROP TABLE IF EXISTS sales");
        execute(
                "CREATE TABLE sales (id BIGINT NOT NULL PRIMARY KEY, region INT, qty BIGINT, updated_at BIGINT NOT NULL)");
        for (long id = 1; id <= 83; id++) {
            execute("INSERT INTO sales VALUES (" + id + ", " + (id % 5 == 0 ? "NULL" : String.valueOf(id % 3)) + ", "
                    + (id % 7 == 0 ? "NULL" : String.valueOf(id * 1000)) + ", " + (id / 2) + ")");
        }
        Map<String, String> config = new HashMap<>(Map.of(
                "url", postgres.getJdbcUrl(),
                "user", postgres.getUsername(),
                "password", postgres.getPassword(),
                "table", "sales",
                "watermark.column", "updated_at",
                "key.column", "id",
                "stream", "sales",
                "fetch.size", "6"));
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx("sales", config));
        plugin.open();
        ReadRequest request = new ReadRequest(
                List.of(new ReadRequest.Filter("id", ReadRequest.Comparison.GE, 4L)),
                List.of(),
                List.of(new ReadRequest.PartialAggregate(
                        List.of("region"),
                        List.of(
                                new ReadRequest.PartialAggregate.AggregateCall(
                                        ReadRequest.PartialAggregate.Kind.COUNT, null, "n"),
                                new ReadRequest.PartialAggregate.AggregateCall(
                                        ReadRequest.PartialAggregate.Kind.SUM, "qty", "total")))));
        com.ash.messaging.pravaha.api.data.StreamSchema partial =
                com.ash.messaging.pravaha.api.data.StreamSchema.builder("partial")
                        .field(
                                "region",
                                com.ash.messaging.pravaha.api.data.Types.int32().withNullable(true))
                        .field("n", com.ash.messaging.pravaha.api.data.Types.int64())
                        .field("total", com.ash.messaging.pravaha.api.data.Types.int64())
                        .build();

        Map<Integer, long[]> summed = new java.util.HashMap<>();
        com.ash.messaging.pravaha.api.plugin.SourceOffset midway;
        ArenaRowCollector first = new ArenaRowCollector(partial);
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), null, request)) {
            assertThat(reader.deliversPartialAggregate()).isTrue();
            reader.poll(first, 5);
            reader.poll(first, 5);
            midway = reader.position();
        }
        ArenaRowCollector rest = new ArenaRowCollector(partial);
        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("sales").get(0), midway, request)) {
            drain(reader, rest);
        }
        for (List<RowView> rows : List.of(first.rows(), rest.rows())) {
            for (RowView row : rows) {
                Integer region = row.isNull(0) ? null : row.getInt(0);
                long[] sums = summed.computeIfAbsent(region, r -> new long[2]);
                sums[0] += row.getLong(1);
                sums[1] += row.getLong(2);
            }
        }

        Map<Integer, long[]> truth = new java.util.HashMap<>();
        try (Statement statement = admin.createStatement();
                java.sql.ResultSet results = statement.executeQuery(
                        "SELECT region, COUNT(*), COALESCE(SUM(qty), 0) FROM sales WHERE id >= 4 GROUP BY region")) {
            while (results.next()) {
                int region = results.getInt(1);
                truth.put(results.wasNull() ? null : region, new long[] {results.getLong(2), results.getLong(3)});
            }
        }
        assertThat(summed.keySet()).isEqualTo(truth.keySet());
        truth.forEach((region, expected) ->
                assertThat(summed.get(region)).as("region %s", region).containsExactly(expected));
        plugin.close();
    }

    @Test
    void aNullTextColumnComesBackAsNull() throws SQLException {
        // The bug the lookup plugin's tests exposed in the shared decoder, checked against the
        // database it would actually have surfaced on.
        insert(1, null, 5.0, 100);
        JdbcSourcePlugin plugin = open(Map.of());
        ArenaRowCollector collector = new ArenaRowCollector(plugin.schema());

        try (PartitionReader reader =
                plugin.createReader(plugin.partitions("orders").get(0), null)) {
            List<RowView> rows = drain(reader, collector);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).isNull(1)).isTrue();
        }
        plugin.close();
    }

    @Test
    void aPostgresTableWorksAsADimensionTable() throws SQLException {
        insert(1, "gold", 1.0, 1);
        insert(2, "silver", 2.0, 2);

        JdbcLookupPlugin lookup = new JdbcLookupPlugin();
        lookup.configure(new Ctx(
                "orders",
                Map.of(
                        "url",
                        postgres.getJdbcUrl(),
                        "user",
                        postgres.getUsername(),
                        "password",
                        postgres.getPassword(),
                        "table",
                        "orders",
                        "key.columns",
                        "id",
                        "cache.seconds",
                        "60")));
        lookup.open();

        ArenaRowCollector out = new ArenaRowCollector(lookup.schema());
        assertThat(lookup.lookup(new Object[] {2L}, out)).isEqualTo(1);
        assertThat(out.rows().get(0).getString(1)).isEqualTo("silver");
        assertThat(lookup.keyColumns()).containsExactly("id");
        lookup.close();
    }
}
