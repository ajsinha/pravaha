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
package com.ash.messaging.pravaha.server.ingest;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Discovering the dimension tables a node's configuration names.
 *
 * <p>Nothing in shipped code discovered a {@code LookupSourcePlugin}. Lookup joins were implemented,
 * optimised, tested and documented, and no deployment could reach one -- the registry had no
 * dimension table to plan against, so a query joining one planned a stream-to-stream join and waited
 * for rows a dimension table never sends.
 */
class PluginLookupSourcesTest {

    @Test
    void aConfiguredDimensionTableIsFoundOpenedAndConfigured() {
        StubLookupPlugin.opens = 0;
        try (PluginLookupSources sources = new PluginLookupSources()
                .bind(new SourceBinding("users", "stub-lookup", Map.of("set", "users", "key.bin", "user_id")))) {
            List<LookupSourcePlugin> opened = sources.open();

            assertThat(opened).hasSize(1);
            assertThat(opened.get(0).schema().name())
                    .as("the plugin supplies its own schema; a hand-declared one can disagree with the table")
                    .isEqualTo("users");
            assertThat(opened.get(0).keyColumns()).containsExactly("user_id");
            assertThat(StubLookupPlugin.lastConfig)
                    .as("the binding's options must reach the plugin, or it connects to nothing")
                    .containsEntry("set", "users")
                    .containsEntry("key.bin", "user_id");
            assertThat(StubLookupPlugin.opens).isEqualTo(1);
        }
    }

    @Test
    void anUnknownLookupPluginIsRefusedWithWhatIsAvailable() {
        // Named, and listing what is there. A node that started without its dimension table and
        // failed later, per record, would be diagnosed from the wrong end.
        try (PluginLookupSources sources =
                new PluginLookupSources().bind(new SourceBinding("users", "not-a-plugin", Map.of()))) {
            assertThatThrownBy(sources::open)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("no lookup plugin named 'not-a-plugin'")
                    .hasMessageContaining("stub-lookup");
        }
    }

    @Test
    void aNodeWithNoLookupsBlockOpensNothing() {
        try (PluginLookupSources sources = new PluginLookupSources()) {
            assertThat(sources.open()).isEmpty();
        }
    }
}
