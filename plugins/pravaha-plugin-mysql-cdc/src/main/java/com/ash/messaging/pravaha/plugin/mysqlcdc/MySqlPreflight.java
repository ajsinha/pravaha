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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.shyiko.mysql.binlog.network.ServerException;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What open checks before anything is read, each refusal naming the statement that fixes it
 * ({@code PRV-5152}), and the binlog positions a reader starts from.
 */
final class MySqlPreflight {

    private MySqlPreflight() {}

    static void check(MySqlClient client, MySqlCdcOptions options) throws IOException {
        Map<String, String> variables = new HashMap<>();
        for (String[] row : client.query("SHOW GLOBAL VARIABLES WHERE Variable_name IN ('log_bin', 'binlog_format', "
                + "'binlog_row_image', 'binlog_transaction_compression')")) {
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
        boolean slave = false;
        boolean clientPrivilege = false;
        for (String[] row : client.query("SHOW GRANTS")) {
            String grant = row[0].toUpperCase(Locale.ROOT);
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
                        + ", needed to read the binary log: GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '"
                        + options.user() + "'@'%'; (granted directly: SHOW GRANTS does not expand roles)");
    }

    /** Where the binary log ends now: the start for a registration with no checkpoint. */
    static BinlogOffset current(MySqlClient client, MySqlCdcOptions options) throws IOException {
        List<String[]> rows;
        try {
            rows = client.query("SHOW BINARY LOG STATUS");
        } catch (ServerException e) {
            // Before MySQL 8.2 the statement had its old name.
            rows = client.query("SHOW MASTER STATUS");
        }
        require(options, !rows.isEmpty(), "the server reports no binary log position; is log_bin on?");
        return BinlogOffset.at(rows.get(0)[0], Long.parseLong(rows.get(0)[1]));
    }

    /** Refuses a restore whose binlog file the server no longer has. */
    static void requireRetained(MySqlClient client, MySqlCdcOptions options, BinlogOffset offset) throws IOException {
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

    private static void require(MySqlCdcOptions options, boolean condition, String message) {
        if (!condition) {
            throw new PravahaException(
                    MySqlCdcErrors.NOT_CAPTURABLE,
                    "plugin '" + options.instanceName() + "' cannot capture " + options.qualifiedTable() + " at "
                            + options.host() + ":" + options.port() + ": " + message);
        }
    }
}
