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
package com.ash.messaging.pravaha.sdk;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Client-side error codes, PRV-1nnn, sharing the engine's catalogue.
 *
 * <p>Plus, at the end, the one engine code this client raises on the engine's behalf. A code means
 * the same thing wherever it is raised; which process noticed is not part of its meaning.
 */
public final class ClientErrors {

    /** The server could not be reached. Retryable: a server may come back. */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(1040, "CLIENT_CONNECT_FAILED");

    /** The server refused the query. Not retryable: the same SQL will be refused again. */
    public static final ErrorCode QUERY_REFUSED = new ErrorCode(1041, "CLIENT_QUERY_REFUSED");

    /** A result could not be read. */
    public static final ErrorCode READ_FAILED = new ErrorCode(1042, "CLIENT_READ_FAILED");

    /** A client operation used after close. */
    public static final ErrorCode CLOSED = new ErrorCode(1043, "CLIENT_CLOSED");

    /**
     * The engine's own parameter-arity refusal, raised here because here is where it is noticed.
     *
     * <p>X-8. The server declares this as {@code SqlErrors.PARAMETER_ARITY} and
     * {@code BoundParameters.requireArity} throws it properly -- but no user of the Java SDK or the
     * CLI could ever reach it, because the SDK checks the arity itself before a prepared statement
     * is executed and refused with the generic {@code PRV-1041}. Identical wording, different code:
     * somebody searching the documented error table for {@code 2061} found a row describing
     * something that had never happened to them.
     *
     * <p>Checking client-side is right and stays: it costs no round trip and names the two counts
     * while the caller's own arguments are still in hand. What was wrong was answering under a code
     * that means "the server refused this", when no server was involved and the diagnosis is the
     * engine's.
     *
     * <p><strong>Declared twice, and it has to be.</strong> The alias
     * {@code FlightErrors.BAD_HANDLE = ControlWire.BAD_REQUEST} is the pattern this would otherwise
     * follow, and it is not available here: this artifact depends on {@code pravaha-api} and nothing
     * else -- enforced by this module's banned-dependencies rule, not merely intended -- so it
     * cannot see {@code pravaha-sql}. Moving the constant into {@code pravaha-api} so both ends
     * import one declaration is the right shape and is an edit to {@code SqlErrors}, which this
     * round does not own. The number and the name are duplicated verbatim so that the cross-check in
     * {@code ErrcCrossCuttingTest} ("PRV-n must mean one thing") fails loudly if either copy is
     * edited alone.
     */
    public static final ErrorCode PARAMETER_ARITY = new ErrorCode(2061, "SQL_PARAMETER_ARITY");

    private ClientErrors() {}
}
