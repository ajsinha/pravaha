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
package com.ash.messaging.pravaha.server.observe;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id -- and, for a request about one query, the query's name -- into the logging
 * context of every HTTP request, and answers the id in {@code X-Correlation-Id}.
 *
 * <p>After Spring's observation filter, so the request's span is open and tracing's {@code traceId}
 * and {@code spanId} are already in the context beside these: a JSON log line then carries all four
 * (see {@code pravaha.logging.format}). A caller that sends {@code X-Correlation-Id} (or {@code
 * X-Request-Id}) has it kept when it is plain ({@link Correlation}); otherwise one is made.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationFilter extends OncePerRequestFilter {

    /** {@code /api/v1/queries/{name}...} and {@code /api/v1/views/{name}...}: the query a request is about. */
    private static final Pattern ABOUT_A_QUERY = Pattern.compile("^/api/v1/(?:queries|views)/([^/]+)");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(Correlation.HEADER);
        if (given == null) {
            given = request.getHeader("X-Request-Id");
        }
        String correlation = Correlation.idOr(given);
        String query = queryOf(request.getRequestURI());
        MDC.put(NodeFlightObservation.MDC_CORRELATION, correlation);
        if (query != null) {
            MDC.put(NodeFlightObservation.MDC_QUERY, query);
        }
        response.setHeader(Correlation.HEADER, correlation);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(NodeFlightObservation.MDC_CORRELATION);
            MDC.remove(NodeFlightObservation.MDC_QUERY);
        }
    }

    /** The query a path is about, when it is one and its name is plain; else null. */
    static @Nullable String queryOf(@Nullable String path) {
        if (path == null) {
            return null;
        }
        Matcher about = ABOUT_A_QUERY.matcher(path);
        if (!about.find()) {
            return null;
        }
        String name = about.group(1);
        // "validate" and "explain" are verbs under /queries, not names.
        if (name.equals("validate") || name.equals("explain") || !Correlation.plain(name)) {
            return null;
        }
        return name;
    }
}
