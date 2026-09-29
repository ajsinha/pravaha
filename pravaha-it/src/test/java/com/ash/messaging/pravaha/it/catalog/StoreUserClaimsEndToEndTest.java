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
package com.ash.messaging.pravaha.it.catalog;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.governance.CatalogProperties;
import com.ash.messaging.pravaha.server.identity.IdentityProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STORECLAIMS-1 on a real node: a user kept in the identity store (ADR-052), given the attribute
 * {@code region=EU}, signs in and is narrowed by a policy reading {@code session_attribute('region')} --
 * on a read, and in a continuous query of her own that is restored, still narrowed, after a restart.
 * Before the fix a store user carried no claims, so the read was refused with {@code PRV-7039}, and the
 * registration was not restored at all: recovery never asked the store who its owner was.
 */
@Timeout(120)
class StoreUserClaimsEndToEndTest {

    private static final String GOOD = "Correct-horse-9";

    private static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .build();

    @TempDir
    Path dir;

    private PravahaNode node;
    private BufferAllocator allocator;
    private FlightClient flight;
    private FlightSqlClient sql;
    private RowArena arena;
    private long sequence = 1;

    @AfterEach
    void stop() throws Exception {
        closeClient();
        if (node != null) {
            node.stop();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    void aStoreUsersAttributeNarrowsHerReadAndHerRestoredRegistration() throws Exception {
        start();
        IdentityService users = node.identity().orElseThrow();
        users.createUser(OPS, "ana", "Ana", null, "acme", Set.of("analyst"), GOOD, false);
        String before = users.login("ana", GOOD, null).token();

        run("ops-token", "GRANT CREATE ON NAMESPACE default TO ROLE analyst");
        run("ops-token", "GRANT BUILD_ON ON STREAM txn TO ROLE analyst");
        run("ops-token", "CREATE ROW FILTER region_scope AS region = session_attribute('region') EXCEPT ROLE admin");
        run("ops-token", "ALTER STREAM txn SET POLICY region_scope");

        // Without the attribute the policy has nothing to read, and says so.
        assertThatThrownBy(() -> run(
                        before, "CREATE CONTINUOUS QUERY ana_txn KEYED BY (id) AS SELECT id, region, amount FROM txn"))
                .hasMessageContaining("PRV-7039");

        users.setAttributes(OPS, "ana", Map.of("region", "EU"));
        String session = users.login("ana", GOOD, null).token();
        run(session, "CREATE CONTINUOUS QUERY ana_txn KEYED BY (id) AS SELECT id, region, amount FROM txn");
        feed();
        assertThat(rows(session, "SELECT id, region FROM ana_txn")).containsExactly("t1|EU", "t3|EU");
        assertThat(run("ops-token", "SHOW EFFECTIVE ACCESS FOR USER ana ON STREAM txn"))
                .anySatisfy(row -> assertThat(String.join(" ", row)).contains("region = 'EU'"));

        // A restart restores the registration as its owner, whose attribute the store still holds.
        closeClient();
        node.stop();
        start();
        assertThat(node.registry().orElseThrow().find("acme.default.ana_txn"))
                .as("restored for a store user")
                .isPresent();
        feed();
        String again = node.identity().orElseThrow().login("ana", GOOD, null).token();
        assertThat(rows(again, "SELECT id, region FROM ana_txn")).containsExactly("t1|EU", "t3|EU");
    }

    // ----------------------------------------------------------------------------------- set-up

    private void start() {
        StreamCatalog streams = new StreamCatalog();
        streams.register(TXN);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(dir.resolve("registry.journal").toString());
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        security.setPolicy("authenticated");
        Map<String, SecurityProperties.TokenSpec> tokens = new LinkedHashMap<>();
        SecurityProperties.TokenSpec ops = new SecurityProperties.TokenSpec();
        ops.setId("ops");
        ops.setTenant("acme");
        ops.setRoles(List.of("admin"));
        tokens.put("ops-token", ops);
        security.setTokens(tokens);
        IdentityProperties identity = new IdentityProperties();
        identity.setEnabled(true);
        identity.setStore(dir.resolve("identity/identity.journal").toString());
        identity.setEnvironment("qa");
        identity.setDev(true);
        node = PravahaNode.builder()
                .withCatalog(streams)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("store-claims-e2e")
                .build();
        node.setIdentity(identity);
        CatalogProperties catalog = new CatalogProperties();
        catalog.setEnabled(true);
        catalog.setJournal(dir.resolve("catalog.journal").toString());
        catalog.setAuthority("catalog");
        node.setCatalog(catalog);
        node.start();
        allocator = new RootAllocator(Long.MAX_VALUE);
        flight = FlightClient.builder(
                        allocator,
                        Location.forGrpcInsecure("127.0.0.1", node.flightPort().orElseThrow()))
                .build();
        sql = new FlightSqlClient(flight);
        arena = arena == null ? new RowArena(MemoryAccess.best(), 1 << 20, 8) : arena;
    }

    private void closeClient() throws Exception {
        if (sql != null) {
            sql.close();
            sql = null;
            flight = null;
        }
        if (allocator != null) {
            allocator.close();
            allocator = null;
        }
    }

    private void feed() {
        RegisteredQuery query =
                node.registry().orElseThrow().find("acme.default.ana_txn").orElseThrow();
        push(query, "t1", "EU", 10);
        push(query, "t2", "US", 20);
        push(query, "t3", "EU", 30);
        query.commit();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (query.view().size() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private void push(RegisteredQuery query, String id, String region, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id).setString(1, region).setLong(2, amount);
        long at = sequence++ * 1_000_000L;
        writer.weight(1).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private static CallOption bearer(String token) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + token);
        return new HeaderCallOption(headers);
    }

    private List<List<String>> run(String token, String statement) throws Exception {
        CallOption auth = bearer(token);
        FlightInfo info = sql.execute(statement, auth);
        List<List<String>> rows = new ArrayList<>();
        try (FlightStream stream = sql.getStream(info.getEndpoints().get(0).getTicket(), auth)) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                for (int row = 0; row < root.getRowCount(); row++) {
                    List<String> cells = new ArrayList<>();
                    for (int column = 0; column < root.getFieldVectors().size(); column++) {
                        Object value = root.getVector(column).getObject(row);
                        cells.add(value == null ? null : value.toString());
                    }
                    rows.add(cells);
                }
            }
        }
        return rows;
    }

    private List<String> rows(String token, String query) throws Exception {
        return run(token, query).stream()
                .map(row -> String.join("|", row))
                .sorted()
                .toList();
    }
}
