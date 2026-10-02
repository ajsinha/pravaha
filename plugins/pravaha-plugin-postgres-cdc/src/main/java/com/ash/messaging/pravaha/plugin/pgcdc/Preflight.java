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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.plugin.pgcdc.PostgresCdcSourcePlugin.SlotStatus;

/**
 * What has to be true of the server, the table, the publication and the slot before a change stream
 * can be trusted -- checked at open, each refusal naming the statement that fixes it.
 *
 * <p>The order matters in one place: the publication is created before the slot. {@code pgoutput}
 * looks a publication up as of each change it decodes, so a slot created first would reach changes
 * from before the publication existed and fail on them.
 */
final class Preflight {

    private Preflight() {}

    static void check(Connection connection, CdcOptions options) throws SQLException {
        int version = Integer.parseInt(single(connection, "SHOW server_version_num"));
        if (version < 140000) {
            throw notCapturable(
                    options,
                    "PostgreSQL " + single(connection, "SHOW server_version") + " is older than 14. "
                            + "This source needs pgoutput's 'messages' option, added in 14, for the heartbeat that keeps a "
                            + "slot moving on a quiet table; PostgreSQL 13 and earlier are past end of life.");
        }
        String walLevel = single(connection, "SHOW wal_level");
        if (!"logical".equals(walLevel)) {
            throw notCapturable(
                    options,
                    "wal_level is '" + walLevel + "', and logical decoding needs 'logical'. Run "
                            + "ALTER SYSTEM SET wal_level = logical; and then RESTART PostgreSQL -- wal_level is read only at "
                            + "server start, so a reload does nothing. Check max_replication_slots and max_wal_senders have "
                            + "room for one more while you are there.");
        }
    }

