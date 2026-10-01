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
 * connector, as {@code docs/guides/CONNECTORS.md} section 5 explains. This plugin is the connector: a full,
 * periodic, partition-parallel scan of a table, paged by {@code token()} so no partition is read
 * through {@code ALLOW FILTERING}.
 *
 * <p><strong>A filter on the key is pushed</strong> ({@link CassandraPushdown}): equality on the whole
 * partition key, or an OR of such keys, reads those partitions instead of the range
 * ({@link KeyedScanReader}), and restrictions on the clustering columns in their declared order --
 * equality down a prefix, then a range -- slice them. Neither needs {@code ALLOW FILTERING}. Any
 * other filter is left with the engine, and the query's feed description says which it was.
 *
 * <p>{@link CassandraStrategy} names two other strategies and implements neither.
 * {@code writetime-incremental} looks like the natural next step -- filter each scan on {@code
 * writetime()} the way the Aerospike plugin filters on {@code record.last_update_time()} -- and CQL
 * makes that dishonest rather than merely unimplemented: there is no index on {@code writetime()}, so
 * the filter cannot run server-side without {@code ALLOW FILTERING}, which reads every partition
 * anyway and buys none of the bandwidth an incremental scan exists for; and {@code writetime()} is
 * per-<em>column</em>, so a key-only write or a write to a column outside the tracked set moves no
 * watermark at all and would be missed in silence. A full scan that says what it is beats an
 * incremental one that quietly drops rows, which is the choice {@code docs/guides/CONNECTORS.md} section 6
 * asks every connector to make explicitly. {@code commitlog-cdc} is the different, unbuilt project
 * above.
 *
 * <p>What a scan cannot do, however carefully it is written:
 *
 * <ul>
 *   <li><strong>Deletes are invisible</strong> to a plain pass. A tombstoned row is simply absent
 *       from the next scan, indistinguishable from one that never existed -- unless {@code deletes:
 *       detect} is set, which compares each pass with the rows already emitted and retracts what is
 *       missing ({@link DetectingTokenRangeReader}).
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
 * {@code user} / {@code password}, {@code deletes} (default {@code ignore}; {@code detect}),
 * {@code deletes.state.dir} (required with {@code detect}), {@code deletes.max.keys} (default
 * 1,000,000 per token range).
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

    /** {@code deletes: detect}; see {@link #configureDeletes}. */
    private boolean detectDeletes;

    private java.nio.file.Path deletesStateDir;
    private long deletesMaxKeys;

    private CqlSession session;
    private PreparedStatement greaterThan;
    private PreparedStatement greaterOrEqual;

    /**
     * The two range statements per projected column list, prepared once each. Keyed by the
     * columns in schema order, so every reader with the same projection shares one pair.
     */
    private final java.util.Map<List<String>, PreparedStatement[]> projected =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A partition read's statement, prepared once per CQL text: its columns and its restrictions. */
    private final java.util.Map<String, PreparedStatement> keyed = new java.util.concurrent.ConcurrentHashMap<>();

    /** The table's clustering columns, in CQL order, named as the stream names them. */
    private List<String> clusteringColumns = List.of();

    /** The key columns a restriction may be pushed on, with their CQL types; empty when none may. */
    private Map<String, CassandraPushdown.ColumnType> pushable = Map.of();

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
        configureDeletes(context);
    }

    /**
     * {@code deletes}: {@code ignore} (the default, and the behaviour before the option existed) or
     * {@code detect}. With {@code detect}, {@code deletes.state.dir} is required -- the remembered
     * rows must survive a restart as exactly as the checkpoint does -- and {@code deletes.max.keys}
     * (default 1,000,000 per token range) bounds how many rows one range may remember.
     */
    private void configureDeletes(PluginContext context) {
        String mode = context.get("deletes", "ignore").strip().toLowerCase(java.util.Locale.ROOT);
        switch (mode) {
            case "ignore" -> this.detectDeletes = false;
            case "detect" -> this.detectDeletes = true;
            default ->
                throw new ConfigurationException(
                        CassandraErrors.BAD_CONFIGURATION, "deletes must be 'ignore' or 'detect', got '" + mode + "'");
        }
        if (!detectDeletes) {
            return;
        }
        String dir = context.get("deletes.state.dir", "").strip();
        if (dir.isEmpty()) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION,
                    "deletes: detect needs deletes.state.dir: a directory on durable local disk where each reader "
                            + "keeps the rows it has emitted. A restore from a checkpoint reads them back; without "
                            + "them the rows the restored view holds are unknown and no pass could retract them.");
        }
        this.deletesStateDir = java.nio.file.Path.of(dir).resolve(instanceName);
        String max = context.get("deletes.max.keys", "1000000").strip();
        try {
            this.deletesMaxKeys = Long.parseLong(max);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION, "deletes.max.keys must be a number, got '" + max + "'");
        }
        if (deletesMaxKeys < 1) {
            throw new ConfigurationException(
                    CassandraErrors.BAD_CONFIGURATION, "deletes.max.keys must be positive, got " + deletesMaxKeys);
        }
    }

    /** Whether this source runs {@code deletes: detect}. */
    public boolean detectsDeletes() {
        return detectDeletes;
    }

    @Override
    public void open() {
        if (detectDeletes) {
            try {
                java.nio.file.Files.createDirectories(deletesStateDir);
            } catch (java.io.IOException e) {
                throw new PravahaException(
                        CassandraErrors.DELETE_STATE_FAILED,
                        "source '" + instanceName + "' cannot create deletes.state.dir " + deletesStateDir + ": " + e,
                        e);
            }
        }
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
        readKeyMetadata();
    }

    /**
     * Learns the table's clustering columns and the CQL types of its key columns from the driver's
     * schema metadata, which is what a pushed restriction is built from. Without the metadata --
     * schema metadata turned off in the driver -- nothing is pushed, as before.
     */
    private void readKeyMetadata() {
        java.util.Optional<com.datastax.oss.driver.api.core.metadata.schema.TableMetadata> found =
                session.getMetadata().getKeyspace(keyspace).flatMap(space -> space.getTable(table));
        if (found.isEmpty()) {
            return;
        }
        com.datastax.oss.driver.api.core.metadata.schema.TableMetadata metadata = found.get();
        Map<String, CassandraPushdown.ColumnType> types = new java.util.HashMap<>();
        List<String> clustering = new java.util.ArrayList<>();
        List<com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata> keyColumns =
                new java.util.ArrayList<>(metadata.getPartitionKey());
        keyColumns.addAll(metadata.getClusteringColumns().keySet());
        for (com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata column : keyColumns) {
            String name = streamNameOf(column.getName().asInternal());
            boolean isClustering = metadata.getClusteringColumns().containsKey(column);
            if (isClustering) {
                // A clustering column the stream does not declare can be restricted by no filter, so the
                // restrictions stop at it; its CQL name keeps its place in the order.
                clustering.add(name == null ? column.getName().asInternal() : name);
            }
            CassandraPushdown.ColumnType type = pushdownType(column.getType());
            if (name != null && type != null) {
                types.put(name, type);
            }
        }
        this.clusteringColumns = List.copyOf(clustering);
        this.pushable = Map.copyOf(types);
    }

    /** The stream's name for a CQL column, matched as CQL matches an unquoted name, or null. */
    private String streamNameOf(String internal) {
        for (com.ash.messaging.pravaha.api.data.Field field : schema.fields()) {
            if (field.name().equalsIgnoreCase(internal)) {
                return field.name();
            }
        }
        return null;
    }

    static CassandraPushdown.ColumnType pushdownType(com.datastax.oss.driver.api.core.type.DataType type) {
        if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.TINYINT)) {
            return CassandraPushdown.ColumnType.TINYINT;
        } else if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.SMALLINT)) {
            return CassandraPushdown.ColumnType.SMALLINT;
        } else if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.INT)) {
            return CassandraPushdown.ColumnType.INT;
        } else if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.BIGINT)) {
            return CassandraPushdown.ColumnType.BIGINT;
        } else if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.TIMESTAMP)) {
            return CassandraPushdown.ColumnType.TIMESTAMP;
        } else if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.TEXT)) {
            return CassandraPushdown.ColumnType.TEXT;
        } else if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.ASCII)) {
            return CassandraPushdown.ColumnType.ASCII;
        } else if (type.equals(com.datastax.oss.driver.api.core.type.DataTypes.BOOLEAN)) {
            return CassandraPushdown.ColumnType.BOOLEAN;
        }
        return null;
    }

    /**
     * What {@code request} is pushed to Cassandra as. {@code deletes: detect} compares whole passes
     * in token order, so it takes a pass of one partition at most; it is never shared, so it is never
     * asked for an OR of keys anyway.
     */
    CassandraPushdown.Plan pushdownFor(com.ash.messaging.pravaha.api.plugin.ReadRequest request) {
        return CassandraPushdown.plan(
                request,
                partitionKeyColumns,
                clusteringColumns,
                pushable,
                detectDeletes ? 1 : CassandraPushdown.MAX_READS);
    }

    @Override
    public String describePushdown(com.ash.messaging.pravaha.api.plugin.ReadRequest request) {
        return pushdownFor(request).description();
    }

    /** Runs one partition read selecting {@code columns}, bound and paged as a range scan is. */
    private java.util.Iterator<com.datastax.oss.driver.api.core.cql.Row> run(
            List<String> columns, CassandraPushdown.KeyRead read) {
        String partitionKeyExpr = "token(" + String.join(",", partitionKeyColumns) + ")";
        String cql = "SELECT " + partitionKeyExpr + " AS " + TOKEN_ALIAS + ", " + String.join(",", columns) + " FROM "
                + keyspace + "." + table + read.where();
        PreparedStatement statement = keyed.computeIfAbsent(cql, session::prepare);
        return session.execute(statement
                        .boundStatementBuilder(read.values())
                        .setPageSize(fetchSize)
                        .setConsistencyLevel(consistencyLevel)
                        .setTimeout(Duration.ofMillis(requestTimeoutMillis))
                        .build())
                .iterator();
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
        if (detectDeletes) {
            // deletes: detect turns the passes into a changelog (DetectingTokenRangeReader): a gone
            // row is retracted as the whole row this source emitted for it, a changed one is that
            // row at -1 then the new one at +1 -- a full before-image, as of the previous pass --
            // and an unchanged one is not emitted again. A resumed reader holds exactly the rows
            // the restored view was built from (EmittedRows), so after its first pass every row of
            // the table is counted once: exactly-once, which also keeps the engine from sharing a
            // reader whose rows are relative to its own emitted state. Still PROJECT only, for the
            // reasons below.
            return new SourceCapabilities(
                    true,
                    false,
                    true,
                    true,
                    DeliveryGuarantee.EXACTLY_ONCE,
                    EnumSet.of(
                            com.ash.messaging.pravaha.api.plugin.PushdownKind.PROJECT,
                            com.ash.messaging.pravaha.api.plugin.PushdownKind.FILTER),
                    Duration.ofMillis(scanIntervalMillis),
                    // An exact changelog: an unchanged row is not emitted again (SCAN-1).
                    false);
        }
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
                // Projection: the SELECT list is ours to choose, and a column not selected is bytes
                // Cassandra does not send. Filter, on the key only (CassandraPushdown): the whole
                // partition key by equality, then clustering restrictions; a predicate on anything
                // else needs ALLOW FILTERING, which still reads every partition, and is left with
                // the engine. Not PARTIAL_AGGREGATE: CQL aggregates are per partition, and every
                // pass here is a full re-read with no retraction of the previous one, so no partial
                // could be "the new rows only".
                EnumSet.of(
                        com.ash.messaging.pravaha.api.plugin.PushdownKind.PROJECT,
                        com.ash.messaging.pravaha.api.plugin.PushdownKind.FILTER),
                Duration.ofMillis(scanIntervalMillis),
                // Every pass emits every row of the range again at +1, changed or not (SCAN-1). A
                // keyed view of the rows survives that -- each copy overwrites its own key -- and an
                // aggregate, a join or an append-only sink does not, so the registry refuses those
                // with PRV-2042 and names deletes: detect as the fix.
                true);
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
        List<String> selected = columns != null
                ? columns
                : schema.fields().stream()
                        .map(com.ash.messaging.pravaha.api.data.Field::name)
                        .toList();
        CassandraPushdown.Plan plan = pushdownFor(request);
        String range = (inclusiveLower ? "[" : "(") + lowerBound + "," + upperBound + "]";
        if (detectDeletes) {
            PreparedStatement fullPass = inclusiveLower ? statements[1] : statements[0];
            java.time.Duration timeout = Duration.ofMillis(requestTimeoutMillis);
            DetectingTokenRangeReader.PassSource passes = plan.pushed()
                    // One partition, one token: a pass of it is in token order by construction.
                    ? () -> KeyedScanReader.inRange(
                            plan.reads(), each -> run(selected, each), lowerBound, upperBound, inclusiveLower, range)
                    : () -> session.execute(fullPass.boundStatementBuilder(lowerBound, upperBound)
                                    .setPageSize(fetchSize)
                                    .setConsistencyLevel(consistencyLevel)
                                    .setTimeout(timeout)
                                    .build())
                            .iterator();
            return new DetectingTokenRangeReader(
                    passes,
                    read,
                    schema,
                    eventTimeColumn,
                    range,
                    fetchSize,
                    scanIntervalMillis,
                    resumeFrom,
                    deletesStateDir,
                    deletesMaxKeys);
        }
        if (plan.pushed()) {
            return new KeyedScanReader(
                    plan.reads(),
                    each -> run(selected, each),
                    read,
                    schema,
                    eventTimeColumn,
                    lowerBound,
                    upperBound,
                    inclusiveLower,
                    scanIntervalMillis,
                    resumeFrom);
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
