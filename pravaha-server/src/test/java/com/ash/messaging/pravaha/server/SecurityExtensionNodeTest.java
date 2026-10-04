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

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityExtensions;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * POLICYPLUG-1: a deployment's own {@code SecurityPolicy}, {@code TokenVerifier} and {@code AuditSink}
 * take the place of the configured ones on a node. Before, the node's refusal of an unknown policy said
 * "or implement SecurityPolicy" and nothing on a node would ever have used one.
 */
class SecurityExtensionNodeTest {

    private static final SecurityPolicy READS_NOTHING = (principal, view) -> AccessDecision.deny("custom says no");

    private static final TokenVerifier OWN_TOKENS = token -> {
        if (!"letmein".equals(token)) {
            throw new PravahaException(com.ash.messaging.pravaha.security.SecurityErrors.UNAUTHENTICATED, "no");
        }
        return new Principal("own", "public", Set.of("admin"), Map.of());
    };

    private static PravahaNode node(SecurityProperties security) {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return PravahaNode.builder()
                .withCatalog(new StreamCatalog())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("extension-node")
                .build();
    }

    private static SecurityProperties tokenAuthentication() {
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        return security;
    }

    @Test
    void aNodeUsesTheDeploymentsPolicyVerifierAndSink() {
        AuditSink.InMemory own = new AuditSink.InMemory();
        PravahaNode node = node(tokenAuthentication());
        node.setSecurityExtensions(SecurityExtensions.of(READS_NOTHING, OWN_TOKENS, own));
        node.start();
        try {
            assertThat(node.securityPolicy()).isSameAs(READS_NOTHING);
            assertThat(node.registry().orElseThrow().policy())
                    .as("the engine decides with it, not only the HTTP surface")
                    .isSameAs(READS_NOTHING);
            assertThat(node.verifier()).isSameAs(OWN_TOKENS);
            assertThat(node.verifier().verify("letmein").id()).isEqualTo("own");

            node.auditSink().record(AuditEvent.of(Principal.of("carol"), "query", "x", AccessDecision.allow(), "q"));
            assertThat(own.events()).hasSize(1);
            assertThat(node.auditTrail().map(AuditTrail::durable))
                    .as("the read API names it")
                    .hasValue(AuditSink.InMemory.class.getName());
        } finally {
            node.stop();
        }
    }

    @Test
    void aVerifierNothingWouldAskIsRefused() {
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PravahaNode node = node(security);
        node.setSecurityExtensions(SecurityExtensions.of(null, OWN_TOKENS, null));

        assertThatThrownBy(node::verifier)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7004")
                .hasMessageContaining("authentication is not token");
    }

    @Test
    void theSpringBeansAreFoundAndTheNodesOwnAreNot() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Beans.class)) {
            SecurityExtensions found = SecurityExtensions.from(context);

            assertThat(found.policy()).containsSame(Beans.POLICY);
            assertThat(found.audit()).isEmpty();
            assertThat(found.verifier()).isEmpty();
            assertThat(found.describePolicy("permissive"))
                    .contains(Beans.POLICY.getClass().getName());
        }
    }

    @Test
    void twoBeansOfOneTypeAreRefusedByName() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(Beans.class, Another.class)) {
            assertThatThrownBy(() -> SecurityExtensions.from(context).policy())
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-7004")
                    .hasMessageContaining("ownPolicy")
                    .hasMessageContaining("secondPolicy");
        }
    }

    @Configuration
    static class Beans {
        static final SecurityPolicy POLICY = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }
        };

        @Bean
        SecurityPolicy ownPolicy() {
            return POLICY;
        }

        /** Stands in for the node's own bean, which an extension replaces and never is. */
        @Bean
        @Primary
        SecurityPolicy pravahaSecurityPolicy() {
            return SecurityPolicy.PERMISSIVE;
        }
    }

    @Configuration
    static class Another {
        @Bean
        SecurityPolicy secondPolicy() {
            return SecurityPolicy.PERMISSIVE;
        }
    }
}
