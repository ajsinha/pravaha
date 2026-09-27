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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With forced change configured, a first sign-in may change its password and nothing else (PRV-7018). */
@SpringBootTest(
        properties = {
            "pravaha.flight.port=0",
            "pravaha.security.authentication=token",
            "pravaha.security.policy=authenticated",
            "pravaha.security.audit=memory",
            "pravaha.identity.enabled=true",
            "pravaha.identity.dev=true",
            "pravaha.identity.password.force-change=true"
        })
@AutoConfigureMockMvc
class IdentityForcedChangeHttpTest {

    @DynamicPropertySource
    static void store(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("pravaha-identity-forced");
        registry.add(
                "pravaha.identity.store", () -> dir.resolve("identity.journal").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void aSessionThatMustChangeItsPasswordCanDoThatAndNothingElse() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"pravaha-dev-admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body)
                .get("token")
                .asText();

        mvc.perform(get("/api/v1/streams").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-7018"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":\"pravaha-dev-admin\",\"new\":\"Correct-horse-9\"}"))
                .andExpect(status().isNoContent());
        // The session held back is not the one that goes on: a new sign-in with the new password is.
        String fresh = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"admin\",\"password\":\"Correct-horse-9\"}"))
                        .andExpect(jsonPath("$.mustChangePassword").value(false))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("token")
                .asText();
        mvc.perform(get("/api/v1/streams").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk());
    }
}
