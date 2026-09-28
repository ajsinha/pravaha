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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import com.ash.messaging.pravaha.catalog.Grant;
import com.ash.messaging.pravaha.catalog.Grantee;
import com.ash.messaging.pravaha.catalog.Privilege;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * The Pravaha Catalog over REST (ADR-059 §3): objects with their owners, descriptions and tags;
 * namespaces; grants; and what a user may do, and why. Every rule is {@link CatalogService}'s, so this
 * decides exactly as {@code GRANT} and {@code SHOW GRANTS} do over SQL, and every change it makes is an
 * audit event there. A node whose catalogue is off answers every call here with {@code PRV-7030}.
 *
 * <p>An object is named in a path by its full name ({@code acme.sales.revenue}, {@code acme.sales},
 * {@code *}), or as a person types it -- a view by its registered name, a namespace by its short name,
 * a stream or sink by its name.
 */
@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog", description = "Namespaces, owners, descriptions, tags and grants (ADR-059)")
public class CatalogController {

    /** Who owns an object, or who a grant is to. */
    public record GranteeDto(String type, String name) {
        static GranteeDto of(Grantee grantee) {
            return new GranteeDto(grantee.type(), grantee.name());
        }

        Grantee toGrantee() {
            return Grantee.parse(type, name);
        }
    }

    /** One governed object and what the catalogue records about it. */
    public record CatalogObjectDto(
            String name,
            String kind,
            String engineName,
            String tenant,
            String namespace,
            String shortName,
            GranteeDto owner,
            String description,
            Map<String, String> tags,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy,
            long version) {
        static CatalogObjectDto of(CatalogObject o) {
            return new CatalogObjectDto(
                    o.fullName(),
                    o.kind().name(),
                    o.engineName(),
                    o.tenant(),
                    o.parent(),
                    o.shortName(),
                    GranteeDto.of(o.owner()),
                    o.description(),
                    o.tags(),
                    o.createdAt(),
                    o.createdBy(),
                    o.updatedAt(),
                    o.updatedBy(),
                    o.version());
        }
    }

    public record GrantDto(
            String object, String privilege, String granteeType, String grantee, String grantedBy, Instant grantedAt) {
        static GrantDto of(Grant g) {
            return new GrantDto(
                    g.object(),
                    g.privilege().name(),
                    g.grantee().type(),
                    g.grantee().name(),
                    g.grantedBy(),
                    g.grantedAt());
        }
    }

    /** One privilege's answer: held or not, through what, or why not. */
    public record AccessLineDto(String privilege, boolean allowed, List<String> via, String refusal) {}

    /** What a user may do to an object, privilege by privilege. */
    public record AccessDto(String object, String user, List<AccessLineDto> privileges) {}

    /** An object with the grants on it the caller may see, and what the caller themselves may do. */
    public record ObjectDetail(CatalogObjectDto object, List<GrantDto> grants, AccessDto access) {}

    public record ObjectPage(List<CatalogObjectDto> items) {}

    public record GrantPage(List<GrantDto> items) {}

    public record NewNamespace(String name, String description, Boolean ifNotExists) {}

    /**
     * A grant or a revocation: privileges on an object to or from a role or user. No privileges, or
     * {@code ALL}, means every privilege that applies to the object.
     */
    public record GrantRequest(String object, List<String> privileges, String granteeType, String grantee) {}

    /**
     * A change to an object's metadata; every field optional. {@code namespace} moves a view, with its
     * grants.
     */
    public record ObjectChange(
            String description,
            Map<String, String> setTags,
            List<String> unsetTags,
            GranteeDto owner,
            String namespace) {}

    private final SecurityPolicy policy;
    private final HttpAuthorizer authorizer;

    public CatalogController(SecurityPolicy policy, HttpAuthorizer authorizer) {
        this.policy = policy;
        this.authorizer = authorizer;
    }

    @GetMapping("/objects")
    @Operation(
            summary = "Objects the caller may see",
            description = "Filtered to what the caller may USE; q matches names, descriptions, owners and tags")
    public ObjectPage objects(
            HttpServletRequest http,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q) {
        Principal caller = authorizer.principalOf(http);
        List<CatalogObject> found = q != null && !q.isBlank()
                ? service().search(caller, q).stream()
                        .filter(o -> namespace == null || namespace.isBlank() || namespace.equals(o.parent()))
                        .filter(o -> kind == null
                                || kind.isBlank()
                                || o.kind().name().equalsIgnoreCase(kind))
                        .toList()
                : service().objects(caller, namespace, kind);
        return new ObjectPage(found.stream().map(CatalogObjectDto::of).toList());
    }

