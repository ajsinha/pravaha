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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A capture role that may replicate but may not create the publication the plugin creates for it.
 *
 * <p>{@code CREATE PUBLICATION ... FOR TABLE} needs the role to own the table AND hold CREATE on the
 * database. A role made to own the table, as the prerequisites said, still lacked the second, and the
 * statement's "permission denied for database" reached the operator as PRV-5111 -- a connection
 * failure -- after the table and schema checks had passed (DOC-PGCDC). Each case here is refused
 * before anything is created on the server, as PRV-5112 naming the statement that fixes it and the
 * way around it; and each fix is shown to work.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class PublicationPrivilegeRefusalTest {

    private static final String PASSWORD = "cdc-role-password";

    /** A replication role of its own, and a REPLICA IDENTITY FULL table; the role owns it when asked. */
    private static String[] roleAndTable(boolean ownsTable) {
        String role = PgServer.unique("cdc_role");
        String table = PgServer.unique("pubpriv");
        PgServer.sql(
                "CREATE ROLE " + role + " WITH LOGIN REPLICATION PASSWORD '" + PASSWORD + "'",
                "CREATE TABLE public." + table + " (id BIGINT PRIMARY KEY, tier TEXT NOT NULL)",
                "ALTER TABLE public." + table + " REPLICA IDENTITY FULL",
                ownsTable
                        ? "ALTER TABLE public." + table + " OWNER TO " + role
                        : "GRANT SELECT ON public." + table + " TO " + role);
        return new String[] {role, table};
    }

    private static Map<String, String> as(String role, String table) {
        Map<String, String> options = PgServer.options(table);
        options.put("user", role);
        options.put("password", PASSWORD);
        options.put("drop.slot.on.close", "true");
        return options;
    }

    private static String database() {
        return PgServer.scalar("SELECT current_database()");
    }

    private static void nothingCreatedFor(String table) {
        assertThat(PgServer.scalar("SELECT count(*) FROM pg_publication WHERE pubname = '" + table + "'"))
                .as("refused before a publication was created")
                .isEqualTo("0");
        assertThat(PgServer.scalar("SELECT count(*) FROM pg_replication_slots WHERE slot_name = '" + table + "'"))
                .as("refused before a slot was created")
                .isEqualTo("0");
    }

    @Test
    void anOwnerWithoutCreateOnTheDatabaseIsRefusedNamingTheGrant() {
        String[] rt = roleAndTable(true);
        String role = rt[0];
        String table = rt[1];

        assertThatThrownBy(() -> PgServer.open(as(role, table)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5112")
                .hasMessageContaining("no CREATE on database '" + database() + "'")
                .hasMessageContaining("GRANT CREATE ON DATABASE " + database() + " TO " + role + ";")
                .hasMessageContaining("create.publication: \"false\"")
                .hasMessageNotContaining("PRV-5111");
        nothingCreatedFor(table);

        // The statement it named is the whole fix.
        PgServer.sql("GRANT CREATE ON DATABASE " + database() + " TO " + role);
        PgServer.open(as(role, table)).close();
        assertThat(PgServer.scalar("SELECT count(*) FROM pg_publication WHERE pubname = '" + table + "'"))
                .isEqualTo("1");
        PgServer.sql("REVOKE CREATE ON DATABASE " + database() + " FROM " + role);
    }

    @Test
    void aRoleThatDoesNotOwnTheTableIsRefusedNamingOwnershipAndTheGrant() {
        String[] rt = roleAndTable(false);
        String role = rt[0];
        String table = rt[1];

        assertThatThrownBy(() -> PgServer.open(as(role, table)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5112")
                .hasMessageContaining("needs the table's owner")
                .hasMessageContaining("ALTER TABLE public." + table + " OWNER TO " + role + ";")
                .hasMessageContaining("GRANT CREATE ON DATABASE " + database() + " TO " + role + ";")
                .hasMessageContaining("CREATE PUBLICATION " + table + " FOR TABLE public." + table + ";");
        nothingCreatedFor(table);
    }

    @Test
    void aPublicationMadeByARoleThatMayNeedsNeitherPrivilege() {
        String[] rt = roleAndTable(false);
        String role = rt[0];
        String table = rt[1];
        // The way around it the refusal names: an administrator creates the publication once.
        PgServer.sql("CREATE PUBLICATION " + table + " FOR TABLE public." + table);
        Map<String, String> options = as(role, table);
        options.put("create.publication", "false");

        PgServer.open(options).close();
    }
}
