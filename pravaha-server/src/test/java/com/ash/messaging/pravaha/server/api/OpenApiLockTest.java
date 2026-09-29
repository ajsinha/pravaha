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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPENAPILOCK-1: the lock records body fields, so changing one is caught.
 *
 * <p>Adding {@code readsFrom} and {@code dependants} to the query detail left the lock unchanged and
 * the contract test passed; a renamed or removed field would have passed too. These change one small
 * document the way a DTO edit changes the real one and read what the lock makes of it.
 */
class OpenApiLockTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One read, one write; the detail nests an object and an array of objects through $refs. */
    private static final String DOCUMENT = """
            {"paths": {
              "/api/v1/queries/{name}": {"get": {
                "parameters": [{"in": "path", "name": "name"}],
                "responses": {
                  "200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/QueryDetail"}}}},
                  "default": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}}},
              "/api/v1/streams": {"post": {
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Register"}}}},
                "responses": {"201": {"content": {"application/json": {"schema": {
                  "type": "array", "items": {"$ref": "#/components/schemas/Owner"}}}}}}}}},
             "components": {"schemas": {
               "QueryDetail": {"type": "object", "required": ["name", "rowsIn"], "properties": {
                 "name": {"type": "string"},
                 "rowsIn": {"type": "integer", "format": "int64"},
                 "owner": {"$ref": "#/components/schemas/Owner"},
                 "readsFrom": {"type": "array", "items": {"type": "string"}},
                 "failure": {"$ref": "#/components/schemas/ApiError"}}},
               "Owner": {"type": "object", "required": ["id"], "properties": {
                 "id": {"type": "string"}, "tenant": {"type": "string"}}},
               "ApiError": {"type": "object", "properties": {
                 "code": {"type": "string"}, "message": {"type": "string"}}},
               "Register": {"type": "object", "required": ["name"], "properties": {
                 "name": {"type": "string"}, "schema": {"type": "string"}}}}}}
            """;

    @Test
    void theLockRecordsEveryBodyFieldFlattenedWithTypeAndRequiredFlag() throws Exception {
        JsonNode lock = JSON.readTree(OpenApiLock.summarise(JSON.readTree(DOCUMENT)));

        assertThat(lock.path("schemas").path("QueryDetail").toString())
                .contains("\"name\":\"required string\"")
                .contains("\"rowsIn\":\"required integer/int64\"")
                .contains("\"owner.id\":\"required string\"")
                .contains("\"owner.tenant\":\"optional string\"")
                .contains("\"readsFrom[]\":\"optional string\"")
                .contains("\"failure.code\":\"optional string\"");
        assertThat(lock.path("paths")
                        .path("/api/v1/streams")
                        .path("post")
                        .path("responseBodies")
                        .path("201")
                        .asText())
                .isEqualTo("Owner[]");
        assertThat(lock.path("paths")
                        .path("/api/v1/streams")
                        .path("post")
                        .path("requestBody")
                        .asText())
                .isEqualTo("Register");
        assertThat(OpenApiLock.breakingChanges(lock, lock)).isEmpty();
    }

    @Test
    void aRemovedResponseFieldIsABreak() throws Exception {
        List<String> breaks = changes(
                DOCUMENT.replace("\"readsFrom\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}},", ""));
        assertThat(breaks)
                .containsExactly("GET /api/v1/queries/{name} 200: readsFrom[] was removed or renamed "
                        + "(was optional string)");
    }

    @Test
    void aRenamedNestedFieldIsABreak() throws Exception {
        assertThat(changes(DOCUMENT.replace("\"tenant\": {", "\"tenantId\": {")))
                .contains("GET /api/v1/queries/{name} 200: owner.tenant was removed or renamed (was optional string)")
                .contains("POST /api/v1/streams 201: [].tenant was removed or renamed (was optional string)");
    }

    @Test
    void aRetypedFieldIsABreak() throws Exception {
        List<String> breaks = changes(DOCUMENT.replace(
                "\"rowsIn\": {\"type\": \"integer\", \"format\": \"int64\"}", "\"rowsIn\": {\"type\": \"string\"}"));
        assertThat(breaks)
                .containsExactly("GET /api/v1/queries/{name} 200: rowsIn changed type from integer/int64 to string");
    }

    @Test
    void aNewlyRequiredRequestFieldIsABreak() throws Exception {
        assertThat(changes(DOCUMENT.replace(
                        "\"Register\": {\"type\": \"object\", \"required\": [\"name\"]",
                        "\"Register\": {\"type\": \"object\", \"required\": [\"name\", \"schema\"]")))
                .containsExactly("POST /api/v1/streams request: schema is newly required");
    }

    @Test
    void anAddedOptionalResponseFieldIsNoBreakButStillChangesTheLock() throws Exception {
        String added = DOCUMENT.replace(
                "\"rowsIn\": {\"type\": \"integer\"",
                "\"dependants\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}}, "
                        + "\"rowsIn\": {\"type\": \"integer\"");
        assertThat(added).isNotEqualTo(DOCUMENT);
        assertThat(changes(added)).isEmpty();
        assertThat(OpenApiLock.summarise(JSON.readTree(added)))
                .as("the contract test fails on this difference, asking for the lock to be regenerated")
                .isNotEqualTo(OpenApiLock.summarise(JSON.readTree(DOCUMENT)))
                .contains("\"dependants[]\" : \"optional string\"");
    }

    private static List<String> changes(String changed) throws Exception {
        assertThat(changed).as("the fixture edit must apply").isNotEqualTo(DOCUMENT);
        JsonNode recorded = JSON.readTree(OpenApiLock.summarise(JSON.readTree(DOCUMENT)));
        JsonNode live = JSON.readTree(OpenApiLock.summarise(JSON.readTree(changed)));
        return OpenApiLock.breakingChanges(recorded, live);
    }
}
