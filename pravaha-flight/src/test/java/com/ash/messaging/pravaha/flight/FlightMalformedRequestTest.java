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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.flight.AsyncPutListener;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FLIGHTTICKET-1: a ticket the server cannot read, and a path descriptor where Flight SQL wants a
 * command, are refused by name -- {@code INVALID_ARGUMENT} PRV-6106 and {@code UNIMPLEMENTED}
 * PRV-6101 -- not gRPC {@code INTERNAL} with no code.
 */
@Timeout(60)
class FlightMalformedRequestTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static final String TOKEN = "admin-token";

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private final CallOption auth = new HeaderCallOption(new FlightCallHeaders() {
        {
            insert("authorization", "Bearer " + TOKEN);
        }
    });

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 100);
        view.commit(100);
        server = new PravahaFlightServer(new ViewCatalog().register(view), allocator)
                .authenticatedBy(StaticTokenVerifier.of(TOKEN, new Principal("admin", "public", Set.of(), Map.of())))
                .authorizedBy((principal, name) -> AccessDecision.allow(), AuditSink.NONE)
                .start("localhost", 0);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
        server.close();
        allocator.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000ÿ garbage", "NOPE:x", "LIST", "pravaha.list", ""})
    void anUnreadableTicketIsInvalidArgumentWithACode(String bytes) {
        FlightRuntimeException refused = catchThrowableOfType(FlightRuntimeException.class, () -> {
            try (FlightStream stream =
                    client.getStream(new Ticket(bytes.getBytes(StandardCharsets.ISO_8859_1)), auth)) {
                stream.next();
            }
        });

        assertThat((Object) refused).isNotNull();
        assertThat(refused.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
        assertThat(refused.getMessage()).contains("PRV-6106");
    }

    @Test
    void aPathDescriptorIsUnimplementedWithACode() {
        FlightRuntimeException info = catchThrowableOfType(
                FlightRuntimeException.class, () -> client.getInfo(FlightDescriptor.path("user_volume"), auth));
        assertThat((Object) info).isNotNull();
        assertThat(info.status().code()).isEqualTo(FlightStatusCode.UNIMPLEMENTED);
        assertThat(info.getMessage()).contains("PRV-6101").contains("path descriptor");

        FlightRuntimeException schema = catchThrowableOfType(
                FlightRuntimeException.class, () -> client.getSchema(FlightDescriptor.path("user_volume"), auth));
        assertThat((Object) schema).isNotNull();
        assertThat(schema.status().code()).isEqualTo(FlightStatusCode.UNIMPLEMENTED);
    }

    @Test
    void aPutWithAPathDescriptorIsUnimplementedWithACode() {
        Schema arrow = new Schema(List.of(new Field("k", FieldType.nullable(new ArrowType.Int(64, true)), null)));
        try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, allocator)) {
            root.setRowCount(0);
            FlightRuntimeException refused = catchThrowableOfType(FlightRuntimeException.class, () -> {
                FlightClient.ClientStreamListener put =
                        client.startPut(FlightDescriptor.path("user_volume"), root, new AsyncPutListener(), auth);
                put.putNext();
                put.completed();
                put.getResult();
            });
            assertThat((Object) refused).isNotNull();
            assertThat(refused.status().code()).isEqualTo(FlightStatusCode.UNIMPLEMENTED);
            assertThat(refused.getMessage()).contains("PRV-6101");
        }
    }

    @Test
    void readableTicketsArePassedThrough() {
        assertThat(RequestShapeGuard.readable(com.google.protobuf.Any.pack(
                                org.apache.arrow.flight.sql.impl.FlightSql.TicketStatementQuery.getDefaultInstance())
                        .toByteArray()))
                .isTrue();
        assertThat(RequestShapeGuard.readable(
                        com.ash.messaging.pravaha.api.wire.ControlWire.subscribeTicket("user_volume", List.of())))
                .isTrue();
        assertThat(RequestShapeGuard.readable("LIST".getBytes(StandardCharsets.US_ASCII)))
                .isFalse();
    }
}
