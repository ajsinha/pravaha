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
 * <p>{@code preferQueryMode=simple}: the extended query protocol is not implemented in this slice
 * (see the package's own documentation), and a JDBC driver defaults to it. Told to use the simple
 * query protocol instead, pgjdbc runs exactly the same catalog introspection real DBeaver would run,
 * over the one protocol this gateway actually speaks.
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
     * The same proof {@code PsqlSessionTest} makes for {@code psql}: a TLS-configured server is not
     * TLS-only. {@code sslmode=disable} tells pgjdbc never to send {@code SSLRequest} at all, so this
     * exercises the identical plaintext path {@link #jdbcConnectsListsTablesAndColumnsAndReadsRows}
     * does, on a server that happens to also hold a certificate.
     */
    @Test
    void jdbcStillConnectsInPlaintextWhenTheServerHasACertificateButTheClientDisablesSsl() throws Exception {
        SelfSignedTestCertificate cert = SelfSignedTestCertificate.generate(tlsDir);
        server = new PravahaPgWireServer(populated())
                .encryptedWith(cert.certificatePem, cert.privateKeyPem)
                .start("127.0.0.1", 0);

        Properties props = new Properties();
        props.setProperty("user", "dana");
        props.setProperty("preferQueryMode", "simple");
        props.setProperty("sslmode", "disable");
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

    private static Connection connect(int port, String password) throws java.sql.SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pravaha";
        Properties props = new Properties();
        props.setProperty("user", "dana");
        if (password != null) {
            props.setProperty("password", password);
        }
        // See the class javadoc: the extended query protocol is not implemented in this slice.
        props.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection(url, props);
    }
}
