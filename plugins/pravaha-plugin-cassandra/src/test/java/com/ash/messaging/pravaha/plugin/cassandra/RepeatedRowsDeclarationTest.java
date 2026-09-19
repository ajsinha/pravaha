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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.SqlErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCAN-1: whether this source repeats rows is its configuration's answer, and the registry acts on
 * it before anything connects.
 *
 * <p>No server: the refusal happens before a feed opens, which is the point of it, and the admitted
 * case is proved against a real node by {@code CassandraDeleteDetectionIT}.
 */
class RepeatedRowsDeclarationTest {

    @TempDir
    Path stateDir;

    private Map<String, String> options(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "contact.points", "127.0.0.1:1",
                "keyspace", "shop",
                "table", "orders",
                "schema", "id:INT64,status:STRING,amount:INT64",
                "partition.key", "id"));
        config.putAll(extra);
        return config;
    }

    private record Ctx(String instanceName, Map<String, String> config)
            implements com.ash.messaging.pravaha.api.plugin.PluginContext {}

    private CassandraSourcePlugin configured(Map<String, String> extra) {
        CassandraSourcePlugin plugin = new CassandraSourcePlugin();
        plugin.configure(new Ctx("orders", options(extra)));
        return plugin;
    }

    @Test
    void ignoreRepeatsEveryRowOnEveryPassAndDetectDoesNot() {
        assertThat(configured(Map.of()).capabilities().repeatsRows())
                .as("deletes: ignore, the default: every pass emits every row again at +1")
                .isTrue();
        assertThat(configured(Map.of("deletes", "detect", "deletes.state.dir", stateDir.toString()))
                        .capabilities()
                        .repeatsRows())
                .as("deletes: detect: an exact changelog")
                .isFalse();
    }

    @Test
    void theBindingAnswersWithoutConnecting() {
        PluginSourceFeeds ignore =
                new PluginSourceFeeds().bind(new SourceBinding("orders", "cassandra", options(Map.of())));
        PluginSourceFeeds detect = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "orders",
                        "cassandra",
                        options(Map.of("deletes", "detect", "deletes.state.dir", stateDir.toString()))));

        assertThat(ignore.repeatingSource("orders")).contains("cassandra");
        assertThat(detect.repeatingSource("orders")).isEmpty();
        assertThat(ignore.repeatingSource("unbound")).isEqualTo(Optional.empty());
    }

    @Test
    void anAggregateOverADefaultBindingIsRefusedBeforeAConnectionIsTried() {
        // 127.0.0.1:1 accepts nothing: a refusal that tried to connect first would say so instead.
        PluginSourceFeeds feeds =
                new PluginSourceFeeds().bind(new SourceBinding("orders", "cassandra", options(Map.of())));
        try (QueryRegistry registry =
                new QueryRegistry(new ViewCatalog(), configured(Map.of()).schema()).feedingFrom(feeds)) {
            assertThatThrownBy(() -> registry.register(
                            "order_total",
                            "SELECT COUNT(*) AS n, SUM(amount) AS total FROM orders",
                            List.of(0),
                            Principal.ANONYMOUS))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e ->
                            assertThat(((PravahaException) e).errorCode()).isEqualTo(SqlErrors.SOURCE_REPEATS_ROWS))
                    .hasMessageContaining("cassandra")
                    .hasMessageContaining("`deletes: detect` on the binding for 'orders'");
            assertThat(registry.names()).isEmpty();
        }
    }
}
