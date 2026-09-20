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

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The Flight SQL transaction verbs, refused in this engine's own vocabulary (E-15).
 *
 * <p>Every Flight SQL <em>metadata</em> call is answered (P-6, and {@code FlightSqlMetadataTest}
 * drives them over a real client). {@code beginTransaction}, {@code endTransaction},
 * {@code beginSavepoint} and {@code endSavepoint} are the ones left, and they are left on purpose:
 * a continuous query is not a transaction, and there is nothing here for a savepoint to roll back
 * to.
 *
 * <p>Without an override they fall through to the framework's default, which is Arrow's own
 * {@code UNIMPLEMENTED "Not implemented."} — no {@code PRV-} code, no help URL, and nothing saying
 * whose server it came from, so a client cannot tell a Pravaha refusal from a proxy's. E-15's own
 * case file named the opposite risk, an empty result read as "no primary keys"; what actually
 * happened was a refusal carrying nothing.
 *
 * <p>{@code UNIMPLEMENTED} is the right gRPC status and stays: {@link FlightErrors#statusFor} maps
 * {@code PRV-6101} to it. What changes is that the description is the engine's.
 */
final class FlightTransactions {

    private FlightTransactions() {}

    /** The refusal for one transaction verb, worded once for all four. */
    static RuntimeException notATransaction(String verb) {
        return FlightErrors.failureOf(new PravahaException(
                        FlightErrors.UNSUPPORTED_REQUEST,
                        "this server does not answer '" + verb + "': a continuous query is not a transaction, "
                                + "and there is no uncommitted state here for one to span. A read of a view is "
                                + "already consistent -- it sees the view at its committed frontier -- and a "
                                + "registration is durable when it returns. Supported: statements, prepared "
                                + "statements, the catalogue metadata calls, and Pravaha's own register, drop, "
                                + "pause, resume, subscribe, replace, debug and dead-letter actions."))
                .toRuntimeException();
    }
}
