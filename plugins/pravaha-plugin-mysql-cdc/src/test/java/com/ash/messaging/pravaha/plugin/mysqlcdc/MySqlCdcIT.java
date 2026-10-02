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

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code mysql-cdc} against a real MySQL 8 with its default row-based binary log: every row here was
 * read from the binlog by this plugin, registered as a replica under a user holding only {@code
 * REPLICATION SLAVE}, {@code REPLICATION CLIENT} and {@code SELECT}.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class MySqlCdcIT {

    private static MySQLContainer<?> mysql;
    private static int tables;

    @BeforeAll
    static void start() throws SQLException {
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("shop").withStartupTimeout(Duration.ofMinutes(3));
        mysql.start();
        root(
                "CREATE USER 'cdc'@'%' IDENTIFIED BY 'cdc-secret'",
                "GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc'@'%'",
                "GRANT SELECT ON shop.* TO 'cdc'@'%'",
                "CREATE USER 'plain'@'%' IDENTIFIED BY 'plain-secret'",
                "GRANT SELECT ON shop.* TO 'plain'@'%'");
    }

    @AfterAll
    static void stop() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    private static void root(String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String newTable() throws SQLException {
        String table = "customers_" + ++tables;
        root("CREATE TABLE shop." + table + " (id BIGINT PRIMARY KEY, tier VARCHAR(16) NOT NULL, "
                + "region VARCHAR(8), credit DECIMAL(10,2), seen DATETIME(6))");
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
        MySqlCdcSourcePlugin plugin = new MySqlCdcSourcePlugin();
        plugin.configure(new TransactionAssemblerTest.Ctx(table + "-cdc", config));
        plugin.open();
        return plugin;
    }

    private static PartitionReader reader(MySqlCdcSourcePlugin plugin, SourceOffset from) {
        return plugin.createReader(new SourcePartition("s", 0, Map.of()), from);
    }

    @Test
    void anInsertIsPlusOneAnUpdateIsMinusOnePlusOneAndADeleteIsMinusOneOfTheWholeRow() throws SQLException {
        String table = newTable();
        MySqlCdcSourcePlugin plugin = plugin(table, "cdc", "cdc-secret");
        try (PartitionReader reader = reader(plugin, null)) {
            root(
                    "INSERT INTO shop." + table + " VALUES (42, 'silver', 'EU', 10.50, '2026-09-27 10:11:12.5')",
                    "UPDATE shop." + table + " SET tier = 'gold' WHERE id = 42",
                    "DELETE FROM shop." + table + " WHERE id = 42");
            Captured sink = new Captured(plugin.schema());
            sink.pollUntil(reader, 1024, 4);
            String silver = " [42, silver, EU, 1050, 1790503872500000000]";
            String gold = " [42, gold, EU, 1050, 1790503872500000000]";
            assertThat(sink.texts()).containsExactly("+1" + silver, "-1" + silver, "+1" + gold, "-1" + gold);
            assertThat(plugin.health().state().name()).isEqualTo("HEALTHY");
        } finally {
            plugin.close();
        }
    }

    @Test
    void aRestartFromACheckpointOffsetLosesNothingAndRepeatsNothing() throws SQLException {
        String table = newTable();
        MySqlCdcSourcePlugin plugin = plugin(table, "cdc", "cdc-secret");
        try {
            SourceOffset checkpoint;
            try (PartitionReader first = reader(plugin, null)) {
                root(
                        "INSERT INTO shop." + table + " (id, tier) VALUES (1, 'a')",
                        "INSERT INTO shop." + table + " (id, tier) VALUES (2, 'b')");
                Captured sink = new Captured(plugin.schema());
                sink.pollUntil(first, 1024, 2);
                checkpoint = first.position();
                // Written after the checkpoint, and after the first reader stopped reading.
                root(
                        "INSERT INTO shop." + table + " (id, tier) VALUES (3, 'c')",
                        "UPDATE shop." + table + " SET tier = 'z' WHERE id = 1");
            }
            SourceOffset afterSecond;
            try (PartitionReader second = reader(plugin, checkpoint)) {
                Captured sink = new Captured(plugin.schema());
                sink.pollUntil(second, 1024, 3);
                root("INSERT INTO shop." + table + " (id, tier) VALUES (4, 'd')");
                sink.pollUntil(second, 1024, 4);
                assertThat(sink.texts())
                        .as("exactly what followed the checkpoint, once")
                        .containsExactly(
                                "+1 [3, c, null, null, null]",
                                "-1 [1, a, null, null, null]",
                                "+1 [1, z, null, null, null]",
                                "+1 [4, d, null, null, null]");
                afterSecond = second.position();
            }

            // A transaction larger than any poll: a checkpoint inside it resumes with exactly the rest.
            SourceOffset inside;
            try (PartitionReader third = reader(plugin, afterSecond)) {
                root("INSERT INTO shop." + table + " (id, tier) VALUES (10,'x'),(11,'x'),(12,'x'),(13,'x'),(14,'x')");
                Captured sink = new Captured(plugin.schema());
                sink.pollUntil(third, 2, 2);
                inside = third.position();
            }
            assertThat(inside.token()).isEqualTo(afterSecond.token() + ";partial=2");
            try (PartitionReader fourth = reader(plugin, inside)) {
                Captured sink = new Captured(plugin.schema());
                sink.pollUntil(fourth, 1024, 3);
                assertThat(sink.rows()).extracting(row -> row.get(0)).containsExactly(12L, 13L, 14L);
                assertThat(fourth.position().token()).doesNotContain("partial");
            }
        } finally {
            plugin.close();
        }
    }

    @Test
    void aServerOrUserThatCannotSupportCaptureIsRefusedByName() throws SQLException {
        String table = newTable();
        assertThatThrownBy(() -> plugin(table, "plain", "plain-secret"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("lacks REPLICATION SLAVE and REPLICATION CLIENT")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(MySqlCdcErrors.NOT_CAPTURABLE);
        root("SET GLOBAL binlog_row_image = 'MINIMAL'");
        try {
            assertThatThrownBy(() -> plugin(table, "cdc", "cdc-secret"))
                    .hasMessageContaining("binlog_row_image is MINIMAL")
                    .hasMessageContaining("SET PERSIST binlog_row_image = 'FULL'");
        } finally {
            root("SET GLOBAL binlog_row_image = 'FULL'");
        }
        root("SET GLOBAL binlog_format = 'STATEMENT'");
        try {
            assertThatThrownBy(() -> plugin(table, "cdc", "cdc-secret"))
                    .hasMessageContaining("binlog_format is STATEMENT");
        } finally {
            root("SET GLOBAL binlog_format = 'ROW'");
        }
        MySqlCdcSourcePlugin plugin = plugin(table, "cdc", "cdc-secret");
        try {
            assertThatThrownBy(() -> reader(plugin, new SourceOffset("binlog=mysql-bin.000999:4")))
                    .hasMessageContaining("has been purged")
                    .extracting(e -> ((PravahaException) e).errorCode())
                    .isEqualTo(MySqlCdcErrors.RESUME_POINT_PURGED);
        } finally {
            plugin.close();
        }
    }

    /** Keeps each committed row as its weight and values. */
    static final class Captured implements PartitionReader.RecordSink {

        private final StreamSchema schema;
        private final List<Object[]> values = new ArrayList<>();
        private final List<Long> weights = new ArrayList<>();

        Captured(StreamSchema schema) {
            this.schema = schema;
        }

        List<List<Object>> rows() {
            return values.stream().map(Arrays::asList).toList();
        }

        List<String> texts() {
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                texts.add((weights.get(i) > 0 ? "+" : "") + weights.get(i) + " " + Arrays.toString(values.get(i)));
            }
            return texts;
        }

        void pollUntil(PartitionReader reader, int max, int rows) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (values.size() < rows) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("timed out waiting for " + rows + " rows; captured " + texts());
                }
                if (reader.poll(this, max) == 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            }
            assertThat(values).as("no more than asked for: " + texts()).hasSize(rows);
        }

        @Override
        public RowWriter beginRow() {
            Object[] row = new Object[schema.fieldCount()];
            long[] weight = new long[1];
            return new RowWriter() {
                private RowWriter set(int ordinal, Object value) {
                    row[ordinal] = value;
                    return this;
                }

                @Override
                public StreamSchema schema() {
                    return schema;
                }

                @Override
                public RowWriter setNull(int ordinal) {
                    return set(ordinal, null);
                }

                @Override
                public RowWriter setBoolean(int ordinal, boolean value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setByte(int ordinal, byte value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setShort(int ordinal, short value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setInt(int ordinal, int value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setLong(int ordinal, long value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setFloat(int ordinal, float value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setDouble(int ordinal, double value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setDecimal(int ordinal, long high, long low) {
                    return set(
                            ordinal,
                            BigInteger.valueOf(high)
                                    .shiftLeft(64)
                                    .add(BigInteger.valueOf(low)
                                            .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))));
                }

                @Override
                public RowWriter setBytes(int ordinal, byte[] value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter setString(int ordinal, String value) {
                    return set(ordinal, value);
                }

                @Override
                public RowWriter weight(long value) {
                    weight[0] = value;
                    return this;
                }

                @Override
                public RowWriter eventTimestampNanos(long nanos) {
                    return this;
                }

                @Override
                public RowWriter sequence(long sequence) {
                    return this;
                }

                @Override
                public int commit() {
                    values.add(row);
                    weights.add(weight[0]);
                    return 1;
                }

                @Override
                public void abort() {
                    // Nothing kept until commit.
                }
            };
        }
    }
}
