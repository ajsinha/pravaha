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

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

/**
 * Everything a {@code postgres-cdc} binding can say, read and checked without touching the network.
 *
 * <p>Parsed at {@code configure} because that is where a mistake costs a minute: the node asks a
 * configured source what it can promise before it opens a connection, so nothing here may connect.
 *
 * @param url a {@code jdbc:postgresql:} URL. TLS goes in it, as for the {@code jdbc} source
 * @param tableName the captured table, {@code schema.table} or a bare name in {@code public}
 * @param slot the logical replication slot, server-side state that retains WAL until confirmed
 * @param publication the publication the slot's {@code pgoutput} stream is filtered through
 * @param heartbeat how often to write a position marker into the WAL so that a quiet table's slot
 *     still advances; zero turns it off
 * @param bufferRows how many decoded rows may wait for the engine before the reader stops reading
 * @param startTimeout how long opening a reader waits to have read the log as far as it stood
 * @param lagWarnBytes retained WAL past which the source reports itself degraded
 * @param snapshotInitial whether a registration starting from nothing first reads the rows already in
 *     the table ({@code snapshot.mode: initial}) or only changes made after its slot ({@code never},
 *     the default -- see {@link InitialSnapshot})
 * @param snapshotChunkRows how many rows one snapshot query reads, and so roughly how many are held
 *     in memory at once
 */
