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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.testkit.tck.SinkPluginTck;

/**
 * The JDBC sink against H2, run through the sink conformance suite in its two shapes: keyed upsert,
 * transactional (the default) -- which exercises every case the suite has -- and append-only without
 * transactions, where the keyed and transactional cases are skipped as not declared.
 */
class JdbcSinkTckTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** One H2 database per test instance, with the destination table. */
    abstract static class OverH2 extends SinkPluginTck {

        final String url = "jdbc:h2:mem:sinktck" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";

        OverH2(String ddl) {
            try (Connection connection = DriverManager.getConnection(url);
                    Statement statement = connection.createStatement()) {
                statement.execute(ddl);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot build the sink TCK's table", e);
            }
        }

        StreamSinkPlugin sink(String table, Map<String, String> extra) {
            Map<String, String> config =
                    new HashMap<>(Map.of("url", url, "table", table, "schema", "user_id:STRING,total:INT64"));
            config.putAll(extra);
            JdbcSinkPlugin sink = new JdbcSinkPlugin();
            sink.configure(new Ctx("tck_sink", config));
            sink.open();
            return sink;
        }

        List<String> rows(String table) {
            List<String> rows = new ArrayList<>();
            try (Connection connection = DriverManager.getConnection(url);
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT user_id, total FROM " + table)) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + "|" + rs.getLong(2));
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return rows;
        }

        @Override
        protected Object[] record(int i) {
            return new Object[] {"u" + i, 100L * (i + 1)};
        }

        @Override
        protected String render(Object[] values) {
            return values[0] + "|" + values[1];
        }
    }

    @Nested
    class UpsertTransactional extends OverH2 {

        UpsertTransactional() {
            super("CREATE TABLE totals (user_id VARCHAR(32) NOT NULL PRIMARY KEY, total BIGINT)");
        }

        @Override
        protected StreamSinkPlugin createSink() {
            return sink("totals", Map.of("key.columns", "user_id"));
        }

        @Override
        protected List<String> readBack() {
            return rows("totals");
        }

        @Override
        protected Object[] revision(int i) {
            return new Object[] {"u" + i, 7L * (i + 1)};
        }
    }

    @Nested
    class AppendOnly extends OverH2 {

        AppendOnly() {
            super("CREATE TABLE events (user_id VARCHAR(32) NOT NULL, total BIGINT)");
        }

        @Override
        protected StreamSinkPlugin createSink() {
            return sink("events", Map.of("mode", "append", "transactional", "false"));
        }

        @Override
        protected List<String> readBack() {
            return rows("events");
        }
    }
}
