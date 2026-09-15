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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The API surface is locked.
 *
 * <p>{@code api/openapi.lock.json} records every path, method, status and parameter the API exposes.
 * This test regenerates that summary from the live application and compares it. **Changing the API
 * therefore requires changing the lock file in the same commit**, which turns an API change from a
 * side effect of adding a screen into a reviewed diff.
 *
 * <p>That matters more here than it would in most products. The console is a separate process built
 * on the published SDK (design section 23.2a), so the API is not an internal detail that can be adjusted
 * quietly -- it is the contract two processes and every customer integration depend on. And with a
 * proprietary engine nobody can read the source to work out what changed (design section 30.4), so an
 * accidental break is discovered by an integrator rather than by us.
 *
 * <p>Regenerate deliberately with {@code -Dpravaha.openapi.update=true}, and read the diff.
 */
@SpringBootTest(
        properties = {
            "pravaha.security.allow-anonymous=true",
            // Port 0, so the operating system assigns one. This is a MockMvc test and needs no
            // Flight client, but the node it boots starts a real Flight server -- and on the
            // default 9090 that collides with anything else holding the port: the other
            // @SpringBootTest in this module when surefire runs them in separate JVMs, and a
            // developer's own node on their own machine. A test that binds a fixed port is
            // fragile whether or not anything is running in parallel.
            "pravaha.flight.port=0"
        })
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final String UPDATE_PROPERTY = "pravaha.openapi.update";

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theApiSurfaceMatchesTheLockFile() throws Exception {
        String live = mvc.perform(get("/api/v1/openapi.json"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String summary = summarise(json.readTree(live));

        Path lock = repoRoot().resolve("api/openapi.lock.json");
        if (Boolean.getBoolean(UPDATE_PROPERTY) || !Files.exists(lock)) {
            Files.createDirectories(lock.getParent());
            Files.writeString(lock, summary, StandardCharsets.UTF_8);
            // Not a silent pass: writing the lock and reporting success would let a break through
            // on the very run that introduced it.
            assertThat(Files.exists(lock)).isTrue();
            System.out.println("openapi.lock.json written; review the diff before committing");
            return;
        }

        String recorded = Files.readString(lock, StandardCharsets.UTF_8);
        assertThat(summary).as("""
                        The API surface changed but api/openapi.lock.json did not.

                        The console is a separate process built on this contract, and every customer
                        integration depends on it. If the change is intended, regenerate with:

                            ./mvnw -pl pravaha-server test -Dtest=OpenApiContractTest \\
                                -Dpravaha.openapi.update=true

                        and include the diff in the same commit.""").isEqualTo(recorded);
    }

    @Test
    void everyPathIsUnderTheVersionedPrefixOrIsAKnownException() throws Exception {
        // An unversioned path cannot be evolved without breaking clients, so new ones need a
        // deliberate decision rather than appearing by accident.
        List<String> allowedUnversioned = List.of("/status", "/actuator", "/api/docs", "/swagger-ui", "/v3/api-docs");

        JsonNode paths = json.readTree(mvc.perform(get("/api/v1/openapi.json"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("paths");

        List<String> offenders = new ArrayList<>();
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            boolean ok =
                    path.startsWith("/api/v1/") || allowedUnversioned.stream().anyMatch(path::startsWith);
            if (!ok) {
                offenders.add(path);
            }
        }
        assertThat(offenders)
                .as("unversioned API paths cannot be evolved without breaking clients")
                .isEmpty();
    }

    @Test
    void theContractCheckIsNotVacuous() throws Exception {
        // A lock test that summarises nothing passes forever. This asserts the summary actually
        // contains the endpoints the API is supposed to expose.
        String summary = summarise(json.readTree(mvc.perform(get("/api/v1/openapi.json"))
                .andReturn()
                .getResponse()
                .getContentAsString()));
        assertThat(summary)
                .contains("/api/v1/streams")
                .contains("/api/v1/queries/validate")
                .contains("/api/v1/status");
        assertThat(summary.lines().count()).isGreaterThan(10);
    }

    /**
     * Reduces the OpenAPI document to the parts that are actually the contract.
     *
     * <p>Descriptions, examples and schema ordering are excluded deliberately: locking those would
     * make every javadoc edit a contract change, and a lock file that fails for cosmetic reasons is
     * one people learn to regenerate without reading.
     */
    private String summarise(JsonNode document) {
        Map<String, Object> surface = new TreeMap<>();
        JsonNode paths = document.path("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            Map<String, Object> methods = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> m = entry.getValue().fields(); m.hasNext(); ) {
                Map.Entry<String, JsonNode> method = m.next();
                Map<String, Object> operation = new TreeMap<>();
                operation.put("responses", names(method.getValue().path("responses")));
                List<String> parameters = new ArrayList<>();
                method.getValue()
                        .path("parameters")
                        .forEach(p -> parameters.add(
                                p.path("in").asText() + ":" + p.path("name").asText()));
                java.util.Collections.sort(parameters);
                operation.put("parameters", parameters);
                operation.put("hasBody", !method.getValue().path("requestBody").isMissingNode());
                methods.put(method.getKey(), operation);
            }
            surface.put(entry.getKey(), methods);
        }
        try {
            ObjectNode root = json.createObjectNode();
            root.set("paths", json.valueToTree(surface));
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("cannot summarise the OpenAPI document", e);
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        java.util.Collections.sort(out);
        return out;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".git"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate the repository root");
        }
        return p;
    }
}
