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
import java.util.Map;
import java.util.Set;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.flight.sql.util.TableRef;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 over Flight: two tenants' {@code orders} are two views, each tenant reaching only its own
 * by that name -- registered, listed, read, described in {@code GetTables}, keyed, subscribed to and
 * dropped -- and another tenant's name answering every one of those as a name nothing holds.
 */
@Timeout(60)
class FlightTenantNamesTest {

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String BIG = "SELECT trade_id, amount FROM trade WHERE amount > 100";
    private static final String SMALL = "SELECT trade_id, amount FROM trade WHERE amount <= 100";

    private static final String DANA = "dana-token";
    private static final String OMAR = "omar-token";
    private static final String ROOT = "root-token";

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private FlightSqlClient sql;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        server = new PravahaFlightServer(views, allocator)
                .hosting(registry)
                .authenticatedBy(
                        StaticTokenVerifier.of(DANA, new Principal("dana", "acme", Set.of("analyst"), Map.of()))
                                .and(OMAR, new Principal("omar", "globex", Set.of("analyst"), Map.of()))
                                .and(ROOT, new Principal("root", "public", Set.of("admin"), Map.of())))
                .authorizedBy(SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .start("localhost", 0);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
        sql = new FlightSqlClient(FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build());

        act(DANA, ControlWire.REGISTER, "orders", BIG, "0");
        act(OMAR, ControlWire.REGISTER, "orders", SMALL, "0");
        act(DANA, ControlWire.REGISTER, "payroll", BIG, "0");
        feed(registry.find("acme.default.orders").orElseThrow(), "t1", 500);
        feed(registry.find("globex.default.orders").orElseThrow(), "t2", 50);
    }

    @AfterEach
    void stop() throws Exception {
        sql.close();
        client.close();
        server.close();
        registry.close();
        arena.close();
        allocator.close();
    }

    @Test
    void eachTenantListsReadsDescribesAndKeysOnlyItsOwnOrders() {
        assertThat(act(DANA, ControlWire.LIST))
                .extracting(row -> row.get(0) + " " + row.get(2))
                .containsExactlyInAnyOrder("orders " + BIG, "payroll " + BIG);
        assertThat(act(OMAR, ControlWire.LIST))
                .extracting(row -> row.get(0) + " " + row.get(2))
                .containsExactly("orders " + SMALL);

        assertThat(read(DANA, "SELECT trade_id FROM orders")).containsExactly(List.of("t1"));
        assertThat(read(OMAR, "SELECT trade_id FROM orders")).containsExactly(List.of("t2"));

        assertThat(rows(sql.getTables(null, null, null, null, false, bearing(OMAR)), OMAR))
                .extracting(row -> row.get(2))
                .containsExactly("orders");
        assertThat(rows(sql.getPrimaryKeys(TableRef.of(null, null, "orders"), bearing(OMAR)), OMAR))
                .extracting(row -> row.get(3))
                .containsExactly("trade_id");

        assertThat(subscribeSnapshot(OMAR, "orders")).containsExactly("t2");

        act(OMAR, ControlWire.DROP, "orders");
        assertThat(registry.find("globex.default.orders")).isEmpty();
        assertThat(registry.find("acme.default.orders")).isPresent();
    }

    @Test
    void anotherTenantsNameAnswersOverFlightAsANameNobodyHolds() {
        for (String verb : List.of(ControlWire.DROP, ControlWire.PAUSE, ControlWire.REPLACEMENT)) {
            assertThat(refusal(() -> act(OMAR, verb, "payroll")).replace("payroll", "X"))
                    .as(verb)
                    .isEqualTo(refusal(() -> act(OMAR, verb, "nothing")).replace("nothing", "X"));
        }
        assertThat(refusal(() -> read(OMAR, "SELECT * FROM payroll")).replace("payroll", "X"))
                .isEqualTo(refusal(() -> read(OMAR, "SELECT * FROM nothing")).replace("nothing", "X"));
        assertThat(refusal(() -> subscribeSnapshot(OMAR, "payroll")).replace("payroll", "X"))
                .isEqualTo(refusal(() -> subscribeSnapshot(OMAR, "nothing")).replace("nothing", "X"));
        assertThat(rows(sql.getPrimaryKeys(TableRef.of(null, null, "payroll"), bearing(OMAR)), OMAR))
                .isEmpty();
        assertThat(refusal(() -> act(OMAR, ControlWire.DROP, "acme.default.payroll"))
                        .replace("payroll", "X"))
                .as("qualified: refused alike, held or not")
                .isEqualTo(refusal(() -> act(OMAR, ControlWire.DROP, "acme.default.nothing"))
                        .replace("nothing", "X"));
        assertThat(registry.find("acme.default.payroll")).isPresent();
    }

    @Test
    void anAdminReachesAnotherTenantsViewByItsCatalogueName() {
        assertThat(act(ROOT, ControlWire.LIST))
                .extracting(row -> row.get(0))
                .contains("acme.default.orders", "globex.default.orders");
        assertThat(read(ROOT, "SELECT trade_id FROM \"acme.default.orders\"")).containsExactly(List.of("t1"));
        act(ROOT, ControlWire.DROP, "acme.default.orders");
        assertThat(registry.find("acme.default.orders")).isEmpty();
        assertThat(registry.find("globex.default.orders")).isPresent();
    }

    // ------------------------------------------------------------------ helpers

    private List<List<String>> act(String token, String type, String... fields) {
        List<List<String>> results = new ArrayList<>();
        client.doAction(new Action(type, ControlWire.encode(fields)), bearing(token))
                .forEachRemaining(result -> results.add(ControlWire.decode(result.getBody())));
        return results;
    }

    private List<List<String>> read(String token, String query) {
        return rows(sql.execute(query, bearing(token)), token);
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    private List<String> subscribeSnapshot(String token, String view) {
        List<String> ids = new ArrayList<>();
        try (FlightStream stream = client.getStream(
                new Ticket(ControlWire.subscribeFromSnapshotTicket(view, List.of())), bearing(token))) {
            while (ids.isEmpty() && stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                for (int row = 0; row < root.getRowCount(); row++) {
                    ids.add(String.valueOf(root.getVector("trade_id").getObject(row)));
                }
            }
        } catch (FlightRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    private List<List<String>> rows(FlightInfo info, String token) {
        List<List<String>> out = new ArrayList<>();
        try (FlightStream stream = sql.getStream(info.getEndpoints().get(0).getTicket(), bearing(token))) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                for (int row = 0; row < root.getRowCount(); row++) {
                    List<String> values = new ArrayList<>();
                    for (FieldVector vector : root.getFieldVectors()) {
                        values.add(vector.isNull(row) ? null : String.valueOf(vector.getObject(row)));
                    }
                    out.add(values);
                }
            }
        } catch (FlightRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static String refusal(Runnable call) {
        try {
            call.run();
            return "answered";
        } catch (FlightRuntimeException e) {
            return e.status().code() + " " + e.getMessage();
        }
    }

    private static CallOption bearing(String token) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + token);
        return new HeaderCallOption(headers);
    }

    private void feed(RegisteredQuery query, String tradeId, long amount) {
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, tradeId);
        writer.setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(java.time.Duration.ofSeconds(10));
        query.commit();
    }
}
