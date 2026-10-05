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

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.TokenVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J21-1: the HTTP surface holds a custom {@link TokenVerifier} to its contract, as Flight and
 * the PostgreSQL gateway already did.
 *
 * <p>A verifier "never returns null and never {@link Principal#ANONYMOUS}". Flight's middleware and
 * pgwire's sign-in refuse either as unauthenticated; the HTTP filter took them as a signed-in caller --
 * {@code null} became a raw {@code NullPointerException} (a 500 with no PRV code), and {@code
 * ANONYMOUS} let a credential the verifier did not accept through as the anonymous caller, which is
 * what a node without authentication serves. A verifier that fails other than by refusing is closed,
 * not open, and not a 500 either.
 */
class BearerTokenFilterVerifierContractTest {

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    @Test
    void aVerifierThatReturnsNullIsARefusalNotA500() throws Exception {
        assertRefused(token -> null);
    }

    @Test
    void aVerifierThatReturnsAnonymousIsARefusalNotTheAnonymousCaller() throws Exception {
        assertRefused(token -> Principal.ANONYMOUS);
    }

    @Test
    void aVerifierThatFailsIsClosedWithACodedRefusalAndDoesNotEchoItsFailure() throws Exception {
        MockHttpServletResponse response = assertRefused(token -> {
            throw new IllegalStateException("idp.internal:8443 refused the connection");
        });
        assertThat(response.getContentAsString()).doesNotContain("idp.internal");
    }

    @Test
    void aVerifierThatAcceptsStillPassesThePrincipalOn() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new BearerTokenFilter(token -> DANA).doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(BearerTokenFilter.principalOf(request)).isEqualTo(DANA);
    }

    private static MockHttpServletResponse assertRefused(TokenVerifier verifier) throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new BearerTokenFilter(verifier).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"PRV-7001\"");
        assertThat(chain.getRequest()).as("the request went no further").isNull();
        assertThat(request.getAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE)).isNull();
        return response;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/queries");
        request.addHeader("authorization", "Bearer some-token");
        return request;
    }
}
