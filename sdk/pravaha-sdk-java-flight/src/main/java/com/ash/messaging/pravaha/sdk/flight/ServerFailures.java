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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.Optional;

import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.wire.ErrorWire;
import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * Turning a Flight failure back into the failure the server described.
 *
 * <p>S-4 and E-7, which are one defect seen from two ends. Every catch block in this SDK used to
 * read {@code new PravahaClientException(ClientErrors.QUERY_REFUSED, description, false, e)},
 * whatever had happened: a planning refusal, an authorization denial, a saturated node, a server
 * that was not running at all. So {@code PRV-1041} meant eight different things, the server's own
 * code survived only as the first token of a string, {@code retryable()} was the constant
 * {@code false} -- and {@code PRV-1040 CLIENT_CONNECT_FAILED}, the first error a new user meets, was
 * unreachable, because {@code connect()} builds a lazy gRPC channel and never fails for an
 * unreachable host.
 *
 * <p>Three questions, asked in this order, because each has a different answer for the caller:
 *
 * <ol>
 *   <li><strong>Did the server diagnose this?</strong> Then its code is the answer, rebuilt from
 *       the wire ({@link ErrorWire}) rather than replaced. A caller branching on {@code PRV-4023}
 *       (no such view) versus {@code PRV-7002} (not allowed to read it) can now do so on the code,
 *       which is what a code is for.
 *   <li><strong>Was there a server at all?</strong> {@code UNAVAILABLE} with no Pravaha code in it
 *       is a connection that never reached one: {@code PRV-1040}, retryable, naming the endpoint.
 *   <li><strong>Anything else</strong> is the caller's own {@code fallback}: {@code PRV-1041} for a
 *       call, which now means what its name says and only that -- the server refused this request
 *       and said nothing further -- and {@code PRV-1042} for a result that stopped arriving part
 *       way, which is a different thing and had no code of its own.
 * </ol>
 *
 * <p>Rejected: mapping the Flight status code to a Pravaha code directly. The status is coarse by
 * design (twelve values for a hundred and eleven codes), it is chosen by
 * {@code FlightErrors.statusFor} from the Pravaha code in the first place, and re-deriving one from
 * the other would lose exactly the detail this exists to keep.
 */
final class ServerFailures {

    private ServerFailures() {}

    /**
     * The exception to raise for a failed call to {@code endpoint}.
     *
     * @param endpoint {@code host:port} as configured, named in a connection failure because
     *     "connection refused" without it is unactionable on a machine that talks to several nodes
     * @param fallback the code for a failure the server did not name and that was not a connection
     *     failure -- {@link ClientErrors#QUERY_REFUSED} for a call, {@link ClientErrors#READ_FAILED}
     *     for a result that stopped part way through
     */
    static PravahaClientException of(FlightRuntimeException e, String endpoint, ErrorCode fallback) {
        CallStatus status = e.status();
        String description = status.description() == null ? e.getMessage() : status.description();

        Optional<ErrorCode> reported = ErrorWire.recover(description, nameTrailer(status));
        if (reported.isPresent()) {
            // The server's diagnosis, verbatim, under the server's own code. The description already
            // begins with that code, so the rendered message is not doubled up the way wrapping it
            // in PRV-1041 used to be ("PRV-1041  PRV-8002  no query named 'nosuch'").
            return new PravahaClientException(
                    reported.get(), messageUnder(reported.get(), description), retryable(status), e);
        }
        if (status.code() == FlightStatusCode.UNAVAILABLE) {
            // No Pravaha code because no Pravaha answered. E-7: this is the scenario every new user
            // hits first -- a server that is not up yet -- and it reached them as a non-retryable
            // "the server refused the query", which is three wrong statements in one.
            return new PravahaClientException(
                    ClientErrors.CONNECT_FAILED,
                    "cannot reach " + endpoint + ": " + description
                            + ". Check that a Pravaha node is running there and that the scheme matches"
                            + " (grpc:// for plaintext, grpc+tls:// for TLS)",
                    true,
                    e);
        }
        return new PravahaClientException(fallback, description, retryable(status), e);
    }

    /**
     * Whether retrying this call unchanged could plausibly succeed.
     *
     * <p>Was the constant {@code false}, which made {@link PravahaClientException#retryable()} --
     * the method that exists so nobody has to parse messages to write a retry policy -- useless.
     * The three retryable statuses are the ones gRPC and the JDBC driver already retry, and
     * {@code FlightErrors.statusFor} chooses them deliberately for exactly the codes a caller
     * should back off on.
     */
    private static boolean retryable(CallStatus status) {
        return status.code() == FlightStatusCode.UNAVAILABLE
                || status.code() == FlightStatusCode.RESOURCE_EXHAUSTED
                || status.code() == FlightStatusCode.TIMED_OUT;
    }

    /** The description with its leading code stripped, since the exception renders one itself. */
    private static String messageUnder(ErrorCode code, String description) {
        String prefix = code.code();
        return description.startsWith(prefix)
                ? description.substring(prefix.length()).stripLeading()
                : description;
    }

    /**
     * The code's name, if the trailer carrying it survived the trip.
     *
     * <p>Defensive about the metadata itself: a failure produced inside the client -- a shut-down
     * channel, a TLS handshake -- has a {@link CallStatus} with no metadata at all, and losing the
     * real diagnosis to a {@code NullPointerException} raised while decoding it would be a poor
     * trade.
     */
    private static String nameTrailer(CallStatus status) {
        try {
            return status.metadata() == null ? null : status.metadata().get(ErrorWire.NAME_HEADER);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
