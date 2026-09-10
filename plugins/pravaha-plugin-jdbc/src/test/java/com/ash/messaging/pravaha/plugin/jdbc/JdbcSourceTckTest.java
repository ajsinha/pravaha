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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * The JDBC source, run against the conformance suite.
 *
 * <p>Configured with a key column, which is the configuration that claims replayable offsets -- so
 * the TCK's replay check tests the claim this plugin actually makes. The keyless configuration
 * declares itself non-replayable and the suite would skip that check, which would be a weaker test
 * of a weaker promise.
 */
class JdbcSourceTckTest extends SourcePluginTck {

    private static final int RECORDS = 5;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    private final String url = createFixture();

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static String createFixture() {
        String url = "jdbc:h2:mem:pravaha-tck" + DATABASE.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tck (id BIGINT NOT NULL, name VARCHAR(64), updated_at BIGINT NOT NULL)");
            for (int i = 1; i <= RECORDS; i++) {
                statement.execute("INSERT INTO tck VALUES (" + i + ", 'row-" + i + "', " + (i * 100) + ")");
            }
            return url;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot build the TCK fixture", e);
        }
    }

    @Override
    protected StreamSourcePlugin createPlugin() {
        JdbcSourcePlugin plugin = new JdbcSourcePlugin();
        plugin.configure(new Ctx(
                "tck",
                Map.of(
                        "url", url,
                        "table", "tck",
                        "watermark.column", "UPDATED_AT",
                        "key.column", "ID",
                        "stream", "tck")));
        plugin.open();
        return plugin;
    }

    @Override
    protected String streamName() {
        return "tck";
    }

    @Override
    protected int expectedRecordCount() {
        return RECORDS;
    }

    @Override
    protected RowCollector newCollector(StreamSourcePlugin plugin) {
        return new JdbcCollector(plugin.discoverSchemas().get(0));
    }
}
