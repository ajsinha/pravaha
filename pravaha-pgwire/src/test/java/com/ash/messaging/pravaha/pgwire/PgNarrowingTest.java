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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PostgreSQL gateway serves a masked, filtered view exactly as Flight does (ADR-059 §4): in text and
 * in binary results, simple and extended protocol, and refuses a masked column compared with 42501.
 */
class PgNarrowingTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("payments")
            .field("id", Types.string())
            .field("region", Types.string())
            .field("card", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal ANA = new Principal("ana", "public", Set.of(), Map.of());

    private PravahaPgWireServer server;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("payments", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"p1", "EU", "4111", 10L}, 1, 100);
        view.applyValues(new Object[] {"p2", "US", "4222", 20L}, 1, 100);
        view.applyValues(new Object[] {"p3", "EU", "4333", 30L}, 1, 100);
        view.commit(100);
        SecurityPolicy policy = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String name) {
                return AccessDecision.allow();
            }

            @Override
            public Narrowing narrowing(Principal principal, String object) {
                return new Narrowing(
                        Optional.of("region = 'EU'"), Map.of("card", "'XXXX'", "amount", "0 * amount"), List.of());
            }
        };
        server = new PravahaPgWireServer(new ViewCatalog().register(view))
                .authenticatedBy(StaticTokenVerifier.of("s3cret", ANA))
                .authorizedBy(policy, AuditSink.NONE)
                .start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private Connection connect(Map<String, String> settings) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "ana");
        props.setProperty("password", "s3cret");
        settings.forEach(props::setProperty);
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port() + "/pravaha", props);
    }

    private static List<String> rows(ResultSet rs) throws SQLException {
        List<String> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getLong(3));
        }
        rows.sort(null);
        return rows;
    }

    @Test
    void textAndBinaryResultsCarryOnlyKeptRowsAndMaskedValues() throws Exception {
        List<String> expected = List.of("p1|XXXX|0", "p3|XXXX|0");
        try (Connection simple = connect(Map.of("preferQueryMode", "simple"));
                ResultSet rs = simple.createStatement().executeQuery("SELECT id, card, amount FROM payments")) {
            assertThat(rows(rs)).isEqualTo(expected);
        }
        // Extended protocol, server-prepared from the first execution, with binary transfer: the int64
        // column arrives in binary, and it is the masked value that was encoded.
        try (Connection binary = connect(Map.of("prepareThreshold", "-1", "binaryTransfer", "true"));
                PreparedStatement statement = binary.prepareStatement("SELECT id, card, amount FROM payments")) {
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rows(rs)).isEqualTo(expected);
            }
        }
    }

    @Test
    void aMaskedColumnComparedIsRefusedAsAnAuthorizationFailure() throws Exception {
        try (Connection conn = connect(Map.of("preferQueryMode", "simple"))) {
            assertThatThrownBy(() -> conn.createStatement().executeQuery("SELECT id FROM payments WHERE card = '4111'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("42501");
                        assertThat(e.getMessage()).contains("PRV-7006");
                    });
        }
    }
}
