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

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.ssl.ProgrammaticSslEngineFactory;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Cassandra as a source, by scanning a table's assigned token range (ADR-039 item 6).
 *
 * <p><strong>Cassandra has no client-pullable change log.</strong> Its CDC writes commitlog segments
 * to a {@code cdc_raw} directory on every node, meant to be read locally; there is no server-side
 * decoding and streaming the way Postgres's logical replication or MySQL's binlog work. A CDC reader
 * for Cassandra is a per-node agent with no ordering across nodes -- a different project from a
 * connector, as {@code docs/CONNECTORS.md} section 5 explains. This plugin is the connector: a full,
 * periodic, partition-parallel scan of a table, paged by {@code token()} so no partition is read
 * through {@code ALLOW FILTERING}.
 *
 * <p>{@link CassandraStrategy} names two other strategies and implements neither.
 * {@code writetime-incremental} looks like the natural next step -- filter each scan on {@code
 * writetime()} the way the Aerospike plugin filters on {@code record.last_update_time()} -- and CQL
 * makes that dishonest rather than merely unimplemented: there is no index on {@code writetime()}, so
 * the filter cannot run server-side without {@code ALLOW FILTERING}, which reads every partition
 * anyway and buys none of the bandwidth an incremental scan exists for; and {@code writetime()} is
 * per-<em>column</em>, so a key-only write or a write to a column outside the tracked set moves no
 * watermark at all and would be missed in silence. A full scan that says what it is beats an
 * incremental one that quietly drops rows, which is the choice {@code docs/CONNECTORS.md} section 6
 * asks every connector to make explicitly. {@code commitlog-cdc} is the different, unbuilt project
 * above.
 *
 * <p>What a scan cannot do, however carefully it is written:
 *
 * <ul>
 *   <li><strong>Deletes are invisible.</strong> A tombstoned row is simply absent from the next scan,
 *       indistinguishable from one that never existed.
 *   <li><strong>Intra-interval overwrites collapse.</strong> Two writes between scans are seen as
 *       one, with only the final value.
 *   <li><strong>No before-image</strong>, so an update arrives as an insert of the new value with
 *       nothing to retract.
 * </ul>
 *
 * <p>Configuration: {@code contact.points} (required, {@code host:port,host:port}), {@code keyspace}
 * (required), {@code table} (required), {@code schema} (required, {@code column:TYPE,...}),
 * {@code partition.key} (required, comma-separated column names, in CQL's own partition-key order),
 * {@code local.datacenter} (optional; a single-DC cluster is auto-detected from the contact points),
 * {@code strategy} (default {@code token-range-scan}), {@code partitions} (default 1),
 * {@code scan.interval.ms} (default 60000 -- ten times the Aerospike plugin's default, because this
 * scan is not incremental: every pass reads the whole assigned range, and a short interval on a large
 * table is a scan that never stops running), {@code fetch.size} (default 5000, the CQL page size),
 * {@code consistency.level} (default {@code LOCAL_ONE}), {@code request.timeout.ms} (default 30000),
 * {@code user} / {@code password}.
 */
public final class CassandraSourcePlugin implements StreamSourcePlugin {

    /** The alias this plugin's own query gives {@code token(...)} in its SELECT list. */
    static final String TOKEN_ALIAS = "pv_token__";

    private String instanceName;
    private List<InetSocketAddress> contactPoints;
    private String localDatacenter;
    private String keyspace;
    private String table;
    private String streamName;
    private StreamSchema schema;
    private List<String> partitionKeyColumns;
    private String eventTimeColumn = "";
    private CassandraStrategy strategy;
    private int partitions;
    private int scanIntervalMillis;
    private int fetchSize;
    private ConsistencyLevel consistencyLevel;
    private int requestTimeoutMillis;
    private String user;
    private String password;
    private ProgrammaticSslEngineFactory sslEngineFactory;

    private CqlSession session;
    private PreparedStatement greaterThan;
    private PreparedStatement greaterOrEqual;

    /**
     * The two range statements per projected column list, prepared once each. Keyed by the
     * columns in schema order, so every reader with the same projection shares one pair.
     */
    private final java.util.Map<List<String>, PreparedStatement[]> projected =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String name() {
        return "cassandra";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.contactPoints = CassandraContactPoints.parse(context.require("contact.points"));
        this.localDatacenter = context.get("local.datacenter", "");
        this.keyspace = context.require("keyspace");
        this.table = context.require("table");
        this.streamName = context.get("stream", table);
        StreamSchema parsed = CassandraSchemas.parse(streamName, context.require("schema"));

        this.partitionKeyColumns = java.util.Arrays.stream(
                        context.require("partition.key").split(","))
                .map(String::strip)
                .toList();
        for (String keyColumn : partitionKeyColumns) {
            if (keyColumn.isBlank() || !parsed.hasField(keyColumn)) {
                throw new ConfigurationException(
                        CassandraErrors.BAD_CONFIGURATION,
                        "partition.key names '" + keyColumn + "', which is not a column of stream '" + streamName
                                + "'. Its columns are "
                                + parsed.fields().stream()
                                        .map(com.ash.messaging.pravaha.api.data.Field::name)
                                        .toList());
            }
        }

        this.eventTimeColumn = context.get("event.time", "");
        if (!eventTimeColumn.isBlank()) {
            if (!parsed.hasField(eventTimeColumn)) {
                throw new ConfigurationException(
                        CassandraErrors.BAD_CONFIGURATION,
                        "event.time names '" + eventTimeColumn + "', which is not a column of stream '"
                                + streamName + "'. Its columns are "
                                + parsed.fields().stream()
                                        .map(com.ash.messaging.pravaha.api.data.Field::name)
                                        .toList());
            }
            if (parsed.field(parsed.indexOf(eventTimeColumn)).type().typeName()
                    != com.ash.messaging.pravaha.api.data.TypeName.TIMESTAMP_LTZ) {
                throw new ConfigurationException(
                        CassandraErrors.BAD_CONFIGURATION,
                        "event.time names '" + eventTimeColumn + "', which is declared "
                                + parsed.field(parsed.indexOf(eventTimeColumn))
                                        .type()
                                        .typeName()
                                + " rather than TIMESTAMP; only a TIMESTAMP column can be a row's event time");
            }
            StreamSchema.Builder builder = StreamSchema.builder(streamName);
            parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
            parsed = builder.eventTime(eventTimeColumn).build();
        }
        this.schema = parsed;

        this.strategy =
                CassandraStrategy.parse(context.get("strategy", CassandraStrategy.TOKEN_RANGE_SCAN.configName()));

        this.partitions = Integer.parseInt(context.get("partitions", "1"));
        if (partitions < 1) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION, "partitions must be positive, got " + partitions);
        }

        // Not incremental: every pass reads the whole assigned range, so a short interval on a
        // large table is a scan that never stops running. Ten times the Aerospike plugin's
        // default, which is filtered and therefore far cheaper per pass.
        this.scanIntervalMillis = Integer.parseInt(context.get("scan.interval.ms", "60000"));
        if (scanIntervalMillis < 0) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION,
                    "scan.interval.ms must not be negative, got " + scanIntervalMillis);
        }

        this.fetchSize = Integer.parseInt(context.get("fetch.size", "5000"));
        if (fetchSize < 1) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION, "fetch.size must be positive, got " + fetchSize);
        }

        String consistency =
                context.get("consistency.level", "LOCAL_ONE").strip().toUpperCase(java.util.Locale.ROOT);
        try {
            this.consistencyLevel = DefaultConsistencyLevel.valueOf(consistency);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION,
                    "consistency.level '" + consistency + "' is not one of "
                            + java.util.Arrays.toString(DefaultConsistencyLevel.values()));
        }

        this.requestTimeoutMillis = Integer.parseInt(context.get("request.timeout.ms", "30000"));
        if (requestTimeoutMillis <= 0) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION,
                    "request.timeout.ms must be positive; zero means wait for ever, and a scan that never "
                            + "returns takes its lane with it and looks exactly like a hung engine");
        }

        this.user = context.get("user", "");
        this.password = context.get("password", "");
        this.sslEngineFactory = CassandraTls.engineFactory(context);
    }

    @Override
    public void open() {
        CqlSessionBuilder builder =
                CqlSession.builder().addContactPoints(contactPoints).withKeyspace(keyspace);
        if (!localDatacenter.isBlank()) {
            builder.withLocalDatacenter(localDatacenter);
        }
        if (!user.isBlank()) {
            builder.withAuthCredentials(user, password);
        }
        if (sslEngineFactory != null) {
            builder.withSslEngineFactory(sslEngineFactory);
        }
        try {
            this.session = builder.build();
        } catch (AllNodesFailedException e) {
            throw new PravahaException(
                    CassandraErrors.CONNECT_FAILED,
                    "source '" + instanceName + "' cannot reach Cassandra at " + contactPoints + ": "
                            + e.getMessage()
                            + ". Check the contact point list, that the cluster is up, and that "
                            + "local.datacenter names a real datacenter if the cluster has more than one.",
                    e);
        }
        PreparedStatement[] all = prepare(schema.fields().stream()
                .map(com.ash.messaging.pravaha.api.data.Field::name)
                .toList());
        this.greaterThan = all[0];
        this.greaterOrEqual = all[1];
    }

    /** The exclusive- and inclusive-lower range statements selecting {@code columns}. */
    private PreparedStatement[] prepare(List<String> columns) {
        String partitionKeyExpr = "token(" + String.join(",", partitionKeyColumns) + ")";
        String selectPrefix = "SELECT " + partitionKeyExpr + " AS " + TOKEN_ALIAS + ", " + String.join(",", columns)
                + " FROM " + keyspace + "." + table + " WHERE " + partitionKeyExpr;
        return new PreparedStatement[] {
            session.prepare(selectPrefix + " > ? AND " + partitionKeyExpr + " <= ?"),
            session.prepare(selectPrefix + " >= ? AND " + partitionKeyExpr + " <= ?")
        };
    }

    /**
     * The columns a pushed projection selects, in schema order, or null for all of them. The
     * event-time column is always kept, because the reader reads it for itself; the partition key
     * is not needed, since {@code token(...)} is computed server-side whatever is selected.
     */
    static List<String> projectedColumns(
            StreamSchema schema, com.ash.messaging.pravaha.api.plugin.ReadRequest request, String eventTimeColumn) {
        if (request == null || request.columns().isEmpty()) {
            return null;
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(request.columns());
        if (!eventTimeColumn.isBlank()) {
            wanted.add(eventTimeColumn);
        }
        for (String column : request.columns()) {
            if (!schema.hasField(column)) {
                return null;
            }
        }
        List<String> columns = schema.fields().stream()
                .map(com.ash.messaging.pravaha.api.data.Field::name)
                .filter(wanted::contains)
                .toList();
        return columns.size() >= schema.fieldCount() ? null : columns;
    }

    /**
     * What a full periodic scan can honestly promise.
     *
     * <p>Every field here is a consequence of scanning a store with no change feed, not of this
     * implementation -- see the class javadoc and {@link CassandraStrategy}. Claiming more would make
     * every query over this source claim it too.
     */
    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                // The offset is a token cursor within the assigned range, and resuming from it
                // re-reads exactly the unread remainder of the pass it was taken from.
                true,
                // A range scan visits partitions in token order, which is an artifact of the query
                // shape rather than a property the engine may rely on across passes -- a later pass
                // rereads the same rows without preserving any relationship to write order.
                false,
                // A tombstoned row is absent from the next scan, indistinguishable from one that
                // never existed. This is the strategy's defining limitation.
                false,
                false,
                DeliveryGuarantee.AT_LEAST_ONCE,
                // Projection only: the SELECT list is ours to choose, and a column not selected is
                // bytes Cassandra does not send. Not FILTER: a predicate on anything but the
                // partition key needs ALLOW FILTERING, which still reads every partition and has
                // its own null and collation rules to be exact about. Not PARTIAL_AGGREGATE: CQL
                // aggregates are per partition, and every pass here is a full re-read with no
                // retraction of the previous one, so no partial could be "the new rows only".
                EnumSet.of(com.ash.messaging.pravaha.api.plugin.PushdownKind.PROJECT),
                Duration.ofMillis(scanIntervalMillis));
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    /** Splits the token ring into {@code partitions} ranges, one reader per range. */
    @Override
    public List<SourcePartition> partitions(String stream) {
        long[] boundaries = TokenRanges.boundaries(partitions);
        return java.util.stream.IntStream.range(0, partitions)
                .mapToObj(index -> new SourcePartition(
                        stream,
                        index,
                        Map.of(
                                "lowerBound",
                                String.valueOf(boundaries[index]),
                                "upperBound",
                                String.valueOf(boundaries[index + 1]),
                                "inclusiveLower",
                                String.valueOf(index == 0))))
                .toList();
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
        if (session == null) {
            throw new PravahaException(
                    CassandraErrors.CONNECT_FAILED, "source '" + instanceName + "' was not opened before use");
        }
        long lowerBound = Long.parseLong(partition.properties().get("lowerBound"));
        long upperBound = Long.parseLong(partition.properties().get("upperBound"));
        boolean inclusiveLower = Boolean.parseBoolean(partition.properties().get("inclusiveLower"));
        List<String> columns = projectedColumns(schema, request, eventTimeColumn);
        PreparedStatement[] statements = columns == null
                ? new PreparedStatement[] {greaterThan, greaterOrEqual}
                : projected.computeIfAbsent(columns, this::prepare);
        boolean[] read = new boolean[schema.fieldCount()];
        for (int ordinal = 0; ordinal < read.length; ordinal++) {
            read[ordinal] =
                    columns == null || columns.contains(schema.field(ordinal).name());
        }
        return new TokenRangeScanReader(
                session,
                statements[0],
                statements[1],
                read,
                schema,
                eventTimeColumn,
                lowerBound,
                upperBound,
                inclusiveLower,
                fetchSize,
                consistencyLevel,
                Duration.ofMillis(requestTimeoutMillis),
                scanIntervalMillis,
                resumeFrom);
    }

    public StreamSchema schema() {
        return schema;
    }

    /** Which strategy this source is running, which determines everything it can promise. */
    public CassandraStrategy strategy() {
        return strategy;
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