record CdcOptions(
        String instanceName,
        String url,
        String user,
        String password,
        String schemaName,
        String tableName,
        String streamName,
        String slot,
        String publication,
        boolean createSlot,
        boolean createPublication,
        String declaredSchema,
        String eventTimeColumn,
        Duration heartbeat,
        Duration statusInterval,
        int bufferRows,
        Duration startTimeout,
        long lagWarnBytes,
        boolean dropSlotOnClose,
        boolean snapshotInitial,
        int snapshotChunkRows) {

    /** PostgreSQL's own rule for a slot name, applied to publications too so both need no quoting. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,63}");

    /** The prefix of every logical message this source writes, so it never mistakes someone else's. */
    static final String MESSAGE_PREFIX = "pravaha-cdc";

    static CdcOptions from(PluginContext context) {
        String instance = context.instanceName();
        refuseSharedTls(context, instance);
        String url = context.require("url");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw bad(
                    instance,
                    "url '" + url + "' is not a PostgreSQL JDBC URL. Expected "
                            + "jdbc:postgresql://host:5432/database -- change data capture reads PostgreSQL's own "
                            + "replication protocol, so no other driver's URL can work here.");
        }
        String table = context.require("table").strip();
        String[] parts = table.split("\\.", -1);
        if (parts.length > 2 || parts[parts.length - 1].isBlank() || (parts.length == 2 && parts[0].isBlank())) {
            throw bad(instance, "table '" + table + "' is not 'schema.table' or 'table'.");
        }
        String schemaName = parts.length == 2 ? parts[0] : "public";
        String tableName = parts[parts.length - 1];
        String defaultName = sanitise("pravaha_" + tableName);
        String slot = context.get("slot", defaultName).strip();
        String publication = context.get("publication", defaultName).strip();
        requireName(instance, "slot", slot);
        requireName(instance, "publication", publication);
        int bufferRows = integer(instance, context, "buffer.rows", 100_000);
        if (bufferRows < 1) {
            throw bad(instance, "buffer.rows must be at least 1, got " + bufferRows);
        }
        long lagWarn = longValue(instance, context, "slot.lag.warn.bytes", 1L << 30);
        Duration heartbeat = duration(instance, context, "heartbeat.interval", Duration.ofSeconds(10));
        Duration status = duration(instance, context, "status.interval", Duration.ofSeconds(10));
        if (status.isZero()) {
            throw bad(
                    instance,
                    "status.interval must be positive: PostgreSQL ends a replication connection "
                            + "that stops reporting (wal_sender_timeout, 60s by default).");
        }
        String mode = context.get("snapshot.mode", "never").strip().toLowerCase(Locale.ROOT);
        if (!mode.equals("never") && !mode.equals("initial")) {
            throw bad(
                    instance,
                    "snapshot.mode must be 'initial' (read the rows already in the table, then stream "
                            + "changes) or 'never' (changes after the slot was created only), got '" + mode + "'");
        }
        int chunkRows = integer(instance, context, "snapshot.chunk.rows", 10_000);
        if (chunkRows < 1) {
            throw bad(instance, "snapshot.chunk.rows must be at least 1, got " + chunkRows);
        }
        return new CdcOptions(
                instance,
                url,
                context.get("user", ""),
                context.get("password", ""),
                schemaName,
                tableName,
                context.get("stream", tableName),
                slot,
                publication,
                bool(instance, context, "create.slot", true),
                bool(instance, context, "create.publication", true),
                context.get("schema", "").strip(),
                context.get("event.time", "").strip(),
                heartbeat,
                status,
                bufferRows,
                duration(instance, context, "start.timeout", Duration.ofSeconds(30)),
                lagWarn,
                bool(instance, context, "drop.slot.on.close", false),
                mode.equals("initial"),
                chunkRows);
    }

    /** The table as the operator wrote it, for messages and for statements an operator will paste. */
    String qualifiedTable() {
        return schemaName + "." + tableName;
    }

    /** The table quoted for SQL this plugin runs itself. */
    String quotedTable() {
        return quote(schemaName) + "." + quote(tableName);
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String sanitise(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return lower.length() > 63 ? lower.substring(0, 63) : lower;
    }

    private static void requireName(String instance, String key, String value) {
        if (!NAME.matcher(value).matches()) {
            throw bad(
                    instance,
                    key + " '" + value + "' must be 1 to 63 lower-case letters, digits or "
                            + "underscores -- PostgreSQL's rule for a replication slot name, applied to both so "
                            + "neither ever needs quoting in the statements this plugin prints for you.");
        }
    }

    private static boolean bool(String instance, PluginContext context, String key, boolean fallback) {
        String raw = context.get(key, Boolean.toString(fallback)).strip().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "true" -> true;
            case "false" -> false;
            default -> throw bad(instance, key + " must be true or false, got '" + raw + "'");
        };
    }

    private static int integer(String instance, PluginContext context, String key, int fallback) {
        long value = longValue(instance, context, key, fallback);
        if (value > Integer.MAX_VALUE) {
            throw bad(instance, key + " is too large: " + value);
        }
        return (int) value;
    }

    private static long longValue(String instance, PluginContext context, String key, long fallback) {
        String raw = context.get(key, Long.toString(fallback)).strip();
        try {
            return Long.parseLong(raw.replace("_", ""));
        } catch (NumberFormatException e) {
            throw bad(instance, key + " must be a whole number, got '" + raw + "'");
        }
    }

    /** {@code 500ms}, {@code 10s}, {@code 5m}, {@code 1h}, or an ISO-8601 duration; {@code 0} is zero. */
    static Duration duration(String instance, PluginContext context, String key, Duration fallback) {
        String raw = context.get(key, "").strip().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            Duration parsed;
            if (raw.equals("0")) {
                parsed = Duration.ZERO;
            } else if (raw.startsWith("p")) {
                parsed = Duration.parse(raw.toUpperCase(Locale.ROOT));
            } else if (raw.endsWith("ms")) {
                parsed = Duration.ofMillis(
                        Long.parseLong(raw.substring(0, raw.length() - 2).strip()));
            } else {
                long amount = Long.parseLong(raw.substring(0, raw.length() - 1).strip());
                parsed = switch (raw.charAt(raw.length() - 1)) {
                    case 's' -> Duration.ofSeconds(amount);
                    case 'm' -> Duration.ofMinutes(amount);
                    case 'h' -> Duration.ofHours(amount);
                    default -> throw new NumberFormatException(raw);
                };
            }
            if (parsed.isNegative()) {
                throw new NumberFormatException(raw);
            }
            return parsed;
        } catch (RuntimeException e) {
            throw bad(instance, key + " must be a duration such as 500ms, 10s or 5m, got '" + raw + "'");
        }
    }

    /**
     * Refuses the shared {@code tls.*} options, for the reason the {@code jdbc} source does: a JDBC
     * driver takes TLS in its URL, and accepting {@code tls.enabled: true} while connecting in
     * plaintext would be a configuration that says one thing and does another.
     */
    private static void refuseSharedTls(PluginContext context, String instance) {
        if (!PluginTls.isConfigured(context)) {
            return;
        }
        throw bad(
                instance,
                "the shared 'tls.*' options do not apply to postgres-cdc: the PostgreSQL driver "
                        + "takes TLS in the URL, and the replication connection is opened from that same URL. Append "
                        + "'?ssl=true&sslmode=verify-full&sslrootcert=/path/ca.pem' and remove the tls.* options, or set "
                        + "'tls.enabled: false' to say the plaintext connection is deliberate.");
    }

    static ConfigurationException bad(String instance, String message) {
        return new ConfigurationException(CdcErrors.BAD_CONFIGURATION, "plugin '" + instance + "': " + message);
    }
}
