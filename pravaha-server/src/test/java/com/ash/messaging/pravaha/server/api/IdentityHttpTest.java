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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-052 stage 2: the identity contract the console and the CLI are written against, over HTTP. */
@SpringBootTest(
        properties = {
            "pravaha.flight.port=0",
            "pravaha.security.authentication=token",
            "pravaha.security.policy=authenticated",
            "pravaha.security.audit=memory",
            "pravaha.identity.enabled=true",
            "pravaha.identity.environment=qa",
            "pravaha.identity.dev=true"
        })
@AutoConfigureMockMvc
class IdentityHttpTest {

    static final String GOOD = "Correct-horse-9";

    @DynamicPropertySource
    static void store(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("pravaha-identity-http");
        registry.add(
                "pravaha.identity.store", () -> dir.resolve("identity.journal").toString());
    }

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    private ResultActions postJson(String path, String token, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private String login(String user, String password) throws Exception {
        String body = postJson(
                        "/api/v1/auth/login", null, "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa").value("ok"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    @Test
    void theWholeLifeOfAnAccountOverHttp() throws Exception {
        String admin = login("admin", "pravaha-dev-admin");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.via").value("session"));

        postJson(
                        "/api/v1/users",
                        admin,
                        "{\"username\":\"ana\",\"roles\":[\"analyst\",\"operator\"],\"password\":\"" + GOOD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
        postJson("/api/v1/users", admin, "{\"username\":\"bob\",\"roles\":[\"analyst\"],\"password\":\"short\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-7012"));

        String ana = login("ana", GOOD);
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + ana))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-7002"));

        // A key, used as a bearer anywhere, then revoked.
        String issued = postJson(
                        "/api/v1/keys", ana, "{\"name\":\"reports\",\"roles\":[\"analyst\"],\"expiresDays\":30}")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode key = json.readTree(issued);
        assertThat(key.get("key").asText())
                .startsWith("prv_qa_" + key.get("keyId").asText() + "_");
        mvc.perform(get("/api/v1/streams")
                        .header("Authorization", "Bearer " + key.get("key").asText()))
                .andExpect(status().isOk());
        postJson("/api/v1/keys", ana, "{\"name\":\"wide\",\"roles\":[\"admin\"]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-7015"));
        mvc.perform(get("/api/v1/keys").header("Authorization", "Bearer " + ana))
                .andExpect(jsonPath("$.keys[0].status").value("active"))
                .andExpect(jsonPath("$.keys[0].key").doesNotExist());
        mvc.perform(delete("/api/v1/keys/" + key.get("keyId").asText()).header("Authorization", "Bearer " + ana))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/streams")
                        .header("Authorization", "Bearer " + key.get("key").asText()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PRV-7001"));

        // Sessions, and signing out.
        mvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer " + ana))
                .andExpect(jsonPath("$.sessions[0].current").value(true));
        postJson("/api/v1/auth/logout", ana, "{}").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + ana))
                .andExpect(status().isUnauthorized());

        // A reset token, redeemed without a credential.
        String reset = postJson("/api/v1/users/ana/password-reset", admin, "{}")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = json.readTree(reset).get("resetToken").asText();
        postJson("/api/v1/auth/reset/redeem", null, "{\"token\":\"" + token + "\",\"password\":\"Brand-new-pass-7\"}")
                .andExpect(status().isNoContent());
        postJson("/api/v1/auth/reset/redeem", null, "{\"token\":\"" + token + "\",\"password\":\"Brand-new-pass-8\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-7017"));
        login("ana", "Brand-new-pass-7");
    }

    @Test
    void aWrongPasswordIs401AndTheFifthLocksTheAccountWith423() throws Exception {
        String admin = login("admin", "pravaha-dev-admin");
        postJson(
                        "/api/v1/users",
                        admin,
                        "{\"username\":\"carl\",\"roles\":[\"analyst\"],\"password\":\"" + GOOD + "\"}")
                .andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/auth/login", null, "{\"username\":\"carl\",\"password\":\"Wrong-password-1\"}")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("PRV-7010"));
        }
        postJson("/api/v1/auth/login", null, "{\"username\":\"carl\",\"password\":\"" + GOOD + "\"}")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("PRV-7011"));
        postJson("/api/v1/auth/login", null, "{\"username\":\"nobody\",\"password\":\"" + GOOD + "\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PRV-7010"));
    }
}
