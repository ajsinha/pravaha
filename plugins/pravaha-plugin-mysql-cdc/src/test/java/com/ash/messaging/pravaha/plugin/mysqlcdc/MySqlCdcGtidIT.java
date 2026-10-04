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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MYC-2 against a real MySQL 8 primary and a GTID replica of it: a checkpoint read from the primary
 * resumes on the replica -- a failover -- with exactly the changes after it, and a checkpoint the
 * replica has not caught up with is refused by name.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class MySqlCdcGtidIT {

    private static Network network;
    private static MySQLContainer<?> primary;
    private static MySQLContainer<?> replica;

    @BeforeAll
    static void start() throws SQLException {
        network = Network.newNetwork();
        primary = server("primary", 1);
        replica = server("replica", 2);
        primary.start();
        replica.start();
        // Each server's own start-up wrote transactions of its own (the test user, the database):
        // the replica is told it has the primary's, and forgets its own, before it replicates.
        String primaryStart = single(primary, "SELECT @@GLOBAL.gtid_executed").replaceAll("\\s", "");
        sql(replica, "RESET MASTER", "SET GLOBAL gtid_purged = '" + primaryStart + "'");
        sql(
                replica,
                "CHANGE REPLICATION SOURCE TO SOURCE_HOST='primary', SOURCE_PORT=3306, SOURCE_USER='repl', "
                        + "SOURCE_PASSWORD='repl-secret', SOURCE_AUTO_POSITION=1, SOURCE_CONNECT_RETRY=1",
                "START REPLICA");
        sql(
                primary,
                "CREATE USER 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'repl-secret'",
                "GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%'",
                "CREATE USER 'cdc'@'%' IDENTIFIED BY 'cdc-secret'",
                "GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc'@'%'",
                "GRANT SELECT ON shop.* TO 'cdc'@'%'",
                "CREATE TABLE shop.orders (id BIGINT PRIMARY KEY, status VARCHAR(16) NOT NULL)");
        awaitReplica();
    }

    private static MySQLContainer<?> server(String alias, int serverId) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("shop")
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withCommand(
                        "--server-id=" + serverId, "--gtid-mode=ON", "--enforce-gtid-consistency=ON", "--log-bin=bin")
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    @AfterAll
    static void stop() {
        if (replica != null) {
            replica.stop();
        }
        if (primary != null) {
            primary.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static void sql(MySQLContainer<?> server, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(server.getJdbcUrl(), "root", server.getPassword());
                Statement statement = connection.createStatement()) {
            for (String each : statements) {
                statement.execute(each);
            }
        }
    }

    private static String single(MySQLContainer<?> server, String query) throws SQLException {
        try (Connection connection = DriverManager.getConnection(server.getJdbcUrl(), "root", server.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** Waits until the replica has executed everything the primary has. */
    private static void awaitReplica() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            String executed = single(primary, "SELECT @@GLOBAL.gtid_executed");
            if ("1".equals(single(replica, "SELECT GTID_SUBSET('" + executed + "', @@GLOBAL.gtid_executed)"))) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the replica did not catch up: "
                        + single(replica, "SELECT @@GLOBAL.gtid_executed") + " of " + executed);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static MySqlCdcSourcePlugin plugin(MySQLContainer<?> server) {
        Map<String, String> config = new HashMap<>();
        config.put("host", server.getHost());
        config.put("port", Integer.toString(server.getMappedPort(3306)));
        config.put("user", "cdc");
        config.put("password", "cdc-secret");
        config.put("table", "shop.orders");
        config.put("start.timeout", "20s");
        MySqlCdcSourcePlugin plugin = new MySqlCdcSourcePlugin();
        plugin.configure(new TransactionAssemblerTest.Ctx("orders-cdc", config));
        plugin.open();
        return plugin;
    }

    private static PartitionReader reader(MySqlCdcSourcePlugin plugin, @Nullable SourceOffset from) {
        return plugin.createReader(new SourcePartition("s", 0, Map.of()), from);
    }

    @Test
    void aCheckpointReadFromThePrimaryResumesOnTheReplicaWithExactlyTheRest() throws SQLException {
        SourceOffset checkpoint;
        SourceOffset inside;
        MySqlCdcSourcePlugin onPrimary = plugin(primary);
        try {
            try (PartitionReader reader = reader(onPrimary, null)) {
                assertThat(reader.position().token()).as("gtid_mode is ON").startsWith("gtid=");
                sql(primary, "INSERT INTO shop.orders VALUES (1, 'new')", "INSERT INTO shop.orders VALUES (2, 'new')");
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(onPrimary.schema());
                sink.pollUntil(reader, 1024, 2);
                checkpoint = reader.position();
                assertThat(checkpoint.token()).startsWith("gtid=");
            }
            sql(
                    primary,
                    "INSERT INTO shop.orders VALUES (3, 'new')",
                    "UPDATE shop.orders SET status = 'paid' WHERE id = 1",
                    "INSERT INTO shop.orders VALUES (10, 'x'), (11, 'x'), (12, 'x')");
            // A checkpoint inside the last transaction, read from the primary too: a reader whose
            // polls have never had room for three rows takes that transaction in parts.
            SourceOffset middle;
            try (PartitionReader reader = reader(onPrimary, checkpoint)) {
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(onPrimary.schema());
                sink.pollUntil(reader, 3, 3);
                middle = reader.position();
            }
            try (PartitionReader reader = reader(onPrimary, middle)) {
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(onPrimary.schema());
                sink.pollUntil(reader, 2, 2);
                inside = reader.position();
            }
            assertThat(inside.token()).contains(";partial=2@");
        } finally {
            onPrimary.close();
        }
        awaitReplica();

        // The failover: the same checkpoints, against the replica.
        MySqlCdcSourcePlugin onReplica = plugin(replica);
        try {
            try (PartitionReader reader = reader(onReplica, checkpoint)) {
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(onReplica.schema());
                sink.pollUntil(reader, 1024, 6);
                assertThat(sink.texts())
                        .as("exactly what followed the checkpoint, once, from another server")
                        .containsExactly(
                                "+1 [3, new]", "-1 [1, new]", "+1 [1, paid]", "+1 [10, x]", "+1 [11, x]", "+1 [12, x]");
                sql(primary, "INSERT INTO shop.orders VALUES (4, 'after')");
                sink.pollUntil(reader, 1024, 7);
                assertThat(sink.texts().get(6)).isEqualTo("+1 [4, after]");
            }
            try (PartitionReader reader = reader(onReplica, inside)) {
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(onReplica.schema());
                sink.pollUntil(reader, 1024, 2);
                assertThat(sink.texts())
                        .as("the rest of the transaction the checkpoint was inside, then what followed it")
                        .containsExactly("+1 [12, x]", "+1 [4, after]");
            }
        } finally {
            onReplica.close();
        }
    }

    @Test
    void aCheckpointTheReplicaHasNotCaughtUpWithIsRefusedByName() throws SQLException {
        awaitReplica();
        sql(replica, "STOP REPLICA");
        try {
            SourceOffset ahead;
            MySqlCdcSourcePlugin onPrimary = plugin(primary);
            try (PartitionReader reader = reader(onPrimary, null)) {
                sql(primary, "INSERT INTO shop.orders VALUES (100, 'ahead')");
                MySqlCdcIT.Captured sink = new MySqlCdcIT.Captured(onPrimary.schema());
                sink.pollUntil(reader, 1024, 1);
                ahead = reader.position();
            } finally {
                onPrimary.close();
            }
            MySqlCdcSourcePlugin onReplica = plugin(replica);
            try {
                assertThatThrownBy(() -> reader(onReplica, ahead))
                        .isInstanceOf(PravahaException.class)
                        .hasMessageContaining("this server has not executed")
                        .extracting(e -> ((PravahaException) e).errorCode())
                        .isEqualTo(MySqlCdcErrors.RESUME_POINT_AHEAD);
            } finally {
                onReplica.close();
            }
        } finally {
            sql(replica, "START REPLICA");
        }
    }
}
