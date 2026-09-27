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

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

/**
 * Everything a {@code mysql-cdc} binding can say, read and checked without touching the network.
 *
 * @param database the captured table's database
 * @param table the captured table
 * @param serverId the replica id this plugin registers under; unique among the server's replicas
 * @param bufferRows how many decoded rows may wait for the engine before the reader stops reading
 * @param startTimeout how long opening a reader waits for the binlog connection
 * @param heartbeat how often the server is asked to send a heartbeat on a quiet binlog
 */
record MySqlCdcOptions(
        String instanceName,
        String host,
        int port,
        String user,
        String password,
        String database,
        String table,
        String streamName,
        long serverId,
        String eventTimeColumn,
        int bufferRows,
        Duration startTimeout,
        Duration heartbeat) {

    /** Unquoted MySQL identifiers only, so nothing this plugin sends ever needs escaping. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_$]{1,64}");

    static MySqlCdcOptions from(PluginContext context) {
        String instance = context.instanceName();
        if (PluginTls.isConfigured(context)) {
            throw bad(
                    instance,
                    "the shared 'tls.*' options are not supported by mysql-cdc yet: this version connects in "
                            + "plaintext. Reach the server over a private network or a tunnel, and set "
                            + "'tls.enabled: false' to say the plaintext connection is deliberate.");
        }
        String mode = context.get("snapshot.mode", "never").strip().toLowerCase(Locale.ROOT);
        if (mode.equals("initial")) {
            throw bad(
                    instance,
                    "snapshot.mode 'initial' is not built for mysql-cdc: this version reads only changes made "
                            + "after the plugin opens. Use 'never', and load the rows already in the table another "
                            + "way (the jdbc source) if the query needs them.");
        }
        if (!mode.equals("never")) {
            throw bad(instance, "snapshot.mode must be 'never', got '" + mode + "'");
        }
        if (!context.get("schema", "").isBlank()) {
            throw bad(
                    instance,
                    "a declared 'schema' is not supported by mysql-cdc yet: the stream is every column of the "
                            + "table, typed from information_schema. Remove the option.");
        }
        String table = context.require("table").strip();
        String[] parts = table.split("\\.", -1);
        if (parts.length != 2
                || !NAME.matcher(parts[0]).matches()
                || !NAME.matcher(parts[1]).matches()) {
            throw bad(
                    instance,
                    "table '" + table + "' must be 'database.table', each an unquoted MySQL identifier "
                            + "(letters, digits, '_' or '$').");
        }
        int port = (int) number(instance, context, "port", 3306);
        if (port < 1 || port > 65535) {
            throw bad(instance, "port must be between 1 and 65535, got " + port);
        }
        long defaultId = 10_000L + Math.floorMod((instance + "/" + table).hashCode(), 1_000_000_000);
        long serverId = number(instance, context, "server.id", defaultId);
        if (serverId < 1 || serverId > 0xFFFFFFFFL) {
            throw bad(instance, "server.id must be between 1 and 4294967295, got " + serverId);
        }
        long bufferRows = number(instance, context, "buffer.rows", 100_000);
        if (bufferRows < 1 || bufferRows > Integer.MAX_VALUE) {
            throw bad(instance, "buffer.rows must be between 1 and " + Integer.MAX_VALUE + ", got " + bufferRows);
        }
        return new MySqlCdcOptions(
                instance,
                context.require("host").strip(),
                port,
                context.require("user"),
                context.get("password", ""),
                parts[0],
                parts[1],
                context.get("stream", parts[1]),
                serverId,
                context.get("event.time", "").strip(),
                (int) bufferRows,
                duration(instance, context, "start.timeout", Duration.ofSeconds(30)),
                duration(instance, context, "heartbeat.interval", Duration.ofSeconds(10)));
    }

    /** The table as the operator wrote it. */
    String qualifiedTable() {
        return database + "." + table;
    }

    private static long number(String instance, PluginContext context, String key, long fallback) {
        String raw = context.get(key, Long.toString(fallback)).strip();
        try {
            return Long.parseLong(raw.replace("_", ""));
        } catch (NumberFormatException e) {
            throw bad(instance, key + " must be a whole number, got '" + raw + "'");
        }
    }

    /** {@code 500ms}, {@code 10s}, {@code 5m}, or {@code 0}. */
    static Duration duration(String instance, PluginContext context, String key, Duration fallback) {
        String raw = context.get(key, "").strip().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            if (raw.equals("0")) {
                return Duration.ZERO;
            }
            if (raw.endsWith("ms")) {
                return positive(Duration.ofMillis(Long.parseLong(raw.substring(0, raw.length() - 2))));
            }
            long amount = Long.parseLong(raw.substring(0, raw.length() - 1));
            return positive(
                    switch (raw.charAt(raw.length() - 1)) {
                        case 's' -> Duration.ofSeconds(amount);
                        case 'm' -> Duration.ofMinutes(amount);
                        default -> throw new NumberFormatException(raw);
                    });
        } catch (RuntimeException e) {
            throw bad(instance, key + " must be a duration such as 500ms, 10s or 5m, got '" + raw + "'");
        }
    }

    private static Duration positive(Duration duration) {
        if (duration.isNegative()) {
            throw new NumberFormatException(duration.toString());
        }
        return duration;
    }

    static ConfigurationException bad(String instance, String message) {
        return new ConfigurationException(MySqlCdcErrors.BAD_CONFIGURATION, "plugin '" + instance + "': " + message);
    }
}
