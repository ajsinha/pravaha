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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.folders.ConfigFolders;
import com.ash.messaging.pravaha.server.folders.DeclarationKind;
import com.ash.messaging.pravaha.server.folders.DeclarationWatcher;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * What is declared on this node, and where each came from: a file in a declaration folder, inline
 * configuration, or the API -- with every declaration file the node has not applied as written, and why
 * (ADR-064). Filtered by what the caller may read, as {@code GET /api/v1/streams} is.
 */
@RestController
@RequestMapping("/api/v1/declarations")
@Tag(name = "Declarations", description = "Declared streams, lookups and sinks, where each came from")
public class DeclarationController {

    private final ConfigFolders folders;
    private final StreamCatalog catalog;
    private final RegistryAccess registry;
    private final HttpAuthorizer authorizer;

    public DeclarationController(
            ConfigFolders folders, StreamCatalog catalog, RegistryAccess registry, HttpAuthorizer authorizer) {
        this.folders = folders;
        this.catalog = catalog;
        this.registry = registry;
        this.authorizer = authorizer;
    }

    @GetMapping
    @Operation(summary = "List declared streams, lookups and sinks with their origin, and files not applied")
    public ApiDtos.Declarations list(HttpServletRequest http) {
        List<DeclarationWatcher.Problem> problems = folders.problems();
        List<ApiDtos.Declaration> out = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        Map<DeclarationKind, Set<String>> declared = new LinkedHashMap<>();
        declared.put(DeclarationKind.STREAM, names(catalog.all()));
        declared.put(DeclarationKind.LOOKUP, names(catalog.lookups()));
        declared.put(
                DeclarationKind.SINK,
                registry.sinks()
                        .map(sinks -> new TreeSet<>(sinks.bindings().keySet()))
                        .orElseGet(TreeSet::new));
        declared.forEach((kind, names) -> {
            for (String name : names) {
                if (!authorizer.mayRead(http, name)) {
                    continue;
                }
                listed.add(kind.label() + ":" + name);
                Optional<DeclarationWatcher.Problem> problem = problems.stream()
                        .filter(p -> p.kind() == kind && p.name().equals(name))
                        .findFirst();
                out.add(entry(kind, name, folders.origin(kind, name), problem));
            }
        });
        for (DeclarationWatcher.Problem problem : problems) {
            // A file that declares nothing yet -- new and invalid, or refused -- and a folder gone missing.
            if (listed.contains(problem.kind().label() + ":" + problem.name())) {
                continue;
            }
            if (!problem.name().equals("*") && !authorizer.mayRead(http, problem.name())) {
                continue;
            }
            out.add(entry(problem.kind(), problem.name(), "file:" + problem.file(), Optional.of(problem)));
        }
        Map<String, String> where = new LinkedHashMap<>();
        folders.folders()
                .forEach(folder ->
                        where.put(folder.kind().plural(), folder.path().toString()));
        return new ApiDtos.Declarations(where, folders.watchInterval(), out);
    }

    private static ApiDtos.Declaration entry(
            DeclarationKind kind, String name, String origin, Optional<DeclarationWatcher.Problem> problem) {
        return new ApiDtos.Declaration(
                kind.label(),
                name,
                origin,
                problem.isPresent() ? "not-applied" : "applied",
                problem.map(DeclarationWatcher.Problem::code).orElse(null),
                problem.map(DeclarationWatcher.Problem::message).orElse(null),
                problem.map(DeclarationWatcher.Problem::users).orElse(List.of()));
    }

    private static Set<String> names(java.util.Collection<StreamSchema> schemas) {
        Set<String> names = new TreeSet<>();
        schemas.forEach(schema -> names.add(schema.name()));
        return names;
    }
}
