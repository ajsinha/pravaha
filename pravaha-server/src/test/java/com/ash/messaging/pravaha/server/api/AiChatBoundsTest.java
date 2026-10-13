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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.ai.CallContext;
import com.ash.messaging.pravaha.ai.ChatRequest;
import com.ash.messaging.pravaha.ai.ChatResponse;
import com.ash.messaging.pravaha.ai.DefaultLlmPolicy;
import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.ai.LlmPolicy;
import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.ModelInfo;
import com.ash.messaging.pravaha.ai.PolicySettings;
import com.ash.messaging.pravaha.ai.PromptMasker;
import com.ash.messaging.pravaha.ai.ProviderConfigLoader;
import com.ash.messaging.pravaha.ai.ProviderFactories;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.ai.AiProperties;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code POST /api/v1/ai/chat}'s bounds (AI QA, wave 7): the caller's {@code feature} cannot mint a policy
 * name of its own (AIQA-1: a fresh per-minute budget per request, or a feature no role list can close), and a
 * call cannot ask for more than the model's answer limit or carry an unbounded prompt (AIQA-2).
 */
class AiChatBoundsTest {

    private static final SecurityPolicy OPEN = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.allow();
        }
    };

    private static final Principal ANN = new Principal("ann", "public", Set.of("analyst"), Map.of());

    @TempDir
    Path dir;

    private final List<CallContext> contexts = new CopyOnWriteArrayList<>();
    private final List<ChatRequest> sent = new CopyOnWriteArrayList<>();

    private AiController controller(AiProperties ai, PolicySettings settings) throws Exception {
        Files.writeString(dir.resolve("mock.yaml"), """
                type: mock
                default-model: m
                models:
                  - name: m
                    max-tokens: 400
                  - name: open
                """);
        LlmRegistry reg = new LlmRegistry(
                ProviderFactories.discover(List.of()), () -> ProviderConfigLoader.load(dir, k -> null), "mock/m");
        reg.reload();
        DefaultLlmPolicy policy =
                new DefaultLlmPolicy(settings, new PromptMasker(List.of(), List.of()), null, Clock.systemUTC());
        LlmPolicy recording = new LlmPolicy() {
            @Override
            public ChatRequest before(CallContext ctx, ModelInfo model, ChatRequest request) {
                contexts.add(ctx);
                ChatRequest r = policy.before(ctx, model, request);
                sent.add(r);
                return r;
            }

            @Override
            public void after(
                    CallContext ctx,
                    ModelInfo model,
                    ChatRequest request,
                    @Nullable ChatResponse response,
                    @Nullable LlmException failure,
                    long millis) {
                policy.after(ctx, model, request, response, failure, millis);
            }
        };
        return new AiController(ai, new LlmGateway(reg, recording), new HttpAuthorizer(OPEN, AuditSink.NONE));
    }

    private static MockHttpServletRequest as(Principal p) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, p);
        return r;
    }

    private static AiController.ChatBody body(
            @Nullable String model, String prompt, @Nullable Integer maxTokens, @Nullable String feature) {
        return new AiController.ChatBody(model, null, null, prompt, null, maxTokens, null, null, feature);
    }

    @Test
    void aFeatureNameThePolicyDoesNotListSharesOneBudgetAndCanBeClosed() throws Exception {
        AiProperties ai = new AiProperties();
        AiProperties.Feature secret = new AiProperties.Feature();
        secret.setRoles(List.of("admin"));
        ai.getPolicy().getFeatures().put("api:secret", secret);
        AiController c = controller(
                ai,
                new PolicySettings(false, List.of(), false, 2, 0, 0, Map.of("api:secret", Set.of("admin")), Map.of()));
        c.chat(body(null, "one", null, "f1"), as(ANN));
        c.chat(body(null, "two", null, "f2"), as(ANN));
        // Before AIQA-1 each new name was its own policy feature, so its own per-minute bucket: never limited.
        assertThatThrownBy(() -> c.chat(body(null, "three", null, "f3"), as(ANN)))
                .hasMessageStartingWith("PRV-1085");
        assertThat(contexts).extracting(CallContext::feature).containsOnly(AiController.API_OTHER);
        // A listed name keeps its own: its roles still apply.
        assertThatThrownBy(() -> c.chat(body(null, "x", null, "secret"), as(ANN)))
                .hasMessageStartingWith("PRV-1084");
        assertThat(contexts.get(contexts.size() - 1).feature()).isEqualTo("api:secret");
    }

    @Test
    void anAnswerIsNeverAskedLongerThanTheModelAllowsAndAPromptIsBounded() throws Exception {
        AiController c = controller(new AiProperties(), PolicySettings.defaults());
        c.chat(body("mock/m", "hi", 1_000_000, null), as(ANN));
        assertThat(sent.get(0).maxTokens())
                .as("the file's max-tokens is the ceiling")
                .isEqualTo(400);
        c.chat(body("mock/open", "hi", 1_000_000, null), as(ANN));
        assertThat(sent.get(1).maxTokens())
                .as("no max-tokens in the file: pravaha.ai.features.max-tokens")
                .isEqualTo(new AiProperties().getFeatures().getMaxTokens());
        c.chat(body("mock/m", "hi", 50, null), as(ANN));
        assertThat(sent.get(2).maxTokens()).isEqualTo(50);
        assertThatThrownBy(() -> c.chat(body(null, "hi", 0, null), as(ANN))).hasMessageStartingWith("PRV-1051");
        String huge = "x".repeat(AiController.CHAT_MAX_CHARS + 1);
        assertThatThrownBy(() -> c.chat(body(null, huge, null, null), as(ANN)))
                .hasMessageStartingWith("PRV-1051")
                .hasMessageContaining("at most");
        assertThat(sent).hasSize(3); // neither refusal reached the policy, let alone a model
    }
}
