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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.testkit.tck.ArenaRowCollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A dimension table in any JDBC database, against H2.
 *
 * <p>This is what makes enrichment work out of the box: {@code JOIN customers FOR SYSTEM_TIME AS OF
 * ...} against Postgres, MySQL or anything else with a driver, needing no new infrastructure.
 */
class JdbcLookupPluginTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private String url;
    private Connection admin;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:lookup" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        admin = DriverManager.getConnection(url);
        execute("CREATE TABLE customers (id BIGINT NOT NULL, segment VARCHAR(32), region VARCHAR(32))");
        execute("INSERT INTO customers VALUES (1, 'gold', 'EU'), (2, 'silver', 'US'), (3, NULL, 'EU')");
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

    private JdbcLookupPlugin open(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("url", url, "table", "customers", "key.columns", "ID"));
        config.putAll(extra);
        JdbcLookupPlugin plugin = new JdbcLookupPlugin();
        plugin.configure(new Ctx("customers", config));
        plugin.open();
        return plugin;
    }

    /** Collects the rows a lookup writes. */
    private static final class Collector implements PartitionReader.RecordSink {
        private final ArenaRowCollector delegate;

        Collector(JdbcLookupPlugin plugin) {
            this.delegate = new ArenaRowCollector(plugin.schema());
        }

        @Override
        public com.ash.messaging.pravaha.api.data.RowWriter beginRow() {
            return delegate.beginRow();
        }

        List<RowView> rows() {
            return delegate.rows();
        }
    }

    @Test
    void aKeyFindsItsRow() {
        JdbcLookupPlugin plugin = open(Map.of());
        Collector out = new Collector(plugin);

        assertThat(plugin.lookup(new Object[] {1L}, out)).isEqualTo(1);
        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).getString(1)).isEqualTo("gold");
        assertThat(out.rows().get(0).getString(2)).isEqualTo("EU");
    }

    @Test
    void aKeyWithNoRowReturnsNothingRatherThanFailing() {
        JdbcLookupPlugin plugin = open(Map.of());
        Collector out = new Collector(plugin);

        assertThat(plugin.lookup(new Object[] {99L}, out)).isZero();
        assertThat(out.rows()).isEmpty();
    }

    @Test
    void aNullColumnComesBackAsNullRatherThanAsAnEmptyString() {
        // Customer 3 has no segment. An empty string here would be a value the database does not
        // hold, and every downstream COALESCE and IS NULL would be wrong about it.
        JdbcLookupPlugin plugin = open(Map.of());
        Collector out = new Collector(plugin);
        plugin.lookup(new Object[] {3L}, out);

        assertThat(out.rows().get(0).isNull(1)).isTrue();
    }

    @Test
    void aNullKeyIsNotSentToTheDatabaseAtAll() throws SQLException {
        // `col = NULL` is UNKNOWN in SQL, so the query would return nothing anyway -- and under a
        // stream of null keys that is one wasted round trip per record. Proving it did not happen
        // needs the query to be impossible: with the table dropped, a lookup that reached the
        // database would throw, and one that short-circuited returns nothing.
        JdbcLookupPlugin plugin = open(Map.of());
        execute("DROP TABLE customers");
        Collector out = new Collector(plugin);

        assertThat(plugin.lookup(new Object[] {null}, out)).isZero();
        assertThatThrownBy(() -> plugin.lookup(new Object[] {1L}, new Collector(plugin)))
                .as("a real key must still go to the database, or this test proves nothing")
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class);
    }

    @Test
    void theSchemaIsReadFromTheDatabaseRatherThanDeclared() {
        JdbcLookupPlugin plugin = open(Map.of());

        assertThat(plugin.schema().fields().stream().map(f -> f.name()).toList())
                .containsExactly("ID", "SEGMENT", "REGION");
    }

    @Test
    void theCacheLifetimeIsTheSourcesToDeclare() {
        assertThat(open(Map.of()).cacheFor())
                .as("ask every time unless told otherwise")
                .isEqualTo(Duration.ZERO);
        assertThat(open(Map.of("cache.seconds", "300")).cacheFor()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aKeyColumnThatDoesNotExistIsRefusedAtRegistration() {
        // Not on the first record, an hour into a run: by then the query has been reporting healthy
        // and producing nothing.
        assertThatThrownBy(() -> open(Map.of("key.columns", "NOPE")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("is not a column of customers");
    }

    @Test
    void aMissingSettingSaysWhichOne() {
        JdbcLookupPlugin plugin = new JdbcLookupPlugin();
        assertThatThrownBy(() -> plugin.configure(new Ctx("customers", Map.of("url", url, "table", "customers"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("key.columns");
    }

    @Test
    void aCompositeKeyIsLookedUpOnAllOfItsColumns() throws SQLException {
        execute("CREATE TABLE rates (base VARCHAR(3) NOT NULL, quote VARCHAR(3) NOT NULL, rate DOUBLE)");
        execute("INSERT INTO rates VALUES ('EUR', 'USD', 1.1), ('EUR', 'GBP', 0.85)");

        JdbcLookupPlugin plugin = new JdbcLookupPlugin();
        plugin.configure(new Ctx("rates", Map.of("url", url, "table", "rates", "key.columns", "BASE, QUOTE")));
        plugin.open();

        Collector out = new Collector(plugin);
        assertThat(plugin.lookup(new Object[] {"EUR", "GBP"}, out)).isEqualTo(1);
        assertThat(out.rows().get(0).getDouble(2)).isEqualTo(0.85);
        assertThat(plugin.keyColumns()).containsExactly("BASE", "QUOTE");
    }

    @Test
    void beingGivenTheWrongNumberOfKeyValuesIsRefused() {
        JdbcLookupPlugin plugin = open(Map.of());
        List<RowView> ignored = new ArrayList<>();

        assertThatThrownBy(() -> plugin.lookup(new Object[] {1L, 2L}, new Collector(plugin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes 1 key value");
        assertThat(ignored).isEmpty();
    }
}
