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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.ash.messaging.pravaha.server.ai.AiFolderWatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The AI layer's REST face over the real HTTP stack of a secured node with the layer switched on and a
 * {@code mock} provider (no network): the chat endpoint through the policy, the admin endpoints refused to a
 * non-admin, health without a network call, the test connection's rate limit, every refusal with its code
 * and status, the audit event of each call without its prompt, a literal key in a file refused, and a file
 * added while the node runs picked up.
 */
@SpringBootTest(
        properties = {
            "pravaha.flight.port=0",
            "pravaha.security.authentication=token",
            "pravaha.security.policy=authenticated",
            "pravaha.security.audit=memory",
            "pravaha.security.tokens.admin-token.id=root",
            "pravaha.security.tokens.admin-token.roles=admin",
            "pravaha.security.tokens.reader-token.id=ann",
            "pravaha.security.tokens.reader-token.roles=analyst",
            "pravaha.ai.enabled=true",
            "pravaha.ai.reload-interval=1h",
            "pravaha.ai.default-model=mock/m",
            "pravaha.ai.test-per-minute=2",
            "pravaha.ai.policy.redact[0]=salary",
            "pravaha.ai.policy.features.[api\\:secret].roles[0]=admin",
            "pravaha.ai.policy.features.draft.roles[0]=admin"
        })
@AutoConfigureMockMvc
class AiEndpointsHttpTest {

    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("pravaha-ai-http");
            Files.writeString(DIR.resolve("mock.yaml"), """
                    type: mock
                    default-model: m
                    models:
                      - name: m
                        cost-per-1k-input: 1.0
                    mock:
                      rules:
                        - match: "boom"
                          error: "scripted failure"
                    """);
            Files.writeString(DIR.resolve("literal.yaml"), "type: openai\napi-key: sk-literal-123\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void dir(DynamicPropertyRegistry registry) {
        registry.add("pravaha.ai.dir", DIR::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AiFolderWatch watch;

    private static String chat(String prompt, String feature) {
        return "{\"prompt\":\"" + prompt + "\",\"feature\":\"" + feature + "\"}";
    }

    @Test
    void theFeaturesStatusOpensEachFeatureByThePolicysRolesAndNeverNamesAKey() throws Exception {
        mvc.perform(get("/api/v1/ai/features").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.values").value("labels-only"))
                .andExpect(jsonPath("$.features.ask").value(true))
                .andExpect(jsonPath("$.features.draft").value(false))
                .andExpect(jsonPath("$.features.alert").value(true))
                .andExpect(jsonPath("$.features.explain").value(true))
                .andExpect(jsonPath("$.features.narrative").value(true))
                .andExpect(jsonPath("$.features.page").value(true));
        mvc.perform(get("/api/v1/ai/features").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.draft").value(true));
    }

    @Test
    void aSignedInCallerChatsThroughThePolicyAndTheCallIsAuditedWithoutItsPrompt() throws Exception {
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chat("my salary: 90000, explain", "explain")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("echo: my salary: •••, explain"))
                .andExpect(jsonPath("$.usage.promptTokens").isNumber())
                .andExpect(jsonPath("$.finishReason").value("stop"));
        mvc.perform(get("/api/v1/audit")
                        .param("action", "ai.call")
                        .param("principal", "ann")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].target").value("mock/m"))
                .andExpect(jsonPath("$.events[0].decision").value("ALLOW"))
                .andExpect(jsonPath("$.events[0].reason").value("ok"))
                .andExpect(jsonPath("$.events[0].detail", containsString("feature=api:other")))
                .andExpect(jsonPath("$.events[0].detail", containsString("promptTokens=")))
                .andExpect(jsonPath("$.events[0].detail", not(containsString("salary"))))
                .andExpect(jsonPath("$.events[0].detail", not(containsString("echo"))));
        mvc.perform(get("/api/v1/ai/models").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ref", hasItem("mock/m")));
    }

    @Test
    void everyRefusalCarriesItsCodeAndItsStatus() throws Exception {
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1050"));
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"x\",\"model\":\"nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRV-1082"));
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chat("boom", "x")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PRV-1087"));
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chat("hello", "secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-1084"));
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chat("hello", "secret")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"wizard\",\"content\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1051"));
        mvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chat("x", "y")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminEndpointsAreRefusedToANonAdminAndShowHealthWithoutCallingAnything() throws Exception {
        mvc.perform(get("/api/v1/admin/ai").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-7002"));
        mvc.perform(post("/api/v1/admin/ai/test")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ai").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.types", hasItem("bedrock")))
                .andExpect(jsonPath("$.providers[0].name").value("mock"))
                .andExpect(jsonPath("$.providers[0].keySet").value(false))
                .andExpect(jsonPath("$.providers[0].health.status").isString())
                .andExpect(jsonPath("$.fileErrors['literal.yaml']", containsString("placeholder")))
                .andExpect(jsonPath("$.fileErrors['literal.yaml']", not(containsString("sk-literal"))));
    }

    @Test
    void theTestConnectionAnswersAndIsRateLimitedPerAdministrator() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/admin/ai/test")
                            .header("Authorization", "Bearer admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"mock/m\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.text").value("echo: Reply with the single word: ready"));
        }
        mvc.perform(post("/api/v1/admin/ai/test")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PRV-1085"));
        mvc.perform(get("/api/v1/admin/ai").header("Authorization", "Bearer admin-token"))
                .andExpect(jsonPath("$.providers[0].health.status").value("UP"));
    }

    @Test
    void aProviderFileAddedWhileTheNodeRunsIsPickedUp() throws Exception {
        Files.writeString(DIR.resolve("second.yaml"), "type: mock\nmodels:\n  - name: two\n");
        assertThat(watch.tick()).isTrue();
        assertThat(watch.tick()).isFalse();
        mvc.perform(get("/api/v1/ai/models").header("Authorization", "Bearer reader-token"))
                .andExpect(jsonPath("$[*].ref", hasItem("second/two")));
        mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hi\",\"model\":\"second/two\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("echo: hi"));
    }
}
