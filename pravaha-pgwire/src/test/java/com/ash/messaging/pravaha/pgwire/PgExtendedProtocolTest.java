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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Parse}/{@code Bind}/{@code Describe}/{@code Execute}/{@code Close}/{@code Sync}, driven
 * with raw protocol bytes exactly as {@link PgWireSessionTest} drives the simple protocol -- {@link
 * PgTestClient} imports nothing from {@link PgBackend}, so a test passing here is not this module
 * agreeing with itself. {@code JdbcClientTest} and {@code PsqlSessionTest} are the stronger claim,
 * a real driver never told {@code preferQueryMode=simple}; this file is where the shapes a real
 * driver does not happen to exercise -- portal chunking, the error-recovery skip, a raw parameter
 * arity mismatch -- get their own direct coverage.
 */
class PgExtendedProtocolTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
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
        view.applyValues(new Object[] {"u2", "silver", 50L}, 1, 100);
        view.applyValues(new Object[] {"u3", null, 7L}, 1, 100);
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

    // ------------------------------------------------------------------ Describe's own two calls

    @Test
    void describeStatementAnswersParameterDescriptionThenRowDescription() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.parse("s1", "SELECT user_id, total FROM user_volume WHERE total > $1");
            client.describeStatement("s1");
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("1tTZ");
            // int8, the type inferred for `total`'s own column, which is what total > $1 must match.
            PgTestClient.Message parameterDescription =
                    PgTestClient.ofType(reply, 't').get(0);
            assertThat(parameterDescription.int16At(0)).isEqualTo((short) 1);
            assertThat(parameterDescription.int32At(2)).isEqualTo(20); // int8

            List<PgTestClient.Described> columns =
                    PgTestClient.described(PgTestClient.ofType(reply, 'T').get(0));
            assertThat(columns).extracting(PgTestClient.Described::name).containsExactly("user_id", "total");
        }
    }

    @Test
    void describingAStatementWithNoPlaceholdersAnswersAnEmptyParameterDescription() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.parse("s1", "SELECT user_id FROM user_volume");
            client.describeStatement("s1");
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.ofType(reply, 't').get(0).int16At(0)).isEqualTo((short) 0);
        }
    }

    // ------------------------------------------------------------------ portal chunking

    @Test
    void executeWithARowLimitSuspendsThenAFollowingExecuteFinishes() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.parse("", "SELECT user_id FROM user_volume");
            client.bindText("", "");
            client.execute("", 2); // three rows exist; ask for two
            client.sync();
            List<PgTestClient.Message> first = client.readUntilReady();

            assertThat(PgTestClient.shape(first)).isEqualTo("12DDsZ"); // 's' PortalSuspended, not CommandComplete
            assertThat(PgTestClient.ofType(first, 'D')).hasSize(2);

            client.execute("", 2); // ask for the rest of the same portal
            client.sync();
            List<PgTestClient.Message> second = client.readUntilReady();

            assertThat(PgTestClient.shape(second)).isEqualTo("DCZ");
            assertThat(PgTestClient.ofType(second, 'D')).hasSize(1);
            // The tag counts the whole answer, not just this last batch.
            assertThat(PgTestClient.ofType(second, 'C').get(0).strings()).containsExactly("SELECT 3");
        }
    }

    // ------------------------------------------------------------------ error recovery

    /**
     * The protocol's own recovery rule: after a failure inside an extended-query sequence, every
     * message is discarded until {@code Sync} -- not answered, not refused again, simply not acted
     * on -- and {@code Sync} still ends the sequence normally. A driver that does not get this
     * hangs rather than fails, which is worse than either.
     */
    @Test
    void anErrorMidSequenceDiscardsEverythingUntilSyncThenTheConnectionRecovers() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.describeStatement("never-parsed"); // fails: PGWIRE_UNKNOWN_STATEMENT
            client.bindText("p1", "never-parsed"); // discarded: still in error recovery
            client.execute("p1", 0); // discarded
            client.sync(); // ends the sequence

            List<PgTestClient.Message> reply = client.readUntilReady();
            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            Map<Character, String> fields =
                    PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0));
            assertThat(fields.get('M')).startsWith("PRV-6207");
            assertThat(fields).containsEntry('C', "26000");

            // The session survives, exactly like the simple-query equivalent case.
            client.parse("", "SELECT user_id FROM user_volume");
            client.bindText("", "");
            client.execute("", 0);
            client.sync();
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("12DDDCZ");
        }
    }

    // ------------------------------------------------------------------ parameters

    @Test
    void bindingTheWrongNumberOfParametersIsRefusedWithTheParameterArityCodeAlreadyDefined() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.parse("s1", "SELECT user_id FROM user_volume WHERE total > $1");
            client.bindText("p1", "s1"); // zero values bound; one placeholder needs one
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            Map<Character, String> fields =
                    PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0));
            // PRV-2061 SQL_PARAMETER_ARITY -- the code ADR-032's own parameter binding already
            // defines, reused rather than a new pgwire-specific one invented beside it.
            assertThat(fields.get('M')).startsWith("PRV-2061");
        }
    }

    @Test
    void aPlaceholderOutsideAWhereClauseIsRefusedWithParameterNotAValue() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            // $1 added to `total` in the SELECT list: the arithmetic gives Calcite's validator a
            // type to infer for the placeholder (unlike a bare `$1` in the select list, which the
            // validator itself refuses as "Illegal use of dynamic parameter" before ParameterMetadata
            // is ever consulted, and unlike GROUP BY $1, refused the same early way). This is the
            // shape that actually reaches ADR-032's own rule: a placeholder used to compute a
            // *result* still decides what the query IS rather than which rows come back.
            client.parse("s1", "SELECT total + $1 FROM user_volume");
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            Map<Character, String> fields =
                    PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0));
            assertThat(fields.get('M')).startsWith("PRV-2063");
        }
    }

    // ------------------------------------------------------------------ Close

    @Test
    void closingAStatementMakesItUnknownAndClosingAnythingElseIsNeverRefused() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.parse("s1", "SELECT user_id FROM user_volume");
            client.closeStatement("s1");
            client.closeStatement("never-existed"); // never refused, per protocol
            client.closePortal("never-existed"); // never refused, per protocol
            client.describeStatement("s1"); // now unknown
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            // '1' ParseComplete first, from the Parse above -- nothing was Synced in between.
            assertThat(PgTestClient.shape(reply)).isEqualTo("1333EZ");
            assertThat(PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0))
                            .get('M'))
                    .startsWith("PRV-6207");
        }
    }

    // ------------------------------------------------------------------ SX-5, through the extended protocol

    /**
     * The same guarantee {@code PgCatalogShimTest} and {@code JdbcClientTest} prove for the simple
     * protocol and {@code getTables()}: authorization is {@code ViewQuery.prepare}'s own, run at
     * {@code Parse}, so a denied principal is refused before a statement even exists to {@code
     * Bind} against -- not a second, weaker check bolted onto the extended path.
     */
    @Test
    void aPrincipalDeniedAViewIsRefusedAtParseNotLaterAndSilently() throws Exception {
        server = new PravahaPgWireServer(populated())
                .authorizedBy(
                        (principal, view) -> AccessDecision.deny("no principal may read anything in this test"),
                        AuditSink.NONE)
                .start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.parse("s1", "SELECT user_id FROM user_volume");
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            assertThat(PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0))
                            .get('M'))
                    .contains("may not read");
        }
    }

    // ------------------------------------------------------------------ pg_catalog, through the extended protocol

    /**
     * The catalog shim answers through {@code Parse}/{@code Bind}/{@code Execute} too, not only the
     * simple protocol -- a driver's {@code DatabaseMetaData} calls run as prepared statements
     * exactly like any other query once {@code preferQueryMode=simple} is not forced.
     */
    @Test
    void aCatalogQueryWorksThroughParseAndBindWithNoPlaceholders() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        try (PgTestClient client = connected()) {
            client.parse(
                    "s1",
                    "SELECT n.nspname as \"Schema\", c.relname as \"Name\", "
                            + "CASE c.relkind WHEN 'r' THEN 'table' END as \"Type\", "
                            + "pg_catalog.pg_get_userbyid(c.relowner) as \"Owner\" "
                            + "FROM pg_catalog.pg_class c LEFT JOIN pg_catalog.pg_namespace n "
                            + "ON n.oid = c.relnamespace WHERE c.relkind IN ('r','p','v','m','S','f','') "
                            + "AND pg_catalog.pg_table_is_visible(c.oid) ORDER BY 1,2");
            client.bindText("", "s1");
            client.execute("", 0);
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("12DCZ");
            assertThat(PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0))
                            .get(1))
                    .isEqualTo("user_volume");
        }
    }
}
