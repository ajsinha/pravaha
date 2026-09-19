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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code deletes} options, what each mode declares, and the row recording a retraction is made from. */
class DeleteDetectionConfigTest {

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static AerospikeSourcePlugin configured(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "hosts", "127.0.0.1:3000", "namespace", "test", "set", "orders", "schema", "id:INT64,status:STRING"));
        config.putAll(extra);
        AerospikeSourcePlugin plugin = new AerospikeSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        return plugin;
    }

    @Test
    void ignoreIsTheDefaultAndDeclaresWhatItAlwaysDeclared() {
        SourceCapabilities caps = configured(Map.of()).capabilities();
        assertThat(caps.emitsDeletes()).isFalse();
        assertThat(caps.emitsBeforeImage()).isFalse();
        assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
    }

    @Test
    void detectDeclaresDeletesBeforeImagesAndExactlyOnce() {
        AerospikeSourcePlugin plugin = configured(Map.of("deletes", "detect", "deletes.state.dir", "/tmp/unused"));
        SourceCapabilities caps = plugin.capabilities();
        assertThat(plugin.detectsDeletes()).isTrue();
        assertThat(caps.emitsDeletes())
                .as("PRV-2041 reads this to refuse an append-only sink")
                .isTrue();
        assertThat(caps.emitsBeforeImage()).isTrue();
        assertThat(caps.guarantee())
                .as("also what keeps the engine from sharing a reader whose rows are relative to its own state")
                .isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
        assertThat(caps.replayableOffsets()).isTrue();
    }

    @Test
    void detectWithoutAStateDirectoryIsRefused() {
        assertThatThrownBy(() -> configured(Map.of("deletes", "detect")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("deletes.state.dir");
    }

    @Test
    void anUnknownModeOrABadCeilingIsRefused() {
        assertThatThrownBy(() -> configured(Map.of("deletes", "sometimes")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'ignore' or 'detect'");
        assertThatThrownBy(() ->
                        configured(Map.of("deletes", "detect", "deletes.state.dir", "/tmp/x", "deletes.max.keys", "0")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> configured(
                        Map.of("deletes", "detect", "deletes.state.dir", "/tmp/x", "deletes.max.keys", "lots")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("a number");
    }

    @Test
    void aRecordingReplaysEveryTypeWithTheSameCalls() {
        StreamSchema schema = AerospikeSchemas.parse(
                "all", "b:BOOLEAN,i8:INT8,i16:INT16,i32:INT32,i64:INT64,f:FLOAT32,d:FLOAT64,s:STRING,x:BYTES,n:STRING");
        RowRecorder recorder = new RowRecorder(schema);
        recorder.setBoolean(0, true)
                .setByte(1, (byte) -7)
                .setShort(2, (short) 300)
                .setInt(3, Integer.MIN_VALUE)
                .setLong(4, Long.MAX_VALUE)
                .setFloat(5, 1.5f)
                .setDouble(6, -2.25)
                .setString(7, "ünïcode")
                .setBytes(8, new byte[] {1, 2, 3})
                .setNull(9);
        recorder.setDecimal(9, 12L, -34L);
        recorder.setUnread(9);
        byte[] recording = recorder.toBytes();

        List<String> calls = new java.util.ArrayList<>();
        RowWriter target = (RowWriter) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {RowWriter.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + java.util.Arrays.deepToString(args));
                    return proxy;
                });
        RowRecorder.replay(recording, target);

        assertThat(calls)
                .containsExactly(
                        "setBoolean[0, true]",
                        "setByte[1, -7]",
                        "setShort[2, 300]",
                        "setInt[3, -2147483648]",
                        "setLong[4, 9223372036854775807]",
                        "setFloat[5, 1.5]",
                        "setDouble[6, -2.25]",
                        "setString[7, ünïcode]",
                        "setBytes[8, [1, 2, 3]]",
                        "setNull[9]",
                        "setDecimal[9, 12, -34]",
                        "setUnread[9]");
        assertThat(recorder.reset().toBytes()).isEmpty();
        RowRecorder nulls = new RowRecorder(schema);
        nulls.setNull(0);
        nulls.setNull(1);
        recorder.setString(0, null);
        recorder.setBytes(1, null);
        assertThat(recorder.toBytes())
                .as("a null string or byte array records as a null")
                .isEqualTo(nulls.toBytes());
    }
}
