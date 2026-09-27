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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java SDK against a server that requires a credential (ADR-031).
 *
 * <p>The companion to the Python SDK's authentication tests, against the same server configuration
 * and the same tokens, so the two clients can be compared line for line. Two SDKs that authenticate
 * differently is two sets of support calls.
 */
@Timeout(60)
class JavaSdkAuthenticationTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final String ANALYST_TOKEN = "analyst-token-1";
    private static final String INTERN_TOKEN = "intern-token-1";

    private PravahaFlightServer server;

    @BeforeEach
    void start() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 10);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 10);
        view.commit(10);

        server = new PravahaFlightServer(new ViewCatalog().register(view))
                .authenticatedBy(StaticTokenVerifier.of(
                                ANALYST_TOKEN, new Principal("dana", "acme", Set.of("analyst"), Map.of("tier", "gold")))
                        .and(INTERN_TOKEN, new Principal("sam", "acme", Set.of("intern"), Map.of())))
                .authorizedBy(
                        (principal, viewName) -> principal.hasRole("analyst")
                                ? AccessDecision.allowWithRowFilter(
                                        "tier = '" + principal.claim("tier").orElse("none") + "'")
                                : AccessDecision.deny("only analysts read " + viewName),
                        AuditSink.NONE)
                .start("localhost", 0);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private PravahaFlightClient clientWith(String token) {
        ClientOptions.Builder options =
                ClientOptions.builder("grpc://localhost:" + server.port()).allowInsecureToken(true);
        if (token != null) {
            options.token(token);
        }
        return PravahaFlightClient.connect(options.build());
    }

    @Test
    void aClientWithNoCredentialIsRefused() {
        try (PravahaFlightClient client = clientWith(null)) {
            assertThatThrownBy(() -> client.query("SELECT user_id FROM user_volume"))
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("PRV-7001");
        }
    }

    @Test
    void anUnknownCredentialIsRefused() {
        try (PravahaFlightClient client = clientWith("guessed")) {
            assertThatThrownBy(() -> client.query("SELECT user_id FROM user_volume"))
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("PRV-7001");
        }
    }

    @Test
    void anAuthenticatedButUnauthorizedCallerIsToldApart() {
        try (PravahaFlightClient client = clientWith(INTERN_TOKEN)) {
            // PRV-7002, not PRV-7001. A fresh credential will not help; access is what is missing.
            assertThatThrownBy(() -> client.query("SELECT user_id FROM user_volume"))
                    .isInstanceOf(PravahaClientException.class)
                    .hasMessageContaining("PRV-7002");
        }
    }

    @Test
    void anAuthorizedCallerSeesOnlyItsOwnRows() {
        try (PravahaFlightClient client = clientWith(ANALYST_TOKEN);
                QueryResult result = client.query("SELECT user_id, tier FROM user_volume")) {
            List<String> users = new java.util.ArrayList<>();
            for (Row row : result) {
                users.add(row.getString("user_id"));
            }

            // The query carried no filter of its own. u2 is silver; u3 has no tier, and NULL is not
            // 'gold' under three-valued logic, so neither reaches the client.
            assertThat(users).containsExactly("u1");
        }
    }

    @Test
    void aParameterisedQueryUnderATokenReturnsItsAnswer() {
        // Found by the tutorials' agent: the prepared statement was closed by try-with-resources,
        // whose close() sends ClosePreparedStatement with no credentials, and under a token the
        // server's refusal (PRV-7001) replaced the answer the query had already computed.
        try (PravahaFlightClient client = clientWith(ANALYST_TOKEN);
                QueryResult result = client.query("SELECT user_id, total FROM user_volume WHERE user_id = ?", "u1")) {
            List<String> users = new java.util.ArrayList<>();
            for (Row row : result) {
                users.add(row.getString("user_id"));
            }
            assertThat(users).containsExactly("u1");
        }
    }

    @Test
    void aTokenIsRefusedOverAPlaintextConnectionUnlessAskedFor() {
        assertThatThrownBy(() -> ClientOptions.builder("grpc://example.com:19090")
                        .token("s3cret")
                        .build())
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("plaintext");
    }

    @Test
    void theTokenNeverAppearsInAToString() {
        ClientOptions options = ClientOptions.builder("grpc://localhost:1")
                .token("s3cret")
                .allowInsecureToken(true)
                .build();

        // Options reach log lines. A credential in one reaches the log with it.
        assertThat(options.toString()).doesNotContain("s3cret").contains("authenticated");
    }
}
