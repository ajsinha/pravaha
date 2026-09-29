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
package com.ash.messaging.pravaha.pgwire;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 over pgwire: each tenant's {@code orders} is the one its queries and its catalogue shim
 * answer with, and another tenant's name -- or the oid minted for it -- answers as nothing does.
 */
class PgTenantNamesTest {

    private static final StreamSchema ORDERS =
            StreamSchema.builder("orders").field("order_id", Types.string()).build();

    private static final String RESOLVE = "SELECT c.oid, n.nspname, c.relname FROM pg_catalog.pg_class c LEFT JOIN "
            + "pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE c.relname OPERATOR(pg_catalog.~) '^(%s)$' "
            + "AND pg_catalog.pg_table_is_visible(c.oid) ORDER BY 2, 3";

    private static final String COLUMNS = "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), "
            + "(SELECT pg_catalog.pg_get_expr(d.adbin, d.adrelid, true) FROM pg_catalog.pg_attrdef d "
            + "WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum AND a.atthasdef), a.attnotnull, "
            + "(SELECT c.collname FROM pg_catalog.pg_collation c, pg_catalog.pg_type t "
            + "WHERE c.oid = a.attcollation AND t.oid = a.atttypid AND a.attcollation <> t.typcollation) "
            + "AS attcollation, ''::pg_catalog.char AS attidentity, ''::pg_catalog.char AS attgenerated "
            + "FROM pg_catalog.pg_attribute a WHERE a.attrelid = '%d' AND a.attnum > 0 AND NOT a.attisdropped "
            + "ORDER BY a.attnum";

    private PravahaPgWireServer server;

    @BeforeEach
    void start() {
        ViewCatalog catalog = new ViewCatalog()
                .registerAs("acme.default.orders", view("a1"))
                .registerAs("globex.default.orders", view("g1"))
                .registerAs("acme.default.payroll", view("p1"));
        server = new PravahaPgWireServer(catalog)
                .authenticatedBy(
                        StaticTokenVerifier.of("dana-token", new Principal("dana", "acme", Set.of("analyst"), Map.of()))
                                .and("omar-token", new Principal("omar", "globex", Set.of("analyst"), Map.of())))
                .authorizedBy(SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .start("127.0.0.1", 0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void eachTenantReadsAndResolvesItsOwnOrders() throws Exception {
        try (PgTestClient dana = authenticated("dana-token");
                PgTestClient omar = authenticated("omar-token")) {
            assertThat(rows(dana, "SELECT order_id FROM orders")).containsExactly(List.of("a1"));
            assertThat(rows(omar, "SELECT order_id FROM orders")).containsExactly(List.of("g1"));

            List<List<String>> danas = rows(dana, RESOLVE.formatted("orders"));
            List<List<String>> omars = rows(omar, RESOLVE.formatted("orders"));
            assertThat(danas)
                    .singleElement()
                    .satisfies(row -> assertThat(row.get(2)).isEqualTo("orders"));
            assertThat(omars)
                    .singleElement()
                    .satisfies(row -> assertThat(row.get(2)).isEqualTo("orders"));
            assertThat(danas.get(0).get(0))
                    .as("two relations, two oids")
                    .isNotEqualTo(omars.get(0).get(0));
            assertThat(rows(omar, RESOLVE.formatted("payroll"))).isEmpty();
        }
    }

    @Test
    void anotherTenantsNameAndItsOidAnswerAsNothingDoes() throws Exception {
        try (PgTestClient dana = authenticated("dana-token");
                PgTestClient omar = authenticated("omar-token")) {
            int payrollOid = Integer.parseInt(
                    rows(dana, RESOLVE.formatted("payroll")).get(0).get(0));
            assertThat(rows(omar, COLUMNS.formatted(payrollOid)))
                    .as("an oid minted for acme's payroll describes nothing to globex")
                    .isEmpty();

            String held = error(omar, "SELECT * FROM payroll");
            String nobody = error(omar, "SELECT * FROM nothing");
            assertThat(held.replace("payroll", "X")).isEqualTo(nobody.replace("nothing", "X"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ServedView view(String id) {
        ServedView view = new ServedView("orders", ORDERS, List.of(0), 100);
        view.applyValues(new Object[] {id}, 1, 100);
        view.commit(100);
        return view;
    }

    private static List<List<String>> rows(PgTestClient client, String sql) throws IOException {
        client.query(sql);
        return PgTestClient.ofType(client.readUntilReady(), 'D').stream()
                .map(PgTestClient::columns)
                .toList();
    }

    private static String error(PgTestClient client, String sql) throws IOException {
        client.query(sql);
        List<PgTestClient.Message> errors = PgTestClient.ofType(client.readUntilReady(), 'E');
        assertThat(errors).hasSize(1);
        Map<Character, String> fields = PgTestClient.errorFields(errors.get(0));
        return fields.get('C') + " " + fields.get('M');
    }

    private PgTestClient authenticated(String password) throws IOException {
        PgTestClient client = new PgTestClient(server.port());
        client.startup(Map.of("user", "anyone", "database", "pravaha"));
        assertThat(client.read().type()).isEqualTo('R');
        client.password(password);
        assertThat(PgTestClient.shape(client.readUntilReady())).endsWith("Z");
        return client;
    }
}
