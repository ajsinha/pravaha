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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
 * A PostgreSQL client and this server, over a real socket, exchanging real protocol bytes.
 *
 * <p><strong>Why a socket and not a unit test of the encoder.</strong> A test that runs this
 * module's encoder against this module's decoder proves that they agree, which is the one thing
 * never in doubt. What is in doubt is whether the bytes on the wire are the bytes PostgreSQL's
 * protocol specifies -- the length that counts itself but not the type byte, the {@code -1} that
 * means NULL rather than empty, the {@code 'N'} that declines encryption without a length prefix.
 * Those are asserted here against numbers written out from the protocol documentation, through
 * {@link PgTestClient}, which shares no code with {@link PgBackend}.
 *
 * <p><strong>psql is better still</strong>, and {@code PsqlSessionTest} is that test. These are not
 * offered as equivalent to it: they are the layer that says which byte is wrong when it fails.
 */
class PgWireSessionTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final Principal ANALYST = new Principal("dana", "public", Set.of("analyst"), Map.of());
    private static final Principal INTERN = new Principal("sam", "public", Set.of("intern"), Map.of());

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

    private PravahaPgWireServer open() {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);
        return server;
    }

    /** Connects and completes the handshake on a server with no verifier. */
    private PgTestClient connected() throws IOException {
        PgTestClient client = new PgTestClient(server.port());
        client.startup(Map.of("user", "anyone", "database", "pravaha"));
        List<PgTestClient.Message> handshake = client.readHandshake();
        assertThat(PgTestClient.shape(handshake)).endsWith("Z");
        return client;
    }

    // ------------------------------------------------------------------ the handshake

    @Test
    void anSslRequestIsDeclinedWithNSoAClientFallsBackToPlaintext() throws Exception {
        open();
        try (PgTestClient client = new PgTestClient(server.port())) {
            // 'N', not silence and not an error. This single unframed byte is the whole reason a
            // default `psql` -- which tries SSL first -- connects to this server at all rather than
            // failing at the handshake with something about encryption.
            assertThat(client.sslRequest()).isEqualTo('N');

            client.startup(Map.of("user", "anyone"));
            assertThat(PgTestClient.shape(client.readHandshake()))
                    .startsWith("R")
                    .endsWith("Z");
        }
    }

    @Test
    void theHandshakeAnnouncesTheEncodingAndDateStyleTheEncoderActuallyUses() throws Exception {
        open();
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "anyone"));
            Map<String, String> statuses = PgTestClient.parameterStatuses(client.readHandshake());

            // Each of these is a promise PgTypes keeps. DateStyle in particular: the timestamp
            // encoder emits "yyyy-MM-dd HH:mm:ss+00", which is ISO and is only correct because the
            // server said ISO here.
            assertThat(statuses).containsEntry("client_encoding", "UTF8");
            assertThat(statuses).containsEntry("DateStyle", "ISO, MDY");
            assertThat(statuses).containsEntry("TimeZone", "UTC");
            assertThat(statuses.get("server_version")).contains("Pravaha");
        }
    }

    @Test
    void readyForQuerySaysIdleAndBackendKeyDataIsSentBecauseClientsExpectIt() throws Exception {
        open();
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "anyone"));
            List<PgTestClient.Message> handshake = client.readHandshake();

            assertThat(PgTestClient.ofType(handshake, 'K')).hasSize(1);
            // And nothing else. A server with no verifier runs every session as anonymous by
            // design; a NOTICE saying so at the top of every session is a warning about a choice
            // the operator already made, which they cannot act on and will learn to ignore.
            assertThat(PgTestClient.ofType(handshake, 'N')).isEmpty();
            PgTestClient.Message ready = handshake.get(handshake.size() - 1);
            assertThat(ready.type()).isEqualTo('Z');
            assertThat((char) ready.payload()[0]).isEqualTo('I');
        }
    }

    // ------------------------------------------------------------------ the read path

    @Test
    void selectStarOverAViewIsRowDescriptionThenDataRowsThenCommandCompleteThenReadyForQuery() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT * FROM user_volume");
            List<PgTestClient.Message> reply = client.readUntilReady();

            // The exact message sequence a client's state machine is written against. Three rows,
            // so three D's; anything else here is a protocol bug rather than a wrong answer.
            assertThat(PgTestClient.shape(reply)).isEqualTo("TDDDCZ");
            assertThat(PgTestClient.ofType(reply, 'C').get(0).strings()).containsExactly("SELECT 3");
        }
    }

    @Test
    void aTrailingSemicolonIsAcceptedBecauseThatIsWhatPsqlSends() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT user_id FROM user_volume;\n");

            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDDDCZ");
        }
    }

    @Test
    void theRowsThatComeBackAreTheRowsInTheView() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT user_id, total FROM user_volume WHERE total > 100");
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.ofType(reply, 'D'))
                    .extracting(PgTestClient::columns)
                    .containsExactly(List.of("u1", "300"));
            assertThat(PgTestClient.ofType(reply, 'C').get(0).strings()).containsExactly("SELECT 1");
        }
    }

    @Test
    void aNullIsMinusOneAndNotAnEmptyString() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT user_id, tier FROM user_volume WHERE user_id = 'u3'");
            List<PgTestClient.Message> reply = client.readUntilReady();

            // The protocol's own distinction, and the whole reason PgTypes.encode returns null
            // rather than "". A client that cannot tell an absent tier from a blank one will
            // eventually group by it and get a different total than the engine would.
            assertThat(PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0)))
                    .containsExactly("u3", null);
        }
    }

    @Test
    void rowDescriptionCarriesPostgresTypeOidsAndTextFormat() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT user_id, total FROM user_volume");
            List<PgTestClient.Message> reply = client.readUntilReady();
            List<PgTestClient.Described> columns =
                    PgTestClient.described(PgTestClient.ofType(reply, 'T').get(0));

            assertThat(columns).extracting(PgTestClient.Described::name).containsExactly("user_id", "total");
            // 25 is text and 20 is int8, from pg_type. Hard-coded rather than read from PgTypes:
            // these are PostgreSQL's numbers, not this repository's, and a test that read them from
            // the code under test would accept any number at all.
            assertThat(columns).extracting(PgTestClient.Described::typeOid).containsExactly(25, 20);
            assertThat(columns).extracting(PgTestClient.Described::typeSize).containsExactly((short) -1, (short) 8);
            // Format 0 is text, and every DataRow above honours it. Declaring text and sending
            // binary is the specific way to make a driver read a number as rubbish.
            assertThat(columns).extracting(PgTestClient.Described::format).containsOnly((short) 0);
            // Not a column of a table, which is what a view's column honestly is.
            assertThat(columns).extracting(PgTestClient.Described::tableOid).containsOnly(0);
        }
    }

    @Test
    void anEmptyQueryIsAnEmptyQueryResponseAndNotAnError() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("   \n  ");

            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("IZ");
        }
    }

    @Test
    void theSessionSurvivesAFailedQueryAndAnswersTheNextOne() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT * FROM no_such_view");
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("EZ");

            // The point of the test: ReadyForQuery after an error is what stops a client hanging,
            // and a connection that could not be reused after one typo would be unusable in psql.
            client.query("SELECT * FROM user_volume");
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDDDCZ");
        }
    }

    // ------------------------------------------------------------------ failures

    @Test
    void anUnknownViewComesBackAsAnErrorResponseCarryingBothTheSqlstateAndThePrvCode() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT * FROM no_such_view");
            List<PgTestClient.Message> reply = client.readUntilReady();
            Map<Character, String> fields =
                    PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0));

            // PRV-4023, the serving layer's own code, and so 42P01 undefined_table -- the
            // SQLSTATE a psql user and every driver already know.
            //
            // This asserted PRV-2002 and the generic 42000 until finding L-3, because ViewQuery
            // answered PRV-4023 only over an EMPTY catalogue and let the planner's SQL-validation
            // failure through otherwise. The mapping in PgWireErrors was already right and was
            // simply not being reached. Its reasoning still holds and is why the change is safe:
            // PRV-2002 deliberately does NOT map to 42P01, because an unknown *column* throws it
            // too and a confident "no such table" would send the user looking in the wrong place.
            assertThat(fields).containsEntry('C', "42P01");
            assertThat(fields).containsEntry('S', "ERROR");
            assertThat(fields.get('M')).startsWith("PRV-4023");
            assertThat(fields.get('M')).contains("no_such_view");
            // The code's name in the detail field, so a client that shows only the primary message
            // still lets a person copy something searchable out of the second line.
            assertThat(fields).containsEntry('D', "SERVING_NO_SUCH_VIEW");
        }
    }

    @Test
    void theExtendedQueryProtocolParsesBindsAndExecutesThenTheSessionCarriesOn() throws Exception {
        // The extended query protocol is implemented -- see PgExtendedSessionTest for its own
        // dedicated coverage (parameter binding, Describe, portal chunking, error recovery). This
        // case's own point is narrower and belongs here, next to every other "the session survives
        // and answers the next thing" case in this file: a full Parse/Bind/Execute/Sync round trip
        // does not disturb a plain simple Query run on the same connection afterward.
        open();
        try (PgTestClient client = connected()) {
            client.parse("", "SELECT user_id FROM user_volume WHERE total > $1");
            client.bindText("", "", "100");
            client.describePortal("");
            client.execute("", 0);
            client.sync();
            List<PgTestClient.Message> reply = client.readUntilReady();

            assertThat(PgTestClient.shape(reply)).isEqualTo("12TDCZ");
            assertThat(PgTestClient.ofType(reply, 'C').get(0).strings()).containsExactly("SELECT 1");

            client.query("SELECT user_id FROM user_volume");
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDDDCZ");
        }
    }

    @Test
    void aMultiStatementQueryIsRefusedRatherThanHalfAnswered() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.query("SELECT user_id FROM user_volume; SELECT total FROM user_volume");
            Map<Character, String> fields = PgTestClient.errorFields(
                    PgTestClient.ofType(client.readUntilReady(), 'E').get(0));

            assertThat(fields.get('M')).startsWith("PRV-6201");
            assertThat(fields.get('M')).contains("2 statements");
        }
    }

    @Test
    void aSemicolonInsideAStringLiteralIsNotAStatementBoundary() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            // The one failure the statement splitter exists to avoid. If this is torn in half the
            // query is refused as multi-statement, and the user's data is blamed for it.
            client.query("SELECT user_id FROM user_volume WHERE tier = 'gold;silver'");

            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TCZ");
        }
    }

    @Test
    void terminateEndsTheSessionWithoutAReply() throws Exception {
        open();
        try (PgTestClient client = connected()) {
            client.terminate();

            assertThat(client.read()).isNull();
        }
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void aStartupPacketWithAnAbsurdLengthIsRefusedRatherThanAllocated() throws Exception {
        open();
        try (PgTestClient client = new PgTestClient(server.port())) {
            // An unauthenticated peer choosing the size of this server's next allocation. The cap
            // is checked before a byte of payload is read, so nothing here is ever allocated.
            client.raw(new byte[] {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff});
            Map<Character, String> fields = PgTestClient.errorFields(client.read());

            assertThat(fields).containsEntry('S', "FATAL");
            assertThat(fields.get('M')).startsWith("PRV-6202");
        }
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void aProtocolTwoClientIsToldWhatThisServerSpeaks() throws Exception {
        open();
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(131_072, Map.of()); // 2.0
            Map<Character, String> fields = PgTestClient.errorFields(client.read());

            assertThat(fields.get('M')).startsWith("PRV-6203");
            assertThat(fields.get('M')).contains("protocol 3.0");
        }
    }

    // ------------------------------------------------------------------ authentication

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void aServerWithAVerifierAsksForAPasswordAndRefusesAWrongOne() throws Exception {
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(StaticTokenVerifier.of("s3cret", ANALYST))
                .start("127.0.0.1", 0);
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "dana"));
            PgTestClient.Message request = client.read();

            // 'R' with sub-code 3 is AuthenticationCleartextPassword. Cleartext because
            // TokenVerifier's shape is "client sends the secret, server asks somebody else" -- MD5
            // and SCRAM both require this server to hold a secret it deliberately does not have.
            assertThat(java.util.Objects.requireNonNull(request).type()).isEqualTo('R');
            assertThat(request.int32At(0)).isEqualTo(3);

            client.password("wrong");
            Map<Character, String> fields = PgTestClient.errorFields(client.read());
            assertThat(fields).containsEntry('S', "FATAL");
            assertThat(fields).containsEntry('C', "28P01");
            // Says the credential was rejected, and nothing about why: "expired" versus "unknown"
            // is three bits of an oracle for whoever is guessing.
            assertThat(fields.get('M')).doesNotContain("expired").doesNotContain("unknown");
        }
    }

    @Test
    void aRightCredentialConnectsAndTheCredentialDecidesThePrincipal() throws Exception {
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(StaticTokenVerifier.of("s3cret", ANALYST))
                .start("127.0.0.1", 0);
        try (PgTestClient client = new PgTestClient(server.port())) {
            // The startup packet says "someone-else"; the credential says dana. The credential wins,
            // and the server says so rather than letting an afternoon's queries run as a surprise.
            client.startup(Map.of("user", "someone-else"));
            assertThat(java.util.Objects.requireNonNull(client.read()).type()).isEqualTo('R');
            client.password("s3cret");

            List<PgTestClient.Message> handshake = client.readUntilReady();
            assertThat(PgTestClient.shape(handshake)).startsWith("R").endsWith("Z");
            assertThat(PgTestClient.ofType(handshake, 'N')).hasSize(1);
            assertThat(PgTestClient.errorFields(
                                    PgTestClient.ofType(handshake, 'N').get(0))
                            .get('M'))
                    .contains("connected as 'dana'");

            client.query("SELECT user_id FROM user_volume");
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDDDCZ");
        }
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void authorizationIsTheSameViewQueryPathWithTheSameAudit() throws Exception {
        AuditSink.InMemory audit = new AuditSink.InMemory();
        SecurityPolicy analystsOnly = (principal, view) -> principal.hasRole("analyst")
                ? AccessDecision.allow()
                : AccessDecision.deny("only analysts may read " + view);
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(StaticTokenVerifier.of("intern-token", INTERN).and("analyst-token", ANALYST))
                .authorizedBy(analystsOnly, audit)
                .start("127.0.0.1", 0);

        try (PgTestClient intern = new PgTestClient(server.port())) {
            intern.startup(Map.of("user", "sam"));
            intern.read();
            intern.password("intern-token");
            intern.readUntilReady();

            intern.query("SELECT * FROM user_volume");
            Map<Character, String> fields = PgTestClient.errorFields(
                    PgTestClient.ofType(intern.readUntilReady(), 'E').get(0));

            // 42501 insufficient_privilege, PRV-7002. The refusal is ViewQuery's, not this
            // module's: pgwire is a transport, and a transport that had its own opinion about who
            // may read what would be a second and weaker path to the data.
            assertThat(fields).containsEntry('C', "42501");
            assertThat(fields.get('M')).startsWith("PRV-7002");
        }

        try (PgTestClient analyst = new PgTestClient(server.port())) {
            analyst.startup(Map.of("user", "dana"));
            analyst.read();
            analyst.password("analyst-token");
            analyst.readUntilReady();

            analyst.query("SELECT * FROM user_volume");
            assertThat(PgTestClient.shape(analyst.readUntilReady())).isEqualTo("TDDDCZ");
        }

        // The audit is ViewQuery's too, and it is the proof that the principal actually reached the
        // authorization path rather than being dropped on the floor by the transport.
        assertThat(audit.denials()).isNotEmpty();
        assertThat(audit.forPrincipal("sam")).isNotEmpty();
        assertThat(audit.forPrincipal("dana")).isNotEmpty();
    }

    // ------------------------------------------------------------------ types

    @Test
    void aColumnTypeThisGatewayCannotEncodeIsRefusedBeforeAnyRowIsSent() throws Exception {
        StreamSchema withTime = StreamSchema.builder("clocked")
                .field("id", Types.int64())
                .field("at", Types.time())
                .build();
        ServedView view = new ServedView("clocked", withTime, List.of(0), 10_000);
        view.applyValues(new Object[] {1L, 3_600_000_000_000L}, 1, 100);
        view.commit(100);
        server = new PravahaPgWireServer(new ViewCatalog().register(view)).start("127.0.0.1", 0);

        try (PgTestClient client = connected()) {
            client.query("SELECT * FROM clocked");
            List<PgTestClient.Message> reply = client.readUntilReady();

            // "EZ" and not "TDE": the refusal arrives before RowDescription, so the client never
            // sees a truncated result set it might treat as complete. That is a property of
            // building each message whole before framing it, and it is worth a test because the
            // obvious streaming implementation loses it.
            assertThat(PgTestClient.shape(reply)).isEqualTo("EZ");
            Map<Character, String> fields =
                    PgTestClient.errorFields(PgTestClient.ofType(reply, 'E').get(0));
            assertThat(fields.get('M')).startsWith("PRV-6200");
            assertThat(fields.get('M')).contains("column 'at'");
            assertThat(fields.get('M')).contains("TY-18");
        }
    }

    @Test
    void decimalDateAndTimestampColumnsArriveAsExactPostgresText() throws Exception {
        StreamSchema ledger = StreamSchema.builder("ledger")
                .field("id", Types.int64())
                .field("amount", Types.decimal(12, 4))
                .field("day", Types.date())
                .field("at", Types.timestamp())
                .build();
        ServedView view = new ServedView("ledger", ledger, List.of(0), 10_000);
        // 2026-09-16, and 2026-09-16T12:34:56.123456789Z as nanoseconds since the epoch.
        view.applyValues(new Object[] {1L, new BigDecimal("1234.5600"), 20_712, 1_789_562_096_123_456_789L}, 1, 100);
        view.commit(100);
        server = new PravahaPgWireServer(new ViewCatalog().register(view)).start("127.0.0.1", 0);

        try (PgTestClient client = connected()) {
            client.query("SELECT * FROM ledger");
            List<PgTestClient.Message> reply = client.readUntilReady();

            List<PgTestClient.Described> columns =
                    PgTestClient.described(PgTestClient.ofType(reply, 'T').get(0));
            // 1700 numeric, 1082 date, 1184 timestamptz. DECIMAL is sent here and refused by the
            // Arrow gateway, and that is not an inconsistency: Arrow needs a rounding decision that
            // BigDecimal.toPlainString does not.
            assertThat(columns).extracting(PgTestClient.Described::typeOid).containsExactly(20, 1700, 1082, 1184);
            // atttypmod for numeric(12,4): ((12 << 16) | 4) + 4.
            assertThat(columns.get(1).modifier()).isEqualTo(((12 << 16) | 4) + 4);

            assertThat(PgTestClient.columns(PgTestClient.ofType(reply, 'D').get(0)))
                    .containsExactly("1", "1234.5600", "2026-09-16", "2026-09-16 12:34:56.123456789+00");
        }
    }
}
