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

import java.time.Instant;
import java.util.List;

/**
 * The wire types of the public API.
 *
 * <p>Kept apart from the engine's own types on purpose. A DTO that is also a domain object couples
 * the wire format to internal structure, so an internal refactor becomes a breaking API change and
 * the API stops being safe to evolve. This layer is the seam that lets the engine change shape
 * without the contract moving (§23.2a).
 *
 * <p>Every field name here is part of the contract and appears in {@code openapi.lock.json}. Adding
 * one is a reviewed diff; removing or renaming one is a breaking change.
 */
public final class ApiDtos {

    private ApiDtos() {}

    /** A stream the engine can read. */
    public record StreamSummary(String name, int version, int fieldCount, List<FieldInfo> fields) {}

    public record FieldInfo(String name, String type, boolean nullable, int ordinal) {}

    /** A validation result. Deliberately not an error response: an invalid query is a normal answer. */
    public record ValidationResult(
            boolean valid, List<Diagnostic> diagnostics, List<FieldInfo> outputFields, long elapsedMicros) {

        public static ValidationResult ok(List<FieldInfo> outputFields, long elapsedMicros) {
            return new ValidationResult(true, List.of(), outputFields, elapsedMicros);
        }
    }

    /**
     * One problem with a query.
     *
     * <p>Carries the stable {@code PRV-nnnn} code and a documentation link, so the console can render
     * an actionable fix rather than a wall of text (design section 24.4).
     */
    public record Diagnostic(String code, String message, String helpUrl, String severity) {}

    /** A plan, at the level asked for. */
    public record ExplainResult(String level, String plan, List<FieldInfo> outputFields) {}

    /** What the node is and how it is doing. Served even when the console process is down. */
    public record NodeStatus(
            String instanceId,
            String version,
            String engineState,
            long uptimeSeconds,
            int registeredQueries,
            List<PluginStatus> plugins) {}

    public record PluginStatus(String name, String version, String health, String detail) {}

    /** The standard error body. Every non-2xx response is one of these and nothing else. */
    public record ApiError(String code, String message, String helpUrl, Instant timestamp, String path) {}
}
