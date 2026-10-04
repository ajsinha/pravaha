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
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PgCatalogShim} and {@link PgSessionSet}, driven with raw protocol bytes the way {@code
 * PgWireSessionTest} drives everything else in this module -- {@link PgTestClient} knows nothing
 * about the encoder, so a test passing here is not the shim agreeing with itself.
 *
 * <p>{@code PsqlSessionTest} and {@code JdbcClientTest} are the stronger claim, a real client over a
 * real socket; this file is where the SX-5 filtering and the {@code SET} allow-list get exercised at
 * the level of individual protocol messages, including the two refusals ({@code
 * PGWIRE_UNSUPPORTED_SET}, {@code PGWIRE_UNSUPPORTED_CATALOG_QUERY}) a real client is not expected to
 * trigger on a normal run and so would not otherwise be covered.
 */
class PgCatalogShimTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final StreamSchema PAYROLL_SCHEMA =
            StreamSchema.builder("payroll").field("employee_id", Types.string()).build();

    @SuppressWarnings("NullAway.Init") /* a test sets it before reading it */
    private PravahaPgWireServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static ViewCatalog populated() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", "gold", 300L}, 1, 100);
        view.commit(100);
        return new ViewCatalog().register(view);
    }

    private PgTestClient connected() throws IOException {
        PgTestClient client = new PgTestClient(server.port());
        client.startup(Map.of("user", "anyone", "database", "pravaha"));
        List<PgTestClient.Message> handshake = client.readHandshake();
        assertThat(PgTestClient.shape(handshake)).endsWith("Z");
        return client;
    }

    // ------------------------------------------------------------------ SX-5: catalog visibility

    /**
     * The finding this whole shim exists to not repeat: a principal authorized for nothing must not
     * be able to enumerate the catalogue by asking {@code pg_catalog.pg_class} instead of running
     * {@code SELECT}. Two views, one policy that denies exactly one of them by name -- the shape
     * SX-5 and SX-11 are both about -- and the denied one must be entirely absent, not marked
     * forbidden.
     */
    @Test
    void aPrincipalDeniedAViewDoesNotSeeItInTheCatalogListing() throws Exception {
        ViewCatalog catalog = populated();
        ServedView payroll = new ServedView("payroll", PAYROLL_SCHEMA, List.of(0), 10_000);
        payroll.applyValues(new Object[] {"e1"}, 1, 100);
        payroll.commit(100);
        catalog.register(payroll);

        com.ash.messaging.pravaha.security.SecurityPolicy denyPayroll = (principal, view) -> "payroll".equals(view)
                ? AccessDecision.deny("payroll is not for this test's principal")
                : AccessDecision.allow();
        server = new PravahaPgWireServer(catalog)
                .authorizedBy(denyPayroll, AuditSink.NONE)
                .start("127.0.0.1", 0);

        try (PgTestClient client = connected()) {
            // The literal query psql sends for `\d` at this server's announced version.
            client.query("SELECT n.nspname as \"Schema\", c.relname as \"Name\", "
                    + "CASE c.relkind WHEN 'r' THEN 'table' END as \"Type\", "
                    + "pg_catalog.pg_get_userbyid(c.relowner) as \"Owner\" "
                    + "FROM pg_catalog.pg_class c LEFT JOIN pg_catalog.pg_namespace n "
                    + "ON n.oid = c.relnamespace WHERE c.relkind IN ('r','p','v','m','S','f','') "
                    + "AND pg_catalog.pg_table_is_visible(c.oid) ORDER BY 1,2");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("TDCZ");
            List<String> names = PgTestClient.ofType(reply, 'D').stream()
                    .map(row -> PgTestClient.columns(row).get(1))
                    .toList();
            assertThat(names).containsExactly("user_volume");
            assertThat(names).doesNotContain("payroll");
        }
    }

    /**
     * The same rule for {@code \d <table>}: an oid legitimately minted for one principal must not
     * describe a view to another who may not read it. Oids are server-global -- {@link
     * PgOidRegistry} mints one per view name, not per connection -- so this drives a real one: an
     * admin principal resolves {@code payroll} to its actual oid first, exactly as {@code \d
     * payroll} would, and a restricted principal is then handed that same real number rather than a
     * guess.
     */
    @Test
    void aPrincipalDeniedAViewCannotDescribeItWithAnOidMintedForSomeoneElse() throws Exception {
        ViewCatalog catalog = populated();
        ServedView payroll = new ServedView("payroll", PAYROLL_SCHEMA, List.of(0), 10_000);
        payroll.applyValues(new Object[] {"e1"}, 1, 100);
        payroll.commit(100);
        catalog.register(payroll);

        Principal admin = new Principal("root", "public", Set.of("admin"), Map.of());
        Principal analyst = new Principal("dana", "public", Set.of("analyst"), Map.of());
        com.ash.messaging.pravaha.security.SecurityPolicy payrollForAdminsOnly =
                (principal, view) -> !"payroll".equals(view) || "root".equals(principal.id())
                        ? AccessDecision.allow()
                        : AccessDecision.deny("payroll is admin-only in this test");
        server = new PravahaPgWireServer(catalog)
                .authenticatedBy(com.ash.messaging.pravaha.security.StaticTokenVerifier.of("root-token", admin)
                        .and("dana-token", analyst))
                .authorizedBy(payrollForAdminsOnly, AuditSink.NONE)
                .start("127.0.0.1", 0);

        int payrollOid;
        try (PgTestClient adminClient = authenticated("root-token")) {
            adminClient.query("SELECT c.oid, n.nspname, c.relname FROM pg_catalog.pg_class c LEFT JOIN "
                    + "pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE c.relname "
                    + "OPERATOR(pg_catalog.~) '^(payroll)$' AND pg_catalog.pg_table_is_visible(c.oid) "
                    + "ORDER BY 2, 3");
            List<PgTestClient.Message> reply = adminClient.readUntilReady();
            List<String> row =
                    PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0));
            assertThat(row.get(2)).isEqualTo("payroll");
            payrollOid = Integer.parseInt(row.get(0));
        }

        try (PgTestClient analystClient = authenticated("dana-token")) {
            analystClient.query("SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), "
                    + "(SELECT pg_catalog.pg_get_expr(d.adbin, d.adrelid, true) FROM pg_catalog.pg_attrdef d "
                    + "WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum AND a.atthasdef), a.attnotnull, "
                    + "(SELECT c.collname FROM pg_catalog.pg_collation c, pg_catalog.pg_type t "
                    + "WHERE c.oid = a.attcollation AND t.oid = a.atttypid AND a.attcollation <> t.typcollation) "
                    + "AS attcollation, ''::pg_catalog.char AS attidentity, ''::pg_catalog.char AS attgenerated "
                    + "FROM pg_catalog.pg_attribute a WHERE a.attrelid = '" + payrollOid
                    + "' AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum");
            List<PgTestClient.Message> reply = analystClient.readUntilReady();

            // Not found and not authorized are the same answer: zero rows, not a forbidden marker
            // and not employee_id.
            assertThat(PgTestClient.ofType(reply, 'D')).isEmpty();
        }
    }

    private PgTestClient authenticated(String password) throws IOException {
        PgTestClient client = new PgTestClient(server.port());
        client.startup(Map.of("user", "anyone", "database", "pravaha"));
        assertThat(java.util.Objects.requireNonNull(client.read()).type())
                .isEqualTo('R'); // AuthenticationCleartextPassword
        client.password(password);
        List<PgTestClient.Message> handshake = client.readUntilReady();
        assertThat(PgTestClient.shape(handshake)).endsWith("Z");
        return client;
    }

    // ------------------------------------------------------------------ SET

    @Test
    void setExtraFloatDigitsIsAcceptedBecauseThisServerAlreadyBehavesAsIfItWere() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.query("SET extra_float_digits = 3");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("CZ");
            assertThat(PgTestClient.ofType(reply, 'C').get(0).strings()).containsExactly("SET");
        }
    }

    @Test
    void setApplicationNameIsAcceptedBecauseNothingHereEverReadsIt() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.query("SET application_name = 'DBeaver'");

            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("CZ");
        }
    }

    @Test
    void setClientEncodingToUtf8IsAcceptedAndToAnythingElseIsRefused() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.query("SET client_encoding = 'UTF8'");
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("CZ");

            // LATIN1 is a real behaviour change this server does not implement: every string this
            // gateway writes is UTF-8, unconditionally. Accepting the request and doing nothing
            // would tell a client its bytes will be decoded as LATIN1 when they will not be.
            client.query("SET client_encoding = 'LATIN1'");
            List<PgTestClient.Message> reply = client.readUntilReady();
            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            assertThat(PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0))
                            .get('M'))
                    .contains("PRV-6204");
        }
    }

    @Test
    void setOfAnUnlistedParameterIsRefusedRatherThanSilentlyAccepted() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            // statement_timeout: accepting and ignoring this would tell a client its queries are
            // now bounded by a per-statement deadline that this SET, specifically, does not set.
            client.query("SET statement_timeout = 5000");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            Map<Character, String> fields =
                    PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0));
            assertThat(fields.get('M')).startsWith("PRV-6204");
            assertThat(fields).containsEntry('C', "0A000");

            // The session survives a refused SET, same as any other refused statement.
            client.query("SELECT user_id FROM user_volume");
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDCZ");
        }
    }

    // ------------------------------------------------------------------ unrecognised catalog query

    @Test
    void aCatalogQueryOutsideTheRecognisedShapesIsRefusedByNameRatherThanApproximated() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            // Real PostgreSQL SQL, genuinely about pg_catalog, and not one of the shapes
            // PgCatalogShim was taught -- publications did not exist until PostgreSQL 10 and this
            // server never claims to be one.
            client.query("SELECT pubname FROM pg_catalog.pg_publication");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            Map<Character, String> fields =
                    PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0));
            assertThat(fields.get('M')).startsWith("PRV-6205");
            assertThat(fields).containsEntry('C', "0A000");
        }
    }

    // ------------------------------------------------------------------ bare scalar functions

    @Test
    void selectVersionAnswersWithThisServersAnnouncedVersion() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.query("SELECT version()");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("TDCZ");
            assertThat(PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0))
                            .get(0))
                    .contains("PostgreSQL")
                    .contains("Pravaha");
        }
    }

    @Test
    void selectCurrentSchemaAnswersPublic() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.query("SELECT current_schema()");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0)))
                    .containsExactly("public");
        }
    }

    @Test
    void aSearchPathWithAnEmptyLastEntryIsNotTakenForPublic() {
        // SPLITTRAIL-4: String.split dropped the trailing empty entry, so "public," passed as "public"
        // although PostgreSQL itself refuses it; an empty entry anywhere else was already refused.
        assertThatCode(() -> PgSessionSet.handle("SET search_path = public")).doesNotThrowAnyException();
        for (String value : List.of("public,", ", public")) {
            assertThatThrownBy(() -> PgSessionSet.handle("SET search_path = " + value))
                    .as(value)
                    .isInstanceOf(PravahaException.class)
                    .extracting(e -> ((PravahaException) e).errorCode())
                    .isEqualTo(PgWireErrors.UNSUPPORTED_SET);
        }
    }
}
