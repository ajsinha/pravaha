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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.PostgreSQLContainer;

import com.ash.messaging.pravaha.api.plugin.PluginContext;

/**
 * One real PostgreSQL 16 with {@code wal_level=logical} for every container test in this module,
 * started on first use and stopped with the JVM (Testcontainers' reaper). A server of its own, never
 * one already running on the machine: every table, slot and publication a test makes is named
 * uniquely, so tests share the server and nothing else.
 */
final class PgServer {

    private static final AtomicInteger NAMES = new AtomicInteger();

    private static @Nullable PostgreSQLContainer<?> logical;

    private PgServer() {}

    static synchronized PostgreSQLContainer<?> container() {
        if (logical == null) {
            PostgreSQLContainer<?> started = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withCommand(
                            "postgres",
                            "-c",
                            "fsync=off",
                            "-c",
                            "wal_level=logical",
                            "-c",
                            "max_replication_slots=40",
                            "-c",
                            "max_wal_senders=40",
                            "-c",
                            "wal_sender_timeout=60s")
                    .withStartupTimeout(Duration.ofMinutes(2));
            started.start();
            logical = started;
        }
        return logical;
    }

    static String url() {
        return container().getJdbcUrl();
    }

    static Connection connect() throws SQLException {
        PostgreSQLContainer<?> server = container();
        return DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword());
    }

    /** A name no other test has used, for a table and its slot and publication. */
    static String unique(String prefix) {
        return prefix + "_" + NAMES.incrementAndGet() + "_" + (System.nanoTime() % 100_000);
    }

    static void sql(String... statements) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot run " + String.join("; ", statements) + ": " + e.getMessage(), e);
        }
    }

    /** Several statements as one transaction. */
    static void transaction(String... statements) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot run " + String.join("; ", statements) + ": " + e.getMessage(), e);
        }
    }

    static @Nullable String scalar(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot run " + sql + ": " + e.getMessage(), e);
        }
    }

    /** The slot's confirmed position, as PostgreSQL reports it. */
    static long confirmedFlush(String slot) {
        String lsn =
                scalar("SELECT confirmed_flush_lsn::text FROM pg_replication_slots WHERE slot_name = '" + slot + "'");
        return lsn == null ? -1L : CdcOffset.parseLsn(lsn);
    }

    static void dropSlotQuietly(String slot) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                sql("SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = '" + slot
                        + "'");
                return;
            } catch (IllegalStateException stillActive) {
                sleep(200);
            }
        }
    }

    /** The options a test binding starts from: this server, this table, a slot and publication named after it. */
    static Map<String, String> options(String table) {
        PostgreSQLContainer<?> server = container();
        Map<String, String> options = new HashMap<>();
        options.put("url", server.getJdbcUrl());
        options.put("user", server.getUsername());
        options.put("password", server.getPassword());
        options.put("table", "public." + table);
        options.put("slot", table);
        options.put("publication", table);
        options.put("start.timeout", "20s");
        return options;
    }

    record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    static PostgresCdcSourcePlugin open(Map<String, String> options) {
        PostgresCdcSourcePlugin plugin = new PostgresCdcSourcePlugin();
        plugin.configure(new Ctx("cdc", options));
        plugin.open();
        return plugin;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
