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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Aerospike as a source, by scanning what changed (design section 19.1).
 *
 * <p>Aerospike is the product's first-class target and the one store where the change-feed question
 * has an awkward answer: <strong>Community Edition has no change feed</strong>. Change propagation
 * is XDR, an Enterprise feature. This plugin therefore runs the one strategy that works everywhere
 * -- a partition-parallel scan with a server-side filter on {@code record.last_update_time()} -- and
 * declares exactly what that strategy can and cannot promise, so the engine's guarantee is derived
 * from the truth rather than from optimism.
 *
 * <p>What a scan cannot do, however carefully it is written:
 *
 * <ul>
 *   <li><strong>Deletes are invisible.</strong> A deleted record is simply absent from the next
 *       scan, which is indistinguishable from one that never existed. A maintained view over this
 *       source will keep serving deleted rows, and that is a property of the strategy.
 *   <li><strong>Intra-interval overwrites collapse.</strong> Two writes between scans are seen as
 *       one, with only the final value. For an aggregate that sums deltas this is wrong; for one
 *       that reads current state it is not.
 *   <li><strong>No before-image</strong>, so an update arrives as an insert of the new value with
 *       nothing to retract.
 * </ul>
 *
 * <p>The filter is applied <em>server-side</em>. That is the difference between reading a whole set
 * every interval and reading what changed in it, and on a production cluster it is the difference
 * between a viable strategy and one nobody would allow near their data.
 *
 * <p><strong>A containerised cluster needs host networking, not a port mapping.</strong> An
 * Aerospike client routes every operation by the cluster's own partition map, and a node inside a
 * bridged container advertises the address it has inside that container. Reads through a mapped port
 * therefore connect and then hang: the connection succeeds, the routing does not, and a scan waits
 * for data from an address that does not answer. Connecting to a single seed node does not fix it,
 * because the routing is still by partition. This cost most of an afternoon and is recorded here so
 * it costs nobody else one.
 *
 * <p>Configuration: {@code hosts} (required, {@code host:port,host:port}), {@code namespace}
 * (required), {@code set} (required), {@code schema} (required, {@code bin:TYPE,...}),
 * {@code strategy} (default {@code lut-scan}), {@code partitions} (default 1),
 * {@code records.per.second} (default 0, unthrottled), {@code user}, {@code password}.
 */
public final class AerospikeSourcePlugin implements StreamSourcePlugin {

    private String instanceName;
    private String namespace;
    private String set;
    private String streamName;
    private StreamSchema schema;

    /** The bin holding each row's own event time, or blank for the scan's time. See configure. */
    private String eventTimeColumn = "";

    private AerospikeStrategy strategy;
    private int partitions;
    private int recordsPerSecond;
    private int scanIntervalMillis;
    private int socketTimeoutMillis;
    private int totalTimeoutMillis;
    private Host[] hosts;
    private ClientPolicy clientPolicy;
    private IAerospikeClient client;

