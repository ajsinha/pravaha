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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public API, exercised the way a client uses it.
 *
 * <p>Through the full HTTP stack rather than by calling controllers directly, because the things
 * that break a client are serialisation, status codes and error shape -- none of which a direct
 * method call exercises.
 *
 * <p>Anonymous access is switched on explicitly, because the node otherwise refuses to start a
 * server that serves everything to unauthenticated callers. These tests are about the API's shape,
 * not its security model -- and saying so here means that if the refusal is ever weakened, this
 * property stops being necessary rather than these tests quietly covering a different configuration.
 * {@code ApiSecurityTest} is where authentication itself is pinned.
 */
@SpringBootTest(properties = "pravaha.security.allow-anonymous=true")
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final String SCHEMA = "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void registerStream() throws Exception {
        // Idempotent-ish: schema versions are immutable, so a second registration of the same
        // version is refused. Tests share the context, so tolerate that.
        try {
            mvc.perform(post("/api/v1/streams")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new StreamController.RegisterStreamRequest("txn", SCHEMA))));
        } catch (Exception ignored) {
            // already registered by an earlier test in this context
        }
    }

    @Test
    void listsAndFetchesStreams() throws Exception {
        mvc.perform(get("/api/v1/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("txn"))
                .andExpect(jsonPath("$[0].fieldCount").value(4));

        mvc.perform(get("/api/v1/streams/txn"))
                .andExpect(status().isOk())
                // The SQL rendering, not a Java enum name: a client should see what SQL calls it.
                .andExpect(jsonPath("$.fields[0].type").value("INT64 NOT NULL"))
                .andExpect(jsonPath("$.fields[0].nullable").value(false));
    }

    @Test
    void anUnknownStreamIsA400WithTheErrorCodeAndAHelpUrl() throws Exception {
        mvc.perform(get("/api/v1/streams/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-2003"))
                .andExpect(jsonPath("$.helpUrl").value("https://docs.pravaha.io/errors/PRV-2003"))
                .andExpect(jsonPath("$.path").value("/api/v1/streams/nope"));
    }

    @Test
    void aValidQueryValidates() throws Exception {
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new QueryController.ValidateRequest(
                                "SELECT user_id, amount FROM txn WHERE amount > 100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.outputFields[0].name").value("user_id"))
                .andExpect(jsonPath("$.diagnostics").isEmpty());
    }

    @Test
    void anInvalidQueryIsA200WithValidFalse() throws Exception {
        // Not a 400. A syntax error while someone is mid-word is a normal state of an editor, and
        // returning an error status would make every keystroke look like a failure in the client's
        // logs and metrics.
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new QueryController.ValidateRequest("SELECT nope FROM txn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("PRV-2002"))
                .andExpect(jsonPath("$.diagnostics[0].helpUrl").exists());
    }

    @Test
    void validationReportsItsOwnLatency() throws Exception {
        // The console calls this on every keystroke burst and design 24.1 targets under 50 ms, so
        // the endpoint reports what it actually took rather than leaving the client to guess.
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elapsedMicros").isNumber());
    }

    @Test
    void explainReturnsBothLevels() throws Exception {
        mvc.perform(post("/api/v1/queries/explain?level=physical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn WHERE amount > 100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("physical"))
                .andExpect(jsonPath("$.plan").value(org.hamcrest.Matchers.containsString("Scan(txn)")));

        mvc.perform(post("/api/v1/queries/explain?level=logical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value(org.hamcrest.Matchers.containsString("Logical")));
    }

    @Test
    void anUnboundedGroupByIsRefusedWithAnActionableMessage() throws Exception {
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new QueryController.ValidateRequest(
                                "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("PRV-2050"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("Bound it with a window")));
    }

    @Test
    void statusIsAvailableAsJson() throws Exception {
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.engineState").exists())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber());
    }

    @Test
    void statusIsAlsoASelfContainedHtmlPageServedByTheEngine() throws Exception {
        // The console is a separate process (design 23.2a). A console that is the only way to see
        // anything makes itself a single point of failure for diagnosis, so this page has no
        // template engine, no static assets, no JavaScript and no external font -- it must render
        // from one HTTP response on a machine where little else works.
        String html = mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html)
                .contains("Pravaha")
                .contains("Ask once. Answer always.")
                .contains("<style>")
                .doesNotContain("<script")
                .doesNotContain("http://")
                .doesNotContain("https://fonts");
    }

    @Test
    void theOpenApiDocumentIsPublished() throws Exception {
        mvc.perform(get("/api/v1/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/streams']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/validate']").exists());
    }
}
