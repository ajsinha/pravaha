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
package com.ash.messaging.pravaha.server.identity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECOVERYOWNER-1: a query registered by a user of the identity store (ADR-052) comes back after a
 * restart. Recovery resolved owners through the static token table alone, so on a node whose people
 * live in the store -- here the token table is empty, as it is on such a node -- every query they
 * had registered was refused {@code PRV-8007} at the next start.
 */
class RecoveryOwnerTest {

    private static final String SQL = "SELECT user_id, amount FROM txn";

    @TempDir
    Path dir;

    @Test
    void aQueryOwnedByAStoreUserSurvivesARestart() {
        runFirstNode(users -> {
            users.createUser(admin(users), "ann", "Ann", null, "acme", Set.of("analyst"), null, false);
            return users.principalOfUser("ann").orElseThrow();
        });

        PravahaNode second = node();
        second.start();
        try {
            assertThat(second.registry().orElseThrow().names())
                    .as("recovered under the store's principal for 'ann'")
                    .contains("acme.default.anns_view");
            assertThat(second.registry().orElseThrow().tenantOf("acme.default.anns_view"))
                    .contains("acme");
        } finally {
            second.stop();
        }
    }

    @Test
    void aDisabledUsersQueryKeepsRunningAcrossARestart() {
        runFirstNode(users -> {
            users.createUser(admin(users), "bob", "Bob", null, "acme", Set.of("analyst"), null, false);
            return users.principalOfUser("bob").orElseThrow();
        });
        PravahaNode between = node();
        IdentityService users = between.identity().orElseThrow();
        users.updateUser(admin(users), "bob", null, null, null, "disabled");

        PravahaNode second = node();
        second.start();
        try {
            // Disabling ends the account's sessions and keys, not its running queries, and a restart
            // must not change what runs; the recovery logs a warning naming the owner instead.
            assertThat(second.registry().orElseThrow().names()).contains("acme.default.anns_view");
        } finally {
            second.stop();
        }
    }

    @Test
    void anOwnerNeitherTheStoreNorTheTokenTableKnowsIsStillRefused() {
        runFirstNode(users -> new Principal("ghost", "acme", Set.of("analyst"), Map.of()));

        PravahaNode second = node();
        second.start();
        try {
            assertThat(second.registry().orElseThrow().names()).doesNotContain("anns_view");
        } finally {
            second.stop();
        }
    }

    private void runFirstNode(java.util.function.Function<IdentityService, Principal> owner) {
        PravahaNode first = node();
        first.start();
        try {
            Principal who = owner.apply(first.identity().orElseThrow());
            first.registry().orElseThrow().register("anns_view", SQL, List.of(0), who);
        } finally {
            first.stop();
        }
    }

    private static Principal admin(IdentityService users) {
        return users.principalOfUser(IdentityService.ADMIN).orElseThrow();
    }

    private PravahaNode node() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        security.setPolicy("authenticated");
        security.setTokens(Map.of());
        IdentityProperties identity = new IdentityProperties();
        identity.setEnabled(true);
        identity.setStore(dir.resolve("identity/identity.journal").toString());
        identity.setEnvironment("qa");
        identity.setDev(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(dir.resolve("registry.journal").toString());
        PravahaNode node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withWatermark(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("recovery-owner-node")
                .build();
        node.setIdentity(identity);
        return node;
    }
}
