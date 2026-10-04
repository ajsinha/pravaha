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
 * <p><strong>The record key is the view's key.</strong> {@code key.bins} names the columns, which a
 * registration must use as its {@code --keys} (the registry refuses a mismatch, since a retraction
 * deletes the record its key names). One column becomes the record key as itself -- an integer or a
 * string, readable by any client -- and is not also written as a bin. Several become one blob key, an
 * injective encoding of the typed values, and each key column is written as a bin as well, because a
 * blob key cannot be read back into its parts.
 *
 * <p>Configuration: {@code hosts}, {@code namespace}, {@code set}, {@code schema}, {@code key.bins}
 * (or {@code key.bin} for one column), {@code ttl.seconds} (default 0, meaning the namespace
 * default), {@code user}, {@code password}, and the {@code tls.*} options.
 */
public final class AerospikeSinkPlugin implements StreamSinkPlugin {

    private String instanceName;
    private String namespace;
    private String set;
    private List<String> keyBins;
    private int[] keyOrdinals;
    private StreamSchema schema;
    private int ttlSeconds;
    private Host[] hosts;
    private ClientPolicy clientPolicy;
    private IAerospikeClient client;
    private long written;
    private long deleted;

    /** How a client is obtained and given back; a seam so the write path is testable without a server. */
    interface Connector {
        IAerospikeClient connect(ClientPolicy policy, Host[] hosts, String instanceName);

        void release(IAerospikeClient client);
    }

    private static final Connector SHARED = new Connector() {
        @Override
        public IAerospikeClient connect(ClientPolicy policy, Host[] hosts, String instanceName) {
            return AerospikeClients.connect(policy, hosts, instanceName);
        }

        @Override
        public void release(IAerospikeClient client) {
            AerospikeClients.release(client);
        }
    };

    private final Connector connector;

    /** What {@code ServiceLoader} constructs: clients shared per cluster and credential. */
    public AerospikeSinkPlugin() {
        this(SHARED);
    }

    AerospikeSinkPlugin(Connector connector) {
        this.connector = connector;
    }

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
        String single = context.get("key.bin", "").strip();
        String several = context.get("key.bins", "").strip();
        if (!single.isEmpty() && !several.isEmpty()) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "both key.bin and key.bins are set; name the key once, in key.bins");
        }
        String declared = several.isEmpty() ? single : several;
        if (declared.isEmpty()) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "key.bins is required: without a key there is nothing to make the write idempotent, and "
                            + "the sink would append duplicates on every replay");
        }
        List<String> names = new ArrayList<>();
        for (String part : declared.split(",", -1)) {
            if (!part.isBlank()) {
                names.add(part.strip());
            }
        }
        this.keyBins = List.copyOf(names);
        this.keyOrdinals = new int[keyBins.size()];
        for (int k = 0; k < keyBins.size(); k++) {
            keyOrdinals[k] = -1;
            for (int i = 0; i < schema.fields().size(); i++) {
                if (schema.field(i).name().equals(keyBins.get(k))) {
                    keyOrdinals[k] = i;
                }
            }
            if (keyOrdinals[k] < 0) {
                throw new ConfigurationException(
                        AerospikeErrors.BAD_CONFIGURATION,
                        "key bin '" + keyBins.get(k) + "' is not in the declared schema, which has "
                                + schema.fields().stream().map(f -> f.name()).toList());
            }
            switch (schema.field(keyOrdinals[k]).type().typeName()) {
                case INT8, INT16, INT32, INT64, DATE, TIME, TIMESTAMP_LTZ, STRING, BYTES -> {}
                default ->
                    throw new ConfigurationException(
                            AerospikeErrors.BAD_CONFIGURATION,
                            "key bin '" + keyBins.get(k) + "' is a "
                                    + schema.field(keyOrdinals[k]).type().typeName()
                                    + "; an Aerospike key is an integer, a string or bytes, and a floating-point "
                                    + "or boolean key would make two values that compare equal two records");
            }
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
        this.client = connector.connect(clientPolicy, hosts, instanceName);
    }

    @Override
    public java.util.Optional<StreamSchema> schema() {
        return java.util.Optional.ofNullable(schema);
    }

    @Override
    public List<String> keyColumns() {
        return keyBins == null ? List.of() : keyBins;
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
            if (keyOrdinals.length == 1 && ordinal == keyOrdinals[0]) {
                // A single-column key is the record's identity and readable as itself, not a bin.
                // Writing it as one too is a duplicate that costs space on every record. A composite
                // key is a blob nobody can read back into parts, so its columns stay as bins.
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

    /**
     * The record's key, from the row's key columns, read with each column's own width.
     *
     * <p>Every integer used to be read with {@code getLong}, which for an {@code INT32} or {@code
     * DATE} column reads eight bytes where four were written -- the key then depended on whatever
     * followed the column in the row.
     */
    Key keyOf(RowView row) {
        if (keyOrdinals.length == 1) {
            int ordinal = keyOrdinals[0];
            requireKeyPresent(row, ordinal);
            return switch (schema.field(ordinal).type().typeName()) {
                case STRING -> new Key(namespace, set, row.getString(ordinal));
                case BYTES -> new Key(namespace, set, bytesOf(row, ordinal));
                default -> new Key(namespace, set, integerOf(row, ordinal));
            };
        }
        // Several columns, one blob: each value tagged with its type and length-prefixed, so the
        // encoding is injective -- ("ab","c") and ("a","bc") are two keys, as are 1 and "1".
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        try {
            for (int ordinal : keyOrdinals) {
                requireKeyPresent(row, ordinal);
                switch (schema.field(ordinal).type().typeName()) {
                    case STRING -> {
                        byte[] text = row.getString(ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.writeByte('s');
                        out.writeInt(text.length);
                        out.write(text);
                    }
                    case BYTES -> {
                        byte[] raw = bytesOf(row, ordinal);
                        out.writeByte('b');
                        out.writeInt(raw.length);
                        out.write(raw);
                    }
                    default -> {
                        out.writeByte('i');
                        out.writeLong(integerOf(row, ordinal));
                    }
                }
            }
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("writing to memory failed", impossible);
        }
        return new Key(namespace, set, bytes.toByteArray());
    }

    private long integerOf(RowView row, int ordinal) {
        return switch (schema.field(ordinal).type().typeName()) {
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            default -> row.getLong(ordinal);
        };
    }

    private void requireKeyPresent(RowView row, int ordinal) {
        if (row.isNull(ordinal)) {
            throw new PravahaException(
                    AerospikeErrors.OPERATION_FAILED,
                    "key bin '" + schema.field(ordinal).name() + "' is null in a row for " + namespace + "." + set
                            + "; a record cannot be keyed by nothing");
        }
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
            connector.release(client);
            client = null;
        }
    }
}
