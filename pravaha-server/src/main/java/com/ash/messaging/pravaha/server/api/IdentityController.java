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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.identity.IdentityErrors;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.identity.IdentityProperties;
import com.ash.messaging.pravaha.server.identity.SignInSource;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * Users, passwords, API keys and sessions over REST (ADR-052, stage 2). Every rule lives in {@link
 * IdentityService}; this maps requests to it and its answers to JSON, so the console, the CLI and a
 * script meet the same rules.
 *
 * <p>{@code auth/login} and {@code auth/reset/redeem} are the two calls a person makes before holding a
 * credential, and the bearer filter lets them through; everything else needs one. A node with identity
 * off answers every call here with {@code PRV-7021}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Identity", description = "Sign-in, passwords, users, API keys and sessions (ADR-052)")
public class IdentityController {

    public record LoginRequest(String username, String password) {}

    /** {@code mfa} is {@code ok} until MFA is built (stage 4). */
    public record LoginAnswer(String token, Instant expiresAt, boolean mustChangePassword, String mfa) {}

    public record Redeem(String token, String password) {}

    public record Me(
            String username,
            String principal,
            String tenant,
            Set<String> roles,
            String via,
            String displayName,
            String email,
            boolean mustChangePassword,
            Instant passwordExpiresAt) {}

    public record NewUser(
            String username,
            String displayName,
            String email,
            String tenant,
            List<String> roles,
            String password,
            Boolean service) {}

    public record UserChange(String displayName, String email, String tenant, String status) {}

    public record Roles(List<String> roles) {}

    /** A user's attributes, name to value, replaced whole; each is a claim on every credential of theirs. */
    public record Attributes(Map<String, String> attributes) {}

    public record ResetIssued(String resetToken, Instant expiresAt) {}

    public record NewKey(String name, List<String> roles, Integer expiresDays, String forUser) {}

    public record IssuedKey(String key, String keyId, Instant expiresAt, Instant oldExpiresAt) {}

    public record Key(
            String keyId,
            String name,
            String holder,
            Set<String> roles,
            String status,
            Instant createdAt,
            String createdBy,
            Instant expiresAt,
            Instant revokedAt,
            String supersededBy,
            Instant lastUsedAt) {}

    public record KeyReport(List<Key> unused, List<Key> expiring, List<Key> superseded) {}

    public record Session(
            String id, String username, Instant createdAt, Instant lastSeenAt, Instant expiresAt, boolean current) {}

    private final PravahaNode node;
    private final HttpAuthorizer authorizer;
    private final SignInSource sources;

    public IdentityController(PravahaNode node, HttpAuthorizer authorizer, IdentityProperties identity) {
        this.node = node;
        this.authorizer = authorizer;
        this.sources = identity.signInSource();
    }

    private IdentityService identity() {
        return node.identity()
                .orElseThrow(() -> new PravahaException(
                        IdentityErrors.NOT_FOUND,
                        "this node keeps no users or API keys: pravaha.identity.enabled is false"));
    }

    private Principal who(HttpServletRequest request) {
        return authorizer.principalOf(request);
    }

    // ------------------------------------------------------------------ signing in

