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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * A verifier over a fixed table of tokens, for tests and for deployments small enough that the list
 * of who may connect is a configuration file.
 *
 * <p>It is worth being honest about what this is and is not. Tokens live in memory as given; there
 * is no rotation, no expiry, and no revocation short of restarting with a different table. That is
 * adequate for a test and for a single-tenant install behind its own boundary, and it is not
 * adequate for a multi-tenant service, which should supply a verifier backed by its identity
 * provider. The class is named for what it does so that nobody discovers this by reading the source
 * after deciding to depend on it.
 *
 * <p>Lookup compares digests rather than the tokens themselves, and compares them with {@link
 * MessageDigest#isEqual}. A hash map keyed by the raw token would answer a lookup in time that
 * depends on where the comparison first differs; over enough attempts that difference is a token,
 * one character at a time. Hashing first makes every comparison the same length and the timing
 * independent of the guess.
 */
public final class StaticTokenVerifier implements TokenVerifier {

    private final Map<String, Principal> byDigest = new ConcurrentHashMap<>();

    private StaticTokenVerifier() {}

    /** A verifier holding one token. */
    public static StaticTokenVerifier of(String token, Principal principal) {
        return new StaticTokenVerifier().and(token, principal);
    }

    /** Adds a token, returning this verifier so the calls chain. */
    public StaticTokenVerifier and(String token, Principal principal) {
        Objects.requireNonNull(principal, "principal");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("a blank token would let an empty header authenticate");
        }
        if (principal.isAnonymous()) {
            throw new IllegalArgumentException(
                    "a token must not resolve to the anonymous principal; that would make an "
                            + "authenticated call indistinguishable from an unauthenticated one in the audit log");
        }
        byDigest.put(digest(token), principal);
        return this;
    }

    @Override
    public Principal verify(String token) {
        if (token != null && !token.isBlank()) {
            String presented = digest(token);
            for (Map.Entry<String, Principal> entry : byDigest.entrySet()) {
                // Scanned rather than looked up, and every entry is visited even after a match, so
                // that the work done does not depend on which token was presented.
                if (MessageDigest.isEqual(
                        entry.getKey().getBytes(StandardCharsets.US_ASCII),
                        presented.getBytes(StandardCharsets.US_ASCII))) {
                    return entry.getValue();
                }
            }
        }
        throw new PravahaException(SecurityErrors.UNAUTHENTICATED, "the credential presented was not accepted");
    }

    /** The number of tokens configured; the tokens themselves are not retrievable by design. */
    public int size() {
        return byDigest.size();
    }

    private static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
