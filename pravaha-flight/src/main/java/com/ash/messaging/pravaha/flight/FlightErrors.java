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
import org.apache.arrow.flight.ErrorFlightMetadata;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.api.wire.ErrorWire;

/** Flight gateway error codes, PRV-8nnn. */
public final class FlightErrors {

    /** A column type this gateway will not put on the wire. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(6100, "FLIGHT_UNSUPPORTED_TYPE");

    /** A Flight SQL request this server does not implement. */
    public static final ErrorCode UNSUPPORTED_REQUEST = new ErrorCode(6101, "FLIGHT_UNSUPPORTED_REQUEST");

    /**
     * A prepared-statement handle or control request this server cannot read.
     *
     * <p>Aliased to {@link ControlWire#BAD_REQUEST} rather than declared again. Both ends of the
     * wire need this code and it was briefly defined in two places with the same number -- which is
     * harmless right up until somebody changes one of them.
     */
    public static final ErrorCode BAD_HANDLE = ControlWire.BAD_REQUEST;

    /** More bound parameter bytes than a handle should carry. */
    public static final ErrorCode PARAMETERS_TOO_LARGE = new ErrorCode(6103, "FLIGHT_PARAMETERS_TOO_LARGE");

    /**
     * A TLS certificate or key was configured and cannot be read.
     *
     * <p>Refused at startup rather than warned about and skipped. Falling back to plaintext because
     * a certificate was missing is how a deployment believes it is encrypted for months.
     */
    public static final ErrorCode TLS_UNREADABLE = new ErrorCode(6104, "FLIGHT_TLS_UNREADABLE");

    /**
     * A snapshot subscriber fell further behind than the server holds commits for it, and its
     * stream was ended rather than continued past a commit it would never receive (SUB-1).
     *
     * <p>A plain subscription drops the batch and carries on, which is its documented contract. A
     * snapshot subscription promises every commit after the snapshot, so a dropped one would leave
     * the client's copy silently wrong; ending the stream is the only honest answer. Subscribing
     * again starts from a fresh snapshot, so nothing is lost for good. Retryable, and sent as such.
     */
    public static final ErrorCode SUBSCRIBER_BEHIND = new ErrorCode(6105, "FLIGHT_SUBSCRIBER_BEHIND");

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
            // Authorization, and the two refusals that come with it: all three mean this caller may
            // not have what they asked for, and none is fixed by a fresh credential. PRV-7005 is
            // the policy's own fault rather than the caller's -- it allowed a sink write only
            // through a row filter, which a sink cannot take -- but the client's move is the same,
            // and a registration refused on the policy's answer is not a malformed request.
            case "PRV-7002", "PRV-7003", "PRV-7005" -> CallStatus.UNAUTHORIZED;
            // Admission. Retryable, and saying so is the difference between a client that backs off
            // and one that hammers a node that is already full.
            case "PRV-4026", "PRV-4027", "PRV-4028", "PRV-6105" -> CallStatus.RESOURCE_EXHAUSTED;
            case "PRV-4021", "PRV-4029" -> CallStatus.TIMED_OUT;
            case "PRV-4023" -> CallStatus.NOT_FOUND;
            // A subscription ending for a reason the client should act on, and the two reasons call
            // for opposite actions (STRM-12). A dropped name is not coming back, so NOT_FOUND: stop,
            // and do not reconnect to a name that no longer exists. A node shutting down is coming
            // back, so UNAVAILABLE, which every gRPC client already retries.
            case "PRV-8018" -> CallStatus.NOT_FOUND;
            case "PRV-8019" -> CallStatus.UNAVAILABLE;
            // The debugger's own two of the same shape. A session that has ended or expired is
            // gone, not a bad request (PRV-8013), and a node already holding its ceiling of
            // sessions is saturated, which is the case this method's own javadoc says must not
            // look like a malformed query (PRV-8014). The rest of 8011..8016 are arguments the
            // caller can correct -- no checkpoint, a source that cannot replay, an unreadable
            // step -- so INVALID_ARGUMENT is right for them.
            case "PRV-8013", "PRV-8016" -> CallStatus.NOT_FOUND;
            case "PRV-8014" -> CallStatus.RESOURCE_EXHAUSTED;
            // A handle the server cannot read, or one from an older version: the client's move is
            // to prepare the statement again, which NOT_FOUND is the conventional prompt for.
            case "PRV-6102" -> CallStatus.NOT_FOUND;
            default -> CallStatus.INVALID_ARGUMENT;
        };
    }

    /**
     * The whole of a Pravaha failure, ready to send: status, diagnosis and code.
     *
     * <p>S-4. Every call site used to be {@code statusFor(e).withDescription(e.getMessage())}, which
     * carries the code only as the first token of a string -- so the client could show it to a human
     * and nothing else could read it. This adds the code's <em>name</em> as a trailer
     * ({@link ErrorWire#NAME_HEADER}) alongside the number the message already begins with, so the
     * client can rebuild the server's {@link ErrorCode} rather than re-stamp it with one of its own.
     *
     * <p>The description is unchanged, deliberately: it is what an operator reads and what the
     * console, the CLI and several tests match on. This adds a channel rather than altering one.
     */
    public static CallStatus failureOf(PravahaException e) {
        return statusFor(e).withDescription(e.getMessage()).withMetadata(named(e.errorCode()));
    }

    /**
     * The same, for a refusal stated as a code and a message rather than as an exception.
     *
     * <p>The authentication and authorization checks refuse before any {@link PravahaException}
     * exists, and they built their descriptions by hand. They go through here so that a client sees
     * one shape of failure rather than two, and so that adding a third such site cannot quietly
     * produce a refusal the client cannot decode.
     */
    public static CallStatus failureOf(CallStatus status, ErrorCode code, String message) {
        return status.withDescription(code.code() + "  " + message).withMetadata(named(code));
    }

    /**
     * The same, where the status is decided by the caller rather than by the code.
     *
     * <p>Authentication is the one place that happens: the middleware refuses before the call
     * reaches a producer, and UNAUTHENTICATED is a property of where it refused, not of the code.
     */
    public static CallStatus failureOf(CallStatus status, PravahaException e) {
        return status.withDescription(e.getMessage()).withMetadata(named(e.errorCode()));
    }

    private static ErrorFlightMetadata named(ErrorCode code) {
        ErrorFlightMetadata trailers = new ErrorFlightMetadata();
        trailers.insert(ErrorWire.NAME_HEADER, code.name());
        return trailers;
    }

    private FlightErrors() {}
}
