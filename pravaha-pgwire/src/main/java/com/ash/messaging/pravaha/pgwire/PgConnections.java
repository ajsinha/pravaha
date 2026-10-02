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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Counts the gateway's connections against {@link PgWireLimits}: all of them, those still in their
 * handshake, and each principal's (PGPREAUTH-1).
 *
 * <p>A connection is admitted by the acceptor, before any thread is spent on it, and holds a {@link
 * Ticket} for the rest of its life. The ticket moves from unauthenticated to a principal's count once
 * when the connection signs in, and gives back whatever it holds exactly once when it closes.
 */
final class PgConnections {

    private final PgWireLimits limits;
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger unauthenticated = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> byPrincipal = new ConcurrentHashMap<>();

    PgConnections(PgWireLimits limits) {
        this.limits = limits;
    }

    /**
     * A new connection's ticket.
     *
     * @throws PravahaException {@link PgWireErrors#TOO_MANY_CONNECTIONS} naming the limit reached
     */
    Ticket admit() {
        if (open.incrementAndGet() > limits.maxConnections()) {
            open.decrementAndGet();
            throw new PravahaException(
                    PgWireErrors.TOO_MANY_CONNECTIONS,
                    "sorry, too many clients already: this gateway has " + limits.maxConnections()
                            + " connections open (pravaha.pgwire.limits.max-connections). Close idle connections or "
                            + "retry later.");
        }
        if (unauthenticated.incrementAndGet() > limits.maxUnauthenticated()) {
            unauthenticated.decrementAndGet();
            open.decrementAndGet();
            throw new PravahaException(
                    PgWireErrors.TOO_MANY_CONNECTIONS,
                    "sorry, too many connections are signing in at once: " + limits.maxUnauthenticated()
                            + " are still in their handshake (pravaha.pgwire.limits.max-unauthenticated). Retry in "
                            + "a moment.");
        }
        return new Ticket();
    }

    int open() {
        return open.get();
    }

    int unauthenticated() {
        return unauthenticated.get();
    }

    /** One connection's hold on the counts. Not thread-safe: one connection, one thread. */
    final class Ticket {

        private boolean signingIn = true;
        private String principal;
        private boolean closed;

        /**
         * Moves this connection from signing in to {@code principalId}'s count.
         *
         * @throws PravahaException {@link PgWireErrors#TOO_MANY_CONNECTIONS} when that principal already holds
         *     its share
         */
        void authenticated(String principalId) {
            if (signingIn) {
                signingIn = false;
                unauthenticated.decrementAndGet();
            }
            int share = limits.maxConnectionsPerPrincipal();
            boolean[] full = {false};
            // One atomic step per principal, so a count is never incremented on an entry a concurrent
            // close has just removed.
            byPrincipal.compute(principalId, (id, count) -> {
                int now = count == null ? 0 : count;
                if (share > 0 && now >= share) {
                    full[0] = true;
                    return count;
                }
                return now + 1;
            });
            if (full[0]) {
                throw new PravahaException(
                        PgWireErrors.TOO_MANY_CONNECTIONS,
                        "sorry, too many connections for '" + principalId + "': it already has " + share
                                + " open (pravaha.pgwire.limits.max-connections-per-principal). Close some, or "
                                + "retry later.");
            }
            principal = principalId;
        }

        /** Gives back whatever this ticket holds. Idempotent. */
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (signingIn) {
                unauthenticated.decrementAndGet();
            }
            if (principal != null) {
                byPrincipal.computeIfPresent(principal, (id, count) -> count <= 1 ? null : count - 1);
            }
            open.decrementAndGet();
        }
    }
}
