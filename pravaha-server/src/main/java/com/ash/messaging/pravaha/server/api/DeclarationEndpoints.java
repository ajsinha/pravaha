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

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.folders.DeclarationKind;
import com.ash.messaging.pravaha.server.folders.DeclarationWrites;
import com.ash.messaging.pravaha.server.folders.DeclarationYaml;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The write half of the declaration endpoints, shared by {@code /api/v1/streams/{name}},
 * {@code /api/v1/connectors/{name}} and {@code /api/v1/declarations/{kind}/{name}} (ADR-064): who may,
 * the file a request means, and the answer.
 *
 * <p>Every write -- and every dry run, which names what uses the object -- needs what changing the object
 * needs: the {@code admin} role or a grant to administer it, as {@code POST /api/v1/streams} always has.
 */
final class DeclarationEndpoints {

    private final DeclarationWrites writes;
    private final HttpAuthorizer authorizer;

    DeclarationEndpoints(DeclarationWrites writes, HttpAuthorizer authorizer) {
        this.writes = writes;
        this.authorizer = authorizer;
    }

    /** {@code POST} (create) or {@code PUT} (update) of a stream file. */
    ResponseEntity<ApiDtos.DeclarationChange> writeStream(
            HttpServletRequest http,
            String name,
            DeclarationWrites.Action action,
            ApiDtos.@Nullable StreamFileRequest request,
            boolean dryRun) {
        Principal who = authorize(http, name);
        String yaml = streamYaml(name, request, who);
        return answer(writes.write(who, DeclarationKind.STREAM, name, action, yaml, dryRun));
    }

    /** {@code POST} or {@code PUT} of a connection, lookup or sink file. */
    ResponseEntity<ApiDtos.DeclarationChange> writeBinding(
            HttpServletRequest http,
            DeclarationKind kind,
            String name,
            DeclarationWrites.Action action,
            ApiDtos.@Nullable BindingFileRequest request,
            boolean dryRun) {
        Principal who = authorize(http, name);
        String yaml = bindingYaml(kind, name, request, who);
        return answer(writes.write(who, kind, name, action, yaml, dryRun));
    }

    ResponseEntity<ApiDtos.DeclarationChange> delete(
            HttpServletRequest http, DeclarationKind kind, String name, boolean dryRun) {
        Principal who = authorize(http, name);
        return answer(writes.delete(who, kind, name, dryRun));
    }

    ResponseEntity<ApiDtos.DeclarationChange> enable(
            HttpServletRequest http, DeclarationKind kind, String name, boolean enabled, boolean dryRun) {
        Principal who = authorize(http, name);
        return answer(writes.enable(who, kind, name, enabled, dryRun));
    }

    /** The file's text, for an editor; only to a caller who may change it. */
    ApiDtos.DeclarationText text(
            HttpServletRequest http, DeclarationKind kind, String name, java.util.List<String> dependents) {
        authorize(http, name);
        java.nio.file.Path path = writes.fileOf(kind, name)
                .orElseThrow(() -> new PravahaException(
                        ConfigErrors.DECLARATION_UNKNOWN,
                        "no file in the " + kind.plural() + " folder declares " + kind.label() + " '" + name + "'"));
        String yaml = writes.textOf(kind, name).orElse("");
        boolean enabled = !yaml.lines().map(String::strip).anyMatch(line -> line.matches("enabled\\s*:\\s*false.*"));
        return new ApiDtos.DeclarationText(kind.label(), name, path.toString(), yaml, enabled, dependents);
    }

    private Principal authorize(HttpServletRequest http, String name) {
        authorizer.requireAdminister(http, name);
        return authorizer.principalOf(http);
    }

    private static ResponseEntity<ApiDtos.DeclarationChange> answer(DeclarationWrites.Outcome outcome) {
        ApiDtos.DeclarationChange body = new ApiDtos.DeclarationChange(
                outcome.kind(),
                outcome.name(),
                outcome.action(),
                outcome.dryRun(),
                outcome.applied(),
                outcome.valid(),
                outcome.path(),
                outcome.yaml(),
                outcome.previous(),
                outcome.dependents(),
                outcome.code(),
                outcome.message());
        // 200 for every write, a create included: one status per operation (CAT-201), and the body says
        // whether anything was written (applied) or only previewed (dryRun).
        return ResponseEntity.ok(body);
    }

    static String streamYaml(String name, ApiDtos.@Nullable StreamFileRequest request, Principal who) {
        if (request == null) {
            throw missing("a stream file needs a body: its schema (and the rest), or yaml");
        }
        if (request.yaml() != null && !request.yaml().isBlank()) {
            return request.yaml();
        }
        if (request.schema() == null || request.schema().isBlank()) {
            throw missing("schema is missing: a stream is declared with its columns, as name:TYPE,...");
        }
        ApiDtos.SourceSpec source = request.source();
        DeclarationYaml.Binding binding = source == null || (blank(source.plugin()) && blank(source.connector()))
                ? null
                : new DeclarationYaml.Binding(source.plugin(), source.connector(), options(source.options()));
        return DeclarationYaml.stream(
                name,
                request.schema(),
                request.eventTime(),
                request.outOfOrderness(),
                request.allowedLateness(),
                !Boolean.FALSE.equals(request.enabled()),
                binding,
                who.id());
    }

    static String bindingYaml(
            DeclarationKind kind, String name, ApiDtos.@Nullable BindingFileRequest request, Principal who) {
        if (request == null) {
            throw missing("a " + kind.label() + " file needs a body: plugin and options, or yaml");
        }
        if (request.yaml() != null && !request.yaml().isBlank()) {
            return request.yaml();
        }
        if (kind != DeclarationKind.CONNECTOR && Boolean.FALSE.equals(request.enabled())) {
            throw new PravahaException(
                    ConfigErrors.FILE_INVALID,
                    "only a stream or a connection can be switched off, not a " + kind.label());
        }
        return DeclarationYaml.binding(
                kind,
                name,
                new DeclarationYaml.Binding(request.plugin(), request.connector(), options(request.options())),
                !Boolean.FALSE.equals(request.enabled()),
                who.id());
    }

    private static Map<String, String> options(@Nullable Map<String, String> options) {
        if (options == null) {
            return Map.of();
        }
        Map<String, String> kept = new java.util.LinkedHashMap<>();
        options.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                kept.put(key.strip(), value);
            }
        });
        return kept;
    }

    private static boolean blank(@Nullable String text) {
        return text == null || text.isBlank();
    }

    private static PravahaException missing(String what) {
        return new PravahaException(ConfigErrors.MISSING_REQUIRED, what);
    }
}
