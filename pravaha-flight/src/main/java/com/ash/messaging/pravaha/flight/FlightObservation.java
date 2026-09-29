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

import java.util.function.UnaryOperator;

/**
 * What the Flight endpoint tells an observer: each call as it starts and ends, and each subscription
 * the engine ended because the caller was no longer entitled to it.
 *
 * <p>An interface rather than a tracer, because this module has no tracing or metrics library and
 * should not gain one: the server implements it over Micrometer (a span per call when tracing is on, a
 * timer per operation always), and an embedded use of the Flight endpoint leaves it at {@link #NONE}.
 *
 * <p>The {@code operation} is one of a fixed set -- {@code query}, {@code query.plan}, {@code
 * subscribe}, {@code register}, {@code replace}, {@code sql.action} and the other actions' names
 * without their {@code pravaha.} prefix -- so it is safe as a metric label. The {@code query} passed
 * beside it is a registered name, and is for spans and log lines only.
 */
public interface FlightObservation {

    /** Why the engine ended a subscription the caller had opened: the {@code reason} label. */
    String CREDENTIAL_REVOKED = "credential_revoked";

    String ACCESS_WITHDRAWN = "access_withdrawn";
    String NARROWING_CHANGED = "narrowing_changed";

    /** One call being observed; ended exactly once, on the thread that began it. */
    interface Call {

        /** Marks the call failed; {@link #end()} is still called. */
        void failed(Throwable failure);

        /** Ends the call. */
        void end();
    }

    /** A call that records nothing. */
    Call NO_CALL = new Call() {
        @Override
        public void failed(Throwable failure) {}

        @Override
        public void end() {}
    };

    /** Observes nothing. */
    FlightObservation NONE = new FlightObservation() {
        @Override
        public Call begin(String operation, String query, UnaryOperator<String> header) {
            return NO_CALL;
        }

        @Override
        public void subscriptionEnded(String reason) {}
    };

    /**
     * A call has started.
     *
     * @param operation what it is, from a fixed set; see the class comment
     * @param query the registered name it concerns, or null
     * @param header the call's propagation headers by name ({@code traceparent}, {@code tracestate},
     *     {@code baggage}, {@code x-correlation-id}), null for one it did not send
     */
    Call begin(String operation, String query, UnaryOperator<String> header);

    /** The engine ended a subscription: {@link #CREDENTIAL_REVOKED}, {@link #ACCESS_WITHDRAWN} or {@link #NARROWING_CHANGED}. */
    void subscriptionEnded(String reason);
}
