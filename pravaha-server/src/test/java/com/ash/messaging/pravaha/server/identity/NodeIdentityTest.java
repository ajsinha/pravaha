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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-052 on a node: one verifier for every transport, the store, and the default-password refusal. */
class NodeIdentityTest {

    @TempDir
    Path dir;

    private IdentityProperties identity(boolean dev) {
        IdentityProperties identity = new IdentityProperties();
        identity.setEnabled(true);
        identity.setStore(dir.resolve("identity/identity.journal").toString());
        identity.setEnvironment("qa");
        identity.setDev(dev);
        return identity;
    }

    private static SecurityProperties tokens(Map<String, SecurityProperties.TokenSpec> table) {
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        security.setPolicy("authenticated");
        security.setTokens(table);
        return security;
    }

    private static PravahaNode node(SecurityProperties security, IdentityProperties identity) {
        PravahaNode node = PravahaNode.builder()
                .withCatalog(new StreamCatalog())
                .withSecurity(security)
                .withWatermark(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(new PersistenceProperties())
                .withNodeId("identity-test-node")
                .build();
        node.setIdentity(identity);
        return node;
    }

    @Test
    void aSessionFromTheIdentityServiceAuthenticatesAndAStaticTokenStillDoes() {
        SecurityProperties.TokenSpec svc = new SecurityProperties.TokenSpec();
        svc.setId("svc");
        PravahaNode node = node(tokens(Map.of("legacy-static-token", svc)), identity(true));
        IdentityService users = node.identity().orElseThrow();
        String session = users.login("admin", IdentityService.DEFAULT_ADMIN_PASSWORD, null)
                .token();

        assertThat(node.verifier().verify(session).id()).isEqualTo("admin");
        assertThat(node.verifier().verify("legacy-static-token").id()).isEqualTo("svc");
        assertThatThrownBy(() -> node.verifier().verify("prv_s_forged"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7001");
    }

    @Test
    void anEmptyTokenTableIsFineWhenIdentityIsOn() {
        PravahaNode node = node(tokens(Map.of()), identity(true));
        assertThatThrownBy(() -> node.verifier().verify("anything"))
                .hasMessageContaining("PRV-7001")
                .hasMessageContaining("the credential was rejected");
    }

    @Test
    void outsideDevTheDefaultAdminPasswordRefusesToStart() {
        PravahaNode node = node(tokens(Map.of()), identity(false));
        assertThatThrownBy(node::start).hasMessageContaining("PRV-7019");
    }

    @Test
    void anInitialPasswordFileLetsANodeOutsideDevStartWithoutThePublishedPassword() throws Exception {
        java.nio.file.Path file = dir.resolve("initial-admin-password");
        java.nio.file.Files.writeString(file, "Generated-pass-9Qa\n");
        IdentityProperties properties = identity(false);
        properties.setBootstrapPasswordFile(file.toString());
        PravahaNode node = node(tokens(Map.of()), properties);
        IdentityService users = node.identity().orElseThrow();
        assertThat(users.defaultAdminPasswordInUse()).isFalse();
        users.requireStartable();
        assertThat(users.login("admin", "Generated-pass-9Qa", null).token()).startsWith("prv_s_");
    }

    @Test
    void aNamedInitialPasswordFileThatCannotBeReadIsRefused() {
        IdentityProperties properties = identity(false);
        properties.setBootstrapPasswordFile(dir.resolve("missing").toString());
        PravahaNode node = node(tokens(Map.of()), properties);
        assertThatThrownBy(node::identity).hasMessageContaining("PRV-7004").hasMessageContaining("missing");
    }

    @Test
    void identityWithoutTokenAuthenticationIsAMisconfiguration() {
        SecurityProperties none = new SecurityProperties();
        none.setAllowAnonymous(true);
        PravahaNode node = node(none, identity(true));
        assertThatThrownBy(node::verifier).hasMessageContaining("PRV-7004");
    }

    @Test
    void offByDefault() {
        PravahaNode node = node(tokens(Map.of()), new IdentityProperties());
        assertThat(node.identity()).isEmpty();
    }
}
