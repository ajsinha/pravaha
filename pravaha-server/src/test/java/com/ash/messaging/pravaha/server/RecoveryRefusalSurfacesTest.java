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

import java.nio.file.Path;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.RegistryJournal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.serving.Retention;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECOVERYHEALTH-1 on a node: a journalled registration the restart refuses turns the engine health
 * {@code DEGRADED}, is counted by a gauge, and stays in the registry until it is dropped -- after which
 * the node is {@code UP} again. It used to leave one WARN line and health {@code UP}.
 */
class RecoveryRefusalSurfacesTest {

    @Test
    void aRefusedRegistrationDegradesHealthAndIsCountedUntilDropped(@TempDir Path dir) {
        Path journal = dir.resolve("registry.journal");
        // Registered by an earlier start, over a stream this node no longer declares.
        new RegistryJournal(journal)
                .recordRegistration(
                        "gone", "SELECT a FROM removed_stream", List.of(0), "anonymous", Retention.DEFAULT, List.of());

        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn").field("id", Types.int64()).build());
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal.toString());
        PravahaNode node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("recovery-node")
                .build();
        node.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PravahaMetrics metrics = new PravahaMetrics(meters, node);
        try {
            var registry = node.registry().orElseThrow();
            assertThat(registry.refusedAtRecovery().all()).singleElement().satisfies(refused -> {
                assertThat(refused.name()).endsWith("gone");
                assertThat(refused.code()).startsWith("PRV-");
            });

            Health health = new EngineHealthIndicator(node).health();
            assertThat(health.getStatus()).isEqualTo(EngineHealthIndicator.DEGRADED);
            assertThat(health.getDetails()).containsEntry("refusedAtRecovery", 1);
            assertThat(String.valueOf(health.getDetails().get("firstRefusedAtRecovery")))
                    .startsWith("PRV-");
            assertThat(meters.get("pravaha.registry.recovery.refused").gauge().value())
                    .isEqualTo(1.0);

            registry.drop(registry.refusedAtRecovery().all().get(0).name());
            assertThat(new EngineHealthIndicator(node).health().getStatus()).isEqualTo(Status.UP);
            assertThat(meters.get("pravaha.registry.recovery.refused").gauge().value())
                    .isZero();
        } finally {
            metrics.close();
            meters.close();
            node.stop();
        }
    }
}
