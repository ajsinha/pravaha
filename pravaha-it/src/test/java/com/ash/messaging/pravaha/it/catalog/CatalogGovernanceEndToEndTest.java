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

import java.nio.file.Files;
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
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.governance.CatalogProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-059 phase 1 on a real node, over a real Flight connection: {@code ops} (admin) creates a namespace
 * and grants; {@code ana} (analyst) reads and subscribes to exactly what she is granted, is refused by
 * name otherwise, loses an open subscription when it is revoked, and a restart keeps every grant.
 */
@Timeout(120)
class CatalogGovernanceEndToEndTest {

    @TempDir
    Path dir;

    private PravahaNode node;
    private BufferAllocator allocator;
    private FlightClient flight;
    private FlightSqlClient sql;

    @AfterEach
    void stop() throws Exception {
        closeClient();
        if (node != null) {
            node.stop();
        }
    }

    @Test
    void grantsGovernReadsAndSubscriptionsAndSurviveARestart() throws Exception {
        start();
        run("ops", "CREATE NAMESPACE sales COMMENT 'Order-to-cash'");
        run("ops", "CREATE CONTINUOUS QUERY revenue KEYED BY (region) AS " + "SELECT region, amount FROM txn");
        run(
                "ops",
                "CREATE CONTINUOUS QUERY margin KEYED BY (region) AS "
                        + "SELECT region, amount AS top FROM txn WHERE amount > 0");
        run("ops", "ALTER VIEW revenue SET NAMESPACE sales");
        run("ops", "ALTER VIEW margin SET NAMESPACE sales");
        run("ops", "COMMENT ON VIEW sales.revenue IS 'Revenue per region'");
        run("ops", "ALTER VIEW sales.revenue SET TAGS ('domain' = 'finance')");
        run("ops", "GRANT USE ON NAMESPACE sales TO ROLE analyst");
        run("ops", "GRANT SELECT ON VIEW sales.revenue TO ROLE analyst");

        // Exactly what she is granted: revenue, by read, and nothing else.
        run("ana", "SELECT * FROM revenue");
        assertRefused(() -> run("ana", "SELECT * FROM margin"), "PRV-7002");
        assertRefused(() -> subscribe("ana", "revenue"), "PRV-7002");
        // Changing a grant is a right she does not hold, refused by its own name.
        assertRefused(() -> run("ana", "GRANT SUBSCRIBE ON VIEW sales.revenue TO USER ana"), "PRV-7033");

        run("ops", "GRANT SUBSCRIBE ON VIEW sales.revenue TO ROLE analyst");
        try (FlightStream open = subscribe("ana", "revenue")) {
            assertThat(open.getSchema().getFields()).isNotEmpty();
            // Revoked while open: the stream ends at its next authorization check, by name.
            run("ops", "REVOKE SUBSCRIBE ON VIEW sales.revenue FROM ROLE analyst");
            assertRefused(open::next, "PRV-7002");
        }
        assertRefused(() -> subscribe("ana", "revenue"), "PRV-7002");

        List<List<String>> why = run("ops", "SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW sales.revenue");
        assertThat(why)
                .anySatisfy(row -> assertThat(row)
                        .containsSequence("SELECT", "true", "grant SELECT on acme.sales.revenue to ROLE analyst"));

        // A restart keeps the namespace, the move, the metadata, the grant and the revocation.
        restart();
        run("ana", "SELECT * FROM revenue");
        assertRefused(() -> subscribe("ana", "revenue"), "PRV-7002");
        assertRefused(() -> run("ana", "SELECT * FROM margin"), "PRV-7002");
        assertThat(run("ops", "SHOW GRANTS ON VIEW sales.revenue"))
                .extracting(row -> row.get(1))
                .containsExactly("SELECT");

        run("ops", "REVOKE SELECT ON VIEW sales.revenue FROM ROLE analyst");
        assertRefused(() -> run("ana", "SELECT * FROM revenue"), "PRV-7002");
        assertThat(Files.exists(dir.resolve("catalog.journal"))).isTrue();
    }

    @Test
    void importingThePolicyOnceAndRefusingTwoAuthorities() throws Exception {
        start("import");
        // authenticated was imported: every verified caller reads, as before the catalogue.
        run("ops", "CREATE CONTINUOUS QUERY revenue KEYED BY (region) AS " + "SELECT region, amount FROM txn");
        run("ana", "SELECT * FROM revenue");
        closeClient();
        node.stop();
        node = null;

        // Now configured with a different policy: the node refuses to start with two authorities.
        PravahaNode changed = node(security("permissive", true), "import");
        assertThatThrownBy(changed::start).hasMessageContaining("PRV-7034");
    }

    // ----------------------------------------------------------------------------------- set-up

    private void start() throws Exception {
        start("catalog");
    }

    private void start(String authority) throws Exception {
        node = node(security("authenticated", false), authority);
        node.start();
        openClient();
    }

    private void restart() throws Exception {
        closeClient();
        node.stop();
        start();
    }

    private PravahaNode node(SecurityProperties security, String authority) {
        StreamCatalog streams = new StreamCatalog();
        streams.register(StreamSchema.builder("txn")
                .field("region", Types.string())
                .field("amount", Types.int64())
                .field("ts", Types.timestamp())
                .eventTime("ts")
                .build());
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(dir.resolve("registry.journal").toString());
        PravahaNode built = PravahaNode.builder()
                .withCatalog(streams)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("catalog-e2e")
                .build();
        CatalogProperties catalog = new CatalogProperties();
        catalog.setEnabled(true);
        catalog.setAuthority(authority);
        built.setCatalog(catalog);
        return built;
    }

    private static SecurityProperties security(String policy, boolean allowAnonymous) {
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        security.setPolicy(policy);
        security.setAllowAnonymous(allowAnonymous);
        Map<String, SecurityProperties.TokenSpec> tokens = new LinkedHashMap<>();
        tokens.put("ana-token", spec("ana", "analyst"));
        tokens.put("ops-token", spec("ops", "admin"));
        security.setTokens(tokens);
        return security;
    }

    private static SecurityProperties.TokenSpec spec(String id, String role) {
        SecurityProperties.TokenSpec spec = new SecurityProperties.TokenSpec();
        spec.setId(id);
        spec.setTenant("acme");
        spec.setRoles(List.of(role));
        return spec;
    }

    private void openClient() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        flight = FlightClient.builder(
                        allocator,
                        Location.forGrpcInsecure("127.0.0.1", node.flightPort().orElseThrow()))
                .build();
        sql = new FlightSqlClient(flight);
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

    private static CallOption bearer(String who) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + who + "-token");
        return new HeaderCallOption(headers);
    }

    /** Runs one statement as {@code who} and answers its rows as text. */
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

    private FlightStream subscribe(String who, String view) {
        FlightStream stream = flight.getStream(new Ticket(ControlWire.subscribeTicket(view, List.of())), bearer(who));
        stream.getSchema();
        return stream;
    }

    private interface Action {
        Object run() throws Exception;
    }

    private static void assertRefused(Action action, String code) {
        assertThatThrownBy(action::run).hasMessageContaining(code);
    }
}