    @PostMapping("/auth/login")
    @Operation(summary = "Sign in with a user name and password; the token is shown once")
    public LoginAnswer login(@RequestBody LoginRequest body, HttpServletRequest request) {
        IdentityService.Login login = identity()
                .login(
                        body == null ? null : body.username(),
                        body == null ? null : body.password(),
                        // LOCKENUM-1: failures bar the source they came from; through a trusted proxy
                        // (the console) that is the address it signs in for.
                        sources.of(request.getRemoteAddr(), request.getHeader("X-Forwarded-For")));
        return new LoginAnswer(login.token(), login.expiresAt(), login.mustChangePassword(), "ok");
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "End the calling session")
    @ApiResponse(responseCode = "204", description = "The session has ended")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String header = request.getHeader("authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            identity().logout(header.substring(7).strip());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    @Operation(summary = "Who the caller is, and their own account when they have one")
    public Me me(HttpServletRequest request) {
        Principal principal = who(request);
        String via = principal.claims().getOrDefault("via", "token");
        if (!"session".equals(via) && !"key".equals(via)) {
            // A static token: a principal, not an account.
            return new Me(
                    principal.id(),
                    principal.id(),
                    principal.tenant(),
                    principal.roles(),
                    via,
                    null,
                    null,
                    false,
                    null);
        }
        IdentityService users = identity();
        IdentityService.UserView user = users.me(principal);
        Instant expires = users.settings().maxAge().isZero() || user.passwordChangedAt() == null
                ? null
                : user.passwordChangedAt().plus(users.settings().maxAge());
        return new Me(
                user.username(),
                principal.id(),
                principal.tenant(),
                principal.roles(),
                via,
                user.displayName(),
                user.email(),
                BearerTokenFilterAccess.mustChange(principal),
                expires);
    }

    @PostMapping("/auth/password")
    @Operation(summary = "Change the caller's own password; ends their other sessions")
    @ApiResponse(responseCode = "204", description = "The password was changed")
    public ResponseEntity<Void> changePassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String replacement = body.getOrDefault("new", body.get("password"));
        identity().changePassword(who(request), body.get("current"), replacement);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/reset/redeem")
    @Operation(summary = "Set a password with a single-use reset token an administrator issued")
    @ApiResponse(responseCode = "204", description = "The password was set")
    public ResponseEntity<Void> redeem(@RequestBody Redeem body) {
        identity().redeemReset(body.token(), body.password());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/users")
    @Operation(summary = "Every user (admin)")
    public Map<String, List<IdentityService.UserView>> users(HttpServletRequest request) {
        return Map.of("users", identity().users(who(request)));
    }

    @PostMapping("/users")
    @Operation(summary = "Create a user (admin)")
    public IdentityService.UserView createUser(@RequestBody NewUser body, HttpServletRequest request) {
        return identity()
                .createUser(
                        who(request),
                        body.username(),
                        body.displayName(),
                        body.email(),
                        body.tenant(),
                        roles(body.roles()),
                        body.password(),
                        Boolean.TRUE.equals(body.service()));
    }

    @PatchMapping("/users/{username}")
    @Operation(summary = "Change a user's name, email, tenant or status (admin); disabling ends their sessions")
    public IdentityService.UserView updateUser(
            @PathVariable String username, @RequestBody UserChange body, HttpServletRequest request) {
        return identity()
                .updateUser(who(request), username, body.displayName(), body.email(), body.tenant(), body.status());
    }

    @PutMapping("/users/{username}/roles")
    @Operation(summary = "Set a user's roles (admin)")
    public IdentityService.UserView setRoles(
            @PathVariable String username, @RequestBody Roles body, HttpServletRequest request) {
        return identity().setRoles(who(request), username, roles(body.roles()));
    }

    @PutMapping("/users/{username}/attributes")
    @Operation(
            summary = "Set a user's attributes (admin)",
            description = "Replaces the whole set, like roles: an attribute left out is removed. Each attribute "
                    + "is presented as a claim by every session and API key of the user's, which is what a "
                    + "policy's session_attribute('name') reads (STORECLAIMS-1). The names via, session, key and "
                    + "mustChangePassword are the engine's own and are refused (PRV-7020).")
    public IdentityService.UserView setAttributes(
            @PathVariable String username, @RequestBody Attributes body, HttpServletRequest request) {
        return identity().setAttributes(who(request), username, body.attributes());
    }

    @PostMapping("/users/{username}/password-reset")
    @Operation(summary = "Issue a single-use reset token, shown once (admin)")
    public ResetIssued issueReset(@PathVariable String username, HttpServletRequest request) {
        IdentityService.Reset reset = identity().issueReset(who(request), username);
        return new ResetIssued(reset.token(), reset.expiresAt());
    }

    // ------------------------------------------------------------------ API keys

    @GetMapping("/keys")
    @Operation(summary = "The caller's keys, or every key with all=true (admin); never a secret")
    public Map<String, List<Key>> keys(
            @RequestParam(name = "all", defaultValue = "false") boolean all, HttpServletRequest request) {
        return Map.of("keys", keys(identity().keys(who(request), all)));
    }

    @PostMapping("/keys")
    @Operation(summary = "Issue a key; the key is shown once")
    public IssuedKey createKey(@RequestBody NewKey body, HttpServletRequest request) {
        IdentityService.IssuedKey key = identity()
                .createKey(
                        who(request),
                        body.name(),
                        body.roles() == null ? null : roles(body.roles()),
                        body.expiresDays(),
                        body.forUser());
        return new IssuedKey(key.key(), key.keyId(), key.expiresAt(), null);
    }

    @DeleteMapping("/keys/{keyId}")
    @Operation(summary = "Revoke a key, at once")
    @ApiResponse(responseCode = "204", description = "The key was revoked")
    public ResponseEntity<Void> revokeKey(@PathVariable String keyId, HttpServletRequest request) {
        identity().revokeKey(who(request), keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/keys/{keyId}/rotate")
    @Operation(summary = "A successor with the same scope; the old key works until the overlap ends")
    public IssuedKey rotateKey(@PathVariable String keyId, HttpServletRequest request) {
        IdentityService.Rotated rotated = identity().rotateKey(who(request), keyId);
        return new IssuedKey(
                rotated.successor().key(),
                rotated.successor().keyId(),
                rotated.successor().expiresAt(),
                rotated.oldExpiresAt());
    }

    @GetMapping("/keys/report")
    @Operation(summary = "Keys never used, expiring within 14 days, or superseded (admin)")
    public KeyReport keyReport(HttpServletRequest request) {
        IdentityService.KeyReport report = identity().keyReport(who(request));
        return new KeyReport(keys(report.unused()), keys(report.expiring()), keys(report.superseded()));
    }

    // ------------------------------------------------------------------ sessions

    @GetMapping("/sessions")
    @Operation(summary = "The caller's sessions, or every session with all=true (admin)")
    public Map<String, List<Session>> sessions(
            @RequestParam(name = "all", defaultValue = "false") boolean all, HttpServletRequest request) {
        Principal principal = who(request);
        String current = principal.claims().get("session");
        return Map.of(
                "sessions",
                identity().sessions(principal, all).stream()
                        .map(s -> new Session(
                                s.id(),
                                s.username(),
                                s.createdAt(),
                                s.lastSeen(),
                                s.expiresAt(),
                                s.id().equals(current)))
                        .toList());
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "End a session: the caller's own, or anybody's (admin)")
    @ApiResponse(responseCode = "204", description = "The session has ended")
    public ResponseEntity<Void> endSession(@PathVariable String id, HttpServletRequest request) {
        identity().endSession(who(request), id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> roles(List<String> roles) {
        return roles == null ? Set.of() : new LinkedHashSet<>(roles);
    }

    private static List<Key> keys(List<IdentityService.KeyView> views) {
        Instant now = Instant.now();
        return views.stream()
                .map(k -> new Key(
                        k.keyId(),
                        k.name(),
                        k.holder(),
                        k.roles(),
                        k.revokedAt() != null
                                ? "revoked"
                                : !now.isBefore(k.expiresAt())
                                        ? "expired"
                                        : k.rotatedTo() != null ? "superseded" : "active",
                        k.createdAt(),
                        k.createdBy(),
                        k.expiresAt(),
                        k.revokedAt(),
                        k.rotatedTo(),
                        k.lastUsedAt()))
                .toList();
    }

    /** The filter's test for a session held back to change its password, read from the principal. */
    private static final class BearerTokenFilterAccess {
        static boolean mustChange(Principal principal) {
            return com.ash.messaging.pravaha.server.security.BearerTokenFilter.mustChangePassword(principal);
        }
    }
}
