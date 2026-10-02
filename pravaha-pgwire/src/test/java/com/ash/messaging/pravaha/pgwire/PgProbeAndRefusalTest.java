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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * PGVALIDATE-1 and PGCOPY-1, through the real pgjdbc driver on both query protocols.
 *
 * <p>The probes a connection pool or a BI tool sends to validate a connection -- {@code SELECT 1},
 * {@code SELECT now()}, {@code SELECT 'a'::text}, {@code SHOW search_path}, {@code SET search_path}
 * -- are answered with PostgreSQL's column names and types; {@code COPY}, SQL cursors and {@code
 * SELECT STREAM} are refused by name with {@code PRV-6201} and {@code 0A000}, and the session goes on
 * answering reads.
 */
class PgProbeAndRefusalTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", com.ash.messaging.pravaha.api.data.Types.string())
            .field("total", com.ash.messaging.pravaha.api.data.Types.int64())
            .build();

    private PravahaPgWireServer server;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 100);
        view.commit(100);
        server = new PravahaPgWireServer(new ViewCatalog().register(view)).start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void selectOneIsAnsweredAsPostgresqlAnswersIt() throws Exception {
        for (boolean simple : new boolean[] {true, false}) {
            try (Connection conn = connect(simple);
                    Statement statement = conn.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1")) {
                ResultSetMetaData meta = rs.getMetaData();
                assertThat(meta.getColumnCount()).isEqualTo(1);
                assertThat(meta.getColumnName(1)).isEqualTo("?column?");
                assertThat(meta.getColumnType(1)).isEqualTo(Types.INTEGER);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void connectionValidationProbesAreAnswered() throws Exception {
        for (boolean simple : new boolean[] {true, false}) {
            try (Connection conn = connect(simple);
                    Statement statement = conn.createStatement()) {
                Instant before = Instant.now().minusSeconds(1);
                try (ResultSet rs = statement.executeQuery("SELECT now()")) {
                    assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("now");
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getTimestamp(1).toInstant()).isAfter(before);
                    // Microseconds, as PostgreSQL's now(): psycopg refuses nine fractional digits.
                    assertThat(rs.getTimestamp(1).getNanos() % 1_000).isZero();
                }
                try (ResultSet rs = statement.executeQuery("SELECT 'a'::text")) {
                    assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("text");
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("a");
                }
                try (ResultSet rs = statement.executeQuery(
                        "select 1 as ok, 'it''s', true, 2.50, CAST('7' AS bigint) n, current_user")) {
                    ResultSetMetaData meta = rs.getMetaData();
                    assertThat(meta.getColumnName(1)).isEqualTo("ok");
                    assertThat(meta.getColumnName(3)).isEqualTo("bool");
                    assertThat(meta.getColumnName(5)).isEqualTo("n");
                    assertThat(meta.getColumnName(6)).isEqualTo("current_user");
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(2)).isEqualTo("it's");
                    assertThat(rs.getBoolean(3)).isTrue();
                    assertThat(rs.getBigDecimal(4)).isEqualByComparingTo(new BigDecimal("2.50"));
                    assertThat(rs.getLong(5)).isEqualTo(7L);
                    assertThat(rs.getString(6)).isNotBlank();
                }
                try (ResultSet rs = statement.executeQuery("SHOW search_path")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("\"$user\", public");
                }
                statement.execute("SET search_path = public");
                statement.execute("SET search_path TO \"$user\", public");
                try (ResultSet rs = statement.executeQuery("SELECT total FROM user_volume")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(300L);
                }
            }
        }
    }

    @Test
    void aSearchPathThatWouldChangeResolutionIsRefused() throws Exception {
        try (Connection conn = connect(true);
                Statement statement = conn.createStatement()) {
            SQLException refused =
                    catchThrowableOfType(SQLException.class, () -> statement.execute("SET search_path = sales"));
            assertThat((Object) refused).isNotNull();
            assertThat(refused.getSQLState()).isEqualTo("0A000");
            assertThat(refused.getMessage()).contains("PRV-6204");
        }
    }

    @Test
    void anExpressionThatIsNotAProbeStillReachesThePlanner() throws Exception {
        try (Connection conn = connect(true);
                Statement statement = conn.createStatement()) {
            SQLException refused =
                    catchThrowableOfType(SQLException.class, () -> statement.executeQuery("SELECT 1 + 1"));
            assertThat((Object) refused).as("arithmetic is not approximated").isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "COPY user_volume TO STDOUT",
                "copy (SELECT * FROM user_volume) TO STDOUT WITH CSV",
                "DECLARE c CURSOR FOR SELECT * FROM user_volume",
                "FETCH 10 FROM c",
                "CLOSE c",
                "LISTEN changes",
                "SELECT STREAM user_id FROM txn",
            })
    void unsupportedStatementsAreRefusedByNameWith0A000(String sql) throws Exception {
        for (boolean simple : new boolean[] {true, false}) {
            try (Connection conn = connect(simple);
                    Statement statement = conn.createStatement()) {
                SQLException refused = catchThrowableOfType(SQLException.class, () -> statement.execute(sql));

                assertThat((Object) refused).as(simple ? "simple" : "extended").isNotNull();
                assertThat(refused.getSQLState()).isEqualTo("0A000");
                assertThat(refused.getMessage())
                        .contains("PRV-6201")
                        .contains("not supported by the PostgreSQL gateway")
                        .doesNotContain("PRV-2001");

                try (ResultSet rs = statement.executeQuery("SELECT total FROM user_volume")) {
                    assertThat(rs.next()).as("the session still answers reads").isTrue();
                }
            }
        }
    }

    private Connection connect(boolean simple) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "dana");
        if (simple) {
            props.setProperty("preferQueryMode", "simple");
        }
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port() + "/pravaha", props);
    }
}