    @Override
    public String name() {
        return "aerospike";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.hosts = AerospikeHosts.parse(context.require("hosts"), AerospikeTls.tlsName(context));
        this.namespace = context.require("namespace");
        this.set = context.require("set");
        this.streamName = context.get("stream", set);
        StreamSchema parsed = AerospikeSchemas.parse(streamName, context.require("schema"));
        // Which bin holds the row's own event time, if the deployment names one.
        //
        // Without this the reader reported the scan's start time as every row's event time, and a
        // windowed query over an Aerospike source dropped every record as late: the watermark ran
        // at wall-clock while the window assigner read a column holding the record's real time, so
        // every window was already long closed by the time its rows arrived. The view stayed empty
        // and the query reported RUNNING -- the same shape of defect the filesystem plugin had, in
        // the connector this product leads with.
        this.eventTimeColumn = context.get("event.time", "");
        if (!eventTimeColumn.isBlank()) {
            if (!parsed.hasField(eventTimeColumn)) {
                throw new ConfigurationException(
                        AerospikeErrors.BAD_CONFIGURATION,
                        "event.time names '" + eventTimeColumn + "', which is not a column of stream '"
                                + streamName + "'. Its columns are "
                                + parsed.fields().stream()
                                        .map(com.ash.messaging.pravaha.api.data.Field::name)
                                        .toList());
            }
            StreamSchema.Builder builder = StreamSchema.builder(streamName);
            parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
            parsed = builder.eventTime(eventTimeColumn).build();
        }
        this.schema = parsed;
        this.strategy = AerospikeStrategy.parse(context.get("strategy", AerospikeStrategy.LUT_SCAN.configName()));
        this.partitions = Integer.parseInt(context.get("partitions", "1"));
        if (partitions < 1 || partitions > 4096) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "partitions must be between 1 and Aerospike's 4096, got " + partitions);
        }
        this.recordsPerSecond = Integer.parseInt(context.get("records.per.second", "0"));
        if (recordsPerSecond < 0) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "records.per.second must not be negative, got " + recordsPerSecond);
        }
        // The gap between one scan finishing and the next starting.
        //
        // There was none. LutScanReader starts a scan whenever poll() finds its buffer empty, and
        // the ingest pump polls about once a millisecond -- so scans ran back to back for as long as
        // a query was registered. Measured against a real Community node: one query took the cluster
        // from 1% to 200-310% CPU, and 43-153 scans a second of a set nobody was writing to.
        //
        // The class javadoc already described "the scan interval" as the source of this strategy's
        // latency. It was describing something the code did not have.
        //
        // One second by default, which is a real change in latency for anyone relying on the old
        // behaviour -- and the old behaviour was a hot loop against their database. Set it to 0 to
        // get it back deliberately.
        this.scanIntervalMillis = Integer.parseInt(context.get("scan.interval.ms", "1000"));
        if (scanIntervalMillis < 0) {
            throw new PravahaException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "scan.interval.ms must not be negative, got " + scanIntervalMillis);
        }
        this.socketTimeoutMillis = Integer.parseInt(context.get("scan.socket.timeout.ms", "30000"));
        this.totalTimeoutMillis = Integer.parseInt(context.get("scan.total.timeout.ms", "120000"));
        if (socketTimeoutMillis <= 0 || totalTimeoutMillis <= 0) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "scan timeouts must be positive; zero means wait for ever, and a scan that never returns "
                            + "takes its lane with it and looks exactly like a hung engine");
        }
        ClientPolicy policy = new ClientPolicy();
        String user = context.get("user", "");
        if (!user.isBlank()) {
            policy.user = user;
            policy.password = context.get("password", "");
        }
        policy.timeout = (int) Duration.ofSeconds(10).toMillis();
        policy.failIfNotConnected = true;
        AerospikeTls.apply(context, policy);
        this.clientPolicy = policy;
    }

    @Override
    public void open() {
        this.client = AerospikeClients.connect(clientPolicy, hosts, instanceName);
    }

    /**
     * What a scan can honestly promise.
     *
     * <p>Every one of these is a consequence of scanning rather than a limitation of the code, and
     * the engine needs them to compute the guarantee it offers downstream. Claiming exactly-once
     * here would make every query over Aerospike Community claim it too.
     */
    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                // The offset is a last-update-time watermark, and re-reading from it is exact --
                // it just re-delivers the boundary records, which is what at-least-once means.
                true,
                // A scan visits partitions in whatever order the cluster answers. Within one
                // partition the order is not guaranteed either, so no ordering is claimed.
                false,
                // A deleted record is absent from the next scan, which is the same as never having
                // existed. This is the strategy's defining limitation.
                false,
                false,
                DeliveryGuarantee.AT_LEAST_ONCE,
                // The scan filter is a server-side expression, which is where most of the value of
                // this plugin is: bytes not sent beat bytes filtered. A scan can also name the bins
                // it wants, so a projection is pushed too.
                //
                // Never PARTIAL_AGGREGATE. Aerospike aggregates server-side only through Lua stream
                // UDFs, which have to be registered on the cluster -- a deployment step this plugin
                // cannot take for an operator -- and a last-update-time scan sees an overwritten
                // record as a new row with no retraction of the old one, so a partial over a scan
                // would be exactly as wrong as the rows are, while costing a UDF to be so.
                EnumSet.of(PushdownKind.FILTER, PushdownKind.PROJECT),
                Duration.ofSeconds(1));
    }

    @Override
    public List<StreamSchema> discoverSchemas() {
        return List.of(schema);
    }

    /**
     * Splits the key space into ranges of Aerospike's 4096 partitions.
     *
     * <p>Partition ranges rather than key ranges: they are the unit Aerospike itself scans by, they
     * are stable, and a reader resuming on one is reading exactly what it was reading before. Any
     * other split has to be reconciled with the cluster's own idea of where data lives.
     */
    @Override
    public List<SourcePartition> partitions(String stream) {
        int perReader = 4096 / partitions;
        return java.util.stream.IntStream.range(0, partitions)
                .mapToObj(index -> new SourcePartition(
                        stream,
                        index,
                        Map.of(
                                "namespace",
                                namespace,
                                "set",
                                set,
                                "firstPartition",
                                String.valueOf(index * perReader),
                                "partitionCount",
                                String.valueOf(index == partitions - 1 ? 4096 - index * perReader : perReader))))
                .toList();
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        return createReader(partition, resumeFrom, ReadRequest.NOTHING);
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom, ReadRequest request) {
        if (client == null) {
            throw new PravahaException(
                    AerospikeErrors.CONNECT_FAILED, "source '" + instanceName + "' was not opened before use");
        }
        return new LutScanReader(
                client,
                namespace,
                set,
                schema,
                Integer.parseInt(partition.properties().getOrDefault("firstPartition", "0")),
                Integer.parseInt(partition.properties().getOrDefault("partitionCount", "4096")),
                recordsPerSecond,
                scanIntervalMillis,
                socketTimeoutMillis,
                totalTimeoutMillis,
                resumeFrom,
                request);
    }

    /** Which strategy this source is running, which determines everything it can promise. */
    public AerospikeStrategy strategy() {
        return strategy;
    }

    public StreamSchema schema() {
        return schema;
    }

    @Override
    public void close() {
        if (client != null) {
            // Released, not closed. The client is shared with every other plugin instance on the
            // same cluster and credential, and the last release is what actually closes it.
            AerospikeClients.release(client);
            client = null;
        }
    }
}
