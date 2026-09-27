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

/**
 * Turning a bearer credential into a {@link Principal}, or refusing to.
 *
 * <p>This is the authentication boundary, and it is deliberately the whole of it: every credential a
 * transport receives passes through one verifier. A node that keeps its own users and API keys
 * (ADR-052, {@code pravaha-identity}) supplies one that resolves sessions and keys; a deployment
 * with its own identity provider supplies one that validates a JWT or calls an introspection
 * endpoint; a test, or a single-tenant deployment behind its own wall, consults a static table.
 *
 * <p>The contract is narrow on purpose. A verifier is given an opaque credential and returns who it
 * belongs to. It is <em>not</em> asked what that principal may read; that is {@link SecurityPolicy},
 * and keeping the two apart is what lets a deployment adopt an external identity provider without
 * rewriting its authorization rules, or tighten its rules without touching authentication.
 *
 * <p>Implementations must be thread-safe: one verifier serves every concurrent call on the server.
 */
@FunctionalInterface
public interface TokenVerifier {

    /**
     * Resolves {@code token} to the principal that presented it.
     *
     * @param token the credential exactly as the client sent it, with any {@code Bearer} prefix
     *     already stripped
     * @return the authenticated principal, never {@code null} and never {@link Principal#ANONYMOUS}
     * @throws com.ash.messaging.pravaha.api.PravahaException with {@link
     *     SecurityErrors#UNAUTHENTICATED} if the credential is not valid. The message reaches the
     *     client, so it must say that the credential was rejected and nothing about why: "expired"
     *     versus "unknown" versus "wrong signature" is three bits of an oracle for whoever is
     *     guessing.
     */
    Principal verify(String token);

    /** A verifier that rejects everything, which is what a server with no identity source should do. */
    static TokenVerifier rejectAll() {
        return token -> {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    SecurityErrors.UNAUTHENTICATED,
                    "this server has no way to verify credentials, so it accepts none. Configure a "
                            + "TokenVerifier, or run without authentication if the server is already "
                            + "behind a boundary that does it.");
        };
    }
}
