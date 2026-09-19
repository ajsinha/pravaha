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
import java.util.Locale;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.registry.QueryListing;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.security.SecurityProperties;

/**
 * What the configured policy lets the caller do, asked of the policy rather than guessed.
 *
 * <p>Read-only, and only about the caller. There is no endpoint to change a grant because the
 * engine is not where grants live: {@code SecurityPolicy} is an SPI a deployment implements against
 * its own identity system, and the configured policies ({@code permissive}, {@code authenticated})
 * have no grants to edit.
 *
 * <p>It names only what the caller could already see. The views are the caller's own listing -- the
 * same {@code QueryListing} rule {@code GET /api/v1/queries} and Flight's {@code pravaha.list} use,
 * so a view hidden from them, by name or by what it reads, is not named here with a "no" beside it.
 * The streams are the ones {@code GET /api/v1/streams} would show them. A row filter is reported as
 * {@code filtered}; its predicate is not repeated.
 */
@RestController
@Tag(name = "Permissions", description = "What the configured policy lets the calling principal do")
public class PermissionsController {

    private final HttpAuthorizer authorizer;
    private final RegistryAccess registry;
    private final StreamCatalog catalog;
    private final String policyName;

    @Autowired
    public PermissionsController(
            HttpAuthorizer authorizer, RegistryAccess registry, StreamCatalog catalog, SecurityProperties security) {
        this(authorizer, registry, catalog, security.getPolicy());
    }

    /** For a test: the policy's name is what the response reports, not what decides. */
    public PermissionsController(
            HttpAuthorizer authorizer, RegistryAccess registry, StreamCatalog catalog, String policyName) {
        this.authorizer = authorizer;
        this.registry = registry;
        this.catalog = catalog;
        this.policyName = policyName == null || policyName.isBlank()
                ? "permissive"
                : policyName.strip().toLowerCase(Locale.ROOT);
    }

    @GetMapping("/api/v1/me/permissions")
    @Operation(summary = "What the configured policy lets the calling principal do")
    public AdminDtos.Permissions permissions(HttpServletRequest http) {
        Principal principal = authorizer.principalOf(http);
        SecurityPolicy policy = authorizer.policyFor(http);

        List<AdminDtos.ObjectPermission> views = new ArrayList<>();
        registry.listing().ifPresent(listing -> {
            for (QueryListing.Entry entry : listing.list(principal, "http.permissions")) {
                views.add(permission(entry.name(), policy, principal));
            }
        });
        List<AdminDtos.ObjectPermission> streams = new ArrayList<>();
        for (StreamSchema stream : catalog.all()) {
            if (policy.mayRead(principal, stream.name()).allowed()) {
                streams.add(permission(stream.name(), policy, principal));
            }
        }
        views.sort(java.util.Comparator.comparing(AdminDtos.ObjectPermission::name));
        streams.sort(java.util.Comparator.comparing(AdminDtos.ObjectPermission::name));
        return new AdminDtos.Permissions(
                principal.id(),
                principal.tenant(),
                principal.roles().stream().sorted().toList(),
                principal.isAnonymous(),
                policyName,
                decision(policy.mayRegisterQuery(principal)),
                decision(policy.mayReadAudit(principal)),
                List.copyOf(views),
                List.copyOf(streams));
    }

    private static AdminDtos.ObjectPermission permission(String name, SecurityPolicy policy, Principal principal) {
        AccessDecision read = policy.mayRead(principal, name);
        AccessDecision administer = policy.mayAdminister(principal, name);
        if (read.rowFilter().isPresent() && !administer.allowed()) {
            // The default refusal quotes the row filter, which is not repeated on this surface.
            administer = AccessDecision.deny("a row-filtered read is not a claim on the whole view");
        }
        return new AdminDtos.ObjectPermission(
                name, read.rowFilter().isPresent() ? "filtered" : "full", decision(administer));
    }

    /** The decision and, when refused, the policy's reason; the reason for an allow says nothing. */
    private static AdminDtos.Decision decision(AccessDecision decision) {
        return new AdminDtos.Decision(decision.allowed(), decision.allowed() ? null : decision.reason());
    }
}
