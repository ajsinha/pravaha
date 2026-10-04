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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.shyiko.mysql.binlog.network.ServerException;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What open checks before anything is read, each refusal naming the statement that fixes it
 * ({@code PRV-5152}), and the binlog positions a reader starts from.
 *
 * <p>The replication privileges are checked as the plugin's own connections will hold them: the
 * user's grants together with those of every role active at login -- its default roles, or all of
 * them under {@code activate_all_roles_on_login} (MYC-3). A role that grants them but is not active
 * at login is named in the refusal with the statement that activates it.
 */
final class MySqlPreflight {

    private MySqlPreflight() {}

    /**
     * Refuses a server or user that cannot support capture; returns whether the server runs with
     * {@code gtid_mode = ON}, in which case a new registration's positions are GTID sets.
     */
    static boolean check(MySqlClient client, MySqlCdcOptions options) throws IOException {
        Map<String, String> variables = new HashMap<>();
        for (String[] row : client.query("SHOW GLOBAL VARIABLES WHERE Variable_name IN ('log_bin', 'binlog_format', "
                + "'binlog_row_image', 'binlog_transaction_compression', 'gtid_mode')")) {
            variables.put(row[0].toLowerCase(Locale.ROOT), row[1].toUpperCase(Locale.ROOT));
        }
        require(
                options,
                "ON".equals(variables.get("log_bin")),
                "binary logging is off (log_bin = " + variables.get("log_bin")
                        + "). Start the server with log-bin set, the default since MySQL 8.0");
        require(
                options,
                "ROW".equals(variables.get("binlog_format")),
                "binlog_format is " + variables.get("binlog_format") + ", and change capture needs every changed row "
                        + "in the log: SET PERSIST binlog_format = 'ROW'; (sessions already connected keep the old "
                        + "format until they reconnect)");
        require(
                options,
                "FULL".equals(variables.get("binlog_row_image")),
                "binlog_row_image is " + variables.get("binlog_row_image") + ", so a delete or an update would carry "
                        + "only part of the old row and its retraction would retract nothing: SET PERSIST "
                        + "binlog_row_image = 'FULL';");
        require(
                options,
                !"ON".equals(variables.get("binlog_transaction_compression")),
                "binlog_transaction_compression is ON, and mysql-cdc does not decompress transactions: SET PERSIST "
                        + "binlog_transaction_compression = OFF;");
        String activeRoles = activeRoles(client);
        boolean slave = false;
        boolean clientPrivilege = false;
        List<String> inactiveRoles = new ArrayList<>();
        for (String[] row : client.query(
                activeRoles.isEmpty() ? "SHOW GRANTS" : "SHOW GRANTS FOR CURRENT_USER() USING " + activeRoles)) {
            String grant = row[0].toUpperCase(Locale.ROOT);
            Matcher role = ROLE_GRANT.matcher(row[0]);
            if (role.matches()) {
                for (String granted : role.group(1).split(",", -1)) {
                    if (!activeRoles.contains(granted.strip())) {
                        inactiveRoles.add(granted.strip());
                    }
                }
            }
            if (!grant.contains(" ON *.* ")) {
                continue;
            }
            boolean all = grant.contains("ALL PRIVILEGES");
            slave |= all || grant.contains("REPLICATION SLAVE");
            clientPrivilege |= all || grant.contains("REPLICATION CLIENT");
        }
        require(
                options,
                slave && clientPrivilege,
                "user '" + options.user() + "' lacks " + (slave ? "" : "REPLICATION SLAVE")
                        + (slave || clientPrivilege ? "" : " and ") + (clientPrivilege ? "" : "REPLICATION CLIENT")
                        + ", needed to read the binary log, directly or through a role active at login"
                        + (activeRoles.isEmpty() ? "" : " (" + activeRoles + ")") + ": GRANT REPLICATION SLAVE, "
                        + "REPLICATION CLIENT ON *.* TO '" + options.user() + "'@'%';"
                        + (inactiveRoles.isEmpty()
                                ? ""
                                : " The user holds role(s) " + String.join(", ", inactiveRoles) + " that are not "
                                        + "active when it logs in, so their privileges do not count: SET DEFAULT "
                                        + "ROLE ALL TO '" + options.user() + "'@'%';"));
        return "ON".equals(variables.get("gtid_mode"));
    }

    /** {@code GRANT `role`@`host` TO ...}: a role granted to the user, with no ON clause. */
    private static final Pattern ROLE_GRANT =
            Pattern.compile("(?i)^GRANT\\s+(`[^`]*`@`[^`]*`(?:\\s*,\\s*`[^`]*`@`[^`]*`)*)\\s+TO\\s.*$");

