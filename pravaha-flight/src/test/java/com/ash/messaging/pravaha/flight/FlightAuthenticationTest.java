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

import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authentication and authorization over the wire (ADR-031).
 *
 * <p>Everything here is asserted through the stock Flight SQL client rather than through Pravaha's
 * own SDK, for the same reason {@link FlightSqlEndToEndTest} does: the JDBC driver and the Python
 * and Go clients are built on this client, so a refusal that this test sees is the refusal they
 * see. A check that only held for a client we also wrote would be a check on our own manners.
 */
@Timeout(60)
class FlightAuthenticationTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final String ANALYST_TOKEN = "analyst-token-1";
    private static final String INTERN_TOKEN = "intern-token-1";

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightSqlClient client;
    private AuditSink.InMemory audit;

    @BeforeEach
    void startServer() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 10);
        view.applyValues(new Object[] {"u4", "gold", 1200L}, 1, 10);
        view.commit(10);
        audit = new AuditSink.InMemory();

        StaticTokenVerifier verifier = StaticTokenVerifier.of(
                        ANALYST_TOKEN, new Principal("dana", "acme", Set.of("analyst"), Map.of("tier", "gold")))
                .and(INTERN_TOKEN, new Principal("sam", "acme", Set.of("intern"), Map.of()));

        SecurityPolicy policy = (principal, viewName) -> {
            if (principal.hasRole("analyst")) {
                return AccessDecision.allowWithRowFilter(
                        "tier = '" + principal.claim("tier").orElse("none") + "'");
            }
            return AccessDecision.deny("only analysts read " + viewName);
        };

        server = new PravahaFlightServer(new ViewCatalog().register(view), allocator)
                .authenticatedBy(verifier)
                .authorizedBy(policy, audit)
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

    private static CallOption[] bearing(String token) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + token);
        return new CallOption[] {new HeaderCallOption(headers)};
    }

    @Test
    void aCallWithNoCredentialIsRefusedBeforeItIsPlanned() {
        assertThatThrownBy(() -> client.execute("SELECT user_id FROM user_volume"))
                .isInstanceOf(FlightRuntimeException.class)
                .satisfies(e -> assertThat(((FlightRuntimeException) e).status().code())
                        .isEqualTo(CallStatus.UNAUTHENTICATED.code()))
                .hasMessageContaining("PRV-7001");
    }

    @Test
    void anUnknownCredentialIsRefused() {
        assertThatThrownBy(() -> client.execute("SELECT user_id FROM user_volume", bearing("guessed")))
                .isInstanceOf(FlightRuntimeException.class)
                .satisfies(e -> assertThat(((FlightRuntimeException) e).status().code())
                        .isEqualTo(CallStatus.UNAUTHENTICATED.code()));
    }

    @Test
    void aCredentialInTheWrongSchemeIsRefused() {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Basic " + ANALYST_TOKEN);

        assertThatThrownBy(() -> client.execute("SELECT user_id FROM user_volume", new HeaderCallOption(headers)))
                .isInstanceOf(FlightRuntimeException.class)
                .hasMessageContaining("Bearer");
    }

    @Test
    void anAuthenticatedButUnauthorizedCallerIsRefusedWithTheEnginesOwnCode() {
        assertThatThrownBy(() -> client.execute("SELECT user_id FROM user_volume", bearing(INTERN_TOKEN)))
                .isInstanceOf(FlightRuntimeException.class)
                // Not UNAUTHENTICATED: the caller is who they say they are, and retrying with a
                // fresh credential will not help. Telling them apart is the difference between
                // "log in again" and "ask for access".
                .hasMessageContaining("PRV-7002");
    }

    @Test
    void anAuthorizedCallerSeesOnlyTheRowsThePolicyAllows() throws Exception {
        FlightInfo info = client.execute("SELECT user_id, tier FROM user_volume", bearing(ANALYST_TOKEN));
        List<String> users = new java.util.ArrayList<>();
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket(), bearing(ANALYST_TOKEN))) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                VarCharVector ids = (VarCharVector) root.getVector("user_id");
                for (int i = 0; i < root.getRowCount(); i++) {
                    users.add(new String(ids.get(i), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }

        // u2 is silver and never crosses the wire. Note what this does *not* rely on: the client
        // asked for no filter at all.
        assertThat(users).containsExactlyInAnyOrder("u1", "u4");
    }

    @Test
    void theServerRecordsWhoAskedWhat() throws Exception {
        FlightInfo info = client.execute("SELECT user_id FROM user_volume", bearing(ANALYST_TOKEN));
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket(), bearing(ANALYST_TOKEN))) {
            while (stream.next()) {
                // Drained so the call completes.
            }
        }

        assertThat(audit.forPrincipal("dana")).isNotEmpty();
        assertThat(audit.forPrincipal("dana"))
                .allSatisfy(event -> assertThat(event.target()).isEqualTo("user_volume"));
    }

    @Test
    void configuringSecurityAfterStartIsRefused() {
        assertThatThrownBy(() -> server.authenticatedBy(StaticTokenVerifier.of("x", Principal.of("y"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already accepting calls");
    }
}
