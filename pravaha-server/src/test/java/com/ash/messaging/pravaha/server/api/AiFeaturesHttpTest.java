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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plain-English features' REST face on a secured node with the AI layer on and a {@code mock} provider:
 * signed-in only, answers never cached, the feature policy's refusal and the engine's capability gate with
 * their codes, a malformed request refused before any model call, and the feature's own audit event. What the
 * features do with a real catalogue, row filters and masks is {@code AiFeaturesEndToEndTest} (pravaha-it).
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
            "pravaha.ai.policy.features.[explain].roles[0]=admin"
        })
@AutoConfigureMockMvc
class AiFeaturesHttpTest {

    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("pravaha-ai-features-http");
            Files.writeString(DIR.resolve("mock.yaml"), """
                    type: mock
                    default-model: m
                    models:
                      - name: m
                    mock:
                      rules:
                        - match: "TASK: page"
                          reply: "This page shows the node's status."
                        - match: "TASK: explain-refusal"
                          reply: "The view is not there."
                    """);
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

    @Test
    void askAboutThisPageAnswersFromThePageAndIsNeverCachedAndAudited() throws Exception {
        mvc.perform(
                        post("/api/v1/ai/page")
                                .header("Authorization", "Bearer reader-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"screen\":\"status\",\"about\":\"The node's health.\",\"question\":\"what is this?\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.answer").value("This page shows the node's status."))
                .andExpect(jsonPath("$.screen").value("status"))
                .andExpect(jsonPath("$.sources[0]").value("this page's About text"));
        mvc.perform(get("/api/v1/audit").param("action", "ai.page").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].principal").value("ann"))
                .andExpect(jsonPath("$.events[0].detail", containsString("questionChars=13")));
    }

    @Test
    void signedInOnlyAndEachRefusalCarriesItsCode() throws Exception {
        mvc.perform(post("/api/v1/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        // No view anyone may read: the capability gate refuses before the model is asked.
        mvc.perform(post("/api/v1/ai/ask")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spend\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-7002"));
        mvc.perform(post("/api/v1/ai/ask")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1051"));
        mvc.perform(post("/api/v1/ai/explain")
                        .header("Authorization", "Bearer reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"refusal\",\"code\":\"PRV-2002\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-1084"));
        mvc.perform(post("/api/v1/ai/explain")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"refusal\",\"code\":\"PRV-2002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").value("The view is not there."))
                .andExpect(jsonPath("$.code").value("PRV-2002"));
        mvc.perform(post("/api/v1/ai/explain")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"poem\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/ai/narrate")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":\"nowhere\",\"changes\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRV-4023"));
    }
}
