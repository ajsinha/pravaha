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

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTPBODY-1: the request filter's bounds, with nothing between it and the assertion. */
class RequestLimitFilterTest {

    private static final Set<String> OPEN = BearerTokenFilter.openPaths(null, null);

    private static MockHttpServletRequest login(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    @Test
    void pastMaxConcurrentSignInsTheNextIs429WithRetryAfterAndTheSlotComesBack() throws Exception {
        RequestLimitFilter filter = new RequestLimitFilter(16 * 1024, 4 * 1024 * 1024, 1, OPEN);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread held = Thread.ofPlatform().start(() -> {
            try {
                filter.doFilter(login(new byte[10]), new MockHttpServletResponse(), (request, response) -> {
                    inside.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse refused = new MockHttpServletResponse();
        boolean[] ran = {false};
        filter.doFilter(login(new byte[10]), refused, (request, response) -> ran[0] = true);
        assertThat(ran[0]).isFalse();
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("1");
        assertThat(refused.getContentAsString())
                .contains("\"code\":\"PRV-1055\"")
                .contains("pravaha.http.max-concurrent-sign-ins");

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/v1/status");
        FilterChain counted = (request, response) -> ran[0] = true;
        filter.doFilter(other, new MockHttpServletResponse(), counted);
        assertThat(ran[0]).as("only sign-ins are counted").isTrue();

        release.countDown();
        held.join(10_000);
        ran[0] = false;
        filter.doFilter(login(new byte[10]), new MockHttpServletResponse(), (request, response) -> ran[0] = true);
        assertThat(ran[0]).as("the held sign-in's slot is free again").isTrue();
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutCallingTheChain() throws Exception {
        RequestLimitFilter filter = new RequestLimitFilter(16 * 1024, 4 * 1024 * 1024, 8, OPEN);
        MockHttpServletRequest request = login(new byte[20 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] ran = {false};
        filter.doFilter(request, response, (req, res) -> ran[0] = true);
        assertThat(ran[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"PRV-1054\"")
                .contains("pravaha.http.max-anonymous-body");
    }

    @Test
    void aBodyWithNoDeclaredLengthIsStoppedAsItIsRead() throws Exception {
        RequestLimitFilter filter = new RequestLimitFilter(1024, 4096, 8, OPEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/queries/validate") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked
            }
        };
        request.setContent(new byte[5000]);
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> assertThatThrownBy(() -> req.getInputStream().readAllBytes())
                        .isInstanceOf(RequestLimitFilter.BodyTooLargeException.class)
                        .hasMessageContaining("PRV-1054")
                        .hasMessageContaining("pravaha.http.max-request-body"));
    }
}
