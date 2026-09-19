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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The administrative endpoints over the real HTTP stack of a node configured the way a secured
 * deployment is: token authentication, the {@code authenticated} policy, a readable audit trail.
 *
 * <p>Through MockMvc rather than by calling controllers, because the statuses are the contract: a
 * reader who may read every view gets {@code 403} from {@code /api/v1/audit}, an admin gets the
 * trail, and the reader's refused attempt is in it.
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
            "pravaha.security.tokens.reader-token.roles=analyst"
        })
@AutoConfigureMockMvc
class AdminHttpTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void aReaderIsRefusedTheAuditTrailWithA403AndAnAdminReadsTheRefusal() throws Exception {
        mvc.perform(get("/api/v1/streams").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRV-7002"));

        mvc.perform(get("/api/v1/audit")
                        .param("principal", "ann")
                        .param("action", "http.audit.read")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recording").value(true))
                .andExpect(jsonPath("$.sink").value("memory"))
                .andExpect(jsonPath("$.events[0].principal").value("ann"))
                .andExpect(jsonPath("$.events[0].decision").value("DENY"));
    }

    @Test
    void withoutACredentialTheAuditTrailIsA401BeforeThePolicyIsAsked() throws Exception {
        mvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedParameterIsA400NamingIt() throws Exception {
        mvc.perform(get("/api/v1/audit").param("since", "yesterday").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1051"));
    }

    @Test
    void permissionsAnswerForTheCaller() throws Exception {
        mvc.perform(get("/api/v1/me/permissions").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("ann"))
                .andExpect(jsonPath("$.policy").value("authenticated"))
                .andExpect(jsonPath("$.register.allowed").value(true))
                .andExpect(jsonPath("$.readAudit.allowed").value(false));
        mvc.perform(get("/api/v1/me/permissions").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAudit.allowed").value(true));
    }

    @Test
    void pluginsListTheClasspathPlugins() throws Exception {
        mvc.perform(get("/api/v1/plugins").header("Authorization", "Bearer reader-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("filesystem")));
    }
}
