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

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * The authorization decision for an HTTP request, against the same policy the engine enforces.
 *
 * <p>The REST controllers consulted no policy, no audit and no principal at all. {@link
 * BearerTokenFilter} authenticated the caller and put them in a request attribute, and nothing read
 * it -- so a principal denied every payroll view on Flight received byte-identical responses to a
 * fully privileged one on every HTTP endpoint: the full schema of {@code payroll} including its
 * {@code salary} column, full unfiltered plans for payroll queries from {@code explain}, and the
 * node's status. {@code POST /api/v1/streams} applied no check beyond "a token verified", so the
 * same principal could publish an arbitrary stream schema.
 *
 * <p>One policy object, shared with the registry, because two ways to decide the same question
 * diverge in the direction of whichever one somebody forgot -- which is the whole of what happened
 * here.
 */
@Component
public class HttpAuthorizer {

    private final SecurityPolicy policy;
    private final AuditSink audit;

    public HttpAuthorizer(SecurityPolicy policy, AuditSink audit) {
        this.policy = policy;
        this.audit = audit;
    }

    /** Who is calling, or anonymous when this node does not authenticate. */
    public Principal principalOf(HttpServletRequest request) {
        Object found = request == null ? null : request.getAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE);
        return found instanceof Principal principal ? principal : Principal.ANONYMOUS;
    }

    /** Whether this caller may see {@code name} at all. */
    public boolean mayRead(HttpServletRequest request, String name) {
        return policy.mayRead(principalOf(request), name).allowed();
    }

    /**
     * Refuses unless this caller may read {@code name}, recording the decision either way.
     *
     * <p>The refusal says only that the name is not readable, which is deliberately the same answer
     * a caller gets for a name that does not exist: telling them apart is an existence oracle over
     * every view on the node.
     */
    public void requireRead(HttpServletRequest request, String name) {
        Principal principal = principalOf(request);
        AccessDecision decision = policy.mayRead(principal, name);
        audit.record(com.ash.messaging.pravaha.security.AuditEvent.of(principal, "http.read", name, decision, ""));
        if (!decision.allowed()) {
            throw new PravahaException(SecurityErrors.FORBIDDEN, principal.id() + " may not read '" + name + "'.");
        }
    }

    /** Refuses unless this caller may change what the node serves. */
    public void requireAdminister(HttpServletRequest request, String what) {
        Principal principal = principalOf(request);
        AccessDecision decision = policy.mayAdminister(principal, what);
        audit.record(
                com.ash.messaging.pravaha.security.AuditEvent.of(principal, "http.administer", what, decision, ""));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, principal.id() + " may not change '" + what + "': " + decision.reason());
        }
    }
}
