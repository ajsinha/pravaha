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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate P6's other half: a real driver, not a real terminal. {@code psql} proves a human at a prompt
 * can use this gateway; the PostgreSQL JDBC driver -- the actual jar, {@code org.postgresql:
 * postgresql:42.7.8}, already a test dependency of {@code plugins/pravaha-plugin-jdbc} and added here
 * for the same reason -- proves a tool like DBeaver, which opens a connection, calls {@code
 * DatabaseMetaData.getTables()} and {@code getColumns()} to build its object tree, and only then runs
 * a query, can too. A test against this module's own {@link PgTestClient} proves the encoder and the
 * decoder agree with each other; this proves this server speaks the protocol a driver neither of them
 * wrote understands.
 *
 * <p><strong>{@code preferQueryMode=simple} is no longer required</strong>, and the tests below whose
 * names say "extended" are the proof: the extended query protocol is implemented (see the package's
 * own documentation), so a connection left on pgjdbc's own default -- which is the extended protocol
 * -- opens, lists tables and columns through {@code DatabaseMetaData}, and runs a {@code
 * PreparedStatement} with a bound parameter, all through {@code Parse}/{@code Bind}/{@code
 * Describe}/{@code Execute}/{@code Sync}. The earlier tests in this file that still set {@code
 * preferQueryMode=simple} keep doing so on purpose: they are what proves the simple protocol still
 * works unchanged, side by side with the extended one, not a leftover workaround.
 */
class JdbcClientTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("tier", Types.string().withNullable(true))
            .field("total", Types.int64())
            .build();

    private static final Principal ANALYST = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private PravahaPgWireServer server;

    @TempDir
    private java.io.File tlsDir;

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

    @Test
    void jdbcConnectsListsTablesAndColumnsAndReadsRows() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        try (Connection conn = connect(server.port(), null)) {
            assertThat(conn.isValid(5)).isTrue();

            List<String> tables = new ArrayList<>();
            DatabaseMetaData metadata = conn.getMetaData();
            try (ResultSet rs = metadata.getTables(null, null, null, new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).containsExactly("user_volume");

            Map<String, String> columns = new java.util.LinkedHashMap<>();
            Map<String, Boolean> nullable = new java.util.LinkedHashMap<>();
            try (ResultSet rs = metadata.getColumns(null, null, "user_volume", null)) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    columns.put(column, rs.getString("TYPE_NAME"));
                    nullable.put(column, rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                }
            }
            assertThat(columns.keySet()).containsExactly("user_id", "tier", "total");
            // Nullability round-trips through pg_attribute.attnotnull, computed from the same
            // StreamSchema field the wire encoder uses -- not re-derived a second way.
            assertThat(nullable.get("user_id")).isFalse();
            assertThat(nullable.get("tier")).isTrue();
            assertThat(nullable.get("total")).isFalse();

            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT user_id, tier, total FROM user_volume")) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                }
                assertThat(rows).isEqualTo(3);
            }
        }
    }

    /**
     * The point of this slice, proved by removing the workaround rather than describing it:
     * {@code preferQueryMode} is never set here, so this is pgjdbc's own default -- the extended
     * query protocol -- and a plain connection opens, lists its one table and that table's three
     * columns through {@code DatabaseMetaData}, exactly as {@link
     * #jdbcConnectsListsTablesAndColumnsAndReadsRows} does over the simple protocol.
     */
    @Test
    void jdbcOverTheExtendedProtocolConnectsListsTablesAndColumns() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        try (Connection conn = connectExtended(server.port(), null)) {
            assertThat(conn.isValid(5)).isTrue();

            List<String> tables = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).containsExactly("user_volume");

            List<String> columns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "user_volume", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
            assertThat(columns).containsExactly("user_id", "tier", "total");
        }
    }

    /**
     * A real {@code PreparedStatement} with a bound parameter, over the extended protocol: {@code
     * Parse} plans {@code SELECT ... WHERE total > $1} once, {@code Bind} sends {@code 100} as a
     * real parameter (not a value pgjdbc inlined into the SQL text, which is what the simple
     * protocol would have forced it to do), and {@code Execute} runs it. This is the shape
     * {@code preferQueryMode=simple} exists to route around, working without that flag.
     */
    @Test
    void jdbcPreparedStatementWithABoundParameterWorksOverTheExtendedProtocol() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        try (Connection conn = connectExtended(server.port(), null);
                PreparedStatement ps =
                        conn.prepareStatement("SELECT user_id, total FROM user_volume WHERE total > ?")) {
            ps.setLong(1, 100L);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("user_id"));
                }
            }
            // Only u1 (300) qualifies; u2 (50) and u3 (7) do not.
            assertThat(ids).containsExactly("u1");

            // The same PreparedStatement, re-executed with a different bound value: proof that Parse
            // planned the statement once and Bind/Execute carry the value, not that the SQL text was
            // rewritten with a literal each time.
            ps.setLong(1, 10L);
            ids.clear();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("user_id"));
                }
            }
            assertThat(ids).containsExactlyInAnyOrder("u1", "u2");
        }
    }

    /**
     * PGINTPARAM-1: {@code setInt} and {@code setShort} against a {@code BIGINT} column. pgjdbc sends
     * them as binary {@code int4}/{@code int2}, declared so in {@code Parse}; the gateway demanded 8
     * bytes and answered {@code 08P01}. PostgreSQL widens them, and so does this gateway now -- past
     * the server-prepare threshold too, where pgjdbc reuses the named statement.
     */
    @Test
    void jdbcSetIntAndSetShortAgainstABigintColumnAreWidened() throws Exception {
        server = new PravahaPgWireServer(populated()).start("127.0.0.1", 0);

        try (Connection conn = connectExtended(server.port(), null);
                PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM user_volume WHERE total > ?")) {
            for (int run = 0; run < 7; run++) {
                ps.setInt(1, 40);
                assertThat(ids(ps)).containsExactlyInAnyOrder("u1", "u2");
            }
            ps.setShort(1, (short) 100);
            assertThat(ids(ps)).containsExactly("u1");
            ps.setInt(1, -2_000_000_000);
            assertThat(ids(ps)).containsExactlyInAnyOrder("u1", "u2", "u3");
        }
    }

    private static List<String> ids(PreparedStatement ps) throws java.sql.SQLException {
        List<String> ids = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString("user_id"));
            }
        }
        return ids;
    }

    /**
     * SX-5 over JDBC: {@code getTables()} is a read like any other, and a principal denied every
     * view must see an empty catalogue through the driver's own metadata call, not a partial or
     * error-marked one that still names what it would not show.
     */
    @Test
    void jdbcGetTablesShowsNothingToAPrincipalDeniedEveryView() throws Exception {
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(com.ash.messaging.pravaha.security.StaticTokenVerifier.of("s3cret", ANALYST))
                .authorizedBy(
                        (principal, view) -> AccessDecision.deny("no principal may read anything in this test"),
                        AuditSink.NONE)
                .start("127.0.0.1", 0);

        try (Connection conn = connect(server.port(), "s3cret")) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).isEmpty();
        }
    }

    /** The same guarantee, over the extended protocol this connection is left on by default. */
    @Test
    void jdbcOverTheExtendedProtocolGetTablesShowsNothingToAPrincipalDeniedEveryView() throws Exception {
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(com.ash.messaging.pravaha.security.StaticTokenVerifier.of("s3cret", ANALYST))
                .authorizedBy(
                        (principal, view) -> AccessDecision.deny("no principal may read anything in this test"),
                        AuditSink.NONE)
                .start("127.0.0.1", 0);

        try (Connection conn = connectExtended(server.port(), "s3cret")) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).isEmpty();
        }
    }

    /**
     * A real pgjdbc connection actually negotiates TLS against this server -- not against a mock,
     * and not against this module's own {@link PgTestClient}, which has no TLS support at all.
     *
     * <p>{@code sslfactory=NonValidatingFactory} is pgjdbc's own, built-in class for exactly this
     * situation: a self-signed certificate this test minted five lines ago that nobody asked the
     * JVM's trust store to trust. It is the JDBC-side equivalent of libpq's {@code sslmode=require}
     * -- encrypted, unverified -- and not a weakening introduced by this test: a driver pointed at a
     * production certificate signed by a real CA needs no such override.
     */
    @Test
    void jdbcConnectsOverTlsAndReadsRows() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        Properties props = new Properties();
        props.setProperty("user", "dana");
        props.setProperty("preferQueryMode", "simple");
        props.setProperty("ssl", "true");
        props.setProperty("sslmode", "require");
        props.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        try (Connection conn =
                DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port() + "/pravaha", props)) {
            assertThat(conn.isValid(5)).isTrue();
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT user_id, tier, total FROM user_volume")) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                }
                assertThat(rows).isEqualTo(3);
            }
        }
    }

    /**
     * PGTLSONLY-1: a TLS-configured server is TLS-only. {@code sslmode=disable} tells pgjdbc never to
     * send {@code SSLRequest}, and that client used to be asked for its token in the clear and signed
     * in. Refused now, {@code 28000}, naming the setting a person changes.
     */
    @Test
    void jdbcWithSslDisabledIsRefusedByAServerWithACertificate() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        Properties props = new Properties();
        props.setProperty("user", "dana");
        props.setProperty("sslmode", "disable");
        assertThatThrownBy(() ->
                        DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port() + "/pravaha", props))
                .isInstanceOfSatisfying(
                        java.sql.SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo("28000"))
                .hasMessageContaining("PRV-6221")
                .hasMessageContaining("sslmode=verify-full");
    }

    /**
     * The raw protocol, as a client with no TLS at all sends it: a startup packet straight away. The
     * answer is the refusal and nothing else -- in particular no {@code AuthenticationCleartextPassword},
     * which is the request that put the token on the wire in the clear.
     */
    @Test
    void aPlaintextStartupIsRefusedBeforeAnyCredentialIsAskedFor() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .authenticatedBy(com.ash.messaging.pravaha.security.StaticTokenVerifier.of("s3cret", ANALYST))
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "dana", "database", "pravaha"));
            PgTestClient.Message first = client.read();
            assertThat(first.type())
                    .as("an ErrorResponse, not a password request")
                    .isEqualTo('E');
            Map<Character, String> fields = PgTestClient.errorFields(first);
            assertThat(fields.get('S')).isEqualTo("FATAL");
            assertThat(fields.get('C')).isEqualTo("28000");
            assertThat(fields.get('M')).startsWith("PRV-6221");
            assertThat(client.read()).as("and the connection closes").isNull();
        }
    }

    /** {@code pravaha.pgwire.tls.allow-plaintext}: PostgreSQL's {@code host} rather than {@code hostssl}. */
    @Test
    void aServerThatAllowsPlaintextStillServesAClientThatDisablesSsl() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .allowingPlaintext(true)
                .start("127.0.0.1", 0);

        Properties props = new Properties();
        props.setProperty("user", "dana");
        props.setProperty("preferQueryMode", "simple");
        props.setProperty("sslmode", "disable");
        try (Connection conn =
                DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port() + "/pravaha", props)) {
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT user_id, tier, total FROM user_volume")) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                }
                assertThat(rows).isEqualTo(3);
            }
        }
    }

    private static Connection connect(int port, String password) throws java.sql.SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pravaha";
        Properties props = new Properties();
        props.setProperty("user", "dana");
        if (password != null) {
            props.setProperty("password", password);
        }
        // Deliberately still simple, side by side with connectExtended below: this is what proves
        // the simple protocol keeps working unchanged now that the extended one also does.
        props.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection(url, props);
    }

    /** As {@link #connect}, but with no {@code preferQueryMode} at all -- pgjdbc's own default. */
    private static Connection connectExtended(int port, String password) throws java.sql.SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pravaha";
        Properties props = new Properties();
        props.setProperty("user", "dana");
        if (password != null) {
            props.setProperty("password", password);
        }
        return DriverManager.getConnection(url, props);
    }
}
