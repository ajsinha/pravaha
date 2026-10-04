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
package com.ash.messaging.pravaha.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What the engine knows about the people and programs that may call it (ADR-052). Immutable: a change
 * is a new value, written whole to the journal, so replay is last-writer-wins and needs no diffing.
 */
public final class Identities {

    private Identities() {}

    /**
     * A person or a service account. Disabled, never deleted: the audit trail names owners by id.
     *
     * <p>{@code attributes} are facts an administrator records about the user -- {@code region=EU} -- and
     * every credential of theirs presents them as the principal's claims, which is what a policy's
     * {@code session_attribute('region')} reads (STORECLAIMS-1).
     */
    public record User(
            String username,
            @Nullable String displayName,
            @Nullable String email,
            @Nullable String tenant,
            Set<String> roles,
            String status,
            boolean service,
            @Nullable String passwordHash,
            List<String> previousHashes,
            boolean mustChangePassword,
            @Nullable Instant passwordChangedAt,
            int failedAttempts,
            @Nullable Instant firstFailedAt,
            @Nullable Instant lockedUntil,
            @Nullable Instant lastLoginAt,
            Instant createdAt,
            Map<String, String> attributes) {

        public User {
            roles = Set.copyOf(roles);
            previousHashes = List.copyOf(previousHashes);
            attributes = attributes == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(attributes));
        }

        public boolean active() {
            return "active".equals(status);
        }

        User withPassword(String hash, List<String> previous, boolean mustChange, Instant at) {
            return new User(
                    username,
                    displayName,
                    email,
                    tenant,
                    roles,
                    status,
                    service,
                    hash,
                    previous,
                    mustChange,
                    at,
                    0,
                    null,
                    null,
                    lastLoginAt,
                    createdAt,
                    attributes);
        }

        User withFailures(int failed, @Nullable Instant first, @Nullable Instant locked) {
            return new User(
                    username,
                    displayName,
                    email,
                    tenant,
                    roles,
                    status,
                    service,
                    passwordHash,
                    previousHashes,
                    mustChangePassword,
                    passwordChangedAt,
                    failed,
                    first,
                    locked,
                    lastLoginAt,
                    createdAt,
                    attributes);
        }

        User withLogin(Instant at, @Nullable String rehashed, boolean mustChange) {
            return new User(
                    username,
                    displayName,
                    email,
                    tenant,
                    roles,
                    status,
                    service,
                    rehashed,
                    previousHashes,
                    mustChange,
                    passwordChangedAt,
                    0,
                    null,
                    null,
                    at,
                    createdAt,
                    attributes);
        }

        User withProfile(
                @Nullable String display, @Nullable String mail, @Nullable String tenantName, String newStatus) {
            return new User(
                    username,
                    display,
                    mail,
                    tenantName,
                    roles,
                    newStatus,
                    service,
                    passwordHash,
                    previousHashes,
                    mustChangePassword,
                    passwordChangedAt,
                    failedAttempts,
                    firstFailedAt,
                    lockedUntil,
                    lastLoginAt,
                    createdAt,
                    attributes);
        }

        User withRoles(Set<String> newRoles) {
            return new User(
                    username,
                    displayName,
                    email,
                    tenant,
                    newRoles,
                    status,
                    service,
                    passwordHash,
                    previousHashes,
                    mustChangePassword,
                    passwordChangedAt,
                    failedAttempts,
                    firstFailedAt,
                    lockedUntil,
                    lastLoginAt,
                    createdAt,
                    attributes);
        }

        /**
         * With {@code newAttributes} as the user's attributes, which every credential of theirs presents as
         * claims (STORECLAIMS-1).
         */
        User withAttributes(Map<String, String> newAttributes) {
            return new User(
                    username,
                    displayName,
                    email,
                    tenant,
                    roles,
                    status,
                    service,
                    passwordHash,
                    previousHashes,
                    mustChangePassword,
                    passwordChangedAt,
                    failedAttempts,
                    firstFailedAt,
                    lockedUntil,
                    lastLoginAt,
                    createdAt,
                    newAttributes);
        }
    }

    /**
     * An API key, {@code prv_<env>_<keyId>_<secret>}. The secret is never stored, only {@code secretHash};
     * {@code keyId} is the lookup index and what an administrator and the audit trail see.
     */
    public record ApiKey(
            String keyId,
            @Nullable String name,
            String holder,
            Set<String> roles,
            String secretHash,
            Instant createdAt,
            @Nullable String createdBy,
            Instant expiresAt,
            @Nullable Instant revokedAt,
            @Nullable String rotatedTo,
            @Nullable Instant lastUsedAt) {

        public ApiKey {
            roles = Set.copyOf(roles);
        }

        public boolean usableAt(Instant now) {
            return revokedAt == null && now.isBefore(expiresAt);
        }

        ApiKey revoked(Instant at) {
            return new ApiKey(
                    keyId, name, holder, roles, secretHash, createdAt, createdBy, expiresAt, at, rotatedTo, lastUsedAt);
        }

        ApiKey rotated(String successor, Instant newExpiry) {
            return new ApiKey(
                    keyId,
                    name,
                    holder,
                    roles,
                    secretHash,
                    createdAt,
                    createdBy,
                    newExpiry,
                    revokedAt,
                    successor,
                    lastUsedAt);
        }

        ApiKey used(Instant at) {
            return new ApiKey(
                    keyId, name, holder, roles, secretHash, createdAt, createdBy, expiresAt, revokedAt, rotatedTo, at);
        }
    }

    /** A signed-in person's session. {@code id} is public (listable, endable); the token is not stored. */
    public record Session(String id, String tokenHash, String username, Instant createdAt, Instant absoluteExpiry) {}

    /** An administrator-issued reset token, stored as its hash, single use. */
    public record ResetToken(
            String tokenHash,
            String username,
            Instant expiresAt,
            @Nullable String issuedBy) {}
}
