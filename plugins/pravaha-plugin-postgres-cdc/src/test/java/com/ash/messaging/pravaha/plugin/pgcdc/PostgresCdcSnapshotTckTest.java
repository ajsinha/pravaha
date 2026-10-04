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

import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * {@code postgres-cdc} with {@code snapshot.mode: initial} run against the conformance suite.
 *
 * <p>Three rows are in the table before its slot exists and two are inserted after -- so "every
 * record" is five rows of which the log alone has only two, and the snapshot must deliver all five
 * while the reader drops the two log inserts that precede the snapshot's point. "Resume from the
 * midpoint" is a position two rows into the snapshot: the resumed reader takes a new snapshot and
 * delivers the other three, and the TCK holds an exactly-once source to no duplicates.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class PostgresCdcSnapshotTckTest extends SourcePluginTck {

    private static final int RECORDS = 5;

    @Override
    protected StreamSourcePlugin createPlugin() {
        String table = PgServer.unique("tcksnap");
        PgServer.sql(
                "CREATE TABLE " + table + " (order_id BIGINT PRIMARY KEY, name TEXT NOT NULL)",
                "ALTER TABLE " + table + " REPLICA IDENTITY FULL",
                "INSERT INTO " + table + " SELECT g, 'row-' || g FROM generate_series(1, 3) g");
        Map<String, String> options = PgServer.options(table);
        options.put("stream", "tck");
        options.put("drop.slot.on.close", "true");
        options.put("snapshot.mode", "initial");
        PostgresCdcSourcePlugin plugin = PgServer.open(options);
        PgServer.sql("INSERT INTO " + table + " VALUES (4, 'row-4')", "INSERT INTO " + table + " VALUES (5, 'row-5')");
        return plugin;
    }

    @Override
    protected String streamName() {
        return "tck";
    }

    @Override
    protected int expectedRecordCount() {
        return RECORDS;
    }
}
