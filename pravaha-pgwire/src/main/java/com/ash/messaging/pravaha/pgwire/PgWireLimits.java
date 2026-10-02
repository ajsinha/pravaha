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
package com.ash.messaging.pravaha.pgwire;

import java.time.Duration;
import java.util.Objects;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What one PostgreSQL gateway will hold at once, and for how long (PGPREAUTH-1).
 *
 * <p>Before these existed the gateway had a thread per connection from an unbounded pool, no cap on
 * how many connections there were, and allocated whatever size a client declared for its {@code
 * PasswordMessage} -- up to 16 MiB -- before checking a byte of it. Sixty unauthenticated sockets
 * were enough to run a 1 GiB container out of heap. Each limit here closes one part of that:
 *
 * <ul>
 *   <li>{@code maxConnections}: every connection, signed in or not. Past it a new socket is answered
 *       {@code FATAL 53300} at once and closed, without a thread.
 *   <li>{@code maxUnauthenticated}: connections still in their handshake. An unauthenticated peer is
 *       the cheap one to multiply, so it has a smaller budget of its own, also {@code 53300}.
 *   <li>{@code authenticationTimeout}: the whole handshake, startup to {@code AuthenticationOk}, as
 *       one deadline -- not a per-read timeout a peer can renew with a byte every nine seconds.
 *   <li>{@code maxConnectionsPerPrincipal}: one credential's share of {@code maxConnections}, so a
 *       runaway pool cannot take every slot; {@code 0} is no share smaller than the whole.
 *   <li>{@code maxMessageBytes}: one message after sign-in. Before sign-in the caps are fixed and
 *       small -- see {@link PgFrontend#MAX_STARTUP_BYTES} and {@link PgFrontend#MAX_PASSWORD_BYTES}.
 *   <li>{@code idleTimeout}: how long a signed-in connection may sit between messages; {@link
 *       Duration#ZERO} is for ever, which is what a psql window or a BI pool usually wants.
 * </ul>
 */
public record PgWireLimits(
        int maxConnections,
        int maxConnectionsPerPrincipal,
        int maxUnauthenticated,
        Duration authenticationTimeout,
        int maxMessageBytes,
        Duration idleTimeout) {

    /** The defaults {@code pravaha.pgwire.limits.*} documents. */
    public static final PgWireLimits DEFAULTS =
            new PgWireLimits(100, 0, 32, Duration.ofSeconds(10), 1024 * 1024, Duration.ZERO);

    /** Smaller than this and an ordinary {@code \d} from psql does not fit. */
    static final int MIN_MESSAGE_BYTES = 64 * 1024;

    /** The protocol's own ceiling on a message length; past it the length field cannot say it. */
    static final int MAX_MESSAGE_BYTES = 1024 * 1024 * 1024;

    public PgWireLimits {
        Objects.requireNonNull(authenticationTimeout, "authenticationTimeout");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (maxConnections < 1) {
            throw invalid("pravaha.pgwire.limits.max-connections must be at least 1; it is " + maxConnections);
        }
        if (maxConnectionsPerPrincipal < 0) {
            throw invalid("pravaha.pgwire.limits.max-connections-per-principal must be 0 (no share smaller than "
                    + "max-connections) or positive; it is " + maxConnectionsPerPrincipal);
        }
        if (maxUnauthenticated < 1 || maxUnauthenticated > maxConnections) {
            throw invalid("pravaha.pgwire.limits.max-unauthenticated must be between 1 and max-connections ("
                    + maxConnections + "); it is " + maxUnauthenticated);
        }
        if (authenticationTimeout.isNegative() || authenticationTimeout.isZero()) {
            throw invalid("pravaha.pgwire.limits.authentication-timeout must be positive: a handshake with no "
                    + "deadline is a connection slot any peer can hold for ever by saying nothing");
        }
        if (maxMessageBytes < MIN_MESSAGE_BYTES || maxMessageBytes > MAX_MESSAGE_BYTES) {
            throw invalid("pravaha.pgwire.limits.max-message-size must be between " + MIN_MESSAGE_BYTES + " and "
                    + MAX_MESSAGE_BYTES + " bytes; it is " + maxMessageBytes);
        }
        if (idleTimeout.isNegative()) {
            throw invalid("pravaha.pgwire.limits.idle-timeout must be 0 (none) or positive; it is " + idleTimeout);
        }
    }

    private static PravahaException invalid(String message) {
        return new PravahaException(PgWireErrors.BAD_LIMITS, message);
    }
}
