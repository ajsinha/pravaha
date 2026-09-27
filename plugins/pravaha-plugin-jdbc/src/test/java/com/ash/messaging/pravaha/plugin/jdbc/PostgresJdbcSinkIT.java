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
package com.ash.messaging.pravaha.plugin.jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The JDBC sink against a real PostgreSQL: {@code INSERT ... ON CONFLICT}, lower-case identifiers,
 * PostgreSQL's own types, and the staging-table transaction protocol on a database that aborts a
 * whole transaction at its first error.
 *
 * <p>Skipped rather than failed when docker is unavailable, like {@link PostgresJdbcIT}.
 */
@Timeout(300)
class PostgresJdbcSinkIT {

    private static PostgreSQLContainer<?> postgres;
    private static Connection admin;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private final SinkTestRows rows = new SinkTestRows();
    private final List<JdbcSinkPlugin> opened = new ArrayList<>();

    @BeforeAll
    static void startDatabase() throws SQLException {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the PostgreSQL tests need a real database")
                .isTrue();
        // Prepared transactions allowed, for commit.mode: prepared (C1); nothing else here uses them.
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "max_prepared_transactions=10");
        postgres.start();
        admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void stopDatabase() throws SQLException {
        if (admin != null) {
            admin.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void createTables() throws SQLException {
        execute("DROP TABLE IF EXISTS totals");
        execute("DROP TABLE IF EXISTS events");
        execute("DROP TABLE IF EXISTS " + JdbcSinkPlugin.DEFAULT_STAGING_TABLE);
        execute("CREATE TABLE totals (user_id VARCHAR(32) NOT NULL PRIMARY KEY, total BIGINT, "
                + "amount NUMERIC(12,2), seen_at TIMESTAMPTZ, local_at TIMESTAMP, flag BOOLEAN, raw BYTEA)");
        execute("CREATE TABLE events (user_id TEXT NOT NULL, amount BIGINT)");
    }

    @AfterEach
    void closeSinks() {
        opened.forEach(JdbcSinkPlugin::close);
        rows.close();
    }

    private static final String TOTALS =
            "user_id:STRING,total:INT64,amount:DECIMAL(12,2)?,seen_at:TIMESTAMP?,local_at:TIMESTAMP?,flag:BOOLEAN?,"
                    + "raw:BYTES?";

    @Test
    void onConflictUpsertsByKeyAndARetractionDeletes() throws SQLException {
        JdbcSinkPlugin sink = open("totals", TOTALS, Map.of("key.columns", "USER_ID", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();
        assertThat(sink.dialect()).isEqualTo(JdbcDialect.POSTGRESQL);
        long nanos =
                LocalDateTime.of(2026, 9, 19, 10, 15, 30, 123_456_000).toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L
                        + 123_456_000L;

        sink.write(List.of(
                rows.row(schema, 1, "u1", 300L, new BigDecimal("1.25"), nanos, nanos, true, new byte[] {9}),
                rows.row(schema, 1, "u2", 50L, null, null, null, null, null)));
        sink.write(List.of(
                rows.row(schema, -1, "u1", 300L, new BigDecimal("1.25"), nanos, nanos, true, new byte[] {9}),
                rows.row(schema, 1, "u1", 375L, new BigDecimal("2.50"), nanos, nanos, false, new byte[] {8}),
                rows.row(schema, -1, "u2", 50L, null, null, null, null, null)));

        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT * FROM totals")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("user_id")).isEqualTo("u1");
            assertThat(rs.getLong("total")).isEqualTo(375L);
            assertThat(rs.getBigDecimal("amount")).isEqualByComparingTo("2.50");
            assertThat(rs.getObject("seen_at", OffsetDateTime.class).toInstant())
                    .isEqualTo(LocalDateTime.of(2026, 9, 19, 10, 15, 30, 123_456_000)
                            .toInstant(ZoneOffset.UTC));
            assertThat(rs.getObject("local_at", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 9, 19, 10, 15, 30, 123_456_000));
            assertThat(rs.getBoolean("flag")).isFalse();
            assertThat(rs.getBytes("raw")).containsExactly(8);
            assertThat(rs.next()).as("the retraction deleted u2").isFalse();
        }
    }

    @Test
    void aTableWithNoUniqueIndexOnTheKeyIsRefusedBeforeOnConflictCouldFail() {
        execute("CREATE TABLE IF NOT EXISTS unkeyed (user_id TEXT NOT NULL, total BIGINT)");
        try {
            assertThatThrownBy(() -> open("unkeyed", "user_id:STRING,total:INT64", Map.of("key.columns", "user_id")))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e ->
                            assertThat(((PravahaException) e).errorCode()).isEqualTo(JdbcErrors.SINK_TABLE_MISMATCH))
                    .hasMessageContaining("no primary key or unique index");
        } finally {
            execute("DROP TABLE unkeyed");
        }
    }

    /** The staging protocol, with a crash between prepare and commit, on PostgreSQL. */
    @Test
    void aPreparedTransactionSurvivesTheProcessAndCommitsOnceWherever() throws SQLException {
        String spec = "user_id:STRING,amount:INT64";
        JdbcSinkPlugin before = open("events", spec, Map.of("mode", "append"));
        StreamSchema schema = before.schema().orElseThrow();
        before.beginTransaction(1);
        before.write(List.of(rows.row(schema, 1, "u1", 300L), rows.row(schema, 1, "u2", 50L)));
        String recorded = before.prepare(1);
        before.beginTransaction(2);
        before.write(List.of(rows.row(schema, 1, "u3", 7L)));
        before.close();
        assertThat(events()).isEmpty();

        JdbcSinkPlugin after = open("events", spec, Map.of("mode", "append"));
        after.commit(recorded);
        after.commit(recorded);
        after.abortAfter(1);
        assertThat(events()).containsExactly("u1|300", "u2|50");

        after.beginTransaction(2);
        after.write(List.of(rows.row(schema, 1, "u3", 7L)));
        after.commit(after.prepare(2));
        assertThat(events()).containsExactly("u1|300", "u2|50", "u3|7");
    }

    @Test
    void aTransactionalUpsertAppearsOnlyAtCommit() throws SQLException {
        JdbcSinkPlugin sink = open("totals", TOTALS, Map.of("key.columns", "user_id"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(1);
        sink.write(List.of(rows.row(schema, 1, "u1", 1L, null, null, null, null, null)));
        sink.write(List.of(
                rows.row(schema, -1, "u1", 1L, null, null, null, null, null),
                rows.row(schema, 1, "u1", 2L, null, null, null, null, null)));
        String handle = sink.prepare(1);
        assertThat(count("totals")).isZero();

        sink.commit(handle);
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT total FROM totals WHERE user_id = 'u1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(2L);
        }
    }

    @Test
    void preparedModeWritesOnceIntoTheTableAndSurvivesTheProcess() throws SQLException {
        String spec = "user_id:STRING,amount:INT64";
        Map<String, String> prepared = Map.of("mode", "append", "commit.mode", "prepared");
        JdbcSinkPlugin before = open("events", spec, prepared);
        StreamSchema schema = before.schema().orElseThrow();
        before.beginTransaction(1);
        before.write(List.of(rows.row(schema, 1, "u1", 300L), rows.row(schema, 1, "u2", 50L)));
        String recorded = before.prepare(1);
        before.beginTransaction(2);
        before.write(List.of(rows.row(schema, 1, "u3", 7L)));
        String lost = before.prepare(2);
        before.close();
        assertThat(events()).as("prepared, not committed: invisible").isEmpty();
        assertThat(count("pg_prepared_xacts"))
                .as("both prepared transactions outlive the process")
                .isEqualTo(2);

        JdbcSinkPlugin after = open("events", spec, prepared);
        after.commit(recorded);
        after.commit(recorded);
        after.abortAfter(1);
        assertThat(events())
                .as("committed once; the one past the checkpoint rolled back")
                .containsExactly("u1|300", "u2|50");
        assertThat(count("pg_prepared_xacts")).as("nothing left holding locks").isZero();
        after.commit(lost);
        assertThat(events()).containsExactly("u1|300", "u2|50");
        try (Statement statement = admin.createStatement();
                ResultSet rs =
                        statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '"
                                + JdbcSinkPlugin.DEFAULT_STAGING_TABLE + "'")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("no staging table in prepared mode: each row is written once")
                    .isZero();
        }
    }

    @Test
    void preparedModeUpsertsAppearOnlyAtCommit() throws SQLException {
        JdbcSinkPlugin sink = open("totals", TOTALS, Map.of("key.columns", "user_id", "commit.mode", "prepared"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(1);
        sink.write(List.of(rows.row(schema, 1, "u1", 1L, null, null, null, null, null)));
        sink.write(List.of(
                rows.row(schema, -1, "u1", 1L, null, null, null, null, null),
                rows.row(schema, 1, "u1", 2L, null, null, null, null, null)));
        String handle = sink.prepare(1);
        assertThat(count("totals")).isZero();
        sink.commit(handle);
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT total FROM totals WHERE user_id = 'u1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(2L);
        }
    }

    private JdbcSinkPlugin open(String table, String spec, Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "url", postgres.getJdbcUrl(),
                "user", postgres.getUsername(),
                "password", postgres.getPassword(),
                "table", table,
                "schema", spec));
        config.putAll(extra);
        JdbcSinkPlugin sink = new JdbcSinkPlugin();
        sink.configure(new Ctx(table + "_sink", config));
        sink.open();
        opened.add(sink);
        return sink;
    }

    private static void execute(String sql) {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(String table) throws SQLException {
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> events() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT user_id, amount FROM events ORDER BY user_id")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "|" + rs.getLong(2));
            }
        }
        return out;
    }
}
