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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.security.TokenVerifier;

/**
 * {@code pravaha.security.*}: who may connect, and what they may see.
 *
 * <p>Every mechanism this configures was already built and tested -- {@code mayRead} on each view,
 * row filters honoured on subscribe, per-source checks at registration, principals from verified
 * tokens, re-authorization on recovery. None of it was reachable, because the node constructed
 * {@code SecurityPolicy.PERMISSIVE} and {@code AuditSink.NONE} in the constructor and never called
 * {@code authenticatedBy} at all. The lock was built and the door was propped open.
 *
 * <p>The defaults are still open, because an engine embedded in a process that has already
 * authenticated its caller should not have to configure authentication twice. What is new is that
 * running open is now a <em>decision</em>: {@link #isAllowAnonymous()} must be set, and the node
 * refuses to start without it rather than serving everything to everybody quietly.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.security")
public class SecurityProperties {

    /** {@code none} or {@code token}. */
    private String authentication = "none";

    /** {@code permissive} (everyone sees everything) or {@code tenant} (a tenant sees its own). */
    private String policy = "permissive";

    /** {@code none}, {@code memory} or {@code log}. */
    private String audit = "none";

    /**
     * Acknowledges that this server serves everything to unauthenticated callers.
     *
     * <p>Deliberately awkward. Without it, a server configured with no authentication and a
     * permissive policy refuses to start, so an open deployment is something somebody wrote down
     * rather than something nobody noticed.
     */
    private boolean allowAnonymous;

    private Map<String, TokenSpec> tokens = new LinkedHashMap<>();

    public String getAuthentication() {
        return authentication;
    }

    public void setAuthentication(String authentication) {
        this.authentication = authentication;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getAudit() {
        return audit;
    }

    public void setAudit(String audit) {
        this.audit = audit;
    }

    public boolean isAllowAnonymous() {
        return allowAnonymous;
    }

    public void setAllowAnonymous(boolean allowAnonymous) {
        this.allowAnonymous = allowAnonymous;
    }

    public Map<String, TokenSpec> getTokens() {
        return tokens;
    }

    public void setTokens(Map<String, TokenSpec> tokens) {
        this.tokens = tokens == null ? new LinkedHashMap<>() : tokens;
    }

    /** Whether callers must present a credential. */
    public boolean authenticates() {
        return "token".equalsIgnoreCase(trimmedAuthentication());
    }

    /**
     * The configured value, validated.
     *
     * <p>An authentication setting that fails open on a typo is worse than no setting at all:
     * "tokens", "basic" and "token " all silently meant `none`, so a deployment that believed it had
     * switched authentication on had switched nothing on. The neighbouring `policy` key trims and
     * refuses; this one did neither.
     */
    private String trimmedAuthentication() {
        String value = authentication == null ? "none" : authentication.trim();
        if (!value.equalsIgnoreCase("none") && !value.equalsIgnoreCase("token")) {
            throw new IllegalArgumentException("pravaha.security.authentication is '" + authentication
                    + "'; the values are 'none' and 'token'. A misspelling here would otherwise mean "
                    + "'none', so a node that looked authenticated would accept every caller.");
        }
        return value;
    }

    /**
     * Builds the verifier, or null when authentication is off.
     *
     * <p>Static tokens are for a development server and a test, and the type says so by name. A
     * deployment that needs real identity implements {@link TokenVerifier} against whatever issues
     * its credentials; this is the rung that makes the authenticated path reachable without one.
     */
    public TokenVerifier verifier() {
        if (!authenticates()) {
            return null;
        }
        if (tokens.isEmpty()) {
            // Rather than accepting nothing silently: a server that authenticates with no
            // credentials configured refuses every call, and the reason should be visible at
            // startup rather than in a support ticket about 401s.
            return TokenVerifier.rejectAll();
        }
        StaticTokenVerifier verifier = null;
        for (Map.Entry<String, TokenSpec> entry : tokens.entrySet()) {
            TokenSpec spec = entry.getValue();
            Principal principal = new Principal(
                    spec.getId() == null ? entry.getKey() : spec.getId(),
                    spec.getTenant(),
                    new LinkedHashSet<>(spec.getRoles()),
                    Map.of());
            verifier = verifier == null
                    ? StaticTokenVerifier.of(entry.getKey(), principal)
                    : verifier.and(entry.getKey(), principal);
        }
        return verifier;
    }

    /** One credential and the identity it stands for. */
    public static class TokenSpec {

        private String id;
        private String tenant = "public";
        private Set<String> roles = new LinkedHashSet<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public Set<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(roles);
        }
    }
}
