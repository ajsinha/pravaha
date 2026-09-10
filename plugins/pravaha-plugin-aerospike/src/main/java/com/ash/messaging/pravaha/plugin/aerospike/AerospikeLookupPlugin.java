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
import java.util.List;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.Policy;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Aerospike as a dimension table (design section 19.1, FR-8).
 *
 * <p>This is Aerospike playing to its strength rather than around its weakness. The change-feed
 * story on Community Edition is awkward; the point-read story is the reason people run Aerospike at
 * all -- a primary-key get is sub-millisecond and the cluster is built for nothing else. A stream
 * enriched from an Aerospike set is the shape this engine and this store were both designed for.
 *
 * <p>The key is the record's primary key, so there is exactly one key column and it is not
 * negotiable: Aerospike has no secondary lookup that is a point read, and a query joining on a
 * non-key bin would be asking for a scan per record. That is refused at registration rather than
 * discovered from a latency graph.
 *
 * <p>The client is thread-safe and the engine calls this from several threads at once, which suits
 * it: the Aerospike client multiplexes over a connection pool of its own and is designed for
 * exactly that. No pool of our own is needed, and adding one would fight the client's.
 *
 * <p>Configuration: {@code hosts}, {@code namespace}, {@code set}, {@code schema}, {@code key.bin}
 * (the schema column carrying the primary key), {@code cache.seconds} (default 0),
 * {@code concurrency} (default 16), {@code user}, {@code password}.
 */
public final class AerospikeLookupPlugin implements LookupSourcePlugin {

    private String instanceName;
    private String namespace;
    private String set;
    private String keyBin;
    private StreamSchema schema;
    private Duration cacheFor;
    private int concurrency;
    private Host[] hosts;
    private ClientPolicy clientPolicy;
    private IAerospikeClient client;

    @Override
    public String name() {
        return "aerospike-lookup";
    }

    @Override
    public Version version() {
        return new Version(1, 0, 0);
    }

    @Override
    public void configure(PluginContext context) {
        this.instanceName = context.instanceName();
        this.hosts = AerospikeHosts.parse(context.require("hosts"));
        this.namespace = context.require("namespace");
        this.set = context.require("set");
        this.schema = AerospikeSchemas.parse(context.get("stream", set), context.require("schema"));
        this.keyBin = context.require("key.bin");
        if (schema.fields().stream().noneMatch(field -> field.name().equals(keyBin))) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "key.bin '" + keyBin + "' is not in the declared schema, which has "
                            + schema.fields().stream().map(f -> f.name()).toList());
        }
        long seconds = Long.parseLong(context.get("cache.seconds", "0"));
        if (seconds < 0) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION, "cache.seconds must not be negative, got " + seconds);
        }
        this.cacheFor = Duration.ofSeconds(seconds);
        this.concurrency = Integer.parseInt(context.get("concurrency", "16"));
        if (concurrency < 1) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION, "concurrency must be at least 1, got " + concurrency);
        }
        ClientPolicy policy = new ClientPolicy();
        String user = context.get("user", "");
        if (!user.isBlank()) {
            policy.user = user;
            policy.password = context.get("password", "");
        }
        policy.failIfNotConnected = true;
        this.clientPolicy = policy;
    }

    @Override
    public void open() {
        this.client = AerospikeClients.connect(clientPolicy, hosts, instanceName);
    }

    @Override
    public StreamSchema schema() {
        return schema;
    }

    @Override
    public List<String> keyColumns() {
        return List.of(keyBin);
    }

    @Override
    public int lookup(Object[] key, PartitionReader.RecordSink sink) {
        if (key.length != 1) {
            throw new IllegalArgumentException(
                    "an Aerospike lookup takes one key value -- the record's primary key -- and was given "
                            + key.length);
        }
        if (key[0] == null) {
            // No record has a null primary key, so this is a round trip that can only return
            // nothing. Under a stream of null keys that is one per record.
            return 0;
        }

        Key recordKey = keyFor(key[0]);
        try {
            Record record = client.get(new Policy(), recordKey);
            if (record == null) {
                return 0;
            }
            RowWriter writer = sink.beginRow();
            AerospikeSchemas.copyInto(record, schema, writer);
            // The key bin is the record's primary key, which Aerospike does not store as a bin
            // unless it was written as one. Filling it from the key we asked with means the joined
            // row carries the value the query joined on rather than a null.
            fillKeyBin(writer, key[0]);
            writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
            return 1;
        } catch (AerospikeException e) {
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "lookup in " + namespace + "." + set + " failed: " + e.getMessage(),
                    e);
        }
    }

    private void fillKeyBin(RowWriter writer, Object value) {
        int ordinal = 0;
        for (int i = 0; i < schema.fields().size(); i++) {
            if (schema.field(i).name().equals(keyBin)) {
                ordinal = i;
                break;
            }
        }
        switch (schema.field(ordinal).type().typeName()) {
            case INT8, INT16, INT32, INT64, DATE, TIME, TIMESTAMP_LTZ ->
                writer.setLong(ordinal, ((Number) value).longValue());
            default -> writer.setString(ordinal, String.valueOf(value));
        }
    }

    private Key keyFor(Object value) {
        if (value instanceof Number number) {
            return new Key(namespace, set, number.longValue());
        }
        if (value instanceof byte[] bytes) {
            return new Key(namespace, set, bytes);
        }
        return new Key(namespace, set, String.valueOf(value));
    }

    @Override
    public Duration cacheFor() {
        return cacheFor;
    }

    @Override
    public int maxConcurrency() {
        return concurrency;
    }

    /**
     * A primary-key read, which is what Aerospike is for.
     *
     * <p>Reported as under a millisecond rather than the conservative default: this is the one store
     * in the design where a point read is genuinely that fast, and a planner told otherwise would
     * decline a lookup join that is in fact the right plan.
     */
    @Override
    public Duration typicalLatency() {
        return Duration.ofNanos(500_000);
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (AerospikeException e) {
                // A cluster that has already gone is not a shutdown failure.
            }
            client = null;
        }
    }
}
