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
     * Paths that answer without a credential whatever the deployment is configured to serve.
     *
     * <p>Liveness must not need one: a health probe that authenticates fails closed when the
     * identity source is down, and takes the node out of rotation for a fault that has nothing to
     * do with it.
     */
    private static final Set<String> ALWAYS_OPEN = Set.of("/actuator/health", "/actuator/info");

    /** springdoc's own default, used when {@code springdoc.api-docs.path} is not configured. */
    public static final String DEFAULT_API_DOCS_PATH = "/v3/api-docs";

    /** springdoc's own default, used when {@code springdoc.swagger-ui.path} is not configured. */
    public static final String DEFAULT_SWAGGER_UI_PATH = "/swagger-ui.html";

    private final TokenVerifier verifier;

    /**
     * The paths this filter lets through, as whole path segments.
     *
     * <p>API-F11, and the second half of it. The set was a constant: {@code /api/docs},
     * {@code /api/v1/openapi.json} and {@code /swagger-ui} were written here, and the paths they
     * are meant to name were written in {@code application.yaml}. They agreed by transcription, and
     * they had already stopped agreeing -- {@code springdoc.swagger-ui.path: /api/docs} makes
     * springdoc serve the page's own resources under {@code /api/swagger-ui}, which was not in the
     * set, so the deliberately-open {@code /api/docs} answered 302 and the address it redirected to
     * answered 401. Adding {@code /api/swagger-ui} to the constant fixes that one deployment and
     * leaves the next operator who changes the property with the same split and no clue that a
     * Java file decides it.
     *
     * <p>So the three are derived from the two properties instead: the document is open at
     * whatever {@code springdoc.api-docs.path} says, the page at whatever
     * {@code springdoc.swagger-ui.path} says, and the page's resources at the address springdoc
     * computes from that path -- {@code <parent>/swagger-ui}. They cannot drift because there is
     * one source for all three. Turning either off with {@code springdoc.*.enabled=false} removes
     * its paths from the set rather than leaving an opening onto nothing.
     *
     * <p>Open by design and worth restating: they describe the shape of the API and disclose no
     * stream, query, row or token text. A client that cannot fetch the schema cannot generate a
     * client, and a person who cannot open the page cannot read the API they are entitled to call.
     */
    private final Set<String> openPaths;

    /** The shipped configuration's paths, for a caller with no environment to read. */
    public BearerTokenFilter(TokenVerifier verifier) {
        this(verifier, "/api/v1/openapi.json", "/api/docs");
    }

    /**
     * @param apiDocs {@code springdoc.api-docs.path}, or null when the document is disabled
     * @param swaggerUi {@code springdoc.swagger-ui.path}, or null when the page is disabled
     */
    public BearerTokenFilter(TokenVerifier verifier, String apiDocs, String swaggerUi) {
        this.verifier = verifier;
        this.openPaths = openPaths(apiDocs, swaggerUi);
    }

    /** The open set for a pair of configured springdoc paths. */
    static Set<String> openPaths(String apiDocs, String swaggerUi) {
        Set<String> paths = new java.util.LinkedHashSet<>(ALWAYS_OPEN);
        if (apiDocs != null && !apiDocs.isBlank()) {
            paths.add(normalise(apiDocs));
        }
        if (swaggerUi != null && !swaggerUi.isBlank()) {
            String page = normalise(swaggerUi);
            paths.add(page);
            // Where springdoc puts the page's own HTML, JavaScript and CSS, and the address the
            // request for `page` is redirected to. It is derived from the configured path's parent
            // by springdoc itself, so it is derived from the same place here.
            paths.add(parentOf(page) + "/swagger-ui");
        }
        return Set.copyOf(paths);
    }

    /** A leading slash and no trailing one, so the segment comparison below has one shape to handle. */
    private static String normalise(String path) {
        String trimmed = path.strip();
        String withSlash = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        return withSlash.length() > 1 && withSlash.endsWith("/")
                ? withSlash.substring(0, withSlash.length() - 1)
                : withSlash;
    }

    /** {@code /api/docs} to {@code /api}, {@code /swagger-ui.html} to the empty string. */
    private static String parentOf(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "" : path.substring(0, lastSlash);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        // Whole segments, not a bare prefix. `startsWith("/api/docs")` also opens
        // `/api/docsomething`, and an open path that opens more than it names is the kind of rule
        // that is right until somebody adds an endpoint next to it.
        return openPaths.stream().anyMatch(open -> path.equals(open) || path.startsWith(open + "/"));
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

    /**
     * Matches what ApiError carries, so one error shape reaches a client rather than two.
     *
     * <p>DOCX-21: read from {@code pravaha.docs.base-url} on every refusal rather than held as a
     * constant, so this filter cannot drift from {@code ErrorCode.helpUrl()} again. Empty when the
     * deployment publishes no help pages -- the field stays, as the contract says it does.
     */
    private static String helpUrl() {
        return com.ash.messaging.pravaha.api.HelpUrls.forCode("PRV-7001");
    }

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
                        + ",\"helpUrl\":\"" + helpUrl() + "\""
                        + ",\"timestamp\":\"" + java.time.Instant.now() + "\""
                        + ",\"path\":\"" + String.valueOf(path).replace("\"", "'") + "\"}");
    }
}
