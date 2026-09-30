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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * PGWIRE-TX-1 through the real PostgreSQL JDBC driver with {@code setAutoCommit(false)} -- the mode
 * every ORM and BI driver that wraps its reads in a transaction uses -- on both query protocols. It
 * was refused as {@code PRV-2001 ... near the keyword 'BEGIN'} before; {@link PgTransactionBlockTest}
 * pins the exact bytes, and this proves a driver that neither of them wrote reads them the same way.
 */
class JdbcTransactionTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
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

    @ParameterizedTest(name = "simple protocol: {0}")
    @ValueSource(booleans = {false, true})
    void readsInsideATransactionCommitAndRollBack(boolean simple) throws Exception {
        try (Connection conn = connect(simple);
                Statement statement = conn.createStatement()) {
            conn.setAutoCommit(false);
            assertThat(total(statement)).isEqualTo(300L);
            assertThat(total(statement)).isEqualTo(300L);
            conn.commit();

            assertThat(total(statement)).isEqualTo(300L);
            conn.rollback();

            conn.setAutoCommit(true);
            assertThat(total(statement)).isEqualTo(300L);
        }
    }

    @ParameterizedTest(name = "simple protocol: {0}")
    @ValueSource(booleans = {false, true})
    void anErrorFailsTheTransactionUntilRollback(boolean simple) throws Exception {
        try (Connection conn = connect(simple);
                Statement statement = conn.createStatement()) {
            conn.setAutoCommit(false);
            assertThat((Object) catchThrowableOfType(
                            SQLException.class, () -> statement.executeQuery("SELECT * FROM no_such_view")))
                    .isNotNull();

            SQLException aborted = catchThrowableOfType(SQLException.class, () -> total(statement));
            assertThat(aborted.getSQLState()).isEqualTo("25P02");
            assertThat(aborted.getMessage()).contains("current transaction is aborted");

            // COMMIT of a failed block ends it (the server tags it ROLLBACK, pinned byte for byte in
            // PgTransactionBlockTest; pgjdbc 42.7 does not itself inspect the tag).
            conn.commit();

            assertThat(total(statement)).as("the next transaction is healthy").isEqualTo(300L);
            conn.rollback();
        }
    }

    @ParameterizedTest(name = "simple protocol: {0}")
    @ValueSource(booleans = {false, true})
    void aSavepointRecoversAFailedTransaction(boolean simple) throws Exception {
        try (Connection conn = connect(simple);
                Statement statement = conn.createStatement()) {
            conn.setAutoCommit(false);
            assertThat(total(statement)).isEqualTo(300L);
            Savepoint savepoint = conn.setSavepoint("before_the_bad_read");
            assertThat((Object) catchThrowableOfType(
                            SQLException.class, () -> statement.executeQuery("SELECT * FROM no_such_view")))
                    .isNotNull();
            conn.rollback(savepoint);
            assertThat(total(statement)).isEqualTo(300L);
            conn.releaseSavepoint(savepoint);
            conn.commit();
        }
    }

    @ParameterizedTest(name = "simple protocol: {0}")
    @ValueSource(booleans = {false, true})
    void isolationAndReadOnlySettersAreAcceptedAndTheIsolationReadBackIsTheTruth(boolean simple) throws Exception {
        try (Connection conn = connect(simple);
                Statement statement = conn.createStatement()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            assertThat(total(statement)).isEqualTo(300L);
            // Every read runs at READ COMMITTED; SHOW says so rather than echo what was asked for.
            try (ResultSet rs = statement.executeQuery("SHOW transaction_isolation")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("read committed");
            }
            conn.commit();
        }
    }

    @ParameterizedTest(name = "simple protocol: {0}")
    @ValueSource(booleans = {false, true})
    void aWriteInsideATransactionIsStillRefused(boolean simple) throws Exception {
        try (Connection conn = connect(simple);
                Statement statement = conn.createStatement()) {
            conn.setAutoCommit(false);
            assertThat((Object) catchThrowableOfType(
                            SQLException.class,
                            () -> statement.executeUpdate("INSERT INTO user_volume (user_id, total) VALUES ('x', 1)")))
                    .isNotNull();
            conn.rollback();
            assertThat(total(statement)).isEqualTo(300L);
        }
    }

    private static long total(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT total FROM user_volume")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
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
