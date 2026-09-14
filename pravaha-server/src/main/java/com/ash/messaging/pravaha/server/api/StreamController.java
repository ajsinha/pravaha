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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;

/** Streams the engine can read from. */
@RestController
@RequestMapping("/api/v1/streams")
@Tag(name = "Streams", description = "Registered streams and their schemas")
public class StreamController {

    private final StreamCatalog catalog;
    private final DtoMapper mapper;
    private final com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer;

    public StreamController(
            StreamCatalog catalog,
            DtoMapper mapper,
            com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.authorizer = authorizer;
    }

    /**
     * The streams this caller may see.
     *
     * <p>Filtered, not refused wholesale: a principal entitled to some streams gets those. It used
     * to return every stream to everybody -- a principal denied every payroll view on Flight was
     * handed payroll's full schema here, salary column included, because this surface asked no
     * policy anything.
     */
    @GetMapping
    @Operation(summary = "List registered streams")
    public List<ApiDtos.StreamSummary> list(jakarta.servlet.http.HttpServletRequest request) {
        return catalog.all().stream()
                .filter(schema -> authorizer.mayRead(request, schema.name()))
                .map(mapper::toSummary)
                .toList();
    }

    @GetMapping("/{name}")
    @Operation(summary = "Fetch one stream's schema")
    public ApiDtos.StreamSummary get(@PathVariable String name, jakarta.servlet.http.HttpServletRequest request) {
        // Authorized before the catalogue is asked, so a refusal for a stream that exists reads the
        // same as one for a stream that does not. Checking existence first would answer "does
        // payroll exist" for anyone who can reach this endpoint.
        authorizer.requireRead(request, name);
        return mapper.toSummary(catalog.require(name));
    }

    /**
     * Registers a stream from a schema specification.
     *
     * <p>The spec form is the plugin's own ({@code name:TYPE,...}) rather than a bespoke JSON
     * schema, deliberately: it is what a user already writes in configuration, and having one
     * spelling of a schema across configuration, CLI and API is worth more than a tidier request
     * body that has to be learned separately.
     */
    @PostMapping
    @Operation(summary = "Register a stream")
    public ResponseEntity<ApiDtos.StreamSummary> register(
            @RequestBody RegisterStreamRequest request, jakarta.servlet.http.HttpServletRequest http) {
        // Changing what the node serves is an administrative act. This applied no check beyond "a
        // token verified", so any authenticated caller -- including one denied every existing view
        // -- could publish an arbitrary stream schema.
        authorizer.requireAdminister(http, request.name());
        var schema = FilesystemSourcePlugin.parseSchema(request.name(), request.schema());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toSummary(catalog.register(schema)));
    }

    /** @param schema a specification such as {@code id:INT64,user:STRING,amount:INT64} */
    public record RegisterStreamRequest(String name, String schema) {}
}
