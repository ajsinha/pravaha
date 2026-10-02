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
package com.ash.messaging.pravaha.server.identity;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PGWIREPASS-1: with the engine's own accounts on (ADR-052), the PostgreSQL gateway's password is an
 * API key or a session token -- verified here with a real PostgreSQL driver -- and a user's own
 * password is not one.
 */
class PgWireSignInTest {

    @TempDir
    Path dir;

    private PravahaNode node;
    private IdentityService users;
    private Principal bea;

    @BeforeEach
    void start() {
        IdentityProperties identity = new IdentityProperties();
        identity.setEnabled(true);
        identity.setStore(dir.resolve("identity/identity.journal").toString());
        identity.setEnvironment("qa");
        identity.setDev(true);
        SecurityProperties security = new SecurityProperties();
        security.setAuthentication("token");
        security.setPolicy("authenticated");
        security.setTokens(Map.of());
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPgWire(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("pgwire-sign-in")
                .build();
        node.setIdentity(identity);
        node.start();
        users = node.identity().orElseThrow();
        Principal admin = node.verifier()
                .verify(users.login("admin", IdentityService.DEFAULT_ADMIN_PASSWORD, null)
                        .token());
        users.createUser(admin, "bea", "Bea", null, null, Set.of("analyst"), "Bea-initial-9Qa", false);
        String first = users.login("bea", "Bea-initial-9Qa", null).token();
        Principal held = node.verifier().verify(first);
        users.changePassword(held, "Bea-initial-9Qa", "Bea-chosen-7Zx");
        bea = node.verifier().verify(users.login("bea", "Bea-chosen-7Zx", null).token());
    }

    @AfterEach
    void stop() {
        node.stop();
    }

    @Test
    void anApiKeyIsAPassword() throws SQLException {
        String key = users.createKey(bea, "power-bi", null, null, null).key();

        try (Connection connection = connect("bea", key)) {
            assertThat(connection.isValid(5)).isTrue();
        }
    }

    @Test
    void aSessionTokenIsAPassword() throws SQLException {
        String session = users.login("bea", "Bea-chosen-7Zx", null).token();

        try (Connection connection = connect("bea", session)) {
            assertThat(connection.isValid(5)).isTrue();
        }
    }

    @Test
    void theAccountsOwnPasswordIsNotAndNeitherIsARevokedKey() {
        assertThatThrownBy(() -> connect("bea", "Bea-chosen-7Zx"))
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28P01"));

        IdentityService.IssuedKey key = users.createKey(bea, "short-lived", null, null, null);
        users.revokeKey(bea, key.keyId());
        assertThatThrownBy(() -> connect("bea", key.key()))
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28P01"));
    }

    @Test
    void theUserNameIsNotWhoTheCredentialSaysAndIsNotTrusted() throws SQLException {
        // The credential decides who is signed in; the startup's user is the client's label.
        String key = users.createKey(bea, "labelled", null, null, null).key();
        try (Connection connection = connect("somebody-else", key)) {
            assertThat(connection.isValid(5)).isTrue();
        }
    }

    // ------------------------------------------------------------------ PGREVOKE-1

    @Test
    void revokingAKeyEndsTheConnectionItOpenedAtItsNextStatement() throws SQLException {
        IdentityService.IssuedKey key = users.createKey(bea, "power-bi", null, null, null);
        try (Connection connection = connect("bea", key.key())) {
            assertThat(show(connection)).isNotEmpty();
            users.revokeKey(bea, key.keyId());
            assertRevoked(connection);
        }
    }

    @Test
    void signingASessionOutEndsTheConnectionItOpened() throws SQLException {
        String session = users.login("bea", "Bea-chosen-7Zx", null).token();
        try (Connection connection = connect("bea", session)) {
            assertThat(show(connection)).isNotEmpty();
            users.logout(session);
            assertRevoked(connection);
        }
    }

    @Test
    void disablingTheUserEndsTheConnectionsTheirKeyOpened() throws SQLException {
        String key = users.createKey(bea, "grafana", null, null, null).key();
        Principal admin = node.verifier()
                .verify(users.login("admin", IdentityService.DEFAULT_ADMIN_PASSWORD, null)
                        .token());
        try (Connection connection = connect("bea", key)) {
            assertThat(show(connection)).isNotEmpty();
            users.updateUser(admin, "bea", null, null, null, "disabled");
            assertRevoked(connection);
        }
    }

    private static String show(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement();
                java.sql.ResultSet rows = statement.executeQuery("SHOW server_version")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    /** The next statement is refused FATAL 28000 with PRV-6218, and the connection is over. */
    private static void assertRevoked(Connection connection) throws SQLException {
        assertThatThrownBy(() -> show(connection)).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("28000");
            assertThat(e.getMessage()).contains("PRV-6218");
        });
        assertThat(connection.isValid(2)).as("and the connection is closed").isFalse();
    }

    private Connection connect(String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + node.pgwirePort().orElseThrow() + "/pravaha", properties);
    }
}