    /** The table's OID, after checking it exists, is an ordinary table, and is REPLICA IDENTITY FULL. */
    static int tableOid(Connection connection, CdcOptions options) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.oid::int, c.relreplident, c.relkind FROM pg_class c WHERE c.oid = to_regclass(?)")) {
            statement.setString(1, options.quotedTable());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new ConfigurationException(
                            CdcErrors.SCHEMA_MISMATCH,
                            "plugin '" + options.instanceName() + "': table " + options.qualifiedTable()
                                    + " does not exist, or this role cannot see it");
                }
                int oid = rows.getInt(1);
                String identity = rows.getString(2);
                String kind = rows.getString(3);
                if (!"r".equals(kind)) {
                    throw notCapturable(
                            options,
                            options.qualifiedTable() + " is not an ordinary table (relkind '" + kind
                                    + "'). Capture a partitioned table's partitions, or publish it with "
                                    + "publish_via_partition_root -- which this source does not yet read.");
                }
                if (!"f".equals(identity)) {
                    throw notCapturable(
                            options,
                            options.qualifiedTable() + " has REPLICA IDENTITY "
                                    + identityName(identity) + ", so the before-image of an update or delete carries "
                                    + ("n".equals(identity) ? "nothing" : "only the key")
                                    + ". Retracting that from a view "
                                    + "holding the whole row retracts nothing: a customer moving from silver to gold would "
                                    + "leave silver counted for ever, with no error anywhere. Run: ALTER TABLE "
                                    + options.qualifiedTable()
                                    + " REPLICA IDENTITY FULL; -- it writes the whole old row into "
                                    + "the WAL on every update and delete, which is the cost of retractions being possible.");
                }
                return oid;
            }
        }
    }

    private static String identityName(String code) {
        return switch (code) {
            case "d" -> "DEFAULT";
            case "n" -> "NOTHING";
            case "i" -> "USING INDEX";
            default -> "'" + code + "'";
        };
    }

    static void ensurePublication(Connection connection, CdcOptions options) throws SQLException {
        String create = "CREATE PUBLICATION " + options.publication() + " FOR TABLE " + options.qualifiedTable() + ";";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pubinsert, pubupdate, pubdelete, puballtables FROM pg_publication WHERE pubname = ?")) {
            statement.setString(1, options.publication());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    if (!options.createPublication()) {
                        throw notCapturable(
                                options,
                                "publication '" + options.publication() + "' does not exist and "
                                        + "create.publication is false. Run: " + create);
                    }
                    mayCreatePublication(connection, options, create);
                    try (Statement ddl = connection.createStatement()) {
                        ddl.execute(
                                "CREATE PUBLICATION " + options.publication() + " FOR TABLE " + options.quotedTable());
                    } catch (SQLException e) {
                        if (!INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
                            throw e;
                        }
                        // Checked above, so this is a rule the check does not know about. Still a missing
                        // prerequisite rather than a connection failure, and said as one.
                        throw notCapturable(
                                options,
                                "PostgreSQL refused to create publication '" + options.publication() + "': "
                                        + e.getMessage() + ". Create it as a role that may -- " + create
                                        + " -- and set create.publication: \"false\".");
                    }
                    return;
                }
                if (!rows.getBoolean(1) || !rows.getBoolean(2) || !rows.getBoolean(3)) {
                    throw notCapturable(
                            options,
                            "publication '" + options.publication() + "' does not publish inserts, "
                                    + "updates and deletes, so some changes would never arrive and the view would keep rows the "
                                    + "table no longer has. Run: ALTER PUBLICATION " + options.publication()
                                    + " SET (publish = 'insert, update, delete, truncate');");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_publication_tables WHERE pubname = ? AND schemaname = ? AND tablename = ?")) {
            statement.setString(1, options.publication());
            statement.setString(2, options.schemaName());
            statement.setString(3, options.tableName());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw notCapturable(
                            options,
                            "publication '" + options.publication() + "' does not include "
                                    + options.qualifiedTable() + ". Run: ALTER PUBLICATION " + options.publication()
                                    + " ADD TABLE " + options.qualifiedTable() + ";");
                }
            }
        }
    }

    static void ensureSlot(Connection connection, CdcOptions options) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT plugin, slot_type, database = current_database(), wal_status FROM pg_replication_slots "
                        + "WHERE slot_name = ?")) {
            statement.setString(1, options.slot());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    if (!"logical".equals(rows.getString(2)) || !"pgoutput".equals(rows.getString(1))) {
                        throw notCapturable(
                                options,
                                "slot '" + options.slot() + "' exists and is a "
                                        + rows.getString(2) + " slot for '" + rows.getString(1)
                                        + "', not a logical slot for "
                                        + "pgoutput. Name another slot, or drop this one if nothing reads it.");
                    }
                    if (!rows.getBoolean(3)) {
                        throw notCapturable(
                                options,
                                "slot '" + options.slot() + "' belongs to another database. A "
                                        + "logical slot decodes one database; name another slot.");
                    }
                    if ("lost".equals(rows.getString(4))) {
                        throw notCapturable(
                                options,
                                "slot '" + options.slot() + "' is invalidated (wal_status 'lost'): "
                                        + "PostgreSQL removed WAL it still needed, usually because max_slot_wal_keep_size was "
                                        + "exceeded. Changes are missing and cannot be recovered from it. Drop it -- SELECT "
                                        + "pg_drop_replication_slot('" + options.slot()
                                        + "') -- drop the registration's "
                                        + "checkpoints, and register the query again.");
                    }
                    return;
                }
            }
        }
        if (!options.createSlot()) {
            throw notCapturable(
                    options,
                    "replication slot '" + options.slot() + "' does not exist and create.slot is "
                            + "false. Run: SELECT pg_create_logical_replication_slot('" + options.slot()
                            + "', 'pgoutput');");
        }
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT pg_create_logical_replication_slot(?, 'pgoutput')")) {
            statement.setString(1, options.slot());
            statement.execute();
        } catch (SQLException e) {
            ConfigurationException privilege = replicationRefused(options, e);
            if (privilege != null) {
                throw privilege; // CDCPRIVCODE-1: a role that may not create a replication slot
            }
            throw e;
        }
    }

    /**
     * Refuses to resume from a position the slot has already released.
     *
     * <p>PostgreSQL answers a request to start before a slot's confirmed position by starting at the
     * confirmed position instead, silently. From an engine's point of view that is a gap: the changes
     * in between are neither in the restored state nor in the stream. It happens when recovery falls
     * back to an older checkpoint than the one the slot was confirmed at, or when a slot was dropped
     * and recreated under the same name.
     */
    static CdcOffset requireResumable(Connection connection, CdcOptions options, CdcOffset start) throws SQLException {
        Optional<SlotStatus> status = slotStatus(connection, options);
        if (status.isEmpty()) {
            throw notCapturable(
                    options,
                    "replication slot '" + options.slot() + "' does not exist, so there is "
                            + "nothing to stream from. If it was dropped, the changes since the last checkpoint are gone: drop "
                            + "the registration's checkpoints and register the query again.");
        }
        long confirmed = CdcOffset.parseLsn(status.get().confirmedFlushLsn());
        if (start.isBeginning()) {
            // "From the start" means from where the slot stands, which is where PostgreSQL would
            // begin anyway -- said explicitly, so every position this reader reports names an LSN.
            return CdcOffset.at(confirmed);
        }
        if (start.lsn() < confirmed) {
            throw new PravahaException(
                    CdcErrors.RESUME_POINT_RELEASED,
                    "plugin '" + options.instanceName() + "': the checkpoint resumes at "
                            + CdcOffset.format(start.lsn())
                            + ", and slot '" + options.slot() + "' has already confirmed "
                            + status.get().confirmedFlushLsn()
                            + ". PostgreSQL has released the changes in between, and would silently start after them. "
                            + "This happens when recovery falls back to an older checkpoint than the newest, or when the "
                            + "slot was recreated. Drop the registration's checkpoints and the slot, and register the "
                            + "query again to rebuild the view from a new slot.");
        }
        return start;
    }

    static Optional<SlotStatus> slotStatus(Connection connection, CdcOptions options) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active, COALESCE(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn), 0)::bigint, "
                        + "COALESCE(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn), 0)::bigint, "
                        + "COALESCE(confirmed_flush_lsn::text, '0/0'), COALESCE(wal_status, 'unknown') "
                        + "FROM pg_replication_slots WHERE slot_name = ?")) {
            statement.setString(1, options.slot());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SlotStatus(
                        options.slot(),
                        rows.getBoolean(1),
                        rows.getLong(2),
                        rows.getLong(3),
                        rows.getString(4),
                        rows.getString(5)));
            }
        }
    }

    static void dropSlot(Connection connection, CdcOptions options) throws SQLException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = ?")) {
                statement.setString(1, options.slot());
                statement.execute();
                return;
            } catch (SQLException e) {
                // 55006: a walsender still holds it for a moment after its reader closed.
                if (!"55006".equals(e.getSQLState()) || System.nanoTime() > deadline) {
                    throw e;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(200_000_000L);
            }
        }
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** PostgreSQL's SQLSTATE for "permission denied" and "must be owner of". */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /**
     * Whether this role may run {@code CREATE PUBLICATION ... FOR TABLE}: PostgreSQL requires it to own
     * the table (or be a member of the role that does) AND to hold CREATE on the database. A fresh role
     * made to own the table has the first and not the second, and the statement failed with "permission
     * denied for database" -- surfacing as PRV-5111, a connection failure, rather than as the missing
     * prerequisite it is (DOC-PGCDC). Checked before anything is created, like every other prerequisite,
     * and refused naming the statement that fixes it and the way around it.
     */
    private static void mayCreatePublication(Connection connection, CdcOptions options, String create)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_database(), current_user, has_database_privilege(current_database(), 'CREATE'), "
                        + "pg_has_role(c.relowner, 'USAGE'), pg_get_userbyid(c.relowner) "
                        + "FROM pg_class c WHERE c.oid = to_regclass(?)")) {
            statement.setString(1, options.quotedTable());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return; // tableOid, which runs first, already refused a table that is not there
                }
                String database = rows.getString(1);
                String role = rows.getString(2);
                boolean mayCreate = rows.getBoolean(3);
                boolean owns = rows.getBoolean(4);
                String owner = rows.getString(5);
                String grant = "GRANT CREATE ON DATABASE " + identifier(database) + " TO " + identifier(role) + ";";
                String instead = " Or create the publication as a role that may -- " + create
                        + " -- and set create.publication: \"false\"; then this role needs neither.";
                if (!owns) {
                    throw notCapturable(
                            options,
                            "role '" + role + "' cannot create publication '" + options.publication()
                                    + "': CREATE PUBLICATION ... FOR TABLE needs the table's owner, and "
                                    + options.qualifiedTable() + " is owned by '" + owner + "'. Run: ALTER TABLE "
                                    + options.qualifiedTable() + " OWNER TO " + identifier(role) + ";"
                                    + (mayCreate ? "" : " and " + grant) + instead);
                }
                if (!mayCreate) {
                    throw notCapturable(
                            options,
                            "role '" + role + "' cannot create publication '" + options.publication()
                                    + "': CREATE PUBLICATION needs CREATE on the database as well as owning the "
                                    + "table, and this role has no CREATE on database '" + database + "'. Run: "
                                    + grant + instead);
                }
            }
        }
    }

    /** A name as SQL needs it written: bare when it is a plain lower-case identifier, quoted otherwise. */
    static String identifier(String name) {
        return name.matches("[a-z_][a-z0-9_$]*") ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * CDCPRIVCODE-1: {@code PRV-5112} naming {@code ALTER ROLE ... REPLICATION} when a replication
     * connection was refused for want of privilege ({@code 42501}, "permission denied to start WAL
     * sender"); null for any other failure. Starting a snapshot or a stream with such a role used to
     * be {@code PRV-5118} or {@code PRV-5117}, with advice about transactions left idle and {@code
     * max_replication_slots} -- the PostgreSQL detail was right, and the code and the remedy were not.
     */
    static ConfigurationException replicationRefused(CdcOptions options, SQLException e) {
        if (!INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
            return null;
        }
        String role = identifier(options.user());
        return new ConfigurationException(
                CdcErrors.NOT_CAPTURABLE,
                "plugin '" + options.instanceName() + "': role " + role + " may not start replication: "
                        + e.getMessage() + ". Logical decoding needs a role with the REPLICATION attribute. Run "
                        + "ALTER ROLE " + role + " REPLICATION; (on Amazon RDS or Aurora: GRANT rds_replication TO "
                        + role + ";), then register the query again.",
                e);
    }

    private static ConfigurationException notCapturable(CdcOptions options, String message) {
        return new ConfigurationException(
                CdcErrors.NOT_CAPTURABLE, "plugin '" + options.instanceName() + "': " + message);
    }
}
