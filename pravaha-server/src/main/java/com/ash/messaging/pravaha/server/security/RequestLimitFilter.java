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
import java.util.concurrent.Semaphore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.server.api.ApiErrors;

/**
 * Bounds what a request may cost before anything else reads it (HTTPBODY-1).
 *
 * <p>Thirty concurrent anonymous {@code POST /api/v1/auth/login} requests, each with a 19 MB body,
 * ran a 1 GiB node out of heap -- the engine's own clock thread included -- because a body was read
 * whole, by Jackson, up to its 20-million-character string limit, before anything had asked who was
 * sending it. This filter runs first, ahead of authentication, and:
 *
 * <ul>
 *   <li>refuses a body whose declared {@code Content-Length} is over the limit with {@code 413}
 *       {@link ApiErrors#BODY_TOO_LARGE}, without reading a byte of it;
 *   <li>counts a body that declares no length (chunked) as it is read, and stops it at the limit --
 *       the reader gets a {@link BodyTooLargeException}, which {@code ApiExceptionHandler} answers
 *       with the same {@code 413};
 *   <li>lets no more than {@code pravaha.http.max-concurrent-sign-ins} sign-ins run at once, each of
 *       which costs a deliberately slow password hash; the rest are {@code 429} {@link
 *       ApiErrors#TOO_MANY_SIGN_INS} with {@code Retry-After}.
 * </ul>
 *
 * <p>The limit is {@code pravaha.http.max-anonymous-body} on a path open without a credential --
 * sign-in, reset, the documentation -- and {@code pravaha.http.max-request-body} everywhere else. A
 * path that needs a credential is refused by {@link BearerTokenFilter} before its body is read at
 * all, so the larger limit is only ever spent on a caller who has authenticated.
 */
public final class RequestLimitFilter extends OncePerRequestFilter {

    /** The paths that sign in, and so run a password hash for an anonymous caller. */
    private static final Set<String> SIGN_INS = Set.of("/api/v1/auth/login", "/api/v1/auth/reset/redeem");

    private final long maxAnonymousBody;
    private final long maxRequestBody;
    private final Semaphore signIns;
    private final int maxConcurrentSignIns;
    private final Set<String> openPaths;

    /**
     * @param openPaths the paths {@link BearerTokenFilter} admits without a credential
     */
    public RequestLimitFilter(
            long maxAnonymousBody, long maxRequestBody, int maxConcurrentSignIns, Set<String> openPaths) {
        this.maxAnonymousBody = maxAnonymousBody;
        this.maxRequestBody = maxRequestBody;
        this.maxConcurrentSignIns = maxConcurrentSignIns;
        this.signIns = new Semaphore(maxConcurrentSignIns);
        this.openPaths = Set.copyOf(openPaths);
    }

    /** The same open set {@link BearerTokenFilter} computes from the configured documentation paths. */
    public static Set<String> openPaths(@Nullable String apiDocs, @Nullable String swaggerUi) {
        return BearerTokenFilter.openPaths(apiDocs, swaggerUi);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean open = path != null && openPaths.stream().anyMatch(o -> path.equals(o) || path.startsWith(o + "/"));
        long limit = open ? maxAnonymousBody : maxRequestBody;
        String setting = open ? "pravaha.http.max-anonymous-body" : "pravaha.http.max-request-body";
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            // Refused on the declaration. The connection is closed after the answer, so the
            // container does not read -- swallow -- the rest of a body nobody asked for.
            response.setHeader("Connection", "close");
            write(
                    response,
                    HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    ApiErrors.BODY_TOO_LARGE,
                    "this request's body is " + declared + " bytes and this endpoint accepts at most " + limit + " ("
                            + setting + ")",
                    path);
            return;
        }
        HttpServletRequest bounded = new Bounded(request, limit, setting);
        if (!(SIGN_INS.contains(path) && "POST".equalsIgnoreCase(request.getMethod()))) {
            chain.doFilter(bounded, response);
            return;
        }
        if (!signIns.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            write(
                    response,
                    429,
                    ApiErrors.TOO_MANY_SIGN_INS,
                    "more than " + maxConcurrentSignIns + " sign-ins are in progress on this node "
                            + "(pravaha.http.max-concurrent-sign-ins); retry in a moment",
                    path);
            return;
        }
        try {
            chain.doFilter(bounded, response);
        } finally {
            signIns.release();
        }
    }

    private static void write(HttpServletResponse response, int status, ErrorCode code, String reason, String path)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        // The ApiError shape, exactly its fields, as BearerTokenFilter writes it: this runs before
        // Spring MVC, so there is no exception handler to make one.
        response.getWriter()
                .write("{\"code\":\"" + code.code() + "\""
                        + ",\"message\":\"" + (code.code() + "  " + reason).replace("\"", "'") + "\""
                        + ",\"helpUrl\":\"" + com.ash.messaging.pravaha.api.HelpUrls.forCode(code.code()) + "\""
                        + ",\"timestamp\":\"" + java.time.Instant.now() + "\""
                        + ",\"path\":\"" + String.valueOf(path).replace("\"", "'") + "\"}");
        // Sent now, not when the request ends: Tomcat discards (swallows) up to
        // server.tomcat.max-swallow-size of an unread body before it writes a buffered answer, and a
        // client that declared 19 MB and is waiting to hear first would wait for nothing.
        response.flushBuffer();
    }

    /**
     * Thrown by a bounded body's stream once more than the limit has been read; {@code
     * ApiExceptionHandler} answers it, wherever Spring wraps it, with {@code 413}.
     */
    public static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException(String message) {
            super(message);
        }
    }

    /** A request whose body stream stops at the limit. */
    private static final class Bounded extends HttpServletRequestWrapper {

        private final long limit;
        private final String setting;
        private @Nullable ServletInputStream stream;

        Bounded(HttpServletRequest request, long limit, String setting) {
            super(request);
            this.limit = limit;
            this.setting = setting;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream counted = stream;
            if (counted == null) {
                counted = new Counted(super.getInputStream(), limit, setting);
                stream = counted;
            }
            return counted;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    getInputStream(),
                    getCharacterEncoding() == null
                            ? java.nio.charset.StandardCharsets.UTF_8
                            : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }

    private static final class Counted extends ServletInputStream {

        private final ServletInputStream in;
        private final long limit;
        private final String setting;
        private long read;

        Counted(ServletInputStream in, long limit, String setting) {
            this.in = in;
            this.limit = limit;
            this.setting = setting;
        }

        private void count(long n) throws BodyTooLargeException {
            if (n > 0) {
                read += n;
                if (read > limit) {
                    throw new BodyTooLargeException(ApiErrors.BODY_TOO_LARGE.code() + "  this request's body is more "
                            + "than " + limit + " bytes, the most this endpoint accepts (" + setting + ")");
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
