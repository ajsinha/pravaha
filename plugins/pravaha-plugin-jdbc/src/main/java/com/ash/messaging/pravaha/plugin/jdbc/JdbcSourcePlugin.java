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
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
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
 * DB2 and SQL Server), {@code pushdown.partial.aggregate} (default {@code true}; see {@link
 * #capabilities()}), {@code collation.binary} (default {@code false}: set it only when the database
 * compares and groups text byte for byte, case-sensitively, as the engine does -- it is what lets a
 * partial aggregate filter or group on a text column), {@code watermark.moves.on.update} (default
 * {@code true}; see {@link #capabilities()}).
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
    private int fetchSize;
    private boolean partialAggregates;
    private boolean binaryCollation;

    /**
     * Whether an update can move a row's watermark column, so that a poll reads the row again. True
     * unless the operator says the column is set once, on insert (SCAN-1).
     */
    private boolean watermarkMovesOnUpdate;

    /**
     * Nanoseconds per unit of the watermark column, or {@code 0} for "this column is a cursor and
     * carries no event time" (finding T-5).
     *
     * <p>The reader used to stamp every row's event time with the watermark column's value, raw.
     * That column is a monotone cursor and need not be a time at all; where it <em>is</em> one, it
     * is in whatever unit the table keeps, while the engine counts nanoseconds (ADR-012). An
     * {@code updated_at BIGINT} of epoch milliseconds therefore produced an event time out by a
     * factor of a million -- a watermark stuck in 1970, windows that never close, and nothing
     * anywhere saying so.
     *
     * <p><strong>Defaults to {@code none}, which is a behaviour change.</strong> A deployment
     * whose watermark column really did hold epoch nanoseconds now has to say
     * {@code watermark.unit: nanos}, and gets exactly what it had. Every other deployment was
     * getting an event time that was wrong by a factor of 1,000 or 1,000,000, or was not a time at
     * all, and now gets none -- which is what {@code feedfile} and {@code delta} already do for a
     * stream that declares no event-time column (HLP-6). There is no unit this plugin can infer,
     * and every guess is wrong for somebody.
     */
    private long watermarkUnitNanos;

    /** Parses {@code watermark.unit}: nanos, micros, millis, seconds, or none. */
    private static long watermarkUnitNanos(String instanceName, String configured) {
        return switch (configured.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> 0L;
            case "nanos", "nanoseconds" -> 1L;
            case "micros", "microseconds" -> 1_000L;
            case "millis", "milliseconds" -> 1_000_000L;
            case "seconds" -> 1_000_000_000L;
            default ->
                throw new ConfigurationException(
                        JdbcErrors.BAD_CONFIGURATION,
                        "plugin '" + instanceName + "' watermark.unit is '" + configured
                                + "'; it is one of none, nanos, micros, millis, seconds. It says what the "
                                + "watermark column's numbers mean, so that a row's event time can be built "
                                + "from them. 'none' -- the default -- says the column is a cursor and not a "
                                + "time, and rows then carry no event time at all, so no window over this "
                                + "stream can close.");
        };
    }

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
        refuseSharedTlsOptions(context);
        this.url = context.require("url");
        this.user = context.get("user", "");
        this.password = context.get("password", "");
        this.watermarkColumn = context.require("watermark.column");
        this.keyColumn = context.get("key.column", "");
        this.pageClause = context.get("page.clause", "LIMIT ?");
        this.fetchSize = Integer.parseInt(context.get("fetch.size", "500"));
        this.partialAggregates = Boolean.parseBoolean(context.get("pushdown.partial.aggregate", "true"));
        this.binaryCollation = Boolean.parseBoolean(context.get("collation.binary", "false"));
        // Strict, because a misspelt "flase" read as false would vouch for something nobody said.
        String moves = context.get("watermark.moves.on.update", "true").strip().toLowerCase(java.util.Locale.ROOT);
        if (!moves.equals("true") && !moves.equals("false")) {
            throw new ConfigurationException(
                    JdbcErrors.BAD_CONFIGURATION,
                    "plugin '" + instanceName + "' watermark.moves.on.update must be true or false, got '" + moves
                            + "'. Set it false only when the watermark column is written once, when a row is "
                            + "inserted (a sequence, a created_at), and never by an update.");
        }
        this.watermarkMovesOnUpdate = moves.equals("true");
        this.watermarkUnitNanos = watermarkUnitNanos(instanceName, context.get("watermark.unit", "none"));

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
     * The first-poll statement, optionally with the engine's filters folded in.
     *
     * <p>Two poll statements rather than one because the first poll has no lower bound at all, and
     * expressing "no bound" as a comparison against {@code Long.MIN_VALUE} would quietly exclude any
     * row that legitimately sits at that value.
     *
     * <p>Pure, so a reader can build its own without disturbing the plugin's. Two readers of one
     * stream can be serving different queries, and a shared statement would give one of them the
     * other's filters -- which reads as data quietly missing from a query nobody changed.
     */
    private String firstQueryWith(JdbcPushdown pushed, String select) {
        String where = pushed.isEmpty() ? "" : " WHERE " + pushed.sql();
        return "SELECT " + select + " FROM " + source + where + " ORDER BY " + orderClause() + " " + pageClause;
    }

    /**
     * The resume statement.
     *
     * <p>The pushed clause goes after the resume predicate in both the SQL and the parameter order,
     * because the reader binds resume parameters first and the page size last. Getting that order
     * wrong binds a filter value as a watermark, which does not fail -- it silently reads from the
     * wrong place.
     */
    private String resumeQueryWith(JdbcPushdown pushed, String select) {
        String predicate = keyColumn.isBlank()
                // Keyless: >= re-selects the boundary, and the reader skips what it already emitted.
                ? watermarkColumn + " >= ?"
                // Keyset pagination: a total order, so the resume is exact.
                : "(" + watermarkColumn + " > ? OR (" + watermarkColumn + " = ? AND " + keyColumn + " > ?))";
        String pushedClause = pushed.isEmpty() ? "" : " AND (" + pushed.sql() + ")";
        return "SELECT " + select + " FROM " + source + " WHERE " + predicate + pushedClause + " ORDER BY "
                + orderClause() + " " + pageClause;
    }

    private String orderClause() {
        return keyColumn.isBlank() ? watermarkColumn : watermarkColumn + ", " + keyColumn;
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
                // Filters and projection always: a WHERE and a SELECT list are what SQL is for.
                //
                // A partial aggregate only with a key column. The partial for a page covers the rows
                // in (offset, page end], and "page end" is a position only a total order can name;
                // keyless mode resumes by counting rows at a tied watermark, which a GROUP BY cannot
                // do. See JdbcPartialAggregateReader for why a polled partial is exactly the sum of
                // the rows the row reader would have sent, updates included, and createReader for
                // the requests it still declines.
                replayable && partialAggregates
                        ? EnumSet.of(PushdownKind.FILTER, PushdownKind.PROJECT, PushdownKind.PARTIAL_AGGREGATE)
                        : EnumSet.of(PushdownKind.FILTER, PushdownKind.PROJECT),
                Duration.ofSeconds(1),
                repeatsRows());
    }

    /**
     * Whether a poll can read a row it has already read (SCAN-1).
     *
     * <p>Two ways, and only the operator can rule out the first. A watermark column an update moves
     * -- the usual {@code updated_at} -- brings an updated row back as the new row at {@code +1} with
     * nothing retracting the old, so a {@code COUNT} counts it twice. The plugin cannot tell such a
     * column from one set once on insert, so it assumes the former unless {@code
     * watermark.moves.on.update: false} says otherwise. And without {@code key.column}, rows tied on
     * the watermark are resumed by counting them, which re-reads a row whenever the database returns
     * tied rows in another order. Either way the registry refuses an aggregate, a join or an
     * append-only sink over the stream (PRV-2042); a keyed view of the rows is unaffected.
     */
    private boolean repeatsRows() {
        return watermarkMovesOnUpdate || keyColumn.isBlank();
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
        return createReader(partition, resumeFrom, com.ash.messaging.pravaha.api.plugin.ReadRequest.NOTHING);
    }

    @Override
    public PartitionReader createReader(
            SourcePartition partition,
            SourceOffset resumeFrom,
            com.ash.messaging.pravaha.api.plugin.ReadRequest request) {
        JdbcPushdown pushed = JdbcPushdown.of(request, schema);
        if (request != null && !request.aggregates().isEmpty()) {
            ReadRequest.PartialAggregate partial = request.aggregates().get(0);
            if (whyNoPartial(partial, pushed, request) == null) {
                return new JdbcPartialAggregateReader(
                        connection,
                        source,
                        watermarkColumn,
                        keyColumn,
                        pageClause,
                        fetchSize,
                        pushed,
                        partial,
                        resumeFrom);
            }
            // Declined: rows, which the engine filters and aggregates itself. Always correct.
        }
        List<String> selected = selectedColumns(request);
        String select = selected == null ? "*" : String.join(", ", selected);
        return new JdbcPartitionReader(
                        connection,
                        firstQueryWith(pushed, select),
                        resumeQueryWith(pushed, select),
                        schema,
                        watermarkColumn,
                        keyColumn,
                        fetchSize,
                        resumeFrom,
                        pushed.values(),
                        selected)
                .readingWatermarkAs(watermarkUnitNanos);
    }

    /**
     * The columns a pushed projection selects, in schema order, or null for {@code SELECT *}.
     *
     * <p>The watermark and key columns are always added: the reader orders and resumes by them
     * whether or not the engine reads them. A requested name this schema does not have means the
     * request is about something else, and reading everything is the safe answer to that.
     */
    private List<String> selectedColumns(ReadRequest request) {
        if (request == null || request.columns().isEmpty()) {
            return null;
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(request.columns());
        for (String column : request.columns()) {
            if (!schema.hasField(column)) {
                return null;
            }
        }
        List<String> selected = new java.util.ArrayList<>();
        for (com.ash.messaging.pravaha.api.data.Field field : schema.fields()) {
            String name = field.name();
            if (wanted.contains(name)
                    || name.equalsIgnoreCase(watermarkColumn)
                    || (!keyColumn.isBlank() && name.equalsIgnoreCase(keyColumn))) {
                selected.add(name);
            }
        }
        return selected.size() == schema.fieldCount() ? null : selected;
    }

    /**
     * Why this source will not pre-combine {@code partial}, or null when it will.
     *
     * <p>Every reason is a way the database's answer could differ from the engine's, and a partial
     * leaves nothing downstream to notice: a filter the SQL could not carry, a text comparison or
     * text grouping under a collation nobody has said is binary (a case-insensitive one groups
     * {@code 'DONE'} with {@code 'done'}), a floating-point group key ({@code -0.0} and {@code 0.0}
     * are one group in SQL and two in the engine), or a sum over anything but BIGINT.
     */
    String whyNoPartial(ReadRequest.PartialAggregate partial, JdbcPushdown pushed, ReadRequest request) {
        if (keyColumn.isBlank() || !partialAggregates) {
            return "partial aggregates need key.column and pushdown.partial.aggregate";
        }
        if (!pushed.exact()) {
            return "a filter could not be expressed in SQL, and a partial has no rows left to filter";
        }
        if (!binaryCollation) {
            List<ReadRequest.Filter> all = new java.util.ArrayList<>(request.filters());
            request.alternatives().forEach(all::addAll);
            for (ReadRequest.Filter filter : all) {
                if (typeOf(filter.column()) == TypeName.STRING) {
                    return "a text filter's meaning depends on the database's collation; set collation.binary";
                }
            }
        }
        for (String column : partial.groupByColumns()) {
            TypeName type = typeOf(column);
            boolean integral = type == TypeName.BOOLEAN
                    || type == TypeName.INT8
                    || type == TypeName.INT16
                    || type == TypeName.INT32
                    || type == TypeName.INT64;
            if (!integral && !(type == TypeName.STRING && binaryCollation)) {
                return "grouping by " + column + " (" + type + ") in the database may not group as the engine does";
            }
        }
        for (ReadRequest.PartialAggregate.AggregateCall call : partial.aggregates()) {
            if (call.column() != null && typeOf(call.column()) == null) {
                return "no column " + call.column();
            }
            if (call.kind() == ReadRequest.PartialAggregate.Kind.SUM && typeOf(call.column()) != TypeName.INT64) {
                return "the engine sums BIGINT only";
            }
        }
        return null;
    }

    private TypeName typeOf(String column) {
        return schema.hasField(column)
                ? schema.field(schema.indexOf(column)).type().typeName()
                : null;
    }

    /** The SQL a reader with these filters would run, for tests and EXPLAIN. */
    String pollQueryFor(com.ash.messaging.pravaha.api.plugin.ReadRequest request) {
        List<String> selected = selectedColumns(request);
        return firstQueryWith(JdbcPushdown.of(request, schema), selected == null ? "*" : String.join(", ", selected));
    }

    /** The stream this plugin exposes. */
    public StreamSchema schema() {
        return schema;
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
    static void refuseSharedTlsOptions(PluginContext context) {
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
