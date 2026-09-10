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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * A dimension table in any JDBC database.
 *
 * <p>What makes {@code JOIN customers FOR SYSTEM_TIME AS OF ...} work against Postgres, MySQL or
 * anything else with a driver -- which is most of the enrichment anybody actually needs, and it
 * needs no new infrastructure at all.
 *
 * <p>Configuration: {@code url} (required), {@code table} (required), {@code key.columns} (required,
 * comma-separated), {@code cache.seconds} (default 0, meaning ask every time), {@code user},
 * {@code password}.
 *
 * <p>The key columns are declared rather than inferred, because a database answers a point lookup
 * quickly only on an indexed key. Left to infer, a query joining on an unindexed column would run a
 * sequential scan per record, and the symptom -- a pipeline that is mysteriously a thousand times
 * slower than the same query elsewhere -- points at the network rather than at the missing index.
 * Declaring them makes the mismatch a registration error instead.
 */
public final class JdbcLookupPlugin implements LookupSourcePlugin {

    private String url;
    private String table;
    private String user;
    private String password;
    private List<String> keyColumns;
    private Duration cacheFor;
    private String query;
    private StreamSchema schema;
    private Connection connection;

    @Override
    public String name() {
        return "jdbc-lookup";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.url = required(context, "url");
        this.table = required(context, "table");
        this.user = context.get("user", "");
        this.password = context.get("password", "");
        this.keyColumns = Arrays.stream(required(context, "key.columns").split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
        if (keyColumns.isEmpty()) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION, "key.columns must name at least one column of '" + table + "'");
        }
        long seconds = Long.parseLong(context.get("cache.seconds", "0"));
        if (seconds < 0) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION, "cache.seconds must not be negative, got " + seconds);
        }
        this.cacheFor = Duration.ofSeconds(seconds);
        this.query = "SELECT * FROM " + table + " WHERE "
                + String.join(" AND ", keyColumns.stream().map(c -> c + " = ?").toList());
    }

    private static String required(PluginContext context, String key) {
        String value = context.get(key, "");
        if (value.isBlank()) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION, "lookup source '" + context.instanceName() + "' needs " + key);
        }
        return value;
    }

    @Override
    public void open() {
        try {
            Properties properties = new Properties();
            if (!user.isBlank()) {
                properties.setProperty("user", user);
            }
            if (!password.isBlank()) {
                properties.setProperty("password", password);
            }
            this.connection = DriverManager.getConnection(url, properties);
        } catch (SQLException e) {
            throw new PravahaException(
                    JdbcErrors.CONNECT_FAILED,
                    "cannot connect to " + url + ": " + e.getMessage()
                            + ". Check the URL, the credentials, and that the driver is on the classpath.",
                    e);
        }
        this.schema = readSchema();
        // Checked once, here, rather than per lookup: a key column that does not exist would
        // otherwise be a SQL error on the first record, an hour into a run.
        for (String column : keyColumns) {
            if (schema.fields().stream().noneMatch(field -> field.name().equalsIgnoreCase(column))) {
                throw new ConfigurationException(
                        JdbcErrors.BAD_CONFIGURATION,
                        "'" + column + "' is not a column of " + table + "; it has "
                                + schema.fields().stream().map(f -> f.name()).toList());
            }
        }
    }

    private StreamSchema readSchema() {
        String probe = "SELECT * FROM " + table + " WHERE 1 = 0";
        try (PreparedStatement statement = connection.prepareStatement(probe);
                ResultSet results = statement.executeQuery()) {
            return JdbcTypes.toStreamSchema(table, results.getMetaData());
        } catch (SQLException e) {
            throw new PravahaException(
                    JdbcErrors.QUERY_FAILED,
                    "cannot read the schema of lookup table '" + table + "': " + e.getMessage() + "\n  query: " + probe,
                    e);
        }
    }

    @Override
    public StreamSchema schema() {
        return schema;
    }

    @Override
    public List<String> keyColumns() {
        return keyColumns;
    }

    @Override
    public int lookup(Object[] key, PartitionReader.RecordSink sink) {
        if (key.length != keyColumns.size()) {
            throw new IllegalArgumentException(
                    "this lookup takes " + keyColumns.size() + " key values " + keyColumns + ", got " + key.length);
        }
        // A null key matches nothing: SQL's `col = NULL` is UNKNOWN, so the query would return
        // nothing anyway -- but a round trip to learn that is a round trip wasted, and under a
        // stream of null keys it is one per record.
        for (Object value : key) {
            if (value == null) {
                return 0;
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < key.length; i++) {
                statement.setObject(i + 1, key[i]);
            }
            try (ResultSet results = statement.executeQuery()) {
                return emit(results, sink);
            }
        } catch (SQLException e) {
            throw new PravahaException(
                    JdbcErrors.QUERY_FAILED,
                    "lookup in '" + table + "' failed: " + e.getMessage() + "\n  query: " + query,
                    e);
        }
    }

    private int emit(ResultSet results, PartitionReader.RecordSink sink) throws SQLException {
        List<com.ash.messaging.pravaha.api.data.Field> fields = new ArrayList<>(schema.fields());
        int emitted = 0;
        while (results.next()) {
            RowWriter writer = sink.beginRow();
            for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
                JdbcTypes.copyValue(
                        results,
                        ordinal + 1,
                        writer,
                        ordinal,
                        fields.get(ordinal).type().typeName());
            }
            // A looked-up row is a fact about the store rather than a change to it, so the header
            // fields are the enriched record's, not this one's. Written anyway because a writer
            // that never sees them cannot know they were meant to be ignored.
            writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
            emitted++;
        }
        return emitted;
    }

    @Override
    public Duration cacheFor() {
        return cacheFor;
    }

    /**
     * A point lookup over the network, which is a millisecond even when the database is fast.
     *
     * <p>Reported honestly because the planner uses it: on the lane thread this caps a lane at about
     * a thousand records a second, and a query that cannot live with that should hear so at
     * registration rather than from a throughput graph.
     */
    @Override
    public Duration typicalLatency() {
        return Duration.ofMillis(1);
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Closing a connection that is already gone is not a failure worth propagating out
                // of a shutdown path.
            }
            connection = null;
        }
    }
}
