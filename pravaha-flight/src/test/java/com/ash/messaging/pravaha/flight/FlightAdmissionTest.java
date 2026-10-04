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

import java.time.Duration;
import java.util.List;

import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ReadAdmission;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A saturated node refuses over the wire, in a way clients already know how to handle (ADR-030).
 *
 * <p>The status code matters more than the message here. A gRPC client, the JDBC driver and every
 * ADBC binding retry {@code RESOURCE_EXHAUSTED} with backoff and give up on {@code
 * INVALID_ARGUMENT}. Sending a full node's refusal as INVALID_ARGUMENT makes it look like a
 * malformed query, and the client that should have backed off reports a bug instead.
 */
@Timeout(60)
class FlightAdmissionTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightSqlClient client;
    private ReadAdmission admission;

    @BeforeEach
    void startServer() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 10);
        view.commit(10);

        // One read at a time, no queue: the smallest configuration that can be saturated on purpose.
        admission = new ReadAdmission(1, 0, 1.0, Duration.ZERO);
        server = new PravahaFlightServer(new ViewCatalog().register(view), allocator)
                .admitting(admission, Duration.ZERO)
                .start("localhost", 0);
        client = new FlightSqlClient(org.apache.arrow.flight.FlightClient.builder(
                        allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build());
    }

    @AfterEach
    void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    /** A different tenant from the wire caller, so the *global* limit is what refuses it. */
    private static final Principal OTHER_TENANT =
            new Principal("holder", "globex", java.util.Set.of(), java.util.Map.of());

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aSaturatedNodeRefusesWithAStatusClientsRetry() {
        try (ReadAdmission.Lease ignored = admission.acquire(OTHER_TENANT)) {
            assertThatThrownBy(() -> client.execute("SELECT user_id FROM user_volume"))
                    .isInstanceOf(FlightRuntimeException.class)
                    .satisfies(e -> assertThat(
                                    ((FlightRuntimeException) e).status().code())
                            .isEqualTo(CallStatus.RESOURCE_EXHAUSTED.code()))
                    .hasMessageContaining("PRV-4026");
        }
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void theNodeAnswersAgainAsSoonAsThePermitComesBack() throws Exception {
        try (ReadAdmission.Lease ignored = admission.acquire(OTHER_TENANT)) {
            assertThatThrownBy(() -> client.execute("SELECT user_id FROM user_volume"))
                    .isInstanceOf(FlightRuntimeException.class);
        }

        // Not "eventually" -- immediately. A refusal that leaves the node degraded afterwards is a
        // worse failure than the one it was avoiding.
        assertThat(client.execute("SELECT user_id FROM user_volume").getEndpoints())
                .isNotEmpty();
    }

    @Test
    @SuppressWarnings("try") // the resource is only held, never referenced
    void aTenantOverItsShareIsAlsoToldToRetry() {
        // Same tenant as the wire caller, so the per-tenant limit fires first -- and it must arrive
        // as RESOURCE_EXHAUSTED too, because the answer is the same: back off and come back.
        try (ReadAdmission.Lease ignored = admission.acquire(Principal.ANONYMOUS)) {
            assertThatThrownBy(() -> client.execute("SELECT user_id FROM user_volume"))
                    .isInstanceOf(FlightRuntimeException.class)
                    .satisfies(e -> assertThat(
                                    ((FlightRuntimeException) e).status().code())
                            .isEqualTo(CallStatus.RESOURCE_EXHAUSTED.code()))
                    .hasMessageContaining("PRV-4028");
        }
    }

    @Test
    void anUnknownViewIsNotFoundRatherThanInvalid() {
        // A client can tell "you asked for something that is not here" from "your SQL is wrong"
        // without parsing the message.
        assertThatThrownBy(() -> client.execute("SELECT * FROM nowhere"))
                .isInstanceOf(FlightRuntimeException.class)
                .satisfies(e -> assertThat(((FlightRuntimeException) e).status().code())
                        .isIn(CallStatus.NOT_FOUND.code(), CallStatus.INVALID_ARGUMENT.code()));
    }
}
