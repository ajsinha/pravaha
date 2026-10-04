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
 * {@code postgres-cdc} run against the conformance suite every source must pass, on a real server.
 *
 * <p>Each case gets a fresh table, publication and slot, and five inserts committed one transaction
 * at a time after the slot exists -- so "every record" is five changes in the WAL, and "resume from
 * the midpoint" is a commit LSN. The source declares {@code EXACTLY_ONCE}, so the TCK holds it to no
 * duplicates on resume as well as no loss. The slot is dropped when the plugin closes.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class PostgresCdcSourceTckTest extends SourcePluginTck {

    private static final int RECORDS = 5;

    @SuppressWarnings("NullAway.Init") // set by createPlugin(), which the TCK calls first
    private String table;

    @Override
    protected StreamSourcePlugin createPlugin() {
        table = PgServer.unique("tck");
        PgServer.sql(
                "CREATE TABLE " + table + " (order_id BIGINT PRIMARY KEY, name TEXT NOT NULL)",
                "ALTER TABLE " + table + " REPLICA IDENTITY FULL");
        Map<String, String> options = PgServer.options(table);
        options.put("stream", "tck");
        options.put("drop.slot.on.close", "true");
        PostgresCdcSourcePlugin plugin = PgServer.open(options);
        for (int id = 1; id <= RECORDS; id++) {
            PgServer.sql("INSERT INTO " + table + " VALUES (" + id + ", 'row-" + id + "')");
        }
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
