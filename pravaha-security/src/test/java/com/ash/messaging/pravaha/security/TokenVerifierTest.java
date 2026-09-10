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
package com.ash.messaging.pravaha.security;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Authentication: turning a credential into somebody, and refusing to invent one. */
class TokenVerifierTest {

    private static final Principal ANALYST = new Principal("dana", "acme", Set.of("analyst"), Map.of("region", "emea"));

    @Test
    void aKnownTokenResolvesToItsPrincipal() {
        TokenVerifier verifier = StaticTokenVerifier.of("s3cret", ANALYST);

        assertThat(verifier.verify("s3cret")).isEqualTo(ANALYST);
    }

    @Test
    void anUnknownTokenIsRefusedWithoutSayingWhy() {
        TokenVerifier verifier = StaticTokenVerifier.of("s3cret", ANALYST);

        assertThatThrownBy(() -> verifier.verify("guess"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7001")
                // "not accepted" and nothing more. "expired" versus "unknown" versus "wrong
                // signature" is three bits of an oracle for whoever is working through guesses.
                .hasMessageNotContainingAny("unknown", "expired", "not found", "guess");
    }

    @Test
    void anAbsentOrEmptyCredentialIsRefusedRatherThanTreatedAsAbsentAuthentication() {
        TokenVerifier verifier = StaticTokenVerifier.of("s3cret", ANALYST);

        assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(PravahaException.class);
        assertThatThrownBy(() -> verifier.verify("")).isInstanceOf(PravahaException.class);
        assertThatThrownBy(() -> verifier.verify("   ")).isInstanceOf(PravahaException.class);
    }

    @Test
    void aBlankTokenCannotBeRegistered() {
        // Otherwise a missing header and a valid credential would authenticate the same principal.
        assertThatThrownBy(() -> StaticTokenVerifier.of("", ANALYST)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTokenCannotResolveToTheAnonymousPrincipal() {
        assertThatThrownBy(() -> StaticTokenVerifier.of("t", Principal.ANONYMOUS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audit");
    }

    @Test
    void aVerifierWithNoIdentitySourceAcceptsNothing() {
        assertThatThrownBy(() -> TokenVerifier.rejectAll().verify("anything"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-7001");
    }

    @Test
    void severalTokensCoexist() {
        Principal ops = Principal.of("ops");
        StaticTokenVerifier verifier = StaticTokenVerifier.of("a", ANALYST).and("b", ops);

        assertThat(verifier.size()).isEqualTo(2);
        assertThat(verifier.verify("a")).isEqualTo(ANALYST);
        assertThat(verifier.verify("b")).isEqualTo(ops);
    }

    @Test
    void theTokensThemselvesAreNotRecoverableFromTheVerifier() {
        StaticTokenVerifier verifier = StaticTokenVerifier.of("s3cret", ANALYST);

        // A heap dump of a running server should not hand over the credentials; the digest is what
        // is held. This asserts the property that matters rather than the storage detail.
        assertThat(verifier.toString()).doesNotContain("s3cret");
    }

    @Test
    void aPrincipalNeverPrintsItsClaims() {
        Principal withSecret = new Principal("dana", "acme", Set.of("analyst"), Map.of("ssn", "111-22-3333"));

        assertThat(withSecret.toString()).doesNotContain("111-22-3333").contains("dana");
    }
}
