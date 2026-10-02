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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CDCPRIVCODE-1: a capture role without the {@code REPLICATION} attribute is the prerequisite's code,
 * {@code PRV-5112}, naming {@code ALTER ROLE ... REPLICATION}. It was {@code PRV-5118} with advice about
 * transactions left idle and {@code max_replication_slots} (or {@code PRV-5111}, "cannot prepare ...
 * for capture", when the plugin created the slot) -- the PostgreSQL detail right, the code and the
 * remedy not.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ReplicationPrivilegeRefusalTest {

    private static final String PASSWORD = "cdc-role-password";

    /** A role that may log in and owns a capturable table, its publication made by an administrator. */
    private static String[] roleWithoutReplication() {
        String role = PgServer.unique("norepl");
        String table = PgServer.unique("noreplt");
        PgServer.sql(
                "CREATE ROLE " + role + " WITH LOGIN NOREPLICATION PASSWORD '" + PASSWORD + "'",
                "CREATE TABLE public." + table + " (id BIGINT PRIMARY KEY, tier TEXT NOT NULL)",
                "ALTER TABLE public." + table + " REPLICA IDENTITY FULL",
                "ALTER TABLE public." + table + " OWNER TO " + role,
                "CREATE PUBLICATION " + table + " FOR TABLE public." + table);
        return new String[] {role, table};
    }

    private static Map<String, String> as(String role, String table) {
        Map<String, String> options = PgServer.options(table);
        options.put("user", role);
        options.put("password", PASSWORD);
        options.put("create.publication", "false");
        return options;
    }

    @Test
    void creatingTheSlotWithoutReplicationIsThePrerequisitesCodeNamingAlterRole() {
        String[] rt = roleWithoutReplication();
        assertThatThrownBy(() -> PgServer.open(as(rt[0], rt[1])))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5112")
                .hasMessageContaining("ALTER ROLE " + rt[0] + " REPLICATION;")
                .hasMessageNotContaining("PRV-5111");
    }

    @Test
    void startingTheSnapshotOrTheStreamWithoutReplicationIsThePrerequisitesCodeToo() {
        String[] rt = roleWithoutReplication();
        String role = rt[0];
        String table = rt[1];
        // An administrator made the slot, so opening passes; the replication connection is refused.
        PgServer.sql("SELECT pg_create_logical_replication_slot('" + table + "', 'pgoutput')");
        try {
            for (String mode : new String[] {"initial", "never"}) {
                Map<String, String> options = as(role, table);
                options.put("create.slot", "false");
                options.put("snapshot.mode", mode);
                PostgresCdcSourcePlugin plugin = PgServer.open(options);
                try {
                    assertThatThrownBy(() -> plugin.createReader(new SourcePartition(table, 0, Map.of()), null))
                            .as("snapshot.mode %s", mode)
                            .isInstanceOf(ConfigurationException.class)
                            .hasMessageContaining("PRV-5112")
                            .hasMessageContaining("ALTER ROLE " + role + " REPLICATION;")
                            .hasMessageNotContaining("PRV-5118")
                            .hasMessageNotContaining("idle in a transaction");
                } finally {
                    plugin.close();
                }
            }
        } finally {
            PgServer.dropSlotQuietly(table);
        }
    }
}
