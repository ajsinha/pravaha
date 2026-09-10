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
package com.ash.messaging.pravaha.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who is asking.
 *
 * <p>Deliberately not a token, a session or a connection. Authentication produces one of these and
 * everything downstream reasons about it, so the engine never has to know whether the identity came
 * from OIDC, a client certificate or a test fixture -- which is what makes the policy testable
 * without an identity provider.
 *
 * <p>The claims map is the extension point that keeps this record from growing. A row filter written
 * as "desk = the caller's desk" needs a desk, and where that comes from is the deployment's
 * business: a JWT claim, an LDAP attribute, a lookup. Putting it in a map means adding one costs
 * nothing here.
 *
 * @param id stable and unique -- what the audit log records and what a row filter is keyed on
 * @param tenant the namespace this principal belongs to; multi-tenancy is enforced on it (FR-9)
 * @param roles for coarse decisions: viewer, analyst, operator, admin (§25)
 * @param claims everything else the identity provider said, for row filters to reference
 */
public record Principal(String id, String tenant, Set<String> roles, Map<String, String> claims) {

    /** What an unauthenticated call is, when a deployment allows them at all. */
    public static final Principal ANONYMOUS = new Principal("anonymous", "public", Set.of(), Map.of());

    public Principal {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a principal needs an id; the audit log has nothing to record "
                    + "without one, and a row filter has nothing to key on");
        }
        tenant = tenant == null || tenant.isBlank() ? "public" : tenant;
        roles = Set.copyOf(roles == null ? Set.of() : roles);
        claims = Map.copyOf(claims == null ? Map.of() : claims);
    }

    /** A principal with an id and nothing else, for a deployment with no roles yet. */
    public static Principal of(String id) {
        return new Principal(id, "public", Set.of(), Map.of());
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAnonymous() {
        return ANONYMOUS.id().equals(id);
    }

    /** A claim, or empty. Row filters read these. */
    public java.util.Optional<String> claim(String name) {
        return java.util.Optional.ofNullable(claims.get(name));
    }

    /**
     * Never prints the claims.
     *
     * <p>Claims carry whatever the identity provider chose to put in a token, which in practice
     * includes email addresses and sometimes worse. A principal ends up in log lines and exception
     * messages, and a toString that helpfully dumped them would put them there too.
     */
    @Override
    public String toString() {
        return "Principal[" + id + " in " + tenant + ", roles=" + roles + "]";
    }
}
