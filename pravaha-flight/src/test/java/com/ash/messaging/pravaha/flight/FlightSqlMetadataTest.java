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

import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.flight.sql.util.TableRef;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
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

/**
 * The calls a SQL client makes before it will show you a table (P-6).
 *
 * <p>Every one of these used to succeed at {@code getFlightInfo} and fail at {@code getStream} with
 * {@code UNIMPLEMENTED "Not implemented."}, so a driver that enumerates tables on connect could not
 * get as far as the SQL that did work. This drives them with {@link FlightSqlClient} -- the client
 * the Flight SQL JDBC driver and the ADBC, Python and Go clients are all built on -- and asserts
 * what comes back, so the evidence is a real round trip rather than a call to the producer.
 *
 * <p><strong>What this does not prove.</strong> It is not DBeaver. The Flight SQL JDBC driver is not
 * on this repository's classpath, so the last mile -- a driver turning these answers into a
 * {@code DatabaseMetaData} a tool is happy with -- is not exercised here. What is proven is that
 * every metadata call in the protocol now answers with a well-formed result over the wire instead of
 * refusing: necessary, and not by itself sufficient.
 */
@Timeout(60)
class FlightSqlMetadataTest {

    private static final StreamSchema USER_VOLUME = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    /** Two key columns, so "key_sequence" has something to be wrong about. */
    private static final StreamSchema POSITIONS = StreamSchema.builder("positions")
            .field("book", Types.string())
            .field("symbol", Types.string())
            .field("qty", Types.int64())
            .build();

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightSqlClient client;

    @BeforeEach
    void startServer() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ServedView volume = new ServedView("user_volume", USER_VOLUME, List.of(0), 10_000);
        volume.applyValues(new Object[] {"u1", "gold", 300L}, 1, 10);
        volume.commit(10);
        ServedView positions = new ServedView("positions", POSITIONS, List.of(0, 1), 10_000);
        positions.commit(10);

        server = new PravahaFlightServer(new ViewCatalog().register(volume).register(positions), allocator)
                .start("localhost", 0);
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

    // ------------------------------------------------------------------------------------------
    // The sequence a driver actually runs
    // ------------------------------------------------------------------------------------------

    @Test
    void everyMetadataCallAnswersInsteadOfRefusing() {
        // The whole finding in one assertion: a client that walks the metadata surface used to get
        // "Not implemented." from every one of these. Listed by name so a regression says which.
        assertThat(rows(client.getCatalogs())).isNotNull();
        assertThat(rows(client.getSchemas(null, null))).isNotNull();
        assertThat(rows(client.getTableTypes())).isNotNull();
        assertThat(rows(client.getTables(null, null, null, null, false))).isNotNull();
        assertThat(rows(client.getPrimaryKeys(TableRef.of(null, null, "user_volume"))))
                .isNotNull();
        assertThat(rows(client.getExportedKeys(TableRef.of(null, null, "user_volume"))))
                .isNotNull();
        assertThat(rows(client.getImportedKeys(TableRef.of(null, null, "user_volume"))))
                .isNotNull();
        assertThat(rows(client.getXdbcTypeInfo())).isNotNull();
        assertThat(rows(client.getSqlInfo())).isNotNull();
    }

    // ------------------------------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------------------------------

    @Test
    void theTableListIsTheViewsThisServerServes() {
        List<List<String>> tables = rows(client.getTables(null, null, null, null, false));

        assertThat(tables.stream().map(row -> row.get(2)).toList()).containsExactly("positions", "user_volume");
        assertThat(tables.stream().map(row -> row.get(3)).toList()).containsOnly("TABLE");
        // No catalogue and no schema, stated as absent rather than invented: the planner resolves
        // bare names, so a qualified name a client built from a made-up catalogue would not resolve.
        assertThat(tables.stream().map(row -> row.get(0)).toList()).containsOnly((String) null);
        assertThat(tables.stream().map(row -> row.get(1)).toList()).containsOnly((String) null);
    }

    @Test
    void aTableNameFilterIsATrueLikePatternRatherThanARegex() {
        assertThat(names(client.getTables(null, null, "user%", null, false))).containsExactly("user_volume");
        assertThat(names(client.getTables(null, null, "%s", null, false))).containsExactly("positions");
        // '_' is LIKE's single-character wildcard, not a literal, and neither is a regex
        // metacharacter: a pattern with a bracket must return nothing, not throw out of the call.
        assertThat(names(client.getTables(null, null, "user_volume", null, false)))
                .containsExactly("user_volume");
        assertThat(names(client.getTables(null, null, "no[t", null, false))).isEmpty();
    }

