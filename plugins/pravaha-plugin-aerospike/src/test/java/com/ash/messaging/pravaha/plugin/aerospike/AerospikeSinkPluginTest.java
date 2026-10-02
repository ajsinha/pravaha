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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.policy.ClientPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Aerospike sink's write path, without an Aerospike.
 *
 * <p>{@code AerospikePluginIT} proves the sink against a real server and needs Docker. This proves
 * what the sink decides -- which key a row gets, whether it upserts or deletes, which bins it writes
 * -- against a client that records the calls, so a machine without Docker still tests the logic that
 * decides whether a retraction deletes the right record.
 */
final class AerospikeSinkPluginTest {

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** One call the sink made on the client: "put" or "delete", the key, and the bins written. */
    private record Call(String method, Key key, List<String> bins) {}

    private final List<Call> calls = new ArrayList<>();
    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);

    @AfterEach
    void close() {
        arena.close();
    }

    private AerospikeSinkPlugin sink(String schema, Map<String, String> extra) {
        IAerospikeClient client = (IAerospikeClient) Proxy.newProxyInstance(
                IAerospikeClient.class.getClassLoader(),
                new Class<?>[] {IAerospikeClient.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "put" ->
                            calls.add(new Call(
                                    "put",
                                    (Key) args[1],
                                    Arrays.stream((Bin[]) args[2])
                                            .map(bin -> bin.name)
                                            .toList()));
                        case "delete" -> calls.add(new Call("delete", (Key) args[1], List.of()));
                        default -> {}
                    }
                    Class<?> returns = method.getReturnType();
                    return returns == boolean.class ? false : returns == int.class ? 0 : null;
                });
        AerospikeSinkPlugin sink = new AerospikeSinkPlugin(new AerospikeSinkPlugin.Connector() {
            @Override
            public IAerospikeClient connect(ClientPolicy policy, Host[] hosts, String instanceName) {
                return client;
            }

            @Override
            public void release(IAerospikeClient released) {}
        });
        java.util.HashMap<String, String> config = new java.util.HashMap<>(
                Map.of("hosts", "127.0.0.1:3000", "namespace", "test", "set", "results", "schema", schema));
        config.putAll(extra);
        sink.configure(new Ctx("results", config));
        sink.open();
        return sink;
    }

    /** A real binary row, laid out the way the engine hands one to a sink. */
    private RowView row(StreamSchema schema, long weight, Object... values) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            switch (values[ordinal]) {
                case Integer v -> writer.setInt(ordinal, v);
                case Long v -> writer.setLong(ordinal, v);
                case String v -> writer.setString(ordinal, v);
                default -> throw new IllegalArgumentException(values[ordinal].toString());
            }
        }
        writer.weight(weight).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    @Test
    void anInt32KeyIsReadAtItsOwnWidthNotFromTheColumnBesideIt() {
        // Two rows with the same id and different neighbouring columns. Reading the INT32 key with
        // getLong took four bytes of `code` into the key, so these were two records -- and a
        // retraction of one could never delete the other's upsert.
        String spec = "id:INT32,code:INT32,amount:INT64";
        StreamSchema schema = AerospikeSchemas.parse("results", spec);
        AerospikeSinkPlugin sink = sink(spec, Map.of("key.bin", "id"));

        sink.write(List.of(row(schema, 1, 7, 1, 10L), row(schema, 1, 7, 2, 20L)));

        assertThat(calls).extracting(Call::key).containsOnly(new Key("test", "results", 7L));
    }

    @Test
    void aPositiveWeightUpsertsAndANegativeOneDeletesTheSameRecord() {
        String spec = "id:INT64,status:STRING,amount:INT64";
        StreamSchema schema = AerospikeSchemas.parse("results", spec);
        AerospikeSinkPlugin sink = sink(spec, Map.of("key.bins", "id"));

        sink.write(List.of(row(schema, 1, 1L, "NEW", 100L), row(schema, -1, 1L, "NEW", 100L)));

        assertThat(calls).extracting(Call::method).containsExactly("put", "delete");
        assertThat(calls.get(0).key()).isEqualTo(calls.get(1).key());
        assertThat(calls.get(0).bins())
                .as("a single-column key is the record's identity, not also a bin")
                .containsExactly("status", "amount");
        assertThat(sink.recordsWritten()).isEqualTo(1);
        assertThat(sink.recordsDeleted()).isEqualTo(1);
    }

    @Test
    void aCompositeKeyIsOneRecordPerKeyTupleAndItsColumnsStayReadableAsBins() {
        String spec = "user_id:STRING,region:STRING,total:INT64";
        StreamSchema schema = AerospikeSchemas.parse("results", spec);
        AerospikeSinkPlugin sink = sink(spec, Map.of("key.bins", "user_id,region"));

        sink.write(List.of(
                row(schema, 1, "ab", "c", 1L),
                row(schema, 1, "a", "bc", 2L),
                row(schema, 1, "ab", "c", 3L),
                row(schema, -1, "a", "bc", 2L)));

        List<Key> keys = calls.stream().map(Call::key).toList();
        assertThat(keys.get(0)).as("the same tuple is the same record").isEqualTo(keys.get(2));
        assertThat(keys.get(1))
                .as("(\"ab\",\"c\") and (\"a\",\"bc\") concatenate alike and must still be two records")
                .isNotEqualTo(keys.get(0));
        assertThat(keys.get(3))
                .as("the retraction deletes the record its own tuple names")
                .isEqualTo(keys.get(1));
        assertThat(calls.get(0).bins())
                .as("a blob key cannot be read back into parts, so the key columns are written too")
                .containsExactly("user_id", "region", "total");
        assertThat(sink.keyColumns()).containsExactly("user_id", "region");
        assertThat(sink.schema()).contains(schema);
    }

    @Test
    void aRowWithANullKeyIsRefusedRatherThanWrittenToAnInventedRecord() {
        String spec = "id:INT64?,amount:INT64";
        StreamSchema schema = AerospikeSchemas.parse("results", spec);
        AerospikeSinkPlugin sink = sink(spec, Map.of("key.bins", "id"));
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setNull(0).setLong(1, 5L).weight(1).commit();
        RowView nullKey = new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));

        assertThatThrownBy(() -> sink.write(List.of(nullKey)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is null");
        assertThat(calls).isEmpty();
    }

    @Test
    void theKeyIsRequiredNamedOnceAndOfAKeyableType() {
        assertThatThrownBy(() -> sink("id:INT64,amount:INT64", Map.of()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("key.bins is required");
        assertThatThrownBy(() -> sink("id:INT64,amount:INT64", Map.of("key.bin", "id", "key.bins", "id")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("name the key once");
        assertThatThrownBy(() -> sink("price:FLOAT64,amount:INT64", Map.of("key.bins", "price")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("floating-point");
        assertThatThrownBy(() -> sink("id:INT64,amount:INT64", Map.of("key.bins", "nope")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("not in the declared schema");
    }

    @Test
    void theSinkIsDiscoverableByNameSoABindingCanNameIt() {
        List<String> names = new ArrayList<>();
        for (StreamSinkPlugin plugin : ServiceLoader.load(StreamSinkPlugin.class)) {
            names.add(plugin.name());
        }
        assertThat(names).contains("aerospike-sink");
    }
}
