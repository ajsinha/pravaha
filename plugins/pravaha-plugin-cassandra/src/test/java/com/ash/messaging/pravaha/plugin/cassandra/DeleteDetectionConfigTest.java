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

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code deletes} options and what each mode declares. */
class DeleteDetectionConfigTest {

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static CassandraSourcePlugin configured(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "contact.points", "127.0.0.1:9042",
                "keyspace", "shop",
                "table", "orders",
                "schema", "id:INT64,status:STRING",
                "partition.key", "id"));
        config.putAll(extra);
        CassandraSourcePlugin plugin = new CassandraSourcePlugin();
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
        CassandraSourcePlugin plugin =
                configured(Map.of("deletes", "detect", "deletes.state.dir", "/tmp/unused", "scan.interval.ms", "5000"));
        SourceCapabilities caps = plugin.capabilities();
        assertThat(plugin.detectsDeletes()).isTrue();
        assertThat(caps.emitsDeletes())
                .as("PRV-2041 reads this to refuse an append-only sink")
                .isTrue();
        assertThat(caps.emitsBeforeImage()).isTrue();
        assertThat(caps.guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
        assertThat(caps.pushdown()).containsExactly(PushdownKind.PROJECT);
        assertThat(caps.typicalLatency()).hasMillis(5000);
    }

    @Test
    void detectWithoutAStateDirectoryAnUnknownModeOrABadCeilingIsRefused() {
        assertThatThrownBy(() -> configured(Map.of("deletes", "detect")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("deletes.state.dir");
        assertThatThrownBy(() -> configured(Map.of("deletes", "maybe")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'ignore' or 'detect'");
        assertThatThrownBy(() -> configured(
                        Map.of("deletes", "detect", "deletes.state.dir", "/tmp/x", "deletes.max.keys", "-1")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> configured(
                        Map.of("deletes", "detect", "deletes.state.dir", "/tmp/x", "deletes.max.keys", "many")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("a number");
    }
}
