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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Flight endpoint tells its observer what each call is -- a fixed set of operation names, never
 * whatever a client wrote -- which query it concerns, and the trace context the client sent; and it
 * says when it ended a subscription for entitlement.
 */
@Timeout(120)
class FlightObservationTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /** What the observer saw: "begin operation query traceparent", "end operation", "ended reason". */
    private final List<String> seen = new CopyOnWriteArrayList<>();

    private final FlightObservation recording = new FlightObservation() {
        @Override
        public Call begin(String operation, String query, UnaryOperator<String> header) {
            seen.add("begin " + operation + " " + query + " " + header.apply("traceparent"));
            return new Call() {
                @Override
                public void failed(Throwable failure) {
                    seen.add("failed " + operation);
                }

                @Override
                public void end() {
                    seen.add("end " + operation);
                }
            };
        }

        @Override
        public void subscriptionEnded(String reason) {
            seen.add("ended " + reason);
        }
    };

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private QueryRegistry registry;

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    private void start(SecurityPolicy policy) {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, policy, AuditSink.NONE, SCHEMA);
        server = new PravahaFlightServer(views, allocator)
                .authorizedBy(policy, AuditSink.NONE)
                .observedBy(recording)
                .hosting(registry)
                .start("localhost", 0);
        registry.register(
                "user_volume",
                "SELECT user_id, total FROM user_volume",
                List.of(0),
                new Principal("dana", "public", Set.of("analyst"), Map.of()));
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
    }

    private static CallOption traced() {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("traceparent", TRACEPARENT);
        return new HeaderCallOption(headers);
    }

    @Test
    void eachCallIsNamedFromAFixedSetAndCarriesTheClientsTraceContext() throws Exception {
        start(SecurityPolicy.PERMISSIVE);
        client.listActions(traced()).forEach(a -> {});
        try {
            client.doAction(new Action("pravaha.no-such-verb", new byte[0])).forEachRemaining(r -> {});
        } catch (RuntimeException refused) {
            // refused by the producer, as it should be; what matters is the label it was given
        }
        assertThat(seen).contains("begin list.actions null " + TRACEPARENT, "end list.actions");
        assertThat(seen).contains("begin action.unknown null null", "failed action.unknown", "end action.unknown");
        assertThat(ObservedFlightProducer.operationOf("pravaha.register")).isEqualTo("register");
        assertThat(ObservedFlightProducer.operationOf("pravaha.dlq.list")).isEqualTo("dlq.list");
        assertThat(ObservedFlightProducer.operationOf("CreatePreparedStatement"))
                .isEqualTo("sql.action");
    }

    @Test
    void aSubscriptionIsOneCallAboutItsViewAndAWithdrawalIsReported() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        start((principal, view) -> allowed.get() ? AccessDecision.allow() : AccessDecision.deny("withdrawn"));
        AtomicReference<String> ended = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try (FlightStream stream =
                    client.getStream(new Ticket(ControlWire.subscribeTicket("user_volume", List.of())), traced())) {
                while (stream.next()) {
                    // draining
                }
                ended.set("completed");
            } catch (Exception e) {
                ended.set(String.valueOf(e.getMessage()));
            }
        });
        Thread.sleep(500);
        assertThat(seen).contains("begin subscribe user_volume " + TRACEPARENT);
        allowed.set(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (ended.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(ended.get()).contains("withdrawn");
        assertThat(seen).contains("ended " + FlightObservation.ACCESS_WITHDRAWN);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!seen.contains("end subscribe") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(seen).contains("end subscribe");
    }
}
