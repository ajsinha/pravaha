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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JDBC incremental-poll source, against H2.
 *
 * <p>H2 rather than a container because the contract being tested is plain JDBC -- metadata,
 * ordering, fetch limits, null handling -- and none of it is dialect-specific. A PostgreSQL
 * integration test proves the driver works, which is Postgres's job; this proves the plugin works.
 *
 * <p>{@link #rowsSharingAWatermarkAreNeitherDuplicatedNorLost} is the one that matters. Watermark
 * columns are not unique in practice, and every naive implementation is wrong at the boundary in
 * one direction or the other.
 */
class JdbcSourcePluginTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private String url;
    private Connection admin;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:pravaha" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        admin = DriverManager.getConnection(url);
        execute(
                "CREATE TABLE orders (id BIGINT NOT NULL, name VARCHAR(64), amount DOUBLE, updated_at BIGINT NOT NULL)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        admin.close();
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private void insert(long id, String name, Double amount, long updatedAt) throws SQLException {
        execute("INSERT INTO orders VALUES (" + id + ", '" + name + "', " + (amount == null ? "NULL" : amount) + ", "
                + updatedAt + ")");
    }

    private JdbcSourcePlugin open(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "url",
                url,
                "table",
                "orders",
                "watermark.column",
                "UPDATED_AT",
                // A unique tie-breaker, without which rows sharing a watermark have no defined
                // order and the boundary cannot be resumed. See JdbcOffset.
                "key.column",
                "ID",
                "stream",
                "orders"));
        config.putAll(extra);
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        plugin.open();
        return plugin;
    }

    private static List<RowView> drain(PartitionReader reader, JdbcCollector collector) {
        while (reader.poll(collector, 64) > 0) {
            // each poll returns what is ready
        }
        return collector.rows();
    }

    @Test
    void derivesItsSchemaFromTheDatabase() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());

        // Asked, not declared: the database holds the authoritative definition, so restating it by
        // hand would only create a second version that can disagree with the first.
        assertThat(plugin.schema().fieldCount()).isEqualTo(4);
        assertThat(plugin.schema().field(0).name()).isEqualTo("ID");
        assertThat(plugin.schema().field(0).type().nullable())
                .as("NOT NULL in the database is NOT NULL in the stream")
                .isFalse();
        assertThat(plugin.schema().field(1).type().nullable()).isTrue();
    }

    @Test
    void readsRowsInWatermarkOrderAndPreservesNulls() throws SQLException {
        insert(3, "cat", 30.0, 300);
        insert(1, "ann", 10.5, 100);
        insert(2, "bob", null, 200);

        JdbcSourcePlugin plugin = open(Map.of());
        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(reader, collector);

            assertThat(rows).hasSize(3);
            assertThat(rows.stream().map(r -> r.getLong(0))).containsExactly(1L, 2L, 3L);
            assertThat(rows.get(1).isNull(2))
                    .as("a SQL NULL is a null, not a zero")
                    .isTrue();
            assertThat(rows.get(0).eventTimestampNanos())
                    .as("the watermark column is the event time; it is the column the query orders by")
                    .isEqualTo(100L);
        }
    }

    @Test
    void onlyNewRowsArriveOnALaterPoll() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());

        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector)).hasSize(1);

            insert(2, "bob", 20.0, 200);
            assertThat(drain(reader, collector).stream().map(r -> r.getLong(0)))
                    .as("the first row must not be re-read")
                    .containsExactly(1L, 2L);
        }
    }

    @Test
    void rowsSharingAWatermarkAreNeitherDuplicatedNorLost() throws SQLException {
        // A batch job stamping a thousand rows with one timestamp is the normal case, not an edge
        // one. Strictly-greater loses rows that commit late at the boundary; greater-or-equal
        // re-emits the boundary on every poll; and counting rows at the boundary only works if the
        // database returns tied rows in a stable order, which it does not promise -- this test read
        // rows 3 and 2 where it expected 1 and 2 until the key column made the sort total.
        insert(1, "a", 1.0, 100);
        insert(2, "b", 2.0, 100);
        insert(3, "c", 3.0, 100);

        JdbcSourcePlugin plugin = open(Map.of());
        SourceOffset checkpoint;
        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 2)).isEqualTo(2);
            checkpoint = reader.position();
            assertThat(collector.rows().stream().map(r -> r.getLong(0))).containsExactly(1L, 2L);
        }

        // A fourth row lands at the same watermark after the checkpoint, which is exactly the case
        // a strictly-greater comparison would drop on the floor.
        insert(4, "d", 4.0, 100);

        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader resumed =
                        plugin.createReader(plugin.partitions("orders").get(0), checkpoint)) {
            assertThat(drain(resumed, collector).stream().map(r -> r.getLong(0)))
                    .as("the boundary is resumed exactly: nothing repeated, nothing dropped")
                    .containsExactly(3L, 4L);
        }
    }

    @Test
    void anArbitraryQueryCanBeThePolledSource() throws SQLException {
        insert(1, "ann", 10.5, 100);
        insert(2, "bob", 20.0, 200);

        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx(
                "big",
                Map.of(
                        "url", url,
                        "query", "SELECT id, updated_at FROM orders WHERE amount > 15",
                        "watermark.column", "UPDATED_AT",
                        "key.column", "ID",
                        "stream", "big")));
        plugin.open();

        assertThat(plugin.schema().fieldCount()).isEqualTo(2);
        try (JdbcCollector collector = new JdbcCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("big").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector).stream().map(r -> r.getLong(0))).containsExactly(2L);
        }
    }

    @Test
    void withoutAKeyColumnTheSourceDeclaresItselfNonReplayable() throws SQLException {
        // The honest half of the finding above. Without a tie-breaker the resume is right only when
        // the database happens to be consistent about tied rows, and "usually right" is not a
        // guarantee -- so the capability says so instead of hoping.
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx(
                "orders", Map.of("url", url, "table", "orders", "watermark.column", "UPDATED_AT", "stream", "orders")));
        plugin.open();

        assertThat(plugin.capabilities().replayableOffsets()).isFalse();
        assertThat(open(Map.of()).capabilities().replayableOffsets())
                .as("with a key column the sort is total and the resume is exact")
                .isTrue();
    }

    @Test
    void capabilitiesRefuseToClaimWhatPollingCannotDo() throws SQLException {
        insert(1, "ann", 10.5, 100);
        var capabilities = open(Map.of()).capabilities();

        assertThat(capabilities.emitsDeletes())
                .as("a deleted row is simply absent from the next result set, which is "
                        + "indistinguishable from a row that never existed")
                .isFalse();
        assertThat(capabilities.emitsBeforeImage()).isFalse();
        assertThat(capabilities.guarantee())
                .as("resumption is exact, but an update seen once with its new value is not a changelog")
                .isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
    }

    @Test
    void aMisconfiguredSourceSaysWhatToFix() {
        assertThatThrownBy(() -> open(Map.of("query", "SELECT 1")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("exactly one of table or query");

        assertThatThrownBy(() -> open(Map.of("watermark.column", "NOT_A_COLUMN")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("is not in the result");
    }

    @Test
    void anUnreachableDatabaseSaysWhereToLook() {
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx(
                "gone", Map.of("url", "jdbc:h2:tcp://127.0.0.1:1/nope", "table", "orders", "watermark.column", "X")));

        assertThatThrownBy(plugin::open)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("classpath")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(JdbcErrors.CONNECT_FAILED);
    }

    @Test
    void anOffsetThisPluginDidNotWriteIsRefused() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());

        assertThatThrownBy(() ->
                        plugin.createReader(plugin.partitions("orders").get(0), new SourceOffset("v=3;p=ADDS;f=0;r=0")))
                .isInstanceOf(PravahaException.class)
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(JdbcErrors.MALFORMED_OFFSET);
    }

    @Test
    void healthReportsTheConnection() throws SQLException {
        insert(1, "ann", 10.5, 100);
        JdbcSourcePlugin plugin = open(Map.of());
        assertThat(plugin.health().isUsable()).isTrue();

        plugin.close();
        assertThat(plugin.health().isUsable()).isFalse();
    }
}
