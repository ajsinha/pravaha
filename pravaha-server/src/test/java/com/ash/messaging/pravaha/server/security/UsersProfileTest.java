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

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PERMISSIVEUSERS-1: the profiles the guides start the console's engine with, {@code dev,users}, as
 * shipped. Under the node's default {@code permissive} policy every signed-in user could pause any
 * view and read the audit trail; the {@code users} profile now sets {@code authenticated}, and a user
 * without the {@code admin} role reads views but administers only their own and reads no trail. The
 * catalogue is on, as it was on the node where the finding was made.
 */
@SpringBootTest(properties = {"pravaha.flight.port=0", "pravaha.security.audit=memory"})
@ActiveProfiles({"dev", "users"})
@AutoConfigureMockMvc
class UsersProfileTest {

    @DynamicPropertySource
    static void home(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("pravaha-users-profile");
        registry.add(
                "pravaha.identity.store", () -> dir.resolve("identity.journal").toString());
        registry.add("pravaha.catalog.enabled", () -> "true");
        registry.add(
                "pravaha.catalog.journal", () -> dir.resolve("catalog.journal").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private final ObjectMapper json = new ObjectMapper();

    private String login(String user, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    @Test
    void theUsersProfileIsAuthenticatedNotPermissive() throws Exception {
        assertThat(security.trimmedPolicy()).isEqualTo("authenticated");
        assertThat(security.authenticates()).isTrue();

        String admin = login("admin", "pravaha-dev-admin");
        mvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"roles\":[\"guest\"],\"password\":\"Bob-password-12\"}"))
                .andExpect(status().isOk());
        String bob = login("bob", "Bob-password-12");

        // The audit trail is the administrators', not every signed-in user's.
        mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-7002"));
        mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // Each tenant's use is its own business: bob is shown his own tenant, the administrator all.
        mvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("own"));
        mvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"));
    }
}
