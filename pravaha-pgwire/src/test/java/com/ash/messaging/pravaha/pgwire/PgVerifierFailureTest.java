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
package com.ash.messaging.pravaha.pgwire;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J21-1: a custom token verifier that fails at sign-in other than by refusing is answered with
 * an ErrorResponse, not a dropped socket.
 *
 * <p>The sign-in caught only {@code PravahaException}; anything else escaped the connection's run
 * loop, and the client saw the connection close with nothing said -- while the same verifier
 * failing at the per-statement recheck was already a {@code FATAL 28000}.
 */
class PgVerifierFailureTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private PravahaPgWireServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aVerifierThatThrowsAtSignInIsAFatalErrorResponseThatDoesNotEchoTheFailure() throws Exception {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.commit(1);
        server = new PravahaPgWireServer(new ViewCatalog().register(view))
                .authenticatedBy(token -> {
                    throw new IllegalStateException("idp.internal:8443 refused the connection");
                })
                .start("127.0.0.1", 0);

        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "dana"));
            assertThat(java.util.Objects.requireNonNull(client.read()).type()).isEqualTo('R');
            client.password("anything");
            PgTestClient.Message answer = client.read();

            assertThat(answer).as("an answer, not a closed socket").isNotNull();
            assertThat(java.util.Objects.requireNonNull(answer).type()).isEqualTo('E');
            Map<Character, String> fields = PgTestClient.errorFields(answer);
            assertThat(fields.get('S')).isEqualTo("FATAL");
            assertThat(fields.get('C')).isEqualTo("28000");
            assertThat(fields.get('M')).doesNotContain("idp.internal");
        }
    }
}
