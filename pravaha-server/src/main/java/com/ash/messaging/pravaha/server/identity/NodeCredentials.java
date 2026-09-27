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
package com.ash.messaging.pravaha.server.identity;

import java.util.Optional;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.identity.IdentityErrors;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.identity.IdentityTokenVerifier;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.SecurityProperties;

/**
 * How a node turns a credential into a principal: static tokens alone, or users, keys and sessions with
 * static tokens as the legacy source (ADR-052). One verifier for HTTP, Flight and the PostgreSQL wire.
 */
public final class NodeCredentials {

    private final SecurityProperties security;
    private final IdentityProperties identity;
    private final Supplier<AuditSink> audit;
    private TokenVerifier verifier;
    private boolean built;

    public NodeCredentials(SecurityProperties security, IdentityProperties identity, Supplier<AuditSink> audit) {
        this.security = security;
        this.identity = identity;
        this.audit = audit;
    }

    /** The identity service, when {@code pravaha.identity.enabled} is set. */
    public Optional<IdentityService> identity() {
        return identity == null ? Optional.empty() : identity.service(audit.get());
    }

    /**
     * The one verifier every transport authenticates with, or null when authentication is off. With
     * identity on, a session or API key resolves through the identity service and a static token is still
     * accepted, logged as deprecated.
     */
    public synchronized TokenVerifier verifier() {
        if (built) {
            return verifier;
        }
        Optional<IdentityService> users = identity();
        if (users.isEmpty()) {
            verifier = security.verifier();
        } else {
            if (!security.authenticates()) {
                throw new PravahaException(
                        SecurityErrors.MISCONFIGURED,
                        "pravaha.identity.enabled is true and "
                                + "pravaha.security.authentication is not token, so nothing would ever ask for the users "
                                + "and keys it keeps. Set authentication: token, or turn identity off.");
            }
            TokenVerifier legacy = security.getTokens().isEmpty()
                    ? token -> {
                        throw new PravahaException(SecurityErrors.UNAUTHENTICATED, "the credential was rejected");
                    }
                    : security.verifier();
            verifier = new IdentityTokenVerifier(users.get(), legacy);
        }
        built = true;
        return verifier;
    }

    /**
     * {@link #verifier()} for Flight and the PostgreSQL gateway, which also refuses a session held back to
     * change its password first (PRV-7018): those transports have no call for changing it, so the only
     * right answer there is no.
     */
    public TokenVerifier transportVerifier() {
        TokenVerifier base = verifier();
        if (base == null || identity == null || !identity.isEnabled()) {
            return base;
        }
        return token -> {
            Principal principal = base.verify(token);
            if (BearerTokenFilter.mustChangePassword(principal)) {
                throw new PravahaException(
                        IdentityErrors.MUST_CHANGE_PASSWORD,
                        "this account must change its password before anything else; sign in to the console "
                                + "or POST /api/v1/auth/password");
            }
            return principal;
        };
    }
}
