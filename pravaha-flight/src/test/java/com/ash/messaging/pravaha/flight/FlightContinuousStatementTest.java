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
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The continuous-query statements over Flight SQL: the path every SDK's {@code query()}, the CLI
 * and any Flight SQL client take. Each statement is also compared with the action it spells, for the
 * same principal, because "refused exactly as through the actions" is a claim about both halves.
 */
@Timeout(60)
class FlightContinuousStatementTest {

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .build();

    private static final String ADMIN = "admin-token";
    private static final String GUEST = "guest-token";
    private static final String NOBODY = "nobody-token";

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient flight;
    private FlightSqlClient sql;
    private QueryRegistry registry;
    private AuditSink.InMemory audit;

    @BeforeEach
    void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        audit = new AuditSink.InMemory();
        SecurityPolicy policy = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                // guest may not learn that the admins' private view exists.
                return principal.id().equals("guest") && view.equals("admin_view")
                        ? AccessDecision.deny("not for guests")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return principal.id().equals("nobody")
                        ? AccessDecision.deny("nobody registers")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayAdminister(Principal principal, String view) {
                return principal.id().equals("admin")
                        ? AccessDecision.allow()
                        : AccessDecision.deny(principal.id() + " administers nothing");
            }
        };
        StaticTokenVerifier verifier = StaticTokenVerifier.of(ADMIN, new Principal("admin", "acme", Set.of(), Map.of()))
                .and(GUEST, new Principal("guest", "acme", Set.of(), Map.of()))
                .and(NOBODY, new Principal("nobody", "acme", Set.of(), Map.of()));
        registry = new QueryRegistry(views, policy, audit, TRADE);
        server = new PravahaFlightServer(views, allocator)
                .hosting(registry)
                .authenticatedBy(verifier)
                .authorizedBy(policy, audit)
                .start("localhost", 0);
        flight = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
        sql = new FlightSqlClient(flight);
    }

    @AfterEach
    void stop() throws Exception {
        if (sql != null) {
            sql.close();
        }
        if (server != null) {
            server.close();
        }
        registry.close();
        allocator.close();
    }

    private static CallOption bearing(String token) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + token);
        return new HeaderCallOption(headers);
    }

    /** Executes and fetches, as an SDK's {@code query()} does. */
    private List<List<String>> query(String text, String token) {
        FlightInfo info = sql.execute(text, bearing(token));
        List<List<String>> rows = new ArrayList<>();
        try (FlightStream stream = sql.getStream(info.getEndpoints().get(0).getTicket(), bearing(token))) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                for (int row = 0; row < root.getRowCount(); row++) {
                    List<String> values = new ArrayList<>();
                    for (int column = 0; column < root.getFieldVectors().size(); column++) {
                        Object value = root.getVector(column).getObject(row);
                        values.add(value == null ? null : value.toString());
                    }
                    rows.add(values);
                }
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private List<List<String>> act(String token, String type, String... fields) {
        List<List<String>> results = new ArrayList<>();
        flight.doAction(new Action(type, ControlWire.encode(fields)), bearing(token))
                .forEachRemaining(result -> results.add(ControlWire.decode(result.getBody())));
        return results;
    }

    private static FlightRuntimeException refused(Runnable call) {
        try {
            call.run();
        } catch (FlightRuntimeException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void createRegistersAndAnswersWithTheRegistration() {
        List<List<String>> rows = query(
                "CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT product_type, trade_id FROM trade",
                ADMIN);

        assertThat(rows)
                .singleElement()
                .isEqualTo(java.util.Arrays.asList(
                        "trade_feed",
                        "RUNNING",
                        registry.require("trade_feed").fingerprint().shortForm(),
                        null));
        assertThat(registry.require("trade_feed").view().keyOrdinals()).containsExactly(1);
    }

    @Test
    void theSchemaIsKnownBeforeTheStatementRunsAndAskingForItRegistersNothing() {
        FlightInfo info = sql.execute(
                "CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", bearing(ADMIN));

        assertThat(info.getSchemaOptional().orElseThrow().getFields())
                .extracting(org.apache.arrow.vector.types.pojo.Field::getName)
                .containsExactly("name", "state", "fingerprint", "sink");
        assertThat(sql.getExecuteSchema("SHOW CONTINUOUS QUERIES", bearing(ADMIN))
                        .getSchema()
                        .getFields())
                .hasSize(8);
        assertThat(registry.names())
                .as("the fetch runs it, not the planning call")
                .isEmpty();
    }

    @Test
    void pauseResumeDropAndShowOverSql() {
        query("CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", ADMIN);

        assertThat(query("PAUSE CONTINUOUS QUERY trade_feed", ADMIN)).containsExactly(List.of("trade_feed", "PAUSED"));
        assertThat(registry.require("trade_feed").state()).isEqualTo(QueryState.PAUSED);
        assertThat(query("RESUME CONTINUOUS QUERY trade_feed", ADMIN))
                .containsExactly(List.of("trade_feed", "RUNNING"));
        assertThat(query("SHOW CONTINUOUS QUERIES", ADMIN))
                .singleElement()
                .satisfies(row -> assertThat(row.subList(0, 3))
                        .containsExactly("trade_feed", "RUNNING", "SELECT trade_id FROM trade"));
        assertThat(query("DROP CONTINUOUS QUERY trade_feed;", ADMIN)).containsExactly(List.of("trade_feed", "DROPPED"));
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void showListsExactlyWhatTheListActionListsForTheSamePrincipal() {
        query("CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", ADMIN);
        query("CREATE CONTINUOUS QUERY admin_view KEYED BY (product_type) AS SELECT product_type FROM trade", ADMIN);

        for (String token : List.of(ADMIN, GUEST)) {
            List<String> bySql = query("SHOW CONTINUOUS QUERIES", token).stream()
                    .map(row -> row.get(0))
                    .toList();
            List<String> byAction =
                    act(token, ControlWire.LIST).stream().map(row -> row.get(0)).toList();
            assertThat(bySql).as(token).isEqualTo(byAction);
        }
        assertThat(query("SHOW CONTINUOUS QUERIES", GUEST))
                .extracting(row -> row.get(0))
                .containsExactly("trade_feed");
    }

    @Test
    void aPrincipalWhoMayNotRegisterIsRefusedAsTheRegisterActionRefusesThem() {
        FlightRuntimeException byAction =
                refused(() -> act(NOBODY, ControlWire.REGISTER, "trade_feed", "SELECT trade_id FROM trade", "0"));
        FlightRuntimeException bySql = refused(() ->
                query("CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", NOBODY));

        assertThat(bySql.status().code()).isEqualTo(byAction.status().code()).isEqualTo(FlightStatusCode.UNAUTHORIZED);
        assertThat(bySql.getMessage()).isEqualTo(byAction.getMessage()).contains("PRV-7002");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aPrincipalWhoMayNotAdministerIsRefusedAsTheActionsRefuseThemAndAuditedTheSame() {
        query("CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", ADMIN);

        for (String verb : List.of("drop", "pause", "resume")) {
            String type = "pravaha." + verb;
            FlightRuntimeException byAction = refused(() -> act(GUEST, type, "trade_feed"));
            FlightRuntimeException bySql = refused(
                    () -> query(verb.toUpperCase(java.util.Locale.ROOT) + " CONTINUOUS QUERY trade_feed", GUEST));

            assertThat(bySql.status().code()).isEqualTo(byAction.status().code());
            assertThat(bySql.getMessage()).isEqualTo(byAction.getMessage()).contains("may not " + verb);
        }
        assertThat(registry.require("trade_feed").state()).isEqualTo(QueryState.RUNNING);
        List<AuditEvent> refusals = audit.forPrincipal("guest").stream()
                .filter(event -> !event.allowed())
                .toList();
        assertThat(refusals)
                .as("each verb recorded twice, once per spelling, under the same action")
                .extracting(AuditEvent::action)
                .containsExactly("drop", "drop", "pause", "pause", "resume", "resume");
    }

    @Test
    void executeUpdateRunsTheStatementAndCountsItsRows() {
        long count = sql.executeUpdate(
                "CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", bearing(ADMIN));

        assertThat(count).isEqualTo(1);
        assertThat(registry.names()).containsExactly("trade_feed");
        assertThat(sql.executeUpdate("DROP CONTINUOUS QUERY trade_feed", bearing(ADMIN)))
                .isEqualTo(1);
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aPreparedStatementRunsItToo() throws Exception {
        // Every prepared statement a JDBC driver runs takes this path, a DDL statement included.
        FlightSqlClient.PreparedStatement prepared = sql.prepare(
                "CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", bearing(ADMIN));
        try {
            assertThat(prepared.getParameterSchema().getFields()).isEmpty();
            assertThat(prepared.getResultSetSchema().getFields()).hasSize(4);
            FlightInfo info = prepared.execute(bearing(ADMIN));
            try (FlightStream stream = sql.getStream(info.getEndpoints().get(0).getTicket(), bearing(ADMIN))) {
                assertThat(stream.next()).isTrue();
                assertThat(stream.getRoot().getRowCount()).isEqualTo(1);
            }
        } finally {
            prepared.close(bearing(ADMIN));
        }
        assertThat(registry.names()).containsExactly("trade_feed");
    }

    @Test
    void parametersBoundToAContinuousStatementAreRefusedWithAdviceThatExists() throws Exception {
        // HLP-14(e). The refusal told a client to register a parameterised query "through the
        // register action, which binds them". The action has no parameters field; only an embedded
        // QueryRegistry.register(..., BoundParameters) binds any.
        FlightSqlClient.PreparedStatement prepared = sql.prepare(
                "CREATE CONTINUOUS QUERY trade_feed KEYED BY (trade_id) AS SELECT trade_id FROM trade", bearing(ADMIN));
        try (org.apache.arrow.vector.IntVector value = new org.apache.arrow.vector.IntVector("p", allocator)) {
            value.allocateNew(1);
            value.set(0, 1);
            value.setValueCount(1);
            try (VectorSchemaRoot parameters = VectorSchemaRoot.of(value)) {
                parameters.setRowCount(1);
                prepared.setParameters(parameters);
                FlightRuntimeException refusal = refused(() -> prepared.execute(bearing(ADMIN)));

                assertThat(refusal.getMessage())
                        .contains("takes no parameters")
                        .contains("embedded")
                        .doesNotContain("through the register action, which binds them");
            }
        } finally {
            prepared.close(bearing(ADMIN));
        }
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aMalformedStatementIsRefusedWithItsShapeBeforeAnythingRuns() {
        FlightRuntimeException refusal = refused(
                () -> sql.execute("CREATE CONTINUOUS QUERY trade_feed AS SELECT trade_id FROM trade", bearing(ADMIN)));

        assertThat(refusal.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT);
        assertThat(refusal.getMessage())
                .contains("PRV-2070")
                .contains("KEYED BY")
                .doesNotContain("PRV-2001");
    }

    @Test
    void aKeyNamedWrongIsRefusedWithItsOwnCode() {
        FlightRuntimeException refusal = refused(() ->
                query("CREATE CONTINUOUS QUERY trade_feed KEYED BY (tradeid) AS SELECT trade_id FROM trade", ADMIN));

        assertThat(refusal.getMessage()).contains("PRV-2071").contains("'tradeid'");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aServerWithoutARegistryRefusesTheStatementByName() throws Exception {
        try (PravahaFlightServer bare = new PravahaFlightServer(new ViewCatalog(), allocator).start("localhost", 0);
                FlightSqlClient client = new FlightSqlClient(
                        FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", bare.port()))
                                .build())) {
            assertThatThrownBy(() -> client.execute("SHOW CONTINUOUS QUERIES"))
                    .isInstanceOf(FlightRuntimeException.class)
                    .hasMessageContaining("PRV-6101")
                    .hasMessageContaining("does not host a registry");
        }
    }
}
