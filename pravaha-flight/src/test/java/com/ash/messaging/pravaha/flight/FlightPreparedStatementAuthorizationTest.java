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

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.Any;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.AsyncPutListener;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.sql.FlightSqlUtils;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
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
 * The {@code doPut} leg of a prepared statement authorizes like every other leg (SX-10).
 *
 * <p>It authorized nothing at all. No rows escaped -- the follow-on {@code
 * getFlightInfoPreparedStatement} re-authorizes and refuses before a batch is built -- but a
 * different principal could bind parameters into another principal's prepared statement and be told
 * the binding was accepted. A handle is a plan, never a permission, and the leg that accepted one
 * was the only one not saying so.
 *
 * <p>Driven at the protocol level rather than through {@code FlightSqlClient.PreparedStatement},
 * deliberately: that client does the put and the fetch inside one {@code execute()}, so a test
 * written through it cannot tell which of the two refused, and would have passed against the defect.
 */
@Timeout(60)
class FlightPreparedStatementAuthorizationTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final String SQL = "SELECT user_id FROM user_volume WHERE user_id = ?";

    private static final String ANALYST_TOKEN = "analyst-token";
    private static final String INTERN_TOKEN = "intern-token";

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        view.commit(10);

        StaticTokenVerifier verifier = StaticTokenVerifier.of(
                        ANALYST_TOKEN, new Principal("dana", "public", Set.of("analyst"), Map.of()))
                .and(INTERN_TOKEN, new Principal("sam", "public", Set.of("intern"), Map.of()));

        SecurityPolicy policy = (principal, viewName) -> principal.hasRole("analyst")
                ? AccessDecision.allow()
                : AccessDecision.deny("only analysts read " + viewName);

        server = new PravahaFlightServer(new ViewCatalog().register(view), allocator)
                .authenticatedBy(verifier)
                .authorizedBy(policy, new AuditSink.InMemory())
                .start("localhost", 0);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
    }

    @AfterEach
    void stop() throws Exception {
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

    /** The handle {@code dana} gets for a statement she is allowed to prepare. */
    private com.google.protobuf.ByteString danasHandle() {
        Action create = new Action(
                FlightSqlUtils.FLIGHT_SQL_CREATE_PREPARED_STATEMENT.getType(),
                Any.pack(FlightSql.ActionCreatePreparedStatementRequest.newBuilder()
                                .setQuery(SQL)
                                .build())
                        .toByteArray());
        Iterator<Result> results = client.doAction(create, bearing(ANALYST_TOKEN));
        FlightSql.ActionCreatePreparedStatementResult prepared = FlightSqlUtils.unpackAndParseOrThrow(
                results.next().getBody(), FlightSql.ActionCreatePreparedStatementResult.class);
        return prepared.getPreparedStatementHandle();
    }

    /** Binds one string into {@code handle} as whoever holds {@code token}. */
    private void bind(com.google.protobuf.ByteString handle, String token) {
        FlightDescriptor descriptor =
                FlightDescriptor.command(Any.pack(FlightSql.CommandPreparedStatementQuery.newBuilder()
                                .setPreparedStatementHandle(handle)
                                .build())
                        .toByteArray());
        Schema parameters = new Schema(List.of(Field.nullable("?0", ArrowType.Utf8.INSTANCE)));
        try (VectorSchemaRoot root = VectorSchemaRoot.create(parameters, allocator)) {
            VarCharVector values = (VarCharVector) root.getVector(0);
            values.allocateNew(1);
            values.setSafe(0, "u1".getBytes(StandardCharsets.UTF_8));
            root.setRowCount(1);
            FlightClient.ClientStreamListener put =
                    client.startPut(descriptor, root, new AsyncPutListener(), bearing(token));
            put.putNext();
            put.completed();
            put.getResult();
        }
    }

    @Test
    void anotherPrincipalCannotBindIntoThisOnesStatement() {
        com.google.protobuf.ByteString handle = danasHandle();

        assertThatThrownBy(() -> bind(handle, INTERN_TOKEN))
                .isInstanceOf(FlightRuntimeException.class)
                // PRV-7002 rather than a generic failure: the caller is who they say they are and a
                // fresh credential will not help, which is the distinction the status carries.
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("sam");
    }

    @Test
    void thePrincipalWhoPreparedItStillBinds() {
        // The other half: authorizing this leg must not break the leg itself. An over-strict fix
        // here would have been invisible in the refusal test and fatal to every prepared statement.
        com.google.protobuf.ByteString handle = danasHandle();

        bind(handle, ANALYST_TOKEN);

        assertThat(handle.size()).isPositive();
    }
}
