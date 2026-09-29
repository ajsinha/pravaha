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

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CAT201-1: the catalogue's writes answer the status the OpenAPI document states, called for real on a
 * node the catalogue governs. {@code OpenApiContractTest} holds every handler's declaration to the
 * document; this holds the catalogue's declarations to what the handlers do.
 */
@SpringBootTest(
        properties = {
            "pravaha.flight.port=0",
            "pravaha.security.authentication=token",
            "pravaha.security.policy=authenticated",
            "pravaha.security.tokens.ops-token.id=ops",
            "pravaha.security.tokens.ops-token.tenant=acme",
            "pravaha.security.tokens.ops-token.roles=admin",
            "pravaha.catalog.enabled=true",
            "pravaha.catalog.authority=catalog"
        })
@AutoConfigureMockMvc
class WriteStatusContractTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyCatalogueWriteAnswersTheStatusItsDocumentStates() throws Exception {
        JsonNode paths = json.readTree(mvc.perform(get("/api/v1/openapi.json"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("paths");

        answers(paths, "post", "/api/v1/streams", post("/api/v1/streams"), """
                {"name":"txn","schema":"id:STRING,region:STRING,amount:INT64"}""");
        answers(paths, "post", "/api/v1/catalog/namespaces", post("/api/v1/catalog/namespaces"), """
                {"name":"sales","description":"the sales desk"}""");
        answers(paths, "post", "/api/v1/catalog/grants", post("/api/v1/catalog/grants"), """
                {"object":"acme.sales","privileges":["USE"],"granteeType":"ROLE","grantee":"analyst"}""");
        answers(paths, "post", "/api/v1/catalog/policies", post("/api/v1/catalog/policies"), """
                {"name":"eu_only","kind":"ROW_FILTER","expression":"region = 'EU'"}""");
        answers(
                paths,
                "post",
                "/api/v1/catalog/policies/{name}/bindings",
                post("/api/v1/catalog/policies/eu_only/bindings"),
                """
                {"tag":"pii"}""");
        answers(
                paths,
                "delete",
                "/api/v1/catalog/grants",
                delete("/api/v1/catalog/grants")
                        .param("object", "acme.sales")
                        .param("privileges", "USE")
                        .param("granteeType", "ROLE")
                        .param("grantee", "analyst"),
                "");
    }

    private void answers(
            JsonNode paths, String verb, String documented, MockHttpServletRequestBuilder call, String body)
            throws Exception {
        int actual = mvc.perform(call.header("Authorization", "Bearer ops-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
        List<String> statuses =
                OpenApiContractTest.names(paths.path(documented).path(verb).path("responses"));
        assertThat(actual).as("%s %s succeeded", verb, documented).isBetween(200, 299);
        assertThat(statuses)
                .as("%s %s answered %d; the document must say so", verb, documented, actual)
                .contains(String.valueOf(actual));
    }
}
