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
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.ConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A PostgreSQL as it ships: {@code wal_level=replica}, which cannot decode logically. The refusal
 * names the setting, the statement, and the restart -- the part people miss, because a reload
 * appears to work and changes nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class WalLevelRefusalTest {

    @Container
    private static final PostgreSQLContainer<?> STOCK = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void aServerWhoseWalLevelIsNotLogicalIsRefusedNamingTheFixAndTheRestart() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(STOCK.getJdbcUrl(), STOCK.getUsername(), STOCK.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY, tier TEXT NOT NULL)");
            statement.execute("ALTER TABLE customers REPLICA IDENTITY FULL");
        }
        Map<String, String> options = new HashMap<>();
        options.put("url", STOCK.getJdbcUrl());
        options.put("user", STOCK.getUsername());
        options.put("password", STOCK.getPassword());
        options.put("table", "customers");
        PostgresCdcSourcePlugin plugin = new PostgresCdcSourcePlugin();
        plugin.configure(new PgServer.Ctx("cdc", options));

        assertThatThrownBy(plugin::open)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5112")
                .hasMessageContaining("wal_level is 'replica'")
                .hasMessageContaining("ALTER SYSTEM SET wal_level = logical;")
                .hasMessageContaining("RESTART PostgreSQL");
        plugin.close();
        try (Connection connection =
                        DriverManager.getConnection(STOCK.getJdbcUrl(), STOCK.getUsername(), STOCK.getPassword());
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM pg_publication")) {
            rows.next();
            assertThat(rows.getInt(1))
                    .as("refused before creating anything on the server")
                    .isZero();
        }
    }
}
