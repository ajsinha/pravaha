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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.folders.ConfigFolders;
import com.ash.messaging.pravaha.server.folders.Connectors;
import com.ash.messaging.pravaha.server.folders.DeclarationFile;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The named connections (ADR-064): each one's plugin, its file, its options with every password, secret,
 * token and key masked, and the bindings that use it. Read-only; filtered by what the caller may read.
 */
@RestController
@RequestMapping("/api/v1/connectors")
@Tag(name = "Connectors", description = "Named connections a binding can take its plugin and options from")
public class ConnectorController {

    private final ConfigFolders folders;
    private final SourceBindingProperties sources;
    private final SinkBindingProperties sinks;
    private final HttpAuthorizer authorizer;

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
        return new ApiDtos.Connector(
                file.name(),
                Objects.requireNonNull(spec.getPlugin()),
                "file:" + file.path(),
                Connectors.masked(spec.getOptions()),
                Connectors.users(file.name(), sources, sinks));
    }
}
