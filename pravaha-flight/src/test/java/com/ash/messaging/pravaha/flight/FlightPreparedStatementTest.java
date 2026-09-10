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

import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prepared statements over the wire (ADR-032).
 *
 * <p>Driven by the stock Flight SQL client, which is what the JDBC driver and the Python, Go and
 * ADBC clients are built on. That matters more here than anywhere else: prepared statements involve
 * four round trips with a handle that the server may rewrite in the middle, and a server that got
 * the protocol subtly wrong would still pass a test written against a client we also wrote.
 */
@Timeout(60)
class FlightPreparedStatementTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightSqlClient client;

    @BeforeEach
    void startServer() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 10);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 10);
        view.applyValues(new Object[] {"u4", "gold", 1200L}, 1, 10);
        view.commit(10);

        server = new PravahaFlightServer(new ViewCatalog().register(view), allocator).start("localhost", 0);
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

    /** Binds one string and returns the user_ids that come back. */
    private List<String> runWithString(FlightSqlClient.PreparedStatement statement, String value) throws Exception {
        try (VectorSchemaRoot parameters = VectorSchemaRoot.create(statement.getParameterSchema(), allocator)) {
            VarCharVector vector = (VarCharVector) parameters.getVector(0);
            vector.allocateNew(1);
            if (value == null) {
                vector.setNull(0);
            } else {
                vector.setSafe(0, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            parameters.setRowCount(1);
            statement.setParameters(parameters);
            return userIds(statement.execute());
        }
    }

    private List<String> userIds(FlightInfo info) throws Exception {
        List<String> users = new ArrayList<>();
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                VarCharVector ids = (VarCharVector) root.getVector("user_id");
                for (int i = 0; i < root.getRowCount(); i++) {
                    users.add(ids.isNull(i) ? null : new String(ids.get(i), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return users;
    }

    @Test
    void aStatementReportsItsParametersBeforeAnythingIsBound() {
        try (FlightSqlClient.PreparedStatement statement =
                client.prepare("SELECT user_id FROM user_volume WHERE user_id = ?")) {

            Schema parameters = statement.getParameterSchema();
            // The type is not declared by the caller: the planner infers it from the column, and it
            // arrives before the client has chosen a value. That is what stops a driver guessing.
            assertThat(parameters.getFields()).hasSize(1);
            assertThat(parameters.getFields().get(0).getType())
                    .isEqualTo(org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE);
            assertThat(statement.getResultSetSchema().getFields()).hasSize(1);
        }
    }

    @Test
    void oneStatementAnswersDifferentQuestions() throws Exception {
        try (FlightSqlClient.PreparedStatement statement =
                client.prepare("SELECT user_id FROM user_volume WHERE user_id = ?")) {

            assertThat(runWithString(statement, "u1")).containsExactly("u1");
            // Re-bound and re-run on the same handle, which is the whole point.
            assertThat(runWithString(statement, "u4")).containsExactly("u4");
        }
    }

    @Test
    void aNumericParameterBinds() throws Exception {
        try (FlightSqlClient.PreparedStatement statement =
                client.prepare("SELECT user_id FROM user_volume WHERE total > ?")) {
            try (VectorSchemaRoot parameters = VectorSchemaRoot.create(statement.getParameterSchema(), allocator)) {
                BigIntVector vector = (BigIntVector) parameters.getVector(0);
                vector.allocateNew(1);
                vector.setSafe(0, 100L);
                parameters.setRowCount(1);
                statement.setParameters(parameters);

                assertThat(userIds(statement.execute())).containsExactlyInAnyOrder("u1", "u4");
            }
        }
    }

    @Test
    void aValueThatLooksLikeSqlIsAValue() throws Exception {
        try (FlightSqlClient.PreparedStatement statement =
                client.prepare("SELECT user_id FROM user_volume WHERE user_id = ?")) {

            // Never escaped, because never parsed: by the time this value exists on the server the
            // statement is already planned and there is no parser left for it to reach.
            assertThat(runWithString(statement, "u1' OR '1'='1")).isEmpty();
        }
    }

    @Test
    void bindingNullMatchesNothingRatherThanEverything() throws Exception {
        try (FlightSqlClient.PreparedStatement statement =
                client.prepare("SELECT user_id FROM user_volume WHERE tier = ?")) {

            // `tier = NULL` is UNKNOWN for every row, u3 included. Three-valued logic, not a bug.
            assertThat(runWithString(statement, null)).isEmpty();
        }
    }

    @Test
    void aStatementWithNoParametersStillPrepares() throws Exception {
        try (FlightSqlClient.PreparedStatement statement = client.prepare("SELECT user_id FROM user_volume")) {

            assertThat(statement.getParameterSchema().getFields()).isEmpty();
            assertThat(userIds(statement.execute())).hasSize(4);
        }
    }

    @Test
    void aStatementThatNamesNoSuchViewIsRefusedAtPrepareTime() {
        // Before any value is bound, so a client building a screen finds out while it is still
        // building rather than when somebody presses a button.
        assertThatThrownBy(() -> client.prepare("SELECT * FROM nowhere")).isInstanceOf(FlightRuntimeException.class);
    }

    @Test
    void closingAStatementIsAcceptedEvenThoughNothingIsHeld() {
        FlightSqlClient.PreparedStatement statement =
                client.prepare("SELECT user_id FROM user_volume WHERE user_id = ?");
        statement.close();

        // A client that closes what it opened should not be given an error for doing the right
        // thing, even on a server with nothing to release.
        assertThat(statement.isClosed()).isTrue();
    }
}
