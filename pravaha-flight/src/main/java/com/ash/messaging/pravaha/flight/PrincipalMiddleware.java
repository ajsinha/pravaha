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
package com.ash.messaging.pravaha.flight;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.RequestContext;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.TokenVerifier;

/**
 * Authentication on the Flight transport: the {@code authorization} header becomes a {@link
 * Principal} that the producer can read (ADR-031).
 *
 * <p>Middleware rather than Flight's {@code CallHeaderAuthenticator} because of what the producer
 * needs. An authenticator hands the call an opaque {@code peerIdentity} string, and a string is not
 * enough to authorize with -- the policy wants the tenant, the roles, and the claims. Recovering
 * those from an identity string means a server-side session table keyed by it, which is state to
 * size, evict, and get wrong. Middleware carries the whole principal on the call itself, and Flight
 * instantiates it per call, so there is nothing to evict and nothing to leak between callers.
 *
 * <p>Every call is verified, including the metadata calls. It is tempting to let {@code
 * getFlightInfo} through unauthenticated on the grounds that a schema is not data, but a schema is
 * a list of the columns a business keeps about its customers, and the set of view names is a map of
 * what this deployment does. Both are worth a refusal.
 */
public final class PrincipalMiddleware implements FlightServerMiddleware {

    /** The key the producer uses to read the principal off a call. */
    public static final Key<PrincipalMiddleware> KEY = Key.of("pravaha-principal");

    private static final String HEADER = "authorization";
    private static final String BEARER = "bearer ";

    private final Principal principal;

    /**
     * The credential and the verifier that accepted it, so a long-lived call can ask again.
     *
     * <p>A subscription is authorised once, when it opens, and then runs for as long as the client
     * keeps the connection. Revoking a credential mid-stream did nothing: a row committed ten
     * seconds after revocation still arrived, while a *fresh* subscribe was correctly refused. The
     * exposure had no bound -- an open subscription outlived the credential that authorised it for
     * as long as the socket stayed up, which is the owner's second constraint broken (only
     * authenticated users may reach data) by a caller who is no longer authenticated.
     *
     * <p>Holding the token for the life of the call is not a new exposure: the client sent it,
     * the call is already running on it, and it lives no longer than the connection it authorised.
     */
    private final String credential;

    private final TokenVerifier verifier;

    private PrincipalMiddleware(Principal principal) {
        this(principal, null, null);
    }

    private PrincipalMiddleware(Principal principal, String credential, TokenVerifier verifier) {
        this.principal = principal;
        this.credential = credential;
        this.verifier = verifier;
    }

    /** The authenticated caller. */
    public Principal principal() {
        return principal;
    }

    /**
     * Whether the credential this call opened with is still accepted.
     *
     * <p>For anything long-lived to ask periodically. A call with no retained credential -- a server
     * that does not authenticate -- answers true: there is nothing to revoke.
     */
    public boolean credentialStillValid() {
        if (verifier == null || credential == null) {
            return true;
        }
        try {
            Principal again = verifier.verify(credential);
            return again != null && !again.isAnonymous();
        } catch (PravahaException refused) {
            return false;
        }
    }

    @Override
    public void onBeforeSendingHeaders(CallHeaders outgoingHeaders) {
        // Nothing is sent back. In particular the principal is not echoed: a client that needs to
        // know who it is authenticated as can ask, and a header saying so is one more place for it
        // to appear in a proxy log.
    }

    @Override
    public void onCallCompleted(CallStatus status) {}

    @Override
    public void onCallErrored(Throwable err) {}

    /**
     * Builds one {@link PrincipalMiddleware} per call by verifying the bearer token.
     *
     * <p>Rejection happens here, before the producer runs, so an unauthenticated call costs a header
     * parse rather than a query plan.
     */
    public static final class Factory implements FlightServerMiddleware.Factory<PrincipalMiddleware> {

        private final TokenVerifier verifier;

        public Factory(TokenVerifier verifier) {
            this.verifier = java.util.Objects.requireNonNull(verifier, "verifier");
        }

        @Override
        public PrincipalMiddleware onCallStarted(CallInfo info, CallHeaders headers, RequestContext context) {
            String header = headers.get(HEADER);
            if (header == null || header.isBlank()) {
                throw FlightErrors.failureOf(
                                CallStatus.UNAUTHENTICATED,
                                SecurityErrors.UNAUTHENTICATED,
                                "this server requires a credential: send it as the header "
                                        + "'authorization: Bearer <token>'")
                        .toRuntimeException();
            }
            if (header.length() <= BEARER.length() || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                throw FlightErrors.failureOf(
                                CallStatus.UNAUTHENTICATED,
                                SecurityErrors.UNAUTHENTICATED,
                                "the authorization header must use the Bearer scheme")
                        .toRuntimeException();
            }
            String token = header.substring(BEARER.length()).trim();
            try {
                Principal principal = verifier.verify(token);
                if (principal == null || principal.isAnonymous()) {
                    // A verifier that returns anonymous has failed to authenticate, whatever it
                    // meant to do; treating that as success is how an audit log fills with calls
                    // attributed to nobody.
                    throw new PravahaException(
                            SecurityErrors.UNAUTHENTICATED, "the credential presented was not accepted");
                }
                return new PrincipalMiddleware(principal, token, verifier);
            } catch (PravahaException e) {
                // The verifier's message, not the exception's cause: the contract on TokenVerifier
                // is that the message says the credential was rejected and not why.
                throw FlightErrors.failureOf(CallStatus.UNAUTHENTICATED, e).toRuntimeException();
            }
        }
    }
}
