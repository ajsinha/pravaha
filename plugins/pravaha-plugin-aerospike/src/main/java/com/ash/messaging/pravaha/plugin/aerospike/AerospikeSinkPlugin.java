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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Value;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * Writing a maintained result back into Aerospike (design sections 19.1 and 14.4).
 *
 * <p>Upserts keyed by the result's own key, which is what makes this sink idempotent and therefore
 * what makes end-to-end output effectively-once without two-phase commit. Replaying a checkpoint
 * rewrites the same key with the same value; the end state is the same whether the write happened
 * once or three times. That is the whole reason the engine can offer a useful guarantee over a store
 * with no transactions across records.
 *
 * <p>A retraction is a delete, because in a keyed view it is one. A row arriving with weight
 * {@code -1} is the query withdrawing it, and leaving it in the set would serve a value the query
 * has already said is wrong.
 *
 * <p>TTL comes from configuration and is written per record, so a windowed result can expire itself
 * rather than needing a cleanup job -- the sink that needs a cron to stay correct is the sink
 * somebody eventually forgets to run.
 *
 * <p>Configuration: {@code hosts}, {@code namespace}, {@code set}, {@code schema}, {@code key.bin},
 * {@code ttl.seconds} (default 0, meaning the namespace default), {@code user}, {@code password}.
 */
public final class AerospikeSinkPlugin implements StreamSinkPlugin {

    private String instanceName;
    private String namespace;
    private String set;
    private String keyBin;
    private int keyOrdinal;
    private StreamSchema schema;
    private int ttlSeconds;
    private Host[] hosts;
    private ClientPolicy clientPolicy;
    private IAerospikeClient client;
    private long written;
    private long deleted;

    @Override
    public String name() {
        return "aerospike-sink";
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
        this.schema = AerospikeSchemas.parse(context.get("stream", set), context.require("schema"));
        this.keyBin = context.require("key.bin");
        this.keyOrdinal = -1;
        for (int i = 0; i < schema.fields().size(); i++) {
            if (schema.field(i).name().equals(keyBin)) {
                keyOrdinal = i;
            }
        }
        if (keyOrdinal < 0) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "key.bin '" + keyBin + "' is not in the declared schema, which has "
                            + schema.fields().stream().map(f -> f.name()).toList()
                            + ". Without a key there is nothing to make the write idempotent, and the sink "
                            + "would append duplicates on every replay.");
        }
        this.ttlSeconds = Integer.parseInt(context.get("ttl.seconds", "0"));
        if (ttlSeconds < 0) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION, "ttl.seconds must not be negative, got " + ttlSeconds);
        }
        ClientPolicy policy = new ClientPolicy();
        String user = context.get("user", "");
        if (!user.isBlank()) {
            policy.user = user;
            policy.password = context.get("password", "");
        }
        policy.failIfNotConnected = true;
        AerospikeTls.apply(context, policy);
        this.clientPolicy = policy;
    }

    @Override
    public void open() {
        this.client = AerospikeClients.connect(clientPolicy, hosts, instanceName);
    }

    /**
     * Upsert, and honest about what that buys.
     *
     * <p>Not transactional: Aerospike has no cross-record transaction this sink could use, and
     * claiming one would make the engine promise exactly-once output it cannot deliver. Idempotent,
     * though, which the engine turns into effectively-once -- the end state after a replay is the
     * state the query computed.
     */
    @Override
    public SinkCapabilities capabilities() {
        return new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 512);
    }

    @Override
    public int write(List<RowView> batch) {
        List<RowView> rows = new ArrayList<>(batch);
        int count = 0;
        for (RowView row : rows) {
            if (row.weight() < 0) {
                delete(row);
            } else {
                upsert(row);
            }
            count++;
        }
        return count;
    }

    private void upsert(RowView row) {
        WritePolicy policy = new WritePolicy();
        policy.recordExistsAction = RecordExistsAction.REPLACE;
        if (ttlSeconds > 0) {
            policy.expiration = ttlSeconds;
        }
        List<Bin> bins = new ArrayList<>(schema.fields().size());
        for (int ordinal = 0; ordinal < schema.fields().size(); ordinal++) {
            if (ordinal == keyOrdinal) {
                // The primary key is the record's identity, not a bin. Writing it as one too is a
                // duplicate that costs space on every record.
                continue;
            }
            bins.add(binOf(row, ordinal));
        }
        try {
            client.put(policy, keyOf(row), bins.toArray(new Bin[0]));
            written++;
        } catch (AerospikeException e) {
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "write to " + namespace + "." + set + " failed: " + e.getMessage(),
                    e);
        }
    }

    private void delete(RowView row) {
        try {
            client.delete(new WritePolicy(), keyOf(row));
            deleted++;
        } catch (AerospikeException e) {
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "delete from " + namespace + "." + set + " failed: " + e.getMessage(),
                    e);
        }
    }

    private Bin binOf(RowView row, int ordinal) {
        String name = schema.field(ordinal).name();
        if (row.isNull(ordinal)) {
            // Aerospike deletes a bin written as null, which is exactly right: an absent bin is how
            // this store spells "no value", and writing a zero would invent one.
            return Bin.asNull(name);
        }
        return switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> new Bin(name, row.getBoolean(ordinal));
            case INT8 -> new Bin(name, row.getByte(ordinal));
            case INT16 -> new Bin(name, row.getShort(ordinal));
            case INT32, DATE -> new Bin(name, row.getInt(ordinal));
            case INT64, TIME, TIMESTAMP_LTZ -> new Bin(name, row.getLong(ordinal));
            case FLOAT32 -> new Bin(name, row.getFloat(ordinal));
            case FLOAT64 -> new Bin(name, row.getDouble(ordinal));
            case BYTES -> new Bin(name, Value.get(bytesOf(row, ordinal)));
            default -> new Bin(name, row.getString(ordinal));
        };
    }

    /**
     * Copies a blob bin out of the row.
     *
     * <p>A slice is an offset and a length into the row's own memory, which the generic row
     * interface deliberately does not expose -- that is what keeps a row a flyweight. So this needs
     * the binary view, which every row on the engine's own path is; anything else is a plan built by
     * something that does not exist yet, and it says so rather than reading arbitrary memory.
     */
    private static byte[] bytesOf(RowView row, int ordinal) {
        if (!(row instanceof com.ash.messaging.pravaha.common.row.BinaryRowView binary)) {
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "cannot read a blob bin from a " + row.getClass().getSimpleName()
                            + "; this sink writes the engine's binary rows");
        }
        com.ash.messaging.pravaha.api.data.MutableSlice slice =
                binary.getBytes(ordinal, new com.ash.messaging.pravaha.api.data.MutableSlice());
        byte[] copy = new byte[slice.length()];
        binary.region().getBytes(slice.offset(), copy, 0, slice.length());
        return copy;
    }

    private Key keyOf(RowView row) {
        return switch (schema.field(keyOrdinal).type().typeName()) {
            case INT8, INT16, INT32, INT64, DATE, TIME, TIMESTAMP_LTZ ->
                new Key(namespace, set, row.getLong(keyOrdinal));
            default -> new Key(namespace, set, row.getString(keyOrdinal));
        };
    }

    /** Nothing is buffered, so there is nothing to flush; the writes were synchronous. */
    @Override
    public void flush() {}

    public long recordsWritten() {
        return written;
    }

    public long recordsDeleted() {
        return deleted;
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
