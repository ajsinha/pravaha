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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MYC-1, MYC-3 and MYC-4 against a real MySQL 8 (file-and-offset positions, {@code gtid_mode} off):
 * an idle table's position follows the log past a purge, a user granted replication through a role
 * is accepted once the role is active at login, and an {@code ALTER} that keeps the column count is
 * refused rather than read with the old conversion.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class MySqlCdcFindingsIT {

    private static MySQLContainer<?> mysql;
    private static int tables;

    @BeforeAll
    static void start() throws SQLException {
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("shop").withStartupTimeout(Duration.ofMinutes(3));
        mysql.start();
        root(
                "CREATE USER 'cdc'@'%' IDENTIFIED BY 'cdc-secret'",
                "GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc'@'%'",
                "GRANT SELECT ON shop.* TO 'cdc'@'%'");
    }

    @AfterAll
    static void stop() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    static void root(String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String currentFile() throws SQLException {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SHOW MASTER STATUS")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static String newTable(String columns) throws SQLException {
        String table = "findings_" + (++tables);
        root("CREATE TABLE shop." + table + " (" + columns + ")");
        return table;
    }

    private static MySqlCdcSourcePlugin plugin(String table, String user, String password) {
        Map<String, String> config = new HashMap<>();
        config.put("host", mysql.getHost());
        config.put("port", Integer.toString(mysql.getMappedPort(3306)));
        config.put("user", user);
        config.put("password", password);
        config.put("table", "shop." + table);
        config.put("start.timeout", "20s");
        config.put("heartbeat.interval", "1s");
        MySqlCdcSourcePlugin plugin = new MySqlCdcSourcePlugin();
        plugin.configure(new TransactionAssemblerTest.Ctx(table + "-cdc", config));
        plugin.open();
        return plugin;
    }

    private static PartitionReader reader(MySqlCdcSourcePlugin plugin, SourceOffset from) {
        return plugin.createReader(new SourcePartition("s", 0, Map.of()), from);
    }

    /** Polls until the position satisfies {@code done}, delivering no rows on the way. */
    private static SourceOffset pollUntilPosition(
            PartitionReader reader, MySqlCdcIT.Captured sink, java.util.function.Predicate<String> done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!done.test(reader.position().token())) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "the position stayed at " + reader.position().token());
            }
            reader.poll(sink, 1024);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        return reader.position();
    }

    @Test
    void anIdleTablesPositionFollowsTheLogSoAPurgeOfFilesItHadNothingInDoesNotRefuseItsRestart() throws SQLException {
        // MYC-1.
        String table = newTable("id BIGINT PRIMARY KEY, tier VARCHAR(16) NOT NULL");
        MySqlCdcSourcePlugin plugin = plugin(table, "cdc", "cdc-secret");
        try {
            SourceOffset checkpoint;
            try (PartitionReader first = reader(plugin, null)) {
                root("INSERT INTO shop." + table + " VALUES (1, 'a')");
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(plugin.schema());
                sink.pollUntil(first, 1024, 1);
                String before = first.position().token();
                root("FLUSH BINARY LOGS");
                String rotatedTo = currentFile();
                assertThat(before).doesNotContain(rotatedTo);
                // Nothing is written anywhere: only the rotation, then the server's heartbeats.
                pollUntilPosition(first, sink, token -> token.contains(rotatedTo + ":"));
                checkpoint = pollUntilPosition(
                        first, sink, token -> Long.parseLong(token.substring(token.lastIndexOf(':') + 1)) > 4);
                assertThat(sink.rows()).as("no row came with the positions").hasSize(1);
            }
            assertThat(checkpoint.token())
                    .as("a heartbeat moved the position past the new file's header")
                    .startsWith("binlog=" + currentFile() + ":");
            root("PURGE BINARY LOGS TO '" + currentFile() + "'");
            try (PartitionReader second = reader(plugin, checkpoint)) {
                root("INSERT INTO shop." + table + " VALUES (2, 'b')");
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(plugin.schema());
                sink.pollUntil(second, 1024, 1);
                assertThat(sink.texts()).containsExactly("+1 [2, b]");
            }
        } finally {
            plugin.close();
        }
    }

    @Test
    void aUserGrantedReplicationThroughARoleIsAcceptedOnceTheRoleIsActiveAtLogin() throws SQLException {
        // MYC-3.
        String table = newTable("id BIGINT PRIMARY KEY, tier VARCHAR(16) NOT NULL");
        root(
                "CREATE ROLE IF NOT EXISTS 'replicator'",
                "GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'replicator'",
                "CREATE USER 'viarole'@'%' IDENTIFIED BY 'role-secret'",
                "GRANT 'replicator' TO 'viarole'@'%'",
                "GRANT SELECT ON shop.* TO 'viarole'@'%'");
        assertThatThrownBy(() -> plugin(table, "viarole", "role-secret"))
                .as("granted, but not active when the plugin's connections log in")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("lacks REPLICATION SLAVE and REPLICATION CLIENT")
                .hasMessageContaining("`replicator`@`%`")
                .hasMessageContaining("SET DEFAULT ROLE ALL TO 'viarole'@'%';")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(MySqlCdcErrors.NOT_CAPTURABLE);

        root("SET DEFAULT ROLE ALL TO 'viarole'@'%'");
        MySqlCdcSourcePlugin plugin = plugin(table, "viarole", "role-secret");
        try (PartitionReader reader = reader(plugin, null)) {
            root("INSERT INTO shop." + table + " VALUES (7, 'role')");
            MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(plugin.schema());
            sink.pollUntil(reader, 1024, 1);
            assertThat(sink.texts()).containsExactly("+1 [7, role]");
        } finally {
            plugin.close();
        }
    }

    @Test
    void aTypeChangeThatKeepsTheColumnCountIsRefusedNotReadWithTheOldConversion() throws SQLException {
        // MYC-4, live: an ALTER behind a comment, then a value SMALLINT cannot hold.
        String table = newTable("id BIGINT PRIMARY KEY, n SMALLINT NOT NULL");
        MySqlCdcSourcePlugin live = plugin(table, "cdc", "cdc-secret");
        try (PartitionReader reader = reader(live, null)) {
            root(
                    "/* migration 42 */ ALTER TABLE shop." + table + " MODIFY n INT NOT NULL",
                    "INSERT INTO shop." + table + " VALUES (1, 40000)");
            MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(live.schema());
            assertThatThrownBy(() -> sink.pollUntil(reader, 1024, 1))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("ALTER of shop." + table)
                    .extracting(e -> ((PravahaException) e).errorCode())
                    .isEqualTo(MySqlCdcErrors.UNREPRESENTABLE_CHANGE);
            assertThat(sink.rows()).as("40000 was never read as a SMALLINT").isEmpty();
        } finally {
            live.close();
        }

        // Restored from before an ALTER the plugin opened after: its rows carry the old types.
        String restored = newTable("id BIGINT PRIMARY KEY, n SMALLINT NOT NULL");
        MySqlCdcSourcePlugin before = plugin(restored, "cdc", "cdc-secret");
        SourceOffset checkpoint;
        try (PartitionReader reader = reader(before, null)) {
            root("INSERT INTO shop." + restored + " VALUES (1, 5)");
            MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(before.schema());
            sink.pollUntil(reader, 1024, 1);
            checkpoint = reader.position();
        } finally {
            before.close();
        }
        root(
                "INSERT INTO shop." + restored + " VALUES (2, -5)",
                "DELETE FROM shop." + restored + " WHERE id = 2",
                "ALTER TABLE shop." + restored + " MODIFY n INT UNSIGNED NOT NULL");
        MySqlCdcSourcePlugin after = plugin(restored, "cdc", "cdc-secret");
        try (PartitionReader reader = reader(after, checkpoint)) {
            MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(after.schema());
            assertThatThrownBy(() -> sink.pollUntil(reader, 1024, 1))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("column 'n' (int unsigned) arrives in the binlog as MySQL type 2 (SMALLINT)")
                    .extracting(e -> ((PravahaException) e).errorCode())
                    .isEqualTo(MySqlCdcErrors.UNREPRESENTABLE_CHANGE);
            assertThat(sink.texts()).as("-5 was never read as 4294967291").isEmpty();
        } finally {
            after.close();
        }
    }

    @Test
    void underFullRowMetadataASignednessChangeIsRefusedToo() throws SQLException {
        // MYC-4: SMALLINT to SMALLINT UNSIGNED keeps the binlog type; FULL metadata says which it is.
        String table = newTable("id BIGINT PRIMARY KEY, n SMALLINT NOT NULL");
        root("SET GLOBAL binlog_row_metadata = 'FULL'");
        try {
            MySqlCdcSourcePlugin before = plugin(table, "cdc", "cdc-secret");
            SourceOffset checkpoint;
            try (PartitionReader reader = reader(before, null)) {
                root("INSERT INTO shop." + table + " VALUES (1, 5)");
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(before.schema());
                sink.pollUntil(reader, 1024, 1);
                checkpoint = reader.position();
            } finally {
                before.close();
            }
            root(
                    "INSERT INTO shop." + table + " VALUES (2, -5)",
                    "DELETE FROM shop." + table + " WHERE id = 2",
                    "ALTER TABLE shop." + table + " MODIFY n SMALLINT UNSIGNED NOT NULL");
            MySqlCdcSourcePlugin after = plugin(table, "cdc", "cdc-secret");
            try (PartitionReader reader = reader(after, checkpoint)) {
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(after.schema());
                assertThatThrownBy(() -> sink.pollUntil(reader, 1024, 1))
                        .hasMessageContaining("column 'n' (smallint unsigned) is signed in the binlog");
            } finally {
                after.close();
            }
        } finally {
            root("SET GLOBAL binlog_row_metadata = 'MINIMAL'");
        }
    }
}
