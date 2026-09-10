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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Incremental-poll source over any JDBC database.
 *
 * <p>One connector for PostgreSQL, MySQL, SQL Server, Oracle, H2 and anything else with a driver,
 * because it uses nothing but standard JDBC and the deployment supplies the driver on this plugin's
 * isolated classpath (ADR-010). That breadth is the point: most databases have no change feed that
 * is available, affordable or permitted, and the choice in practice is between polling and not
 * ingesting at all.
 *
 * <p><strong>What polling cannot do, said out loud.</strong> It sees inserts, and updates when the
 * watermark column is a modification timestamp. It <em>cannot see a delete</em> -- a deleted row is
 * simply absent from the next result set, which is indistinguishable from a row that never existed
 * -- and it cannot produce a before-image. The capabilities say so, so the planner refuses a query
 * that needs either rather than maintaining a view that quietly keeps deleted rows alive. For
 * PostgreSQL specifically, logical decoding (design section 19.5) is a far better source and this is
 * the fallback for when it cannot be enabled.
 *
 * <p><strong>A key column is what makes resumption exact.</strong> Watermark values tie -- a batch
 * job stamps a thousand rows with one timestamp -- and SQL defines no order among tied rows, so
 * without a unique tie-breaker in the sort the boundary cannot be resumed reliably. Configure
 * {@code key.column} and the plugin uses keyset pagination and declares replayable offsets; leave it
 * out and it falls back to counting rows at the boundary and declares itself <em>not</em>
 * replayable, because that fallback is correct only when the database happens to return tied rows
 * in a stable order.
 *
 * <p>Configuration: {@code url} (required), {@code table} or {@code query} (one required),
 * {@code watermark.column} (required), {@code key.column} (strongly recommended, must be a unique
 * integer column), {@code user}, {@code password}, {@code stream}, {@code fetch.size} (default 500),
 * {@code page.clause} (default {@code LIMIT ?}; use {@code FETCH FIRST ? ROWS ONLY} for Oracle,
 * DB2 and SQL Server).
 */
public final class JdbcSourcePlugin implements StreamSourcePlugin {

    private String instanceName = "jdbc";
    private String url;
    private String user;
    private String password;
    private String streamName;
    private String watermarkColumn;
    private String keyColumn;
    private String source;
    private String pageClause;
    private String firstQuery;
    private String resumeQuery;
    private int fetchSize;

    private Connection connection;
    private StreamSchema schema;

