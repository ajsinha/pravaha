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

import org.apache.arrow.flight.CallStatus;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/** Flight gateway error codes, PRV-8nnn. */
public final class FlightErrors {

    /** A column type this gateway will not put on the wire. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(6100, "FLIGHT_UNSUPPORTED_TYPE");

    /** A Flight SQL request this server does not implement. */
    public static final ErrorCode UNSUPPORTED_REQUEST = new ErrorCode(6101, "FLIGHT_UNSUPPORTED_REQUEST");

    /**
     * The Flight status a Pravaha failure should arrive as.
     *
     * <p>The message always carries the engine's own PRV code and diagnosis, but the status code is
     * what a client acts on <em>before</em> reading anything: gRPC clients and the JDBC driver retry
     * {@code RESOURCE_EXHAUSTED} and {@code UNAVAILABLE}, re-authenticate on {@code UNAUTHENTICATED},
     * and give up on {@code INVALID_ARGUMENT}. Sending every failure as INVALID_ARGUMENT means a
     * saturated node looks like a malformed query, and the client that should have backed off and
     * retried instead reports a bug.
     */
    public static CallStatus statusFor(PravahaException e) {
        return switch (e.errorCode().code()) {
            case "PRV-7001" -> CallStatus.UNAUTHENTICATED;
            // Authorization, and the unenforceable-filter refusal with it: both mean this caller may
            // not have these rows, and neither is fixed by a fresh credential.
            case "PRV-7002", "PRV-7003" -> CallStatus.UNAUTHORIZED;
            // Admission. Retryable, and saying so is the difference between a client that backs off
            // and one that hammers a node that is already full.
            case "PRV-4026", "PRV-4027", "PRV-4028" -> CallStatus.RESOURCE_EXHAUSTED;
            case "PRV-4021", "PRV-4029" -> CallStatus.TIMED_OUT;
            case "PRV-4023" -> CallStatus.NOT_FOUND;
            default -> CallStatus.INVALID_ARGUMENT;
        };
    }

    private FlightErrors() {}
}
