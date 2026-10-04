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

import java.io.DataInputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.pgwire.PgWireLimits;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PGPREAUTH-1: {@code pravaha.pgwire.limits.*} bind, reach the node's gateway, and refuse nonsense. */
class PravahaNodePgWireLimitsTest {

    private static PgWireLimitsProperties bound(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bind("pravaha.pgwire.limits", PgWireLimitsProperties.class)
                .orElseGet(PgWireLimitsProperties::new);
    }

    @Test
    void theDefaultsAreTheLibrarysAndTheKeysBindAsDocumented() {
        assertThat(new PgWireLimitsProperties().limits()).isEqualTo(PgWireLimits.DEFAULTS);
        PgWireLimits limits = bound(Map.of(
                        "pravaha.pgwire.limits.max-connections", "20",
                        "pravaha.pgwire.limits.max-connections-per-principal", "4",
                        "pravaha.pgwire.limits.max-unauthenticated", "5",
                        "pravaha.pgwire.limits.authentication-timeout", "3s",
                        "pravaha.pgwire.limits.max-message-size", "2MB",
                        "pravaha.pgwire.limits.idle-timeout", "30m"))
                .limits();
        assertThat(limits)
                .isEqualTo(new PgWireLimits(20, 4, 5, Duration.ofSeconds(3), 2 * 1024 * 1024, Duration.ofMinutes(30)));
    }

    @Test
    void anOutOfRangeLimitIsRefusedByName() {
        assertThatThrownBy(() -> bound(Map.of("pravaha.pgwire.limits.max-message-size", "1KB"))
                        .limits())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6220")
                .hasMessageContaining("pravaha.pgwire.limits.max-message-size");
    }

    @Test
    void theNodesGatewayEnforcesTheConfiguredConnectionLimit() throws Exception {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        PravahaNode node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPgWire(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("pgwire-limits-node")
                .build();
        node.setPgWireLimits(bound(Map.of(
                "pravaha.pgwire.limits.max-connections", "1", "pravaha.pgwire.limits.max-unauthenticated", "1")));
        node.start();
        try (Socket first = new Socket(
                java.net.InetAddress.getLoopbackAddress(), node.pgwirePort().orElseThrow())) {
            first.setSoTimeout(10_000);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            int type = -1;
            while (System.nanoTime() < deadline) {
                // The first socket may not have been counted yet when the second arrives; retry
                // until the second is the one refused.
                try (Socket second = new Socket(
                        java.net.InetAddress.getLoopbackAddress(),
                        node.pgwirePort().orElseThrow())) {
                    second.setSoTimeout(1_000);
                    DataInputStream in = new DataInputStream(second.getInputStream());
                    try {
                        type = in.read();
                    } catch (java.net.SocketTimeoutException notRefused) {
                        type = -1;
                    }
                    if (type == 'E') {
                        byte[] body = new byte[in.readInt() - 4];
                        in.readFully(body);
                        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                        assertThat(List.of(text.split("\0")))
                                .contains("C53300")
                                .anySatisfy(field -> assertThat(field).contains("PRV-6216"));
                        break;
                    }
                }
            }
            assertThat((char) type)
                    .as("the second connection was refused at once")
                    .isEqualTo('E');
        } finally {
            node.stop();
        }
    }
}
