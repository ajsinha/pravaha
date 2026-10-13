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
import java.util.List;
import java.util.Objects;

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
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.folders.ConfigFolders;
import com.ash.messaging.pravaha.server.folders.Connectors;
import com.ash.messaging.pravaha.server.folders.DeclarationFile;
import com.ash.messaging.pravaha.server.folders.DeclarationKind;
import com.ash.messaging.pravaha.server.folders.DeclarationWrites;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The named connections (ADR-064): each one's plugin, its file, its options with every password, secret,
 * token and key masked, and the bindings that use it; filtered by what the caller may read.
 *
 * <p>Managed by writing {@code config/connectors/<name>.yaml} -- create, update, delete, enable, disable,
 * each with {@code dryRun=true} -- and tested through the connection's own plugin. A secret option is
 * written as {@code ${ENV_NAME}}; a literal one is refused ({@code PRV-1063}) exactly as in a file written
 * by hand. A disabled connection stops the stream sources that name it, as a disabled stream does; the
 * lookups and sinks that name it keep running. Changing or removing a connection a binding names is
 * refused ({@code PRV-1065}); disabling it is not.
 */
@RestController
@RequestMapping("/api/v1/connectors")
@Tag(name = "Connectors", description = "Named connections a binding can take its plugin and options from")
public class ConnectorController {

    private final ConfigFolders folders;
    private final SourceBindingProperties sources;
    private final SinkBindingProperties sinks;
    private final HttpAuthorizer authorizer;
    private @Nullable DeclarationEndpoints endpoints;

    /** The file writes (ADR-064); a controller made by hand has none. */
    @Autowired(required = false)
    public void setDeclarationWrites(DeclarationWrites writes) {
        this.endpoints = new DeclarationEndpoints(writes, authorizer);
    }

    public ConnectorController(
            ConfigFolders folders,
            SourceBindingProperties sources,
            SinkBindingProperties sinks,
            HttpAuthorizer authorizer) {
        this.folders = folders;
        this.sources = sources;
        this.sinks = sinks;
        this.authorizer = authorizer;
    }

    @GetMapping
    @Operation(summary = "List the named connections, secret options masked")
    public List<ApiDtos.Connector> list(HttpServletRequest http) {
        List<ApiDtos.Connector> out = new ArrayList<>();
        folders.connectors().all().forEach((name, file) -> {
            if (authorizer.mayRead(http, name)) {
                out.add(summary(file));
            }
        });
        return out;
    }

    @GetMapping("/{name}")
    @Operation(summary = "One named connection, secret options masked")
    public ApiDtos.Connector get(@PathVariable String name, HttpServletRequest http) {
        authorizer.requireRead(http, name);
        return folders.connectors()
                .find(name)
                .map(this::summary)
                .orElseThrow(() -> new PravahaException(
                        ConfigErrors.CONNECTOR_UNKNOWN, "no connection named '" + name + "' is declared on this node"));
    }

    private ApiDtos.Connector summary(DeclarationFile file) {
        SourceBindingProperties.Spec spec = Objects.requireNonNull(file.source());
        boolean enabled = folders.files(DeclarationKind.CONNECTOR)
                .getOrDefault(file.name(), file)
                .enabled();
        return new ApiDtos.Connector(
                file.name(),
                Objects.requireNonNull(spec.getPlugin()),
                "file:" + file.path(),
                Connectors.masked(spec.getOptions()),
                Connectors.users(file.name(), sources, sinks),
                enabled);
    }

    @PostMapping("/{name}")
    @Operation(
            summary = "Declare a named connection by writing config/connectors/<name>.yaml; dryRun=true writes nothing")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written -- or, on a dry run, what would be")
    public ResponseEntity<ApiDtos.DeclarationChange> create(
            @PathVariable String name,
            @RequestBody ApiDtos.BindingFileRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http) {
        return endpoints()
                .writeBinding(http, DeclarationKind.CONNECTOR, name, DeclarationWrites.Action.CREATE, request, dryRun);
    }

    @PutMapping("/{name}")
    @Operation(summary = "Replace a named connection's file; refused while a binding names it (PRV-1065)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> update(
            @PathVariable String name,
            @RequestBody ApiDtos.BindingFileRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest http) {
        return endpoints()
                .writeBinding(http, DeclarationKind.CONNECTOR, name, DeclarationWrites.Action.UPDATE, request, dryRun);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Remove a named connection's file; refused while a binding names it")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> delete(
            @PathVariable String name, @RequestParam(defaultValue = "false") boolean dryRun, HttpServletRequest http) {
        return endpoints().delete(http, DeclarationKind.CONNECTOR, name, dryRun);
    }

    @PostMapping("/{name}/enable")
    @Operation(summary = "Enable a named connection: the stream sources that name it are read again")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> enable(
            @PathVariable String name, @RequestParam(defaultValue = "false") boolean dryRun, HttpServletRequest http) {
        return endpoints().enable(http, DeclarationKind.CONNECTOR, name, true, dryRun);
    }

    @PostMapping("/{name}/disable")
    @Operation(
            summary =
                    "Disable a named connection: the stream sources that name it stop; never refused for being in use")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "What was written, or would be on a dry run")
    public ResponseEntity<ApiDtos.DeclarationChange> disable(
            @PathVariable String name, @RequestParam(defaultValue = "false") boolean dryRun, HttpServletRequest http) {
        return endpoints().enable(http, DeclarationKind.CONNECTOR, name, false, dryRun);
    }

    @GetMapping("/{name}/file")
    @Operation(summary = "A named connection's file as written, for an editor (secrets are ${ENV} references)")
    public ApiDtos.DeclarationText file(@PathVariable String name, HttpServletRequest http) {
        return endpoints().text(http, DeclarationKind.CONNECTOR, name, Connectors.users(name, sources, sinks));
    }

    /**
     * Tests a connection through its plugin -- configure, open, health, close -- in bounded time; answers
     * the outcome and how long it took, never an option's value. Needs what changing the connection needs.
     */
    @PostMapping("/{name}/test")
    @Operation(summary = "Test a named connection through its plugin, in bounded time; never shows a secret")
    public ApiDtos.ConnectionTest test(
            @PathVariable String name,
            @RequestBody(required = false) ApiDtos.@Nullable ConnectionTestRequest request,
            HttpServletRequest http) {
        authorizer.requireAdminister(http, name);
        DeclarationFile file = folders.connectors()
                .find(name)
                .orElseThrow(() -> new PravahaException(
                        ConfigErrors.CONNECTOR_UNKNOWN, "no connection named '" + name + "' is declared on this node"));
        SourceBindingProperties.Spec spec = Objects.requireNonNull(file.source());
        int seconds = request == null || request.timeoutSeconds() == null ? 10 : request.timeoutSeconds();
        ApiDtos.ConnectionTest outcome = ConnectionTester.test(
                name,
                Objects.requireNonNull(spec.getPlugin()),
                spec.getOptions(),
                request == null ? null : request.options(),
                seconds);
        authorizer.record(http, "config.connector.test", name, outcome.ok(), outcome.state());
        return outcome;
    }

    private DeclarationEndpoints endpoints() {
        DeclarationEndpoints wired = endpoints;
        if (wired == null) {
            throw new PravahaException(ConfigErrors.FOLDER_NOT_WRITABLE, "this node writes no declaration files");
        }
        return wired;
    }
}
