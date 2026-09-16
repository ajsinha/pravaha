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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java SDK binding values, as an application would (ADR-032).
 *
 * <p>The companion to the Python SDK's parameter tests, against the same data and the same
 * assertions, so the two clients can be read side by side. Two SDKs that bind differently is two
 * sets of support calls.
 */
@Timeout(60)
class JavaSdkParameterTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private PravahaFlightServer server;
    private PravahaFlightClient client;

    @BeforeEach
    void start() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 10);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 10);
        view.commit(10);

        server = new PravahaFlightServer(new ViewCatalog().register(view)).start("localhost", 0);
        client = PravahaFlightClient.connect("grpc://localhost:" + server.port());
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private List<String> userIds(String sql, Object... parameters) {
        List<String> users = new ArrayList<>();
        try (QueryResult result = client.query(sql, parameters)) {
            for (Row row : result) {
                users.add(row.getString("user_id"));
            }
        }
        return users;
    }

    @Test
    void aParameterBindsRatherThanBeingInterpolated() {
        assertThat(userIds("SELECT user_id FROM user_volume WHERE user_id = ?", "u1"))
                .containsExactly("u1");
    }

    @Test
    void oneStatementAnswersDifferentQuestions() {
        assertThat(userIds("SELECT user_id FROM user_volume WHERE user_id = ?", "u1"))
                .containsExactly("u1");
        assertThat(userIds("SELECT user_id FROM user_volume WHERE user_id = ?", "u2"))
                .containsExactly("u2");
    }

    @Test
    void aNumericParameterBinds() {
        assertThat(userIds("SELECT user_id FROM user_volume WHERE total > ?", 40L))
                .containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void anIntBindsToABigintPlaceholder() {
        // A caller who writes 40 rather than 40L should not have to care; the server said what the
        // placeholder needs and this converts to it.
        assertThat(userIds("SELECT user_id FROM user_volume WHERE total > ?", 40))
                .containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void aValueThatLooksLikeSqlIsAValue() {
        assertThat(userIds("SELECT user_id FROM user_volume WHERE user_id = ?", "u1' OR '1'='1"))
                .isEmpty();
    }

    @Test
    void bindingNullMatchesNothingRatherThanEverything() {
        // `tier = NULL` is UNKNOWN for every row, u3's included. Three-valued logic, not a bug.
        assertThat(userIds("SELECT user_id FROM user_volume WHERE tier = ?", new Object[] {null}))
                .isEmpty();
    }

    @Test
    void theWrongNumberOfValuesIsRefusedBeforeTheCall() {
        assertThatThrownBy(() -> client.query("SELECT user_id FROM user_volume WHERE user_id = ? AND total > ?", "u1"))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void anArityMismatchCarriesTheEnginesOwnCodeInAllThreeShapes() {
        // X-8. This refusal is raised client-side, before a request is sent -- which is right, and
        // is why the server's PRV-2061 (SqlErrors.PARAMETER_ARITY, thrown by
        // BoundParameters.requireArity) was unreachable through the SDK or the CLI: the SDK answered
        // first, under its own generic PRV-1041. A user who looked up 2061 in TROUBLESHOOTING.md
        // found a row for something that could not have happened to them. Same wording, engine's
        // code.
        //
        // All three shapes the register names, since they take different branches of the comparison:
        // too few, too many, and none expected but one bound.
        assertArityRefusal("SELECT user_id FROM user_volume WHERE user_id = ? AND total > ?", new Object[] {"u1"});
        assertArityRefusal("SELECT user_id FROM user_volume WHERE user_id = ?", new Object[] {"u1", 2L});
        assertArityRefusal("SELECT user_id FROM user_volume", new Object[] {"u1"});
    }

    private void assertArityRefusal(String sql, Object[] values) {
        assertThatThrownBy(() -> client.query(sql, values))
                .as(sql)
                .isInstanceOfSatisfying(PravahaClientException.class, e -> {
                    assertThat(e.errorCode().code()).isEqualTo("PRV-2061");
                    assertThat(e.errorCode().name()).isEqualTo("SQL_PARAMETER_ARITY");
                    assertThat(e.retryable())
                            .as("binding the same values again will fail the same way")
                            .isFalse();
                    assertThat(e.getMessage()).contains("placeholder");
                });
    }

    @Test
    void aValueOfTheWrongTypeNamesThePlaceholder() {
        assertThatThrownBy(() -> client.query("SELECT user_id FROM user_volume WHERE total > ?", "not a number"))
                .isInstanceOf(PravahaClientException.class)
                // "?1" rather than "a String was given", which is useless in a statement with six.
                .hasMessageContaining("?1");
    }

    @Test
    void aQueryWithNoParametersTakesTheSimplePath() {
        assertThat(userIds("SELECT user_id FROM user_volume")).hasSize(3);
    }
}
