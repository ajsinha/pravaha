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

import java.time.Duration;
import java.util.Optional;

import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.jspecify.annotations.Nullable;

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
        return of(e, endpoint, fallback, null, null);
    }

    /**
     * The same, for a call made with a deadline: {@code call} names it and {@code deadline} is the
     * one it was given, so a call that ran out of time says which call and which setting to raise.
     *
     * <p>SDKDEADLINE-1. gRPC reports an expired deadline as {@code DEADLINE_EXCEEDED}, which Flight
     * calls {@code TIMED_OUT}; with no Pravaha code in it, that is this client giving up rather than
     * the server refusing, and it is {@link ClientErrors#DEADLINE_EXCEEDED}. A server that diagnosed
     * a timeout of its own still answers under its own code -- the first question below is asked
     * first.
     */
    static PravahaClientException of(
            FlightRuntimeException e,
            String endpoint,
            ErrorCode fallback,
            @Nullable String call,
            @Nullable Duration deadline) {
        CallStatus status = e.status();
        String description = describe(status, e);

        Optional<ErrorCode> reported = ErrorWire.recover(description, nameTrailer(status));
        if (reported.isPresent()) {
            // The server's diagnosis, verbatim, under the server's own code. The description already
            // begins with that code, so the rendered message is not doubled up the way wrapping it
            // in PRV-1041 used to be ("PRV-1041  PRV-8002  no query named 'nosuch'").
            return new PravahaClientException(
                    reported.get(), messageUnder(reported.get(), description), retryable(status), e);
        }
        if (status.code() == FlightStatusCode.TIMED_OUT && call != null && deadline != null) {
            return deadlineExceeded(call, deadline, endpoint, e);
        }
        Throwable handshake = tlsHandshakeFailure(e);
        if (handshake != null) {
            // TLSDIAG-1: a node that answered with a certificate this client would not accept is not
            // a node that could not be reached, and retrying will not change the certificate.
            return new PravahaClientException(
                    ClientErrors.TLS_HANDSHAKE_FAILED,
                    "the TLS handshake with " + endpoint + " failed: " + handshake.getMessage()
                            + ". The node's certificate is not trusted by this client, has expired, or does not"
                            + " name this host: check TlsOptions (the CA or trust store) and the certificate's"
                            + " dates and names",
                    false,
                    e);
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
     * {@code call} was not answered by {@code endpoint} within {@code deadline}: retryable, since
     * nothing was refused, and naming the setting that sets it.
     */
    static PravahaClientException deadlineExceeded(String call, Duration deadline, String endpoint, Throwable cause) {
        return new PravahaClientException(
                ClientErrors.DEADLINE_EXCEEDED,
                call + " was not answered by " + endpoint + " within the " + seconds(deadline)
                        + " deadline (ClientOptions.requestTimeout). The node is slow, stalled or"
                        + " overloaded; retry, or raise requestTimeout if the request is known to take"
                        + " longer",
                true,
                cause);
    }

    /**
     * The certificate failure underneath {@code e}, or null: an {@link javax.net.ssl.SSLHandshakeException}
     * or a {@link java.security.cert.CertificateException} anywhere in the cause chain, the status's
     * own cause included. The deepest one, because it is the one that names the reason ("PKIX path
     * building failed", "certificate expired") rather than the one that wraps it.
     */
    static @Nullable Throwable tlsHandshakeFailure(FlightRuntimeException e) {
        Throwable found = deepestTlsFailure(e.getCause(), null);
        return deepestTlsFailure(e.status().cause(), found);
    }

    private static @Nullable Throwable deepestTlsFailure(@Nullable Throwable start, @Nullable Throwable found) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable at = start; at != null && seen.add(at); at = at.getCause()) {
            if (at instanceof javax.net.ssl.SSLHandshakeException
                    || at instanceof java.security.cert.CertificateException
                    || at instanceof java.security.cert.CertPathValidatorException
                    || at instanceof java.security.cert.CertPathBuilderException) {
                found = at;
            }
        }
        return found;
    }

    /** {@code 30 s}, {@code 0.5 s}: how a deadline is named in a message. */
    static String seconds(Duration deadline) {
        long millis = deadline.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + " s" : (millis / 1000.0) + " s";
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

    /**
     * What the failure said: the status's description, else the exception's message, else a sentence
     * saying there was none -- so a refusal never reads "null".
     */
    private static String describe(CallStatus status, FlightRuntimeException e) {
        String described = status.description();
        if (described != null) {
            return described;
        }
        String message = e.getMessage();
        return message != null ? message : "the server sent no description";
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
    private static @Nullable String nameTrailer(CallStatus status) {
        try {
            return status.metadata() == null ? null : status.metadata().get(ErrorWire.NAME_HEADER);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
