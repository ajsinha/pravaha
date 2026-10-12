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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A node as it starts, with the AI layer off: every plain-English feature answers {@code PRV-1080} (409). */
@SpringBootTest(
        properties = {
            "pravaha.flight.port=0",
            "pravaha.security.authentication=token",
            "pravaha.security.policy=authenticated",
            "pravaha.security.tokens.reader-token.id=ann",
            "pravaha.security.tokens.reader-token.roles=analyst"
        })
@AutoConfigureMockMvc
class AiFeaturesOffHttpTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void everyFeatureIsRefusedWhileTheLayerIsOff() throws Exception {
        Map<String, String> bodies = Map.of(
                "/api/v1/ai/ask", "{\"question\":\"total spend\"}",
                "/api/v1/ai/draft", "{\"description\":\"spend per minute\"}",
                "/api/v1/ai/alert-draft", "{\"description\":\"tell me when spend passes 500\"}",
                "/api/v1/ai/explain", "{\"kind\":\"sql\",\"sql\":\"SELECT 1\"}",
                "/api/v1/ai/narrate", "{\"view\":\"v\",\"changes\":[]}",
                "/api/v1/ai/page", "{\"screen\":\"s\",\"about\":\"a\",\"question\":\"q\"}");
        for (Map.Entry<String, String> e : bodies.entrySet()) {
            mvc.perform(post(e.getKey())
                            .header("Authorization", "Bearer reader-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(e.getValue()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRV-1080"));
        }
    }

    @Test
    void theFeaturesStatusSaysOffAndOpensNothing() throws Exception {
        mvc.perform(get("/api/v1/ai/features").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.values").value("labels-only"))
                .andExpect(jsonPath("$.features.ask").value(false))
                .andExpect(jsonPath("$.features.page").value(false));
        mvc.perform(get("/api/v1/ai/features")).andExpect(status().isUnauthorized());
    }
}
