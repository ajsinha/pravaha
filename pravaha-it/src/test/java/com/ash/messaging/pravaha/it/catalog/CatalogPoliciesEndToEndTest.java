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

import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
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
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.governance.CatalogProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-059 phase 2 on a real node, over real Flight connections: one view, two analysts. {@code ana}
 * (claim {@code region: EU}) and {@code bob} ({@code region: US}) read and subscribe to the same view and
 * are each shown their own region's rows with the card number masked; {@code ops} (admin, exempt) sees
 * everything. A masked column compared is refused by name, a changed policy ends an open subscription,
 * and a restart keeps every policy and binding.
 */
@Timeout(120)
class CatalogPoliciesEndToEndTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.string())
            .field("region", Types.string())
            .field("card", Types.string())
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
    void twoUsersSeeTheirOwnRowsAndMaskedValuesFromTheSameView() throws Exception {
        start();
        run("ops", "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, region, card, amount FROM txn");
        run("ops", "GRANT SELECT, SUBSCRIBE ON VIEW payments TO ROLE analyst");
        run("ops", "CREATE ROW FILTER region_scope AS region = session_attribute('region') EXCEPT ROLE admin");
        run("ops", "CREATE MASK card_last4 ON COLUMN card AS 'XXXX-' || SUBSTRING(card FROM 6) EXCEPT ROLE admin");
        run("ops", "ALTER VIEW payments SET POLICY region_scope");
        run("ops", "ALTER TAG 'pii' SET POLICY card_last4");
        run("ops", "ALTER VIEW payments SET TAGS ('pii')");
        pay();

        assertThat(rows("ana", "SELECT id, region, card, amount FROM payments"))
                .containsExactly("p1|EU|XXXX-1111|10", "p3|EU|XXXX-3333|30");
        assertThat(rows("bob", "SELECT id, region, card, amount FROM payments")).containsExactly("p2|US|XXXX-2222|20");
        assertThat(rows("ops", "SELECT id, region, card, amount FROM payments"))
                .containsExactly("p1|EU|4111-1111|10", "p2|US|4222-2222|20", "p3|EU|4333-3333|30");

        // A point read by key is narrowed as a scan is: masked, and nothing for a row the filter drops.
        assertThat(rows("ana", "SELECT card FROM payments WHERE id = 'p1'")).containsExactly("XXXX-1111");
        assertThat(rows("ana", "SELECT card FROM payments WHERE id = 'p2'")).isEmpty();
        assertThat(rows("ana", "SELECT SUM(amount) AS s FROM payments")).containsExactly("40");
        assertRefused(() -> run("ana", "SELECT id FROM payments WHERE card = '4111-1111'"), "PRV-7006");

        // What applies, and why.
        assertThat(run("ops", "SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW payments"))
                .anySatisfy(row -> assertThat(row)
                        .containsSequence(
                                "ROW FILTER acme.default.region_scope",
                                "true",
                                "region = 'EU' (bound to acme.default.payments)"));
        assertThat(run("ops", "SHOW POLICIES ON VIEW payments"))
                .extracting(row -> row.get(0) + " " + row.get(5))
                .containsExactly(
                        "acme.default.region_scope acme.default.payments", "acme.default.card_last4 TAG 'pii'");

        // A subscription is shown the same, and ends when its policy changes rather than change meaning.
        try (FlightStream open = flight.getStream(
                new Ticket(ControlWire.subscribeFromSnapshotTicket("payments", List.of())), bearer("bob"))) {
            assertThat(open.next()).isTrue();
            assertThat(cells(open.getRoot())).containsExactly("p2|US|XXXX-2222|20|1");
            run("ops", "ALTER VIEW payments UNSET POLICY region_scope");
            assertRefused(
                    () -> {
                        while (open.next()) {
                            // draining until the policy change ends it
                        }
                        return null;
                    },
                    "PRV-7007");
        }
        assertThat(rows("bob", "SELECT id FROM payments")).containsExactly("p1", "p2", "p3");
        run("ops", "ALTER VIEW payments SET POLICY region_scope");

        // A restart keeps the policies, the bindings and the tag.
        closeClient();
        node.stop();
        start();
        pay();
        assertThat(rows("ana", "SELECT id, card FROM payments")).containsExactly("p1|XXXX-1111", "p3|XXXX-3333");
        assertThat(rows("bob", "SELECT id, card FROM payments")).containsExactly("p2|XXXX-2222");
    }

    @Test
    void aFilterThatRestrictsNothingIsRefusedWhenBoundOrWhenApplied() throws Exception {
        // TAUTOFILTER-1. Only a filter the planner folded to TRUE used to be refused; these reached the
        // plan as predicates and were bound as though they restricted something.
        start();
        run("ops", "CREATE CONTINUOUS QUERY payments KEYED BY (id) AS SELECT id, region, card, amount FROM txn");
        run("ops", "GRANT SELECT ON VIEW payments TO ROLE analyst");
        run("ops", "CREATE ROW FILTER same_region AS region = region");
        run("ops", "CREATE ROW FILTER either AS 1 = 1 OR region = 'EU'");
        run("ops", "CREATE ROW FILTER nothing AS amount <> amount");
        for (String vacuous : List.of("same_region", "either")) {
            assertThatThrownBy(() -> run("ops", "ALTER VIEW payments SET POLICY " + vacuous))
                    .as(vacuous)
                    .hasMessageContaining("PRV-7038")
                    .hasMessageContaining("TAUTOFILTER-1");
        }
        assertThatThrownBy(() -> run("ops", "ALTER VIEW payments SET POLICY nothing"))
                .hasMessageContaining("false for every row");

        // A filter that reads the session is judged when it is bound to a reader: under the probe it
        // is TRUE, and for a member of auditor it would restrict; for ana it restricts nothing.
        run("ops", "CREATE ROW FILTER outsiders AS NOT is_member('auditor') OR region = 'EU' EXCEPT ROLE admin");
        run("ops", "ALTER VIEW payments SET POLICY outsiders");
        pay();
        assertRefused(() -> run("ana", "SELECT id FROM payments"), "PRV-7003");
        run("ops", "ALTER VIEW payments UNSET POLICY outsiders");
        run("ops", "CREATE ROW FILTER eu AS region = 'EU' EXCEPT ROLE admin");
        run("ops", "ALTER VIEW payments SET POLICY eu");
        assertThat(rows("ana", "SELECT id FROM payments")).containsExactly("p1", "p3");
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
        tokens.put("ana-token", spec("ana", "analyst", "EU"));
        tokens.put("bob-token", spec("bob", "analyst", "US"));
        tokens.put("ops-token", spec("ops", "admin", null));
        security.setTokens(tokens);
        node = PravahaNode.builder()
                .withCatalog(streams)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("policies-e2e")
                .build();
        CatalogProperties catalog = new CatalogProperties();
        catalog.setEnabled(true);
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

    private static SecurityProperties.TokenSpec spec(String id, String role, String region) {
        SecurityProperties.TokenSpec spec = new SecurityProperties.TokenSpec();
        spec.setId(id);
        spec.setTenant("acme");
        spec.setRoles(List.of(role));
        if (region != null) {
            spec.setClaims(Map.of("region", region));
        }
        return spec;
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

    private void pay() {
        RegisteredQuery payments =
                node.registry().orElseThrow().find("payments").orElseThrow();
        push(payments, "p1", "EU", "4111-1111", 10);
        push(payments, "p2", "US", "4222-2222", 20);
        push(payments, "p3", "EU", "4333-3333", 30);
        payments.commit();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (payments.view().size() < 3 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private void push(RegisteredQuery query, String id, String region, String card, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id).setString(1, region).setString(2, card).setLong(3, amount);
        long at = sequence++ * 1_000_000L;
        writer.weight(1).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private static CallOption bearer(String who) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + who + "-token");
        return new HeaderCallOption(headers);
    }

    private List<List<String>> run(String who, String statement) throws Exception {
        CallOption auth = bearer(who);
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

    private List<String> rows(String who, String query) throws Exception {
        return run(who, query).stream()
                .map(row -> String.join("|", row))
                .sorted()
                .toList();
    }

    private static List<String> cells(VectorSchemaRoot root) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < root.getRowCount(); row++) {
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < root.getFieldVectors().size(); column++) {
                cells.add(String.valueOf(root.getVector(column).getObject(row)));
            }
            rows.add(String.join("|", cells));
        }
        return rows;
    }

    private interface Action {
        Object run() throws Exception;
    }

    private static void assertRefused(Action action, String code) {
        assertThatThrownBy(action::run).hasMessageContaining(code);
    }
}
