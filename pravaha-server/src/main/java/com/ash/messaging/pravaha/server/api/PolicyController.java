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
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.catalog.CatalogErrors;
import com.ash.messaging.pravaha.catalog.CatalogObject;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogService;
import com.ash.messaging.pravaha.catalog.PolicyBinding;
import com.ash.messaging.pravaha.catalog.PolicyDefinition;
import com.ash.messaging.pravaha.catalog.PolicyService;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * Row filters and column masks over REST (ADR-059 §4): list, show, create, bind, unbind and drop. Every
 * rule is {@link PolicyService}'s, so this decides exactly as {@code CREATE ROW FILTER}, {@code ALTER ...
 * SET POLICY} and {@code DROP MASK} do over SQL, and every change is an audit event there. A node whose
 * catalogue is off answers with {@code PRV-7030}.
 *
 * <p>A policy is named in a path as {@code name} (the caller's default namespace), {@code ns.name}, or in
 * full.
 */
@RestController
@RequestMapping("/api/v1/catalog/policies")
@Tag(name = "Catalog", description = "Namespaces, owners, descriptions, tags and grants (ADR-059)")
public class PolicyController {

    /** Where a policy is bound: one object, or every object carrying a tag. */
    public record BindingDto(String object, String tag, String boundBy, Instant boundAt) {
        static BindingDto of(PolicyBinding binding) {
            return new BindingDto(
                    binding.byTag() ? null : binding.object(),
                    binding.byTag() ? binding.tag() : null,
                    binding.boundBy(),
                    binding.boundAt());
        }
    }

    /** A row filter or a mask, and where it is bound. */
    public record PolicyDto(
            String name,
            String kind,
            String column,
            String expression,
            List<String> exceptRoles,
            CatalogController.GranteeDto owner,
            String description,
            Map<String, String> tags,
            long version,
            List<BindingDto> bindings) {
        static PolicyDto of(PolicyService.PolicyView view) {
            PolicyDefinition policy = view.definition();
            CatalogObject object = view.object();
            return new PolicyDto(
                    policy.fullName(),
                    policy.type().name(),
                    policy.column().isEmpty() ? null : policy.column(),
                    policy.expression(),
                    List.copyOf(policy.exceptRoles()),
                    CatalogController.GranteeDto.of(object.owner()),
                    object.description(),
                    object.tags(),
                    object.version(),
                    view.bindings().stream().map(BindingDto::of).toList());
        }
    }

    public record PolicyPage(List<PolicyDto> items) {}

    /**
     * A new policy. {@code kind} is {@code ROW_FILTER} or {@code MASK}; a mask names its {@code column}.
     * Nothing is narrowed until it is bound.
     */
    public record NewPolicy(
            String name, String kind, String column, String expression, List<String> exceptRoles, String description) {}

    /** A binding: exactly one of {@code object} (a stream or view) and {@code tag} ({@code key[=value]}). */
    public record BindRequest(String object, String tag) {}

    /** What an unbinding did: whether the policy was bound there. */
    public record Unbound(String policy, String target, boolean unbound) {}

    private final SecurityPolicy policy;
    private final HttpAuthorizer authorizer;

    public PolicyController(SecurityPolicy policy, HttpAuthorizer authorizer) {
        this.policy = policy;
        this.authorizer = authorizer;
    }

    @GetMapping
    @Operation(
            summary = "Row filters and masks the caller may see",
            description = "With object, the policies reaching that stream or view -- bound to it or to one of its tags")
    public PolicyPage list(HttpServletRequest http, @RequestParam(required = false) String object) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        List<PolicyService.PolicyView> views = object == null || object.isBlank()
                ? service.policies().list(caller)
                : service.policies().on(caller, service.resolveWritten(caller, object));
        return new PolicyPage(views.stream().map(PolicyDto::of).toList());
    }

    @GetMapping("/{name}")
    @Operation(summary = "One row filter or mask, and where it is bound")
    public PolicyDto show(HttpServletRequest http, @PathVariable String name) {
        return PolicyDto.of(service().policies().show(authorizer.principalOf(http), name));
    }

    @PostMapping
    @Operation(
            summary = "Create a row filter or a mask",
            description = "Needs CREATE on the namespace; the caller owns it. Bind it to take effect")
    @ApiResponse(responseCode = "201", description = "The policy was created")
    public ResponseEntity<PolicyDto> create(HttpServletRequest http, @RequestBody NewPolicy body) {
        Principal caller = authorizer.principalOf(http);
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new PravahaException(CatalogErrors.INVALID_REQUEST, "a policy needs a name");
        }
        PolicyService policies = service().policies();
        CatalogObject made = policies.create(
                caller,
                PolicyDefinition.Type.parse(body.kind()),
                parts(body.name()),
                body.column(),
                body.expression(),
                body.exceptRoles(),
                body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyDto.of(policies.show(caller, made.fullName())));
    }

    @PostMapping("/{name}/bindings")
    @Operation(
            summary = "Bind a policy to a stream, a view or a tag",
            description = "MANAGE on the object; for a tag, MANAGE on the policy's tenant")
    @ApiResponse(responseCode = "201", description = "The policy was bound")
    public ResponseEntity<BindingDto> bind(
            HttpServletRequest http, @PathVariable String name, @RequestBody BindRequest body) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        PolicyBinding bound = tagged(body)
                ? service.policies().bindTag(caller, parts(name), body.tag())
                : service.policies().bindObject(caller, parts(name), service.resolveWritten(caller, body.object()));
        return ResponseEntity.status(HttpStatus.CREATED).body(BindingDto.of(bound));
    }

    @DeleteMapping("/{name}/bindings")
    @Operation(summary = "Unbind a policy from a stream, a view or a tag", description = "The same rights as binding")
    public Unbound unbind(
            HttpServletRequest http,
            @PathVariable String name,
            @RequestParam(required = false) String object,
            @RequestParam(required = false) String tag) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        BindRequest place = new BindRequest(object, tag);
        String policyName = service.policies().fullNameOf(caller, parts(name));
        if (tagged(place)) {
            return new Unbound(
                    policyName, "TAG '" + tag + "'", service.policies().unbindTag(caller, parts(name), tag));
        }
        CatalogObject target = service.resolveWritten(caller, object);
        return new Unbound(policyName, target.fullName(), service.policies().unbindObject(caller, parts(name), target));
    }

    @DeleteMapping("/{name}")
    @Operation(
            summary = "Drop a row filter or mask",
            description = "MANAGE on the policy, and no binding left: unbind it first, so the widening is audited")
    @ApiResponse(responseCode = "204", description = "The policy was dropped")
    public ResponseEntity<Void> drop(HttpServletRequest http, @PathVariable String name) {
        Principal caller = authorizer.principalOf(http);
        PolicyService policies = service().policies();
        PolicyDefinition.Type type = policies.show(caller, name).definition().type();
        policies.drop(caller, type, parts(name), false);
        return ResponseEntity.noContent().build();
    }

    private static boolean tagged(BindRequest body) {
        boolean object = body != null && body.object() != null && !body.object().isBlank();
        boolean tag = body != null && body.tag() != null && !body.tag().isBlank();
        if (object == tag) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "say where: exactly one of object=<stream or view> and tag=<key[=value]>");
        }
        return tag;
    }

    private static List<String> parts(String name) {
        return List.of(name.strip().split("\\.", -1));
    }

    private CatalogService service() {
        if (policy instanceof CatalogPolicy catalog) {
            return catalog.service();
        }
        throw new PravahaException(
                CatalogErrors.DISABLED,
                "this node's catalogue is off (pravaha.catalog.enabled is false), so there are no row filters or "
                        + "masks to show or change; a policy's programmatic row filter is all there is");
    }
}
