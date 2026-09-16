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
package com.ash.messaging.pravaha.plugin.cassandra;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * How this plugin gets rows out of Cassandra (ADR-039 item 6).
 *
 * <p>The central constraint, and the reason this is an enum rather than an implementation detail:
 * <strong>Cassandra has no client-pullable change log.</strong> Its CDC writes commitlog segments to
 * a {@code cdc_raw} directory on <em>every node</em>, to be read locally -- there is no server that
 * streams them to a remote client the way Postgres streams its WAL or MySQL its binlog. A CDC reader
 * for Cassandra is a per-node agent with no ordering across nodes, which is a different project from
 * a plugin ({@code docs/CONNECTORS.md} section 5). So the strategy that is tractable here is a table
 * scan, and this enum says so rather than pretending otherwise.
 *
 * <p>A second question follows immediately: can a scan at least be incremental, the way the Aerospike
 * plugin's {@code lut-scan} is incremental on last-update-time? CQL's answer is no. {@code writetime()}
 * exists, but it cannot appear in a {@code WHERE} clause without {@code ALLOW FILTERING} -- Cassandra
 * has no index on it -- so filtering by it server-side costs exactly what a full scan costs: every
 * partition is still read. And {@code writetime()} is per-<em>column</em>, not per-row: a table with
 * several non-key columns can have a different writetime on each one, a write that only touches
 * primary-key columns changes no regular column's writetime at all, and a `null` column (never
 * written, or written and then deleted) has no writetime to compare. There is no single value that
 * honestly answers "when was this row last touched." Building {@code writetime-incremental} anyway
 * would silently miss exactly the rows that changed only in their key or only in a column that
 * happened to be excluded from the tracked set -- the failure this framework exists to prevent
 * ({@code docs/CONNECTORS.md} section 6). So it stays refused.
 *
 * <p>What remains is a full periodic scan: read the whole assigned token range every interval, paged
 * by {@code token()} so no partition is read through {@code ALLOW FILTERING}. It sees a row's current
 * value, cannot see a delete (a tombstone is not delivered to an ordinary read), and two writes
 * between scans look like one -- properties of scanning a store with no change feed, not of this
 * implementation, and the same ones the Aerospike {@code lut-scan} strategy declares. The difference
 * from Aerospike is that there is no watermark to make the re-read partial: every pass reads
 * everything.
 */
public enum CassandraStrategy {

    /**
     * A full periodic scan of the assigned token range, paged by {@code token()}.
     *
     * <p>The only strategy this build implements, and the only one CQL makes tractable without
     * either a per-node agent (CDC) or a full-table {@code ALLOW FILTERING} pass that costs the same
     * as this one while additionally claiming an incrementality it cannot deliver. Honest about what
     * it costs: at-least-once, no before-image, and deletes are invisible -- a tombstoned row is
     * simply absent from the next scan, indistinguishable from one that never existed. It also
     * misses intra-interval overwrites: two writes between scans are seen as one.
     */
    TOKEN_RANGE_SCAN("token-range-scan"),

    /**
     * A scan filtered on {@code writetime()} of a nominated column, the way the Aerospike plugin
     * filters on {@code record.last_update_time()}. Designed and refused, not implemented: CQL has no
     * index on {@code writetime()}, so the filter cannot run server-side without {@code ALLOW
     * FILTERING} -- which reads every partition anyway, buying none of the bandwidth savings the
     * Aerospike strategy gets from its server-side expression. Worse, {@code writetime()} is
     * per-column: a key-only update changes no regular column's writetime, and a table with several
     * tracked columns can disagree on which one last moved. There is no row-level "last changed" to
     * compare against a watermark, so a plugin that shipped this would miss rows silently.
     */
    WRITETIME_INCREMENTAL("writetime-incremental"),

    /**
     * A per-node agent reading commitlog segments from {@code cdc_raw}. Designed and refused: this is
     * the different project {@code docs/CONNECTORS.md} section 5
     * describes -- an agent on every node, no cross-node ordering, and nothing a client-side plugin
     * can implement by connecting to the cluster the way it connects for a scan.
     */
    COMMITLOG_CDC("commitlog-cdc");

    private final String configName;

    CassandraStrategy(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    /** Whether this build can actually run it. */
    public boolean isImplemented() {
        return this == TOKEN_RANGE_SCAN;
    }

    static CassandraStrategy parse(String name) {
        for (CassandraStrategy strategy : values()) {
            if (strategy.configName.equalsIgnoreCase(name.strip())) {
                if (!strategy.isImplemented()) {
                    throw new ConfigurationException(
                            CassandraErrors.BAD_CONFIGURATION,
                            "strategy '" + name + "' is designed but not implemented in this build. "
                                    + describe(strategy) + " Use 'token-range-scan', which works against any "
                                    + "Cassandra cluster and declares its weaker guarantees.");
                }
                return strategy;
            }
        }
        throw new ConfigurationException(
                CassandraErrors.BAD_CONFIGURATION,
                "unknown strategy '" + name + "'. Supported: token-range-scan. Designed but not implemented: "
                        + "writetime-incremental, commitlog-cdc.");
    }

    private static String describe(CassandraStrategy strategy) {
        return switch (strategy) {
            case WRITETIME_INCREMENTAL ->
                "CQL cannot filter on writetime() without ALLOW FILTERING, which "
                        + "reads every partition anyway, and writetime() is per-column rather than per-row -- so "
                        + "there is no honest watermark to resume from.";
            case COMMITLOG_CDC ->
                "Cassandra's CDC writes to cdc_raw on every node with no ordering across "
                        + "them, which needs a per-node agent rather than a plugin that connects to the cluster.";
            case TOKEN_RANGE_SCAN -> "";
        };
    }
}
