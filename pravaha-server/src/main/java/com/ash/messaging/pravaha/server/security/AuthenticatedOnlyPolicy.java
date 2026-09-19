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
package com.ash.messaging.pravaha.server.security;

import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * Anyone who proved who they are may read and register; nobody else may do either.
 *
 * <p>The smallest policy that is not {@code PERMISSIVE}, and deliberately not more than that. It
 * would be easy to ship something here that looked like multi-tenancy -- matching a view's name
 * against a principal's tenant, say -- and it would be a guess: views carry a name and a schema and
 * no notion of who owns them, so any mapping from one to the other would be this class inventing a
 * convention and enforcing it on deployments that never agreed to it.
 *
 * <p>What a deployment with real rules does instead is implement {@link SecurityPolicy}, which is
 * three methods and gets the principal, the view name and the chance to return a row filter. This
 * exists so that "only authenticated callers reach data" is reachable from configuration alone,
 * which is the property most deployments actually need first.
 */
public final class AuthenticatedOnlyPolicy implements SecurityPolicy {

    /** The role that may read the audit trail when none is configured. */
    public static final String DEFAULT_AUDIT_ROLE = "admin";

    private final java.util.Set<String> auditRoles;

    /** Audit readable by principals holding {@value #DEFAULT_AUDIT_ROLE}. */
    public AuthenticatedOnlyPolicy() {
        this(java.util.Set.of(DEFAULT_AUDIT_ROLE));
    }

    /**
     * @param auditRoles the roles whose holders may read the audit trail
     *     ({@code pravaha.security.audit-readers}). Empty means nobody may, over HTTP: the file, if
     *     there is one, is still there for whoever the operating system lets read it
     */
    public AuthenticatedOnlyPolicy(java.util.Collection<String> auditRoles) {
        this.auditRoles = auditRoles == null
                ? java.util.Set.of()
                : auditRoles.stream()
                        .filter(role -> role != null && !role.isBlank())
                        .map(String::strip)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Only for a verified principal holding one of the configured roles.
     *
     * <p>Not "any authenticated caller", which is who may read every view here: the trail says who
     * else read them and with which SQL, and being entitled to the data is not being entitled to
     * that. A role is the only thing about a principal this policy can check without inventing a
     * convention, and it is the one the design gives this screen (§23.6, admin).
     */
    @Override
    public AccessDecision mayReadAudit(Principal principal) {
        if (principal == null || principal.isAnonymous()) {
            return AccessDecision.deny("the audit trail is served only to authenticated callers");
        }
        for (String role : auditRoles) {
            if (principal.hasRole(role)) {
                return AccessDecision.allow();
            }
        }
        return AccessDecision.deny(
                auditRoles.isEmpty()
                        ? "no role may read the audit trail on this node (pravaha.security.audit-readers is empty)"
                        : "reading the audit trail needs one of the roles " + new java.util.TreeSet<>(auditRoles));
    }

    /** The roles that may read the audit trail. */
    public java.util.Set<String> auditRoles() {
        return auditRoles;
    }

    @Override
    public AccessDecision mayRead(Principal principal, String view) {
        if (principal == null || principal.isAnonymous()) {
            return AccessDecision.deny("this server serves data only to authenticated callers, and this "
                    + "call presented no credential Pravaha could verify");
        }
        return AccessDecision.allow();
    }

    @Override
    public AccessDecision mayRegisterQuery(Principal principal) {
        if (principal == null || principal.isAnonymous()) {
            return AccessDecision.deny("registering a continuous query costs threads and state for as long "
                    + "as it runs, so it is refused to unauthenticated callers");
        }
        return AccessDecision.allow();
    }

    @Override
    public String toString() {
        return "authenticated-only";
    }
}
