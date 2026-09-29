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
package com.ash.messaging.pravaha.server.observe;

import java.util.List;
import java.util.function.UnaryOperator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.flight.FlightObservation;

/**
 * The Flight endpoint's calls as Micrometer observations: a {@code pravaha_flight_calls_seconds} timer
 * per operation always, and -- with {@code pravaha.tracing.enabled} -- a span per call, continuing the
 * trace a client sent in {@code traceparent}.
 *
 * <p>While a call runs its thread carries the query's name and the correlation id in the logging
 * context ({@code query}, {@code correlationId}), beside the {@code traceId} and {@code spanId} tracing
 * puts there, so every log line the call writes can be found from any one of them.
 *
 * <p>Also counts the subscriptions the engine ends because the caller is no longer entitled to them --
 * a credential revoked, a grant withdrawn, a row filter or mask changed (ADR-059 §8).
 */
@Component
public class NodeFlightObservation implements FlightObservation {

    /** The logging-context keys a call sets, beside tracing's own {@code traceId} and {@code spanId}. */
    public static final String MDC_QUERY = "query";

    public static final String MDC_CORRELATION = "correlationId";

    private final ObservationRegistry observations;
    private final Counter revoked;
    private final Counter withdrawn;
    private final Counter narrowed;

    public NodeFlightObservation(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.revoked = ended(meters, CREDENTIAL_REVOKED);
        this.withdrawn = ended(meters, ACCESS_WITHDRAWN);
        this.narrowed = ended(meters, NARROWING_CHANGED);
    }

    private static Counter ended(MeterRegistry meters, String reason) {
        // Registered at zero, so a dashboard reads 0 rather than "no data" on a node where nothing ended.
        return Counter.builder("pravaha.catalog.subscriptions.ended")
                .description("Subscriptions the engine ended because the caller was no longer entitled to them")
                .tag("reason", reason)
                .register(meters);
    }

    @Override
    public Call begin(String operation, String query, UnaryOperator<String> header) {
        ReceiverContext<UnaryOperator<String>> context = new ReceiverContext<>((carrier, key) -> carrier.apply(key));
        context.setCarrier(header);
        Observation observation = Observation.createNotStarted("pravaha.flight.calls", () -> context, observations)
                .contextualName("pravaha.flight." + operation)
                .lowCardinalityKeyValue("operation", operation);
        if (query != null) {
            observation.highCardinalityKeyValue("pravaha.query", query);
        }
        observation.start();
        Observation.Scope scope = observation.openScope();
        String correlation = Correlation.idOr(header.apply("x-correlation-id"));
        List<String> previous = List.of(orEmpty(MDC.get(MDC_QUERY)), orEmpty(MDC.get(MDC_CORRELATION)));
        put(MDC_QUERY, query);
        put(MDC_CORRELATION, correlation);
        return new Call() {
            @Override
            public void failed(Throwable failure) {
                observation.error(failure);
            }

            @Override
            public void end() {
                put(MDC_QUERY, previous.get(0));
                put(MDC_CORRELATION, previous.get(1));
                scope.close();
                observation.stop();
            }
        };
    }

    @Override
    public void subscriptionEnded(String reason) {
        switch (reason) {
            case CREDENTIAL_REVOKED -> revoked.increment();
            case ACCESS_WITHDRAWN -> withdrawn.increment();
            case NARROWING_CHANGED -> narrowed.increment();
            default -> throw new IllegalArgumentException("no such subscription ending: " + reason);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void put(String key, String value) {
        if (value == null || value.isEmpty()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
