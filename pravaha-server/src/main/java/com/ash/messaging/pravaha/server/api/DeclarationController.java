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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.folders.ConfigFolders;
import com.ash.messaging.pravaha.server.folders.DeclarationKind;
import com.ash.messaging.pravaha.server.folders.DeclarationWatcher;
import com.ash.messaging.pravaha.server.folders.DeclarationWrites;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * What is declared on this node, and where each came from: a file in a declaration folder, inline
 * configuration, or the API -- with every declaration file the node has not applied as written, and why
 * (ADR-064). Filtered by what the caller may read, as {@code GET /api/v1/streams} is.
 *
 * <p>Lookups and sinks are written here, {@code /api/v1/declarations/lookups/{name}} and
 * {@code /api/v1/declarations/sinks/{name}}, by the code that writes stream and connection files: the file
 * is put in its folder and applied at once, and a change to one a query uses is refused, writing nothing.
 */
@RestController
@RequestMapping("/api/v1/declarations")
@Tag(name = "Declarations", description = "Declared streams, lookups and sinks, where each came from")
public class DeclarationController {

    private final ConfigFolders folders;
    private final StreamCatalog catalog;
    private final RegistryAccess registry;
    private final HttpAuthorizer authorizer;
    private @Nullable DeclarationEndpoints endpoints;

    /** The file writes (ADR-064); a controller made by hand has none. */
    @Autowired(required = false)
    public void setDeclarationWrites(DeclarationWrites writes) {
        this.endpoints = new DeclarationEndpoints(writes, authorizer);
    }

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

    @PostMapping("/{kind}/{name}")
    @Operation(
            summary =
                    "Declare a lookup or sink by writing its file (kind: lookups or sinks); dryRun=true writes nothing")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written -- or, on a dry run, what would be")
    public ResponseEntity<ApiDtos.DeclarationChange> create(
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody ApiDtos.BindingFileRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http) {
        return endpoints().writeBinding(http, kindOf(kind), name, DeclarationWrites.Action.CREATE, request, dryRun);
    }

    @PutMapping("/{kind}/{name}")
    @Operation(summary = "Replace a lookup's or sink's file; refused while a query uses it (PRV-1065)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> update(
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody ApiDtos.BindingFileRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http) {
        return endpoints().writeBinding(http, kindOf(kind), name, DeclarationWrites.Action.UPDATE, request, dryRun);
    }

    @DeleteMapping("/{kind}/{name}")
    @Operation(summary = "Remove a lookup's or sink's file; refused while a query uses it")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> delete(
            @PathVariable String kind,
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http) {
        return endpoints().delete(http, kindOf(kind), name, dryRun);
    }

    @GetMapping("/{kind}/{name}/file")
    @Operation(summary = "A lookup's or sink's declaration file as written, for an editor")
    public ApiDtos.DeclarationText file(@PathVariable String kind, @PathVariable String name, HttpServletRequest http) {
        DeclarationKind which = kindOf(kind);
        return endpoints()
                .text(
                        http,
                        which,
                        name,
                        registry.registry()
                                .map(found -> found.usersOf(
                                        which == DeclarationKind.LOOKUP
                                                ? com.ash.messaging.pravaha.registry.QueryRegistry.Use.LOOKUP
                                                : com.ash.messaging.pravaha.registry.QueryRegistry.Use.SINK,
                                        name))
                                .orElse(List.of()));
    }

    private static DeclarationKind kindOf(String kind) {
        return switch (kind) {
            case "lookups", "lookup" -> DeclarationKind.LOOKUP;
            case "sinks", "sink" -> DeclarationKind.SINK;
            default ->
                throw new PravahaException(
                        ConfigErrors.FILE_INVALID,
                        "'" + kind + "' is not lookups or sinks. Streams are written at /api/v1/streams/{name} and "
                                + "connections at /api/v1/connectors/{name}.");
        };
    }

    private DeclarationEndpoints endpoints() {
        DeclarationEndpoints wired = endpoints;
        if (wired == null) {
            throw new PravahaException(ConfigErrors.FOLDER_NOT_WRITABLE, "this node writes no declaration files");
        }
        return wired;
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
