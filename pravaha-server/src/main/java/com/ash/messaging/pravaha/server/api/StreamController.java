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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.folders.DeclarationKind;
import com.ash.messaging.pravaha.server.folders.DeclarationWrites;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;

/**
 * Streams the engine can read from.
 *
 * <p>Two ways to declare one. {@code POST /api/v1/streams} registers a stream on the running node only
 * (origin {@code api}): it binds no source and is gone after a restart, as it always was, and is kept as
 * it is. {@code POST /api/v1/streams/{name}} and the rest of {@code /{name}} write
 * {@code config/streams/<name>.yaml} -- the file a person would write, with its source -- and apply it at
 * once; the file is what the node reads at the next start (ADR-064).
 */
@RestController
@RequestMapping("/api/v1/streams")
@Tag(name = "Streams", description = "Registered streams and their schemas")
public class StreamController {

    private final StreamCatalog catalog;
    private final DtoMapper mapper;
    private final com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer;
    private final SourceBindingProperties sources;
    private com.ash.messaging.pravaha.server.folders.ConfigFolders folders =
            com.ash.messaging.pravaha.server.folders.ConfigFolders.none();
    private @Nullable DeclarationEndpoints endpoints;
    private @Nullable RegistryAccess registry;

    /** The file writes behind {@code /{name}} (ADR-064); a controller made by hand has none. */
    @Autowired(required = false)
    public void setDeclarationWrites(DeclarationWrites writes) {
        this.endpoints = new DeclarationEndpoints(writes, authorizer);
    }

    /** How many queries read each stream, for the listing; a controller made by hand reports none. */
    @Autowired(required = false)
    public void setRegistryAccess(RegistryAccess registry) {
        this.registry = registry;
    }

    /** Where each stream came from (ADR-064); a controller made by hand reports none. */
    @Autowired(required = false)
    public void setConfigFolders(com.ash.messaging.pravaha.server.folders.ConfigFolders folders) {
        this.folders = folders;
    }

    public StreamController(
            StreamCatalog catalog,
            DtoMapper mapper,
            com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer) {
        this(catalog, mapper, authorizer, new SourceBindingProperties());
    }