    /**
     * The roles active in this session, which are the ones every connection of this user starts with,
     * as {@code SHOW GRANTS ... USING} takes them; empty when there are none, or on a server without
     * roles (before MySQL 8.0).
     */
    private static String activeRoles(MySqlClient client) throws IOException {
        String roles;
        try {
            roles = client.single("SELECT CURRENT_ROLE()").strip();
        } catch (ServerException e) {
            return "";
        }
        return roles.isEmpty() || roles.equalsIgnoreCase("NONE") ? "" : roles;
    }

    /**
     * Where the binary log ends now: the start for a registration with no checkpoint; with {@code
     * gtid} the executed GTID set as it stood at that same point.
     */
    static BinlogOffset current(MySqlClient client, MySqlCdcOptions options, boolean gtid) throws IOException {
        List<String[]> rows;
        try {
            rows = client.query("SHOW BINARY LOG STATUS");
        } catch (ServerException e) {
            // Before MySQL 8.2 the statement had its old name.
            rows = client.query("SHOW MASTER STATUS");
        }
        require(options, !rows.isEmpty(), "the server reports no binary log position; is log_bin on?");
        String[] row = rows.get(0);
        String executed = gtid && row.length > 4 && row[4] != null ? row[4].replaceAll("\\s", "") : null;
        return BinlogOffset.at(row[0], Long.parseLong(row[1]), executed);
    }

    /**
     * Refuses a restore whose binlog file the server no longer has; for a GTID position, one whose
     * following transactions the server has purged ({@code PRV-5155}), or one holding transactions
     * the server has not executed ({@code PRV-5158}: a replica behind the server that wrote them).
     */
    static void requireRetained(MySqlClient client, MySqlCdcOptions options, BinlogOffset offset, boolean gtidMode)
            throws IOException {
        if (offset.isGtid()) {
            requireGtidRetained(client, options, offset, gtidMode);
            return;
        }
        List<String[]> logs = client.query("SHOW BINARY LOGS");
        if (logs.stream().noneMatch(row -> row[0].equals(offset.file()))) {
            throw new PravahaException(
                    MySqlCdcErrors.RESUME_POINT_PURGED,
                    "plugin '" + options.instanceName() + "' cannot resume from " + offset + ": binlog file '"
                            + offset.file() + "' has been purged (the oldest the server has is '"
                            + (logs.isEmpty() ? "none" : logs.get(0)[0]) + "'). The changes in between are gone, so "
                            + "the checkpoint cannot be resumed exactly. Raise binlog_expire_logs_seconds above the "
                            + "longest outage, then drop the registration and its checkpoint and register again.");
        }
    }

    private static void requireGtidRetained(
            MySqlClient client, MySqlCdcOptions options, BinlogOffset offset, boolean gtidMode) throws IOException {
        require(
                options,
                gtidMode,
                "the checkpoint is a GTID position (" + offset + ") and gtid_mode is not ON here, so the server "
                        + "cannot be asked for the transactions after it. Turn gtid_mode ON (it is what the "
                        + "checkpoint was written against), or drop the registration and its checkpoint and "
                        + "register again");
        String set = offset.gtidSet();
        String purged = client.single("SELECT GTID_SUBTRACT(@@GLOBAL.gtid_purged, '" + set + "')")
                .replaceAll("\\s", "");
        if (!purged.isEmpty()) {
            throw new PravahaException(
                    MySqlCdcErrors.RESUME_POINT_PURGED,
                    "plugin '" + options.instanceName() + "' cannot resume from " + offset + ": " + options.host()
                            + ":" + options.port() + " has purged transactions " + purged + " that come after it. "
                            + "The changes in between are gone, so the checkpoint cannot be resumed exactly. Raise "
                            + "binlog_expire_logs_seconds above the longest outage, then drop the registration and "
                            + "its checkpoint and register again.");
        }
        String missing = client.single("SELECT GTID_SUBTRACT('" + set + "', @@GLOBAL.gtid_executed)")
                .replaceAll("\\s", "");
        if (!missing.isEmpty()) {
            throw new PravahaException(
                    MySqlCdcErrors.RESUME_POINT_AHEAD,
                    "plugin '" + options.instanceName() + "' cannot resume from " + offset + " at " + options.host()
                            + ":" + options.port() + ": the checkpoint holds transactions " + missing + " this server "
                            + "has not executed. It is a replica that has not caught up with the server the "
                            + "checkpoint was read from, or another server altogether. Wait for it to catch up "
                            + "(SELECT GTID_SUBSET('" + set + "', @@GLOBAL.gtid_executed) returns 1), then start "
                            + "again.");
        }
    }

    private static void require(MySqlCdcOptions options, boolean condition, String message) {
        if (!condition) {
            throw new PravahaException(
                    MySqlCdcErrors.NOT_CAPTURABLE,
                    "plugin '" + options.instanceName() + "' cannot capture " + options.qualifiedTable() + " at "
                            + options.host() + ":" + options.port() + ": " + message);
        }
    }
}
