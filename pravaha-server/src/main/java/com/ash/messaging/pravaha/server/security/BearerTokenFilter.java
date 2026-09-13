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

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.TokenVerifier;

/**
 * Authenticates the HTTP surface with the same credentials Flight uses.
 *
 * <p>The REST API had no authentication of any kind. That mattered less than it sounds for reads --
 * row data goes over Flight, and this surface serves schemas, validation and explain -- and more
 * than it sounds for writes, because {@code POST /api/v1/streams} registered a stream and anyone who
 * could reach the port could call it.
 *
 * <p>One {@link TokenVerifier} for both transports rather than a second identity model beside the
 * first. Two ways to say who you are is two sets of rules to keep in agreement, and they diverge in
 * the direction of whichever one somebody forgot.
 *
 * <p>Spring Security is not used here on purpose. It would bring a second authorization model --
 * roles, matchers, its own principal -- to sit beside {@link
 * com.ash.messaging.pravaha.security.SecurityPolicy}, which is the one the engine actually enforces
 * on every read. A filter that resolves a token to a Pravaha {@link Principal} and stops is smaller
 * and leaves exactly one set of rules.
 */
public final class BearerTokenFilter extends OncePerRequestFilter {

    /**
     * Paths that answer without a credential.
     *
     * <p>Liveness must not need one: a health probe that authenticates fails closed when the
     * identity source is down, and takes the node out of rotation for a fault that has nothing to do
     * with it. The OpenAPI document and the docs UI describe the shape of the API and disclose no
     * data.
     */
    private static final Set<String> OPEN_PREFIXES =
            Set.of("/actuator/health", "/actuator/info", "/api/v1/openapi.json", "/api/docs", "/swagger-ui");

    private final TokenVerifier verifier;

    public BearerTokenFilter(TokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return OPEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("authorization");
        if (header == null || header.isBlank()) {
            refuse(
                    response,
                    request.getRequestURI(),
                    "this server requires a credential; send it as 'Authorization: Bearer <token>'");
            return;
        }
        String token = header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).strip()
                : header.strip();
        Principal principal;
        try {
            principal = verifier.verify(token);
        } catch (PravahaException e) {
            // The verifier's message is deliberately uninformative about *why* a credential failed,
            // and it is passed through unchanged: "expired" versus "unknown" versus "bad signature"
            // is three bits of an oracle for whoever is guessing.
            refuse(response, request.getRequestURI(), e.getMessage());
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    /** Matches what ApiError carries, so one error shape reaches a client rather than two. */
    private static final String HELP_URL = "https://docs.pravaha.io/errors/PRV-7001";

    /** Where an authenticated principal is left for a controller that needs one. */
    public static final String PRINCIPAL_ATTRIBUTE = "pravaha.principal";

    /** The caller's identity, or {@link Principal#ANONYMOUS} when authentication is off. */
    public static Principal principalOf(HttpServletRequest request) {
        Object found = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        return found instanceof Principal principal ? principal : Principal.ANONYMOUS;
    }

    private static void refuse(HttpServletResponse response, String path, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        // The same ApiError shape every other failure uses. A client that has to parse two error
        // shapes will handle one of them badly.
        // The same field set as ApiError, which every other failure on this surface uses. A 401 that
        // answered {code, message, status} while everything else answered {code, message, helpUrl,
        // timestamp, path} gave clients two error shapes to parse -- and a client that has to parse
        // two will handle one of them badly, which is the thing this codebase says three times it
        // will not do.
        // Exactly ApiError's fields, and no more. My first version added the four ApiError carries
        // on top of a "status" that it does not, so the surface every unauthenticated client meets
        // first was the one place a strict parser saw a sixth field. It also emitted PRV-0400, a
        // code the ErrorCode constructor would reject.
        response.getWriter()
                .write("{\"code\":\"PRV-7001\""
                        + ",\"message\":\"" + reason.replace("\"", "'") + "\""
                        + ",\"helpUrl\":\"" + HELP_URL + "\""
                        + ",\"timestamp\":\"" + java.time.Instant.now() + "\""
                        + ",\"path\":\"" + String.valueOf(path).replace("\"", "'") + "\"}");
    }
}
