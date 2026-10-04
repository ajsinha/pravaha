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
import java.util.Objects;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;
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

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private String url;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private String table;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private String user;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private String password;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private List<String> keyColumns;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private Duration cacheFor;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private String query;

    @SuppressWarnings("NullAway.Init") // set by configure(), which the engine calls before anything else
    private StreamSchema schema;

    private int poolSize;

    /**
     * Connections, one per concurrent lookup, borrowed and returned.
     *
     * <p>Not one shared connection. The engine calls {@code lookup} from several threads at once to
     * hide the round trip, and a JDBC {@code Connection} used from two threads does not fail
     * cleanly -- it interleaves statements and result sets, and the rows come back attached to the
     * wrong query. A pool sized to the concurrency the engine is told about is the whole fix.
     */
    private java.util.concurrent.@Nullable ArrayBlockingQueue<Connection> pool;

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
        refuseSharedTlsOptions(context);
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
        this.poolSize = Integer.parseInt(context.get("pool.size", "8"));
        if (poolSize < 1) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION, "pool.size must be at least 1, got " + poolSize);
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
        Properties properties = new Properties();
        if (!user.isBlank()) {
            properties.setProperty("user", user);
        }
        if (!password.isBlank()) {
            properties.setProperty("password", password);
        }
        this.pool = new java.util.concurrent.ArrayBlockingQueue<>(poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                pool.add(DriverManager.getConnection(url, properties));
            }
        } catch (SQLException e) {
            close();
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

    /**
     * Takes a connection, waiting if every one is busy.
     *
     * <p>Waiting rather than opening another: the pool is sized to the concurrency the engine
     * declared it will not exceed, so an empty pool means the accounting is wrong somewhere, and
     * quietly opening connections would turn that into a database running out of them.
     */
    private Connection borrow() {
        try {
            return Objects.requireNonNull(pool, "open() first").take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PravahaException(
                    JdbcErrors.QUERY_FAILED, "interrupted while waiting for a connection to " + table, e);
        }
    }

    private StreamSchema readSchema() {
        String probe = "SELECT * FROM " + table + " WHERE 1 = 0";
        Connection connection = borrow();
        try (PreparedStatement statement = connection.prepareStatement(probe);
                ResultSet results = statement.executeQuery()) {
            return JdbcTypes.toStreamSchema(table, results.getMetaData());
        } catch (SQLException e) {
            throw new PravahaException(
                    JdbcErrors.QUERY_FAILED,
                    "cannot read the schema of lookup table '" + table + "': " + e.getMessage() + "\n  query: " + probe,
                    e);
        } finally {
            Objects.requireNonNull(pool, "open() first").add(connection);
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

        Connection connection = borrow();
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
        } finally {
            Objects.requireNonNull(pool, "open() first").add(connection);
        }
    }

    /** As many at once as there are connections, and not one more. */
    @Override
    public int maxConcurrency() {
        return poolSize;
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
        if (pool == null) {
            return;
        }
        List<Connection> open = new ArrayList<>();
        pool.drainTo(open);
        for (Connection connection : open) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Closing a connection that is already gone is not a failure worth propagating out
                // of a shutdown path.
            }
        }
        pool = null;
    }

    /**
     * Refuses the shared {@code tls.*} options, which a JDBC driver cannot be handed.
     *
     * <p>Every other connector takes an {@link javax.net.ssl.SSLContext}. A JDBC driver does not:
     * {@code DriverManager} is given a URL and a property bag, and each driver spells TLS its own
     * way. Accepting {@code tls.enabled: true} here and quietly doing nothing with it would leave a
     * plaintext connection behind a configuration that says otherwise, which is the one outcome
     * worth refusing outright.
     */
    private static void refuseSharedTlsOptions(PluginContext context) {
        if (!PluginTls.isConfigured(context)) {
            return;
        }
        throw new ConfigurationException(
                JdbcErrors.BAD_CONFIGURATION,
                "the shared 'tls.*' options do not apply to a JDBC connector, and accepting them would "
                        + "leave you with a plaintext connection that looks encrypted in config. A JDBC "
                        + "driver takes its TLS settings in the URL. PostgreSQL: append "
                        + "'?ssl=true&sslmode=verify-full&sslrootcert=/path/ca.pem'. MySQL: "
                        + "'?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/path/truststore.p12'. "
                        + "Oracle and SQL Server each have their own spelling -- check the driver's "
                        + "documentation. Remove the tls.* options once the URL carries them, or set "
                        + "'tls.enabled: false' to say the plaintext connection is deliberate.");
    }
}
