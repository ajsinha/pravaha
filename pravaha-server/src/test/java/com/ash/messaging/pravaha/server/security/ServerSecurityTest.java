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
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
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
        //
        // WITH FLIGHT ENABLED, and that is the whole value of this test. It previously ran with
        // Flight off and passed while `policy: authenticated` could not start a real node at all:
        // hosting() compared the server's default PERMISSIVE against the registry's policy and
        // threw. A test that exercises a security setting on a node with the security transport
        // switched off is not testing the setting.
        SecurityProperties closed = new SecurityProperties();
        closed.setPolicy("authenticated");
        // With a way to authenticate, because the policy without one is now refused as the
        // contradiction it is: serve only verified callers, verify nobody.
        closed.setAuthentication("token");
        SecurityProperties.TokenSpec spec = new SecurityProperties.TokenSpec();
        spec.setId("ann");
        closed.setTokens(Map.of("a-token", spec));

        PravahaNode node = nodeWithFlight(closed);
        assertThatCode(node::start).doesNotThrowAnyException();
        node.stop();
    }

    @Test
    void aPolicyNobodyCanSatisfyIsRefusedRatherThanLeavingHttpOpen() {
        // The combination reads as locked down and is not. Flight refuses everybody, correctly --
        // and the HTTP surface has no filter, because authentication is off, and does not consult
        // the policy, so it goes on serving stream schemas and accepting stream registrations from
        // anyone who can reach the port.
        SecurityProperties contradictory = new SecurityProperties();
        contradictory.setPolicy("authenticated");
        contradictory.setAuthentication("none");

        assertThatThrownBy(() -> node(contradictory).start())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("nobody can use")
                .hasMessageContaining("pravaha.security.authentication=token");
    }

    @Test
    void anOpenServerStartsWithFlightEnabledToo() {
        SecurityProperties open = new SecurityProperties();
        open.setAllowAnonymous(true);
        PravahaNode node = nodeWithFlight(open);
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

    @Test
    void checkpointSettingsReachTheEngineInAFormItCanParse() {
        // Spring parses "2s" into a Duration whose toString is ISO-8601 "PT2S", and the engine's own
        // parser rejects that. The two formats met in the middle and neither was wrong on its own:
        // the node started cleanly with checkpointing configured and then failed every registration
        // with "expected a number with a unit". Only running it found this.
        com.ash.messaging.pravaha.server.state.PersistenceProperties persistence =
                new com.ash.messaging.pravaha.server.state.PersistenceProperties();
        persistence.getCheckpoint().setInterval(java.time.Duration.ofSeconds(2));

        assertThat(persistence.checkpointConfiguration().getDuration("pravaha.checkpoint.interval"))
                .contains(java.time.Duration.ofSeconds(2));
    }

    /** A node that actually listens, so the Flight wiring is exercised rather than skipped. */
    private static PravahaNode nodeWithFlight(SecurityProperties security) {
        return new PravahaNode(
                new StreamCatalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                security,
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                true,
                "127.0.0.1",
                0,
                persistence(""),
                "SINGLE",
                "single",
                "security-flight-node",
                true,
                false,
                null);
    }

    private static PravahaNode node(SecurityProperties security) {
        return new PravahaNode(
                new StreamCatalog(),
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                security,
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                false,
                "127.0.0.1",
                0,
                persistence(""),
                "SINGLE",
                "single",
                "security-test-node",
                true,
                false,
                null);
    }

    /** Journal where the caller asked for one, and no checkpoint directory. */
    private static PersistenceProperties persistence(String journal) {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal == null ? "" : journal);
        return persistence;
    }

    @Test
    void recoveryReconstructsTheConfiguredIdentityRatherThanInventingOne() {
        // The journal records an owner id and nothing else, so recovery has to resolve it. The node
        // fabricated instead: a role-less principal in tenant "unknown", which any policy that
        // inspects either correctly refused -- so on a secured node no query survived a restart, and
        // the re-authorization that recovery does right was the thing that made it fail.
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        SecurityProperties.TokenSpec ann = new SecurityProperties.TokenSpec();
        ann.setId("ann");
        ann.setTenant("acme");
        ann.setRoles(List.of("reader"));
        security.setTokens(Map.of("a-token", ann));

        assertThat(security.principalFor("ann"))
                .as("the identity the registration was made under, reconstructed")
                .hasValueSatisfying(principal -> {
                    assertThat(principal.id()).isEqualTo("ann");
                    assertThat(principal.tenant()).isEqualTo("acme");
                    assertThat(principal.hasRole("reader")).isTrue();
                });

        assertThat(security.principalFor("nobody"))
                .as("an owner this node cannot identify must not be resurrected under an invented "
                        + "identity: refusing is visible, inventing is not")
                .isEmpty();
    }

    @Test
    void aTokenAuthenticatedNodeWithAPermissivePolicyStarts() {
        // CFG-9/SX-12. authentication=token + a real token table + policy=permissive +
        // allow-anonymous=false is the posture an operator sets out to configure: every
        // unauthenticated caller is refused a 401 by BearerTokenFilter, and authenticated ones see
        // what the policy allows. It could not start.
        //
        // The guard computed "is this node open" from the policy type alone, so any non-authenticated
        // policy tripped it however the node authenticated -- and the only way to start was
        // allow-anonymous=true, which is a lie about the node. An operator following the message
        // would have made a secure deployment less secure to get it to boot.
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        security.setPolicy("permissive");
        security.setAllowAnonymous(false);
        SecurityProperties.TokenSpec ann = new SecurityProperties.TokenSpec();
        ann.setId("ann");
        security.setTokens(Map.of("ann-token", ann));

        PravahaNode node = nodeWithFlight(security);
        assertThatCode(node::start)
                .as("a node that refuses every unauthenticated caller is not an open server")
                .doesNotThrowAnyException();
        node.stop();
    }

    @Test
    void aGenuinelyOpenNodeIsStillRefused() {
        // The property the fix must not cost, and the whole reason the guard exists: no
        // authentication at all plus a policy that serves everything is an open server, and it must
        // not start unless somebody said so on purpose.
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("none");
        security.setPolicy("permissive");
        security.setAllowAnonymous(false);

        assertThatThrownBy(() -> nodeWithFlight(security).start())
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("unauthenticated");
    }

    @Test
    void theRefusalReportsTheAuthenticationSettingActuallyInForce() {
        // The message hard-coded "pravaha.security.authentication=none" whatever was configured, so
        // on the misfiring case it misattributed the cause and its first suggested remedy was a
        // setting already in force.
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("none");
        security.setPolicy("permissive");
        security.setAllowAnonymous(false);

        assertThatThrownBy(() -> nodeWithFlight(security).start()).hasMessageContaining("authentication=none");
    }
}