    @Autowired
    public StreamController(
            StreamCatalog catalog,
            DtoMapper mapper,
            com.ash.messaging.pravaha.server.security.HttpAuthorizer authorizer,
            SourceBindingProperties sources) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.sources = sources;
    }

    /** A stream as the API shows it, with the plugin that feeds it -- the plugin's name, never its options. */
    private ApiDtos.StreamSummary summaryOf(StreamSchema schema, java.util.Set<String> stopped) {
        var binding = sources.getSources().get(schema.name());
        var kind = DeclarationKind.STREAM;
        return mapper.toSummary(schema, binding == null ? null : binding.effectivePlugin())
                .from(
                        folders.origin(kind, schema.name()),
                        folders.notApplied(kind, schema.name()).orElse(null))
                .managed(
                        !stopped.contains(schema.name()),
                        binding == null ? null : binding.getConnector(),
                        readersOf(schema.name()).size());
    }

    private java.util.List<String> readersOf(String stream) {
        RegistryAccess access = registry;
        return access == null
                ? java.util.List.of()
                : access.registry()
                        .map(found ->
                                found.usersOf(com.ash.messaging.pravaha.registry.QueryRegistry.Use.STREAM, stream))
                        .orElse(java.util.List.of());
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
        java.util.Set<String> stopped = folders.stoppedStreams(sources);
        return catalog.all().stream()
                .filter(schema -> authorizer.mayRead(request, schema.name()))
                .map(schema -> summaryOf(schema, stopped))
                .toList();
    }

    @GetMapping("/{name}")
    @Operation(summary = "Fetch one stream's schema")
    public ApiDtos.StreamSummary get(@PathVariable String name, jakarta.servlet.http.HttpServletRequest request) {
        // Authorized before the catalogue is asked, so a refusal for a stream that exists reads the
        // same as one for a stream that does not. Checking existence first would answer "does
        // payroll exist" for anyone who can reach this endpoint.
        authorizer.requireRead(request, name);
        return summaryOf(catalog.require(name), folders.stoppedStreams(sources));
    }

    /**
     * Declares a stream by writing {@code config/streams/<name>.yaml} and applying it at once.
     *
     * <p>{@code dryRun=true} writes nothing and answers what would happen: the file's text, whether it is
     * accepted and why not, and what uses the stream.
     */
    @PostMapping("/{name}")
    @Operation(
            summary = "Declare a stream by writing its file (config/streams/<name>.yaml); dryRun=true writes nothing")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written -- or, on a dry run, what would be")
    public ResponseEntity<ApiDtos.DeclarationChange> create(
            @PathVariable String name,
            @RequestBody ApiDtos.StreamFileRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest http) {
        return endpoints().writeStream(http, name, DeclarationWrites.Action.CREATE, request, dryRun);
    }

    /** Replaces a stream's file. Refused, writing nothing, while a query reads it -- unless only {@code enabled} changes. */
    @PutMapping("/{name}")
    @Operation(
            summary = "Replace a stream's file; refused while a query reads it (PRV-1065); dryRun=true writes nothing")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> update(
            @PathVariable String name,
            @RequestBody ApiDtos.StreamFileRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest http) {
        return endpoints().writeStream(http, name, DeclarationWrites.Action.UPDATE, request, dryRun);
    }

    /** Removes a stream's file and the stream. Refused, writing nothing, while a query reads it. */
    @DeleteMapping("/{name}")
    @Operation(
            summary =
                    "Remove a stream's file and the stream; refused while a query reads it; dryRun=true lists its readers")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> delete(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest http) {
        return endpoints().delete(http, DeclarationKind.STREAM, name, dryRun);
    }

    /** Switches a stream's source back on: its readers resume where they stood. */
    @PostMapping("/{name}/enable")
    @Operation(summary = "Enable a stream: its source is read again (writes enabled: in its file)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> enable(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest http) {
        return endpoints().enable(http, DeclarationKind.STREAM, name, true, dryRun);
    }

    /**
     * Switches a stream's source off: the stream stays declared, queries over it keep running and receive
     * nothing. Never refused for being in use -- that is what it is for.
     */
    @PostMapping("/{name}/disable")
    @Operation(summary = "Disable a stream: declared, its source read from nothing, its queries kept running")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> disable(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest http) {
        return endpoints().enable(http, DeclarationKind.STREAM, name, false, dryRun);
    }

    /** The stream's file as written, for an editor, with the queries that read it. */
    @GetMapping("/{name}/file")
    @Operation(summary = "A stream's declaration file as written, for an editor")
    public ApiDtos.DeclarationText file(@PathVariable String name, jakarta.servlet.http.HttpServletRequest http) {
        return endpoints().text(http, DeclarationKind.STREAM, name, readersOf(name));
    }

    private DeclarationEndpoints endpoints() {
        DeclarationEndpoints wired = endpoints;
        if (wired == null) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    com.ash.messaging.pravaha.common.config.ConfigErrors.FOLDER_NOT_WRITABLE,
                    "this node writes no declaration files");
        }
        return wired;
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
    // P-5. The code returns 201 and the published document said 200, so `openapi.lock.json` --
    // which is generated from the document -- recorded 200 as well. The drift was not intended and
    // the code is the half that is right: this is the one call in the API that creates a resource,
    // and 201 is what a creation answers. What was wrong is that springdoc infers the status from
    // the declared return type, which for a ResponseEntity is 200 and says nothing about the entity.
    // So the document is corrected to state 201 and the lock regenerated from it, rather than the
    // controller being downgraded to match a document that was only ever a default.
    //
    // It matters more than a number: a generated client that treats anything but the documented
    // status as a failure breaks on the only write this API has, and the console is a separate
    // process built from exactly this document.
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "The stream was registered")
    public ResponseEntity<ApiDtos.StreamSummary> register(
            @RequestBody RegisterStreamRequest request, jakarta.servlet.http.HttpServletRequest http) {
        // Changing what the node serves is an administrative act. This applied no check beyond "a
        // token verified", so any authenticated caller -- including one denied every existing view
        // -- could publish an arbitrary stream schema.
        authorizer.requireAdminister(http, request.name());
        var schema = StreamCatalog.withEventTime(
                FilesystemSourcePlugin.parseSchema(request.name(), request.schema()),
                request.eventTime(),
                durationOf("outOfOrderness", request.outOfOrderness()),
                durationOf("allowedLateness", request.allowedLateness()));
        return ResponseEntity.status(HttpStatus.CREATED).body(summaryOf(catalog.register(schema), java.util.Set.of()));
    }

    private static java.time.@Nullable Duration durationOf(String setting, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.Duration.parse(text.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    setting + " must be an ISO-8601 duration such as PT10S, got '" + text + "'");
        }
    }

    /**
     * A stream to declare.
     *
     * @param schema a specification such as {@code id:INT64,user:STRING,amount:INT64}
     * @param eventTime the column event time is read from, optional; without one no window over the
     *     stream can ever close
     * @param outOfOrderness how late a row may be, as ISO-8601 such as {@code PT10S}; optional, and
     *     refused without an event time for it to be about
     * @param allowedLateness how long after a window closes a late row may still correct it, as
     *     ISO-8601; optional, zero when absent, and refused without an event time (HLP-7)
     */
    public record RegisterStreamRequest(
            String name,
            String schema,
            @Nullable String eventTime,
            @Nullable String outOfOrderness,
            @Nullable String allowedLateness) {

        public RegisterStreamRequest(String name, String schema) {
            this(name, schema, null, null, null);
        }

        public RegisterStreamRequest(
                String name, String schema, @Nullable String eventTime, @Nullable String outOfOrderness) {
            this(name, schema, eventTime, outOfOrderness, null);
        }
    }
}
