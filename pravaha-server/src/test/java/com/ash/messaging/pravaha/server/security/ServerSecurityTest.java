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
package com.ash.messaging.pravaha.server.security;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lock was built and the door was propped open.
 *
 * <p>Every mechanism these tests exercise already existed and was tested on its own: {@code mayRead}
 * per view, row filters on subscribe, per-source checks at registration, principals from verified
 * tokens. None of it was reachable, because the node constructed {@code SecurityPolicy.PERMISSIVE}
 * and {@code AuditSink.NONE} in its own constructor and never called {@code authenticatedBy}. What
 * is pinned here is the wiring, and the refusal that stops the open configuration shipping again.
 */
class ServerSecurityTest {

    @Test
    void aNodeThatWouldServeEverythingToAnybodyRefusesToStart() {
        // The state this server shipped in: no authentication, permissive policy, no audit, no TLS,
        // and nothing anywhere recording that as the intent. A warning would have been read by
        // whoever was watching the log that day; an open server outlives their attention.
        assertThatThrownBy(() -> node(new SecurityProperties()).start())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002")
                // The message has to carry the fix, because whoever meets it is starting a server
                // and has no reason to know which of three properties is the one they want.
                .hasMessageContaining("pravaha.security.authentication=token")
                .hasMessageContaining("pravaha.security.policy=authenticated")
                .hasMessageContaining("pravaha.security.allow-anonymous=true");
    }

    @Test
    void anOpenServerStartsWhenSomebodySaysSoOnPurpose() {
        SecurityProperties open = new SecurityProperties();
        open.setAllowAnonymous(true);
        PravahaNode node = node(open);
        assertThatCode(node::start).doesNotThrowAnyException();
        node.stop();
    }

    @Test
    void anAuthenticatedPolicyStartsWithoutTheAcknowledgement() {
        // Nothing to acknowledge: this node serves no data to anonymous callers, so it is not the
        // configuration the refusal exists to catch.
        SecurityProperties closed = new SecurityProperties();
        closed.setPolicy("authenticated");
        PravahaNode node = node(closed);
        assertThatCode(node::start).doesNotThrowAnyException();
        node.stop();
    }

    @Test
    void configuredTokensBecomePrincipals() {
        SecurityProperties properties = new SecurityProperties();
        properties.setAuthentication("token");
        SecurityProperties.TokenSpec spec = new SecurityProperties.TokenSpec();
        spec.setId("ann");
        spec.setTenant("acme");
        spec.setRoles(List.of("reader"));
        properties.setTokens(Map.of("s3cret", spec));

        TokenVerifier verifier = properties.verifier();
        Principal principal = verifier.verify("s3cret");
        assertThat(principal.id()).isEqualTo("ann");
        assertThat(principal.tenant()).isEqualTo("acme");
        assertThat(principal.hasRole("reader")).isTrue();
        assertThat(principal.isAnonymous()).isFalse();
    }

    @Test
    void authenticationWithNoTokensRejectsEverythingRatherThanAcceptingIt() {
        // The failure mode worth refusing: a deployment turns authentication on, configures no
        // credentials, and a verifier built from an empty map lets everyone through because there
        // was nothing to check against.
        SecurityProperties properties = new SecurityProperties();
        properties.setAuthentication("token");

        assertThatThrownBy(() -> properties.verifier().verify("anything"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7001");
    }

    @Test
    void theAuthenticatedPolicyRefusesAnonymousReadsAndRegistrations() {
        SecurityPolicy policy = new AuthenticatedOnlyPolicy();

        assertThat(policy.mayRead(Principal.ANONYMOUS, "payroll").allowed()).isFalse();
        assertThat(policy.mayRegisterQuery(Principal.ANONYMOUS).allowed()).isFalse();
        assertThat(policy.mayRead(Principal.of("ann"), "payroll").allowed()).isTrue();
        assertThat(policy.mayRegisterQuery(Principal.of("ann")).allowed()).isTrue();
    }

    @Test
    void anUnknownPolicyNameIsRefusedRatherThanFallingBackToPermissive() {
        // Falling back to permissive on a typo is how a deployment believes it is locked down for
        // months. The name is checked at startup, where somebody is still looking.
        SecurityProperties typo = new SecurityProperties();
        typo.setPolicy("authenticatd");
        assertThatThrownBy(() -> node(typo).start())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("authenticatd");
    }

    private static PravahaNode node(SecurityProperties security) {
        return new PravahaNode(
                new StreamCatalog(),
                new SourceBindingProperties(),
                security,
                null,
                null,
                false,
                "127.0.0.1",
                0,
                persistence(""),
                "SINGLE",
                "single",
                "security-test-node");
    }

    /** Journal where the caller asked for one, and no checkpoint directory. */
    private static PersistenceProperties persistence(String journal) {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal == null ? "" : journal);
        return persistence;
    }
}
