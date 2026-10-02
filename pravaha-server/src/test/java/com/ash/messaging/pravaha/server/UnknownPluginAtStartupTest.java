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
package com.ash.messaging.pravaha.server;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGINLATE-1: a source naming a plugin nothing on the classpath answers to stops the node at
 * startup with PRV-5090, as CONNECTORS.md says. It started UP and refused only the first registration,
 * and the refusal said the jar "carries filesystem alone" right after listing the nine it carries.
 */
class UnknownPluginAtStartupTest {

    @Test
    void aSourceNamingAMissingPluginStopsTheNodeWithTheListOfWhatIsThere() {
        Map<String, String> properties = Map.of(
                "pravaha.streams.t.schema", "key:STRING,v:INT64",
                "pravaha.sources.t.plugin", "redis",
                "pravaha.sources.t.options.host", "localhost");
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        PravahaNode node = PravahaNode.builder()
                .withNodeId("unknown-plugin")
                .withDeclaredStreams(binder.bind("pravaha", StreamDeclarationProperties.class)
                        .get())
                .withSources(
                        binder.bind("pravaha", SourceBindingProperties.class).get())
                .withSecurity(security)
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .build();
        try {
            assertThatThrownBy(node::start)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5090")
                    .hasMessageContaining("'redis'")
                    .hasMessageContaining("kafka")
                    .hasMessageContaining("postgres-cdc")
                    .hasMessageNotContaining("filesystem alone");
        } finally {
            node.stop();
        }
    }
}