    @Test
    void aTableTypeFilterIsHonoured() {
        assertThat(names(client.getTables(null, null, null, List.of("TABLE"), false)))
                .containsExactly("positions", "user_volume");
        assertThat(names(client.getTables(null, null, null, List.of("SYSTEM TABLE"), false)))
                .isEmpty();
    }

    @Test
    void aCatalogueFilterMatchesNothingBecauseThereAreNoCatalogues() {
        // Answered rather than ignored. A client that scoped a listing and got everything back would
        // believe it had scoped it.
        assertThat(names(client.getTables("anything", null, null, null, false))).isEmpty();
        assertThat(rows(client.getCatalogs())).isEmpty();
        assertThat(rows(client.getSchemas(null, null))).isEmpty();
    }

    @Test
    void includeSchemaCarriesTheRealArrowSchema() throws Exception {
        List<List<String>> tables = rows(client.getTables(null, null, "user_volume", null, true));
        assertThat(tables).hasSize(1);

        byte[] serialised = tableSchemaBytes(client.getTables(null, null, "user_volume", null, true));
        Schema schema = MessageSerializer.deserializeSchema(new org.apache.arrow.vector.ipc.ReadChannel(
                java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(serialised))));

        assertThat(schema.getFields().stream().map(f -> f.getName()).toList())
                .containsExactly("user_id", "tier", "total");
    }

    // ------------------------------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------------------------------

    @Test
    void aViewsKeyColumnsAreItsPrimaryKey() {
        List<List<String>> keys = rows(client.getPrimaryKeys(TableRef.of(null, null, "positions")));

        assertThat(keys.stream().map(row -> row.get(3)).toList()).containsExactly("book", "symbol");
        // One-based and in key order, which is what every other SQL surface means by a key position.
        assertThat(keys.stream().map(row -> row.get(4)).toList()).containsExactly("1", "2");
        assertThat(keys.stream().map(row -> row.get(2)).toList()).containsOnly("positions");
    }

    @Test
    void anUnknownTableHasNoPrimaryKeyRatherThanAnError() {
        assertThat(rows(client.getPrimaryKeys(TableRef.of(null, null, "nowhere"))))
                .isEmpty();
    }

    @Test
    void thereAreNoForeignKeysAndThatIsAnAnswer() {
        // Empty, not UNIMPLEMENTED. A driver that populates its object tree eagerly must not fail
        // against a database that is merely simple.
        assertThat(rows(client.getExportedKeys(TableRef.of(null, null, "user_volume"))))
                .isEmpty();
        assertThat(rows(client.getImportedKeys(TableRef.of(null, null, "user_volume"))))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Types and server info
    // ------------------------------------------------------------------------------------------

    @Test
    void theTypeListIsExactlyWhatTheWireCanCarry() {
        List<String> types =
                rows(client.getXdbcTypeInfo()).stream().map(row -> row.get(0)).toList();

        assertThat(types).contains("BIGINT", "VARCHAR", "DOUBLE", "TIMESTAMP", "TIME", "DATE", "BOOLEAN");
        // DECIMAL is refused by ArrowSchemas rather than rounded onto a float. Advertising it here
        // would be the same defect as a documented-but-unreachable feature one layer down.
        assertThat(types).doesNotContain("DECIMAL", "NUMERIC");
    }

    @Test
    void theServerNamesItselfToAClientThatAsks() {
        List<List<String>> infos = rows(client.getSqlInfo(new int[] {FlightSql.SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE}));

        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).get(1)).isEqualTo("Pravaha");
    }

    @Test
    void anInfoCodeThisServerDoesNotPublishDoesNotFailTheCall() {
        // SqlInfoBuilder.send dereferences a provider it does not have, so one unrecognised code
        // from a client would take the whole call with it -- and this is the first call a JDBC or
        // ADBC connection makes, so it would take the connection too.
        assertThat(rows(client.getSqlInfo(new int[] {FlightSql.SqlInfo.SQL_MAX_ROW_SIZE_VALUE})))
                .isEmpty();
        assertThat(rows(client.getSqlInfo(new int[] {
                    FlightSql.SqlInfo.SQL_MAX_ROW_SIZE_VALUE, FlightSql.SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE
                })))
                .hasSize(1);
    }

    // ------------------------------------------------------------------------------------------
    // Schemas without rows
    // ------------------------------------------------------------------------------------------

    @Test
    void theResultSchemaOfAQueryIsAvailableWithoutRunningIt() {
        // getFlightInfo already answered this and getSchema refused it -- the same question over the
        // cheaper of the two calls. A driver building ResultSetMetaData takes this path.
        SchemaResult result = client.getExecuteSchema("SELECT user_id, total FROM user_volume");

        assertThat(result.getSchema().getFields().stream().map(f -> f.getName()).toList())
                .containsExactly("user_id", "total");
    }

    @Test
    void nullabilityOnTheWireIsTheColumnsOwn() {
        // REST reported nullable:false for user_id while Flight reported every column nullable --
        // one column, two surfaces, two answers. The Arrow one was the wrong one.
        Schema schema =
                client.getExecuteSchema("SELECT user_id, tier FROM user_volume").getSchema();

        assertThat(schema.findField("user_id").isNullable())
                .as("user_id is declared NOT NULL and must not arrive nullable")
                .isFalse();
        assertThat(schema.findField("tier").isNullable())
                .as("tier is declared nullable and must stay nullable")
                .isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------------------------------

    @Test
    void aTableListDoesNotTellAPrincipalWhatTheyMayNotRead() throws Exception {
        // The same rule as the control-plane LIST (S-4, SX-11). A table list is how a client finds
        // what it may use; it must not also be how a principal learns that payroll exists.
        StreamSchema payroll = StreamSchema.builder("payroll")
                .field("employee", Types.string())
                .field("salary", Types.int64())
                .build();
        ServedView open = new ServedView("user_volume", USER_VOLUME, List.of(0), 100);
        ServedView closed = new ServedView("payroll", payroll, List.of(0), 100);
        SecurityPolicy policy = (principal, view) ->
                view.equals("payroll") ? AccessDecision.deny("not for analysts") : AccessDecision.allow();

        try (BufferAllocator own = new RootAllocator(Long.MAX_VALUE)) {
            PravahaFlightServer restricted = new PravahaFlightServer(
                            new ViewCatalog().register(open).register(closed), own)
                    .authenticatedBy(StaticTokenVerifier.of(
                            "analyst-token", new Principal("dana", "acme", Set.of("analyst"), Map.of())))
                    .authorizedBy(policy, AuditSink.NONE)
                    .start("localhost", 0);
            FlightCallHeaders headers = new FlightCallHeaders();
            headers.insert("authorization", "Bearer analyst-token");
            HeaderCallOption bearing = new HeaderCallOption(headers);
            try (FlightSqlClient restrictedClient = new FlightSqlClient(org.apache.arrow.flight.FlightClient.builder(
                            own, Location.forGrpcInsecure("localhost", restricted.port()))
                    .build())) {
                List<String> visible = rows(
                                restrictedClient,
                                restrictedClient.getTables(null, null, null, null, false, bearing),
                                bearing)
                        .stream()
                        .map(row -> row.get(2))
                        .toList();

                assertThat(visible).containsExactly("user_volume");
                assertThat(rows(
                                restrictedClient,
                                restrictedClient.getPrimaryKeys(TableRef.of(null, null, "payroll"), bearing),
                                bearing))
                        .as("nor may the key columns of a refused view be read off the keys call")
                        .isEmpty();
            } finally {
                restricted.close();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reading a metadata stream the way a client does
    // ------------------------------------------------------------------------------------------

    private List<List<String>> rows(FlightInfo info) {
        return rows(client, info);
    }

    private static List<List<String>> rows(
            FlightSqlClient from, FlightInfo info, org.apache.arrow.flight.CallOption... options) {
        List<List<String>> out = new ArrayList<>();
        // The ticket is fetched with the same credential the descriptor was: getStream is a separate
        // call and the server authenticates it separately, which is the whole shape of this finding.
        try (FlightStream stream = from.getStream(info.getEndpoints().get(0).getTicket(), options)) {
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
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
        return out;
    }

    private List<String> names(FlightInfo info) {
        return rows(info).stream().map(row -> row.get(2)).toList();
    }

    private byte[] tableSchemaBytes(FlightInfo info) {
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                if (root.getRowCount() > 0) {
                    return ((org.apache.arrow.vector.VarBinaryVector) root.getVector("table_schema")).get(0);
                }
            }
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
        throw new IllegalStateException("no table row carried a schema");
    }
}
