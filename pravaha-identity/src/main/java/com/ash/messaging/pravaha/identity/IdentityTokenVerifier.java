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

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.TokenVerifier;

/**
 * The engine's one authentication path once identity is on: a session or API key resolves through
 * {@link IdentityService}; anything else is tried against the legacy verifier (the static token table),
 * which is logged as deprecated once per token.
 *
 * <p>Every refusal answers the caller with the same message, as {@link TokenVerifier} requires: whether a
 * key was expired, revoked or for another environment is in the audit trail, not in the reply.
 */
public final class IdentityTokenVerifier implements TokenVerifier {

    private static final System.Logger LOG = System.getLogger(IdentityTokenVerifier.class.getName());

    private final IdentityService identity;
    private final TokenVerifier legacy;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public IdentityTokenVerifier(IdentityService identity, @Nullable TokenVerifier legacy) {
        this.identity = identity;
        this.legacy = legacy == null ? TokenVerifier.rejectAll() : legacy;
    }

    @Override
    public Principal verify(String token) {
        Optional<Principal> ours;
        try {
            ours = identity.principalFor(token);
        } catch (PravahaException refused) {
            throw new PravahaException(SecurityErrors.UNAUTHENTICATED, "the credential was rejected");
        }
        if (ours.isPresent()) {
            return ours.get();
        }
        Principal legacyPrincipal = legacy.verify(token);
        if (warned.add(legacyPrincipal.id())) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "a static token from pravaha.security.tokens authenticated "
                            + legacyPrincipal.id()
                            + "; static tokens are deprecated now that this node keeps users and "
                            + "API keys (ADR-052) -- issue an API key and remove the token from the configuration");
        }
        return legacyPrincipal;
    }
}