    @Override
    public String name() {
        return "jdbc";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.url = context.require("url");
        this.user = context.get("user", "");
        this.password = context.get("password", "");
        this.watermarkColumn = context.require("watermark.column");
        this.keyColumn = context.get("key.column", "");
        this.pageClause = context.get("page.clause", "LIMIT ?");
        this.fetchSize = Integer.parseInt(context.get("fetch.size", "500"));

        String table = context.get("table", "");
        String query = context.get("query", "");
        if (table.isBlank() == query.isBlank()) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' needs exactly one of table or query. 'table' polls a whole "
                            + "table; 'query' polls an arbitrary SELECT, which is where a cast or a join goes.");
        }
        this.source = table.isBlank() ? "(" + query + ") AS src" : table;
        this.streamName = context.get("stream", table.isBlank() ? instanceName : table);
    }

    /**
     * Builds the two poll statements.
     *
     * <p>Two rather than one because the first poll has no lower bound at all, and expressing "no
     * bound" as a comparison against {@code Long.MIN_VALUE} would quietly exclude any row that
     * legitimately sits at that value.
     */
    private void buildQueries() {
        String order = keyColumn.isBlank() ? watermarkColumn : watermarkColumn + ", " + keyColumn;
        this.firstQuery = "SELECT * FROM " + source + " ORDER BY " + order + " " + pageClause;
        String predicate = keyColumn.isBlank()
                // Keyless: >= re-selects the boundary, and the reader skips what it already emitted.
                ? watermarkColumn + " >= ?"
                // Keyset pagination: a total order, so the resume is exact.
                : "(" + watermarkColumn + " > ? OR (" + watermarkColumn + " = ? AND " + keyColumn + " > ?))";
        this.resumeQuery = "SELECT * FROM " + source + " WHERE " + predicate + " ORDER BY " + order + " " + pageClause;
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
                    "plugin '" + instanceName + "' cannot connect to " + url + ": " + e.getMessage()
                            + ". The JDBC driver is supplied by the deployment, not by the engine -- check it "
                            + "is on this plugin's classpath.",
                    e);
        }
        this.schema = discoverSchema();
        buildQueries();
    }

    /**
     * Asks the database for the schema rather than asking the operator to restate it.
     *
     * <p>The database holds an authoritative typed definition; a human restating it introduces a
     * second version that can disagree. That is the opposite of the feed-file plugin's rule, and the
     * difference is exactly whether an authority exists to ask.
     */
    private StreamSchema discoverSchema() {
        // A bare projection with no predicate and no ordering: metadata without moving a row, and
        // without naming the watermark column -- so a misspelled column produces this plugin's
        // message about configuration rather than the driver's message about SQL.
        String probe = "SELECT * FROM " + source + " " + pageClause.replace("?", "0");
        try (PreparedStatement statement = connection.prepareStatement(probe)) {
            try (ResultSet results = statement.executeQuery()) {
                StreamSchema discovered = JdbcTypes.toStreamSchema(streamName, results.getMetaData());
                requireColumn(discovered, watermarkColumn, "watermark.column");
                if (!keyColumn.isBlank()) {
                    requireColumn(discovered, keyColumn, "key.column");
                }
                return discovered;
            }
        } catch (SQLException e) {
            throw new PravahaException(
                    JdbcErrors.QUERY_FAILED,
                    "cannot read the schema of stream '" + streamName + "': " + e.getMessage() + "\n  query: " + probe,
                    e);
        }
    }

    private static void requireColumn(StreamSchema schema, String column, String setting) {
        boolean present = schema.fields().stream().anyMatch(f -> f.name().equalsIgnoreCase(column));
        if (!present) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION,
                    setting + " '" + column + "' is not in the result: "
                            + schema.fields().stream()
                                    .map(com.ash.messaging.pravaha.api.data.Field::name)
                                    .toList());
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new PravahaException(JdbcErrors.CONNECT_FAILED, "cannot close the connection: " + e, e);
            } finally {
                connection = null;
            }
        }
    }

    @Override
    public HealthStatus health() {
        try {
            return connection != null && connection.isValid(1)
                    ? HealthStatus.healthy()
                    : HealthStatus.unhealthy("the JDBC connection is closed or not responding");
        } catch (SQLException e) {
            return HealthStatus.unhealthy("connection check failed: " + e.getMessage());
        }
    }

    @Override
    public SourceCapabilities capabilities() {
        // Replayable only with a key column. Without one the sort is not total, so the database may
        // return rows that tie on the watermark in any order it likes -- and a resume that is right
        // only when it happens to be consistent is not a guarantee. This was a bug here before it
        // was a declaration: a test resumed at rows 3 and 2 where it expected 1 and 2.
        boolean replayable = !keyColumn.isBlank();
        return new SourceCapabilities(
                replayable,
                true,
                // The two that matter. A poll cannot see a delete: the row is simply not in the
                // next result set, and nothing distinguishes that from a row that never existed.
                false,
                false,
                // Not EXACTLY_ONCE. Resumption is exact, but a row updated between two polls is
                // seen once with its new value and never as a correction of the old one, so the
                // stream is not a faithful changelog however careful the offsets are.
                DeliveryGuarantee.AT_LEAST_ONCE,
                EnumSet.noneOf(PushdownKind.class),
                Duration.ofSeconds(1));
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    @Override
    public List<SourcePartition> partitions(String stream) {
        // One reader. Splitting by key range is the obvious parallelism and needs a range the
        // database can index on, which is a per-deployment decision rather than a default.
        return List.of(new SourcePartition(stream, 0, Map.of("url", url)));
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        return new JdbcPartitionReader(
                connection, firstQuery, resumeQuery, schema, watermarkColumn, keyColumn, fetchSize, resumeFrom);
    }

    /** The stream this plugin exposes. */
    public StreamSchema schema() {
        return schema;
    }
}
