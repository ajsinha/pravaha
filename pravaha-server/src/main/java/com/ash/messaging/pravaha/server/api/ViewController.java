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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.QueryListing;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ServingErrors;

/**
 * Describes a view without reading it.
 *
 * <p>A client that wanted a view's columns used to read the view, or re-validate the SQL of the
 * query behind it -- the first costs a scan and the second answers a slightly different question
 * (the schema the SQL would have <em>now</em>, not the one the running view has). This answers from
 * the view itself.
 *
 * <p>Decided like the listing, by {@link QueryListing}: a denied name is refused whether or not it
 * exists, and a view reading a stream the caller may not read answers exactly as a name that is not
 * registered. That is stricter than a read ({@code ViewQuery} tells the two apart), deliberately --
 * describing is how a client finds out what exists, which is the listing's question, and a describe
 * endpoint that distinguished them would be an existence oracle the listing refuses to be.
 */
@RestController
@RequestMapping("/api/v1/views")
@Tag(name = "Views", description = "Registered queries' views, described without being read")
public class ViewController {

    private final DtoMapper mapper;
    private final HttpAuthorizer authorizer;
    private final RegistryAccess registry;

    public ViewController(DtoMapper mapper, HttpAuthorizer authorizer, RegistryAccess registry) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.registry = registry;
    }

    @GetMapping("/{name}")
    @Operation(summary = "Describe a view: its schema, key, retention and sink")
    public ApiDtos.ViewDescription describe(@PathVariable String name, HttpServletRequest http) {
        Principal principal = authorizer.principalOf(http);
        QueryListing listing = registry.listing().orElseGet(() -> {
            authorizer.requireRead(http, name);
            throw noSuchView(name);
        });
        QueryListing.Entry entry =
                listing.find(principal, name, "http.describe").orElseThrow(() -> noSuchView(name));
        RegisteredQuery query = entry.query();
        var view = query.view();
        return new ApiDtos.ViewDescription(
                entry.name(),
                mapper.toFields(view.schema()),
                mapper.toKeyColumns(view.schema(), view.keyOrdinals()),
                view.retention().toString(),
                entry.sink().orElse(null),
                query.fingerprint().shortForm());
    }

    private static PravahaException noSuchView(String name) {
        return new PravahaException(
                ServingErrors.NO_SUCH_VIEW,
                "'" + name + "' is not a view you may see on this server. The views are not listed here; "
                        + "GET /api/v1/queries lists the ones you may see.");
    }
}