    @GetMapping("/objects/{name}")
    @Operation(summary = "One object, its grants, and what the caller may do to it")
    public ObjectDetail object(HttpServletRequest http, @PathVariable String name) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        CatalogObject object = service.resolveWritten(caller, name);
        return new ObjectDetail(
                CatalogObjectDto.of(object),
                service.grantsOn(caller, object).stream().map(GrantDto::of).toList(),
                caller.isAnonymous() ? null : access(service.effectiveAccess(caller, caller.id(), object)));
    }

    @PatchMapping("/objects/{name}")
    @Operation(
            summary = "Change an object's description, tags, owner or namespace",
            description = "MANAGE for description and tags, ownership for the owner, CREATE on the target to move")
    public CatalogObjectDto change(
            HttpServletRequest http, @PathVariable String name, @RequestBody ObjectChange change) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        CatalogObject object = service.resolveWritten(caller, name);
        if (change.description() != null) {
            object = service.comment(caller, object, change.description());
        }
        if (change.setTags() != null && !change.setTags().isEmpty()) {
            object = service.setTags(caller, object, change.setTags());
        }
        if (change.unsetTags() != null && !change.unsetTags().isEmpty()) {
            object = service.unsetTags(caller, object, change.unsetTags());
        }
        if (change.namespace() != null && !change.namespace().isBlank()) {
            object = service.move(caller, object, List.of(change.namespace().split("\\.", -1)));
        }
        if (change.owner() != null) {
            object = service.setOwner(caller, object, change.owner().toGrantee());
        }
        return CatalogObjectDto.of(object);
    }

    @GetMapping("/namespaces")
    @Operation(summary = "Namespaces the caller may use")
    public ObjectPage namespaces(HttpServletRequest http) {
        return new ObjectPage(service().namespaces(authorizer.principalOf(http)).stream()
                .map(CatalogObjectDto::of)
                .toList());
    }

    @PostMapping("/namespaces")
    @Operation(summary = "Create a namespace", description = "Needs CREATE on the tenant; the caller owns it")
    public ResponseEntity<CatalogObjectDto> createNamespace(HttpServletRequest http, @RequestBody NewNamespace body) {
        Principal caller = authorizer.principalOf(http);
        String name = body == null || body.name() == null ? "" : body.name().strip();
        CatalogObject made = service()
                .createNamespace(
                        caller,
                        List.of(name.split("\\.", -1)),
                        body != null && Boolean.TRUE.equals(body.ifNotExists()),
                        body == null ? "" : body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(CatalogObjectDto.of(made));
    }

    @GetMapping("/grants")
    @Operation(
            summary = "Grants on an object, or to a role or user",
            description = "Give object, or granteeType and grantee")
    public GrantPage grants(
            HttpServletRequest http,
            @RequestParam(required = false) String object,
            @RequestParam(required = false) String granteeType,
            @RequestParam(required = false) String grantee) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        List<Grant> found;
        if (object != null && !object.isBlank()) {
            found = service.grantsOn(caller, service.resolveWritten(caller, object));
        } else if (grantee != null && !grantee.isBlank()) {
            found = service.grantsTo(caller, Grantee.parse(granteeType == null ? "USER" : granteeType, grantee));
        } else {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "say which grants: ?object=<name>, or ?granteeType=ROLE&grantee=<name>");
        }
        return new GrantPage(found.stream().map(GrantDto::of).toList());
    }

    @PostMapping("/grants")
    @Operation(summary = "Grant privileges on an object to a role or user", description = "Needs MANAGE on the object")
    public ResponseEntity<GrantPage> grant(HttpServletRequest http, @RequestBody GrantRequest body) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        GrantRequest request = require(body);
        CatalogObject target = service.resolveWritten(caller, request.object());
        List<Grant> made = service.grant(caller, target, privileges(request.privileges()), grantee(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new GrantPage(made.stream().map(GrantDto::of).toList()));
    }

    @DeleteMapping("/grants")
    @Operation(summary = "Revoke privileges on an object from a role or user", description = "Needs MANAGE")
    public ResponseEntity<Void> revoke(
            HttpServletRequest http,
            @RequestParam String object,
            @RequestParam(required = false) List<String> privileges,
            @RequestParam String granteeType,
            @RequestParam String grantee) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        CatalogObject target = service.resolveWritten(caller, object);
        service.revoke(caller, target, privileges(privileges), Grantee.parse(granteeType, grantee));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/access")
    @Operation(
            summary = "What a user may do to an object, and why",
            description = "Which grant, via which role, namespace or ownership; for oneself or a manager of it")
    public AccessDto access(HttpServletRequest http, @RequestParam String user, @RequestParam String object) {
        Principal caller = authorizer.principalOf(http);
        CatalogService service = service();
        return access(service.effectiveAccess(caller, user, service.resolveWritten(caller, object)));
    }

    private static AccessDto access(CatalogService.EffectiveAccess effective) {
        List<AccessLineDto> lines = new ArrayList<>();
        for (CatalogService.AccessLine line : effective.lines()) {
            lines.add(new AccessLineDto(line.privilege().name(), line.allowed(), line.via(), line.refusal()));
        }
        return new AccessDto(effective.object(), effective.user(), lines);
    }

    private CatalogService service() {
        if (policy instanceof CatalogPolicy catalog) {
            return catalog.service();
        }
        throw new PravahaException(
                CatalogErrors.DISABLED,
                "this node's catalogue is off (pravaha.catalog.enabled is false); who may do what is decided by "
                        + "pravaha.security.policy, and there are no grants to show or change");
    }

    private static GrantRequest require(GrantRequest body) {
        if (body == null || body.object() == null || body.object().isBlank()) {
            throw new PravahaException(CatalogErrors.INVALID_REQUEST, "a grant names an object");
        }
        return body;
    }

    private static Grantee grantee(GrantRequest request) {
        return Grantee.parse(request.granteeType(), request.grantee() == null ? "" : request.grantee());
    }

    private static Set<Privilege> privileges(List<String> written) {
        Set<Privilege> privileges = EnumSet.noneOf(Privilege.class);
        if (written == null) {
            return privileges;
        }
        for (String each : written) {
            for (String part : each.split(",")) {
                if (part.isBlank() || part.strip().equalsIgnoreCase("ALL")) {
                    continue;
                }
                privileges.add(Privilege.parse(part));
            }
        }
        return privileges;
    }
}
