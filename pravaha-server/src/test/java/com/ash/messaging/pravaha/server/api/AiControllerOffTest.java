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
package com.ash.messaging.pravaha.server.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.ai.DefaultLlmPolicy;
import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.ai.LlmPolicy;
import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.ProviderConfigLoader;
import com.ash.messaging.pravaha.ai.ProviderFactories;
import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.ai.AiProperties;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The layer as every node starts: switched off. Every AI call is refused with {@code PRV-1080} (409),
 * before anything is read or sent; the administrator still sees that it is off. And each AI code maps to
 * its status, and an audit row becomes an audit event that carries sizes and never text.
 */
class AiControllerOffTest {

    private static final SecurityPolicy OPEN = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.allow();
        }
    };

    private static MockHttpServletRequest as(Principal p) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, p);
        return r;
    }

    @Test
    void withTheLayerOffEveryCallIsRefusedWithItsOwnCode() {
        AiProperties off = new AiProperties();
        assertThat(off.isEnabled()).as("off by default").isFalse();
        LlmRegistry reg = new LlmRegistry(
                ProviderFactories.discover(List.of()), () -> new ProviderConfigLoader.Loaded(List.of(), Map.of()), "");
        reg.reload();
        AiController c = new AiController(
                off, new LlmGateway(reg, LlmPolicy.none(), off::isEnabled), new HttpAuthorizer(OPEN, AuditSink.NONE));
        Principal admin = new Principal("root", "public", java.util.Set.of("admin"), Map.of());
        assertThatThrownBy(() -> c.chat(
                        new AiController.ChatBody(null, null, null, "hi", null, null, null, null, null), as(admin)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.errorCode().code()).isEqualTo("PRV-1080");
                    assertThat(ApiExceptionHandler.statusFor(e.errorCode())).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThatThrownBy(() -> c.models(as(admin))).hasMessageStartingWith("PRV-1080");
        assertThatThrownBy(() -> c.test(null, as(admin))).hasMessageStartingWith("PRV-1080");
        assertThat(c.overview(as(admin)).enabled()).isFalse();
        assertThat(c.overview(as(admin)).providers()).isEmpty();
        assertThatThrownBy(() -> c.overview(as(Principal.of("ann")))).hasMessageContaining("admin role");
    }

    @Test
    void eachAiCodeHasItsStatus() {
        Map<Integer, HttpStatus> want = Map.of(
                1080,
                HttpStatus.CONFLICT,
                1081,
                HttpStatus.SERVICE_UNAVAILABLE,
                1082,
                HttpStatus.NOT_FOUND,
                1083,
                HttpStatus.BAD_REQUEST,
                1084,
                HttpStatus.FORBIDDEN,
                1085,
                HttpStatus.TOO_MANY_REQUESTS,
                1086,
                HttpStatus.GATEWAY_TIMEOUT,
                1087,
                HttpStatus.BAD_GATEWAY,
                1088,
                HttpStatus.UNPROCESSABLE_ENTITY);
        want.forEach((n, s) -> assertThat(ApiExceptionHandler.statusFor(new ErrorCode(n, "X")))
                .as("PRV-" + n)
                .isEqualTo(s));
    }

    @Test
    void anAuditRowBecomesAnEventWithSizesAndNeverText() {
        AuditEvent ok = com.ash.messaging.pravaha.server.ai.AiConfigurationAccess.event(
                new DefaultLlmPolicy.AuditRecord("ann", "api:explain", "mock", "m", 120, 40, 30, 10, 0.25, "ok", 17));
        assertThat(ok.action()).isEqualTo("ai.call");
        assertThat(ok.target()).isEqualTo("mock/m");
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.principal().id()).isEqualTo("ann");
        assertThat(ok.detail())
                .hasValueSatisfying(d -> assertThat(d)
                        .contains("feature=api:explain")
                        .contains("promptChars=120")
                        .contains("cost=0.250000")
                        .contains("ms=17"));
        AuditEvent refused = com.ash.messaging.pravaha.server.ai.AiConfigurationAccess.event(
                new DefaultLlmPolicy.AuditRecord("ann", "api:x", "mock", "m", 1, 0, 0, 0, 0, "rate_limited", 0));
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).isEqualTo("rate_limited");
        assertThat(Instant.now()).isAfterOrEqualTo(refused.at());
        assertThat(Duration.ofSeconds(15)).isEqualTo(AiController.TEST_CAP);
    }
}
