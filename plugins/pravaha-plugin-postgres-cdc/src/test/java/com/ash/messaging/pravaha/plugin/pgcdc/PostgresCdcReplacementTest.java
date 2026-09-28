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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryReplacement;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.ReplacementOptions;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replacing a query over a {@code postgres-cdc} stream, against a real PostgreSQL (CDCREPL-1).
 *
 * <p>A replacement's backfill opens a second reader on the stream's binding, and a binding names
 * one replication slot. PostgreSQL lets one connection stream a slot at a time, and a slot keeps no
 * WAL from before its confirmed position, so a second reader beside the running one can neither
 * start nor replay the history the running version has already read. The replacement is refused
 * before anything opens, naming the slot, and the running version is left exactly as it was.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class PostgresCdcReplacementTest {

    private static final StreamSchema CUSTOMERS = StreamSchema.builder("customers")
            .field("id", Types.int64())
            .field("tier", Types.string())
            .field("region", Types.string())
            .build();

    private static final String PER_TIER =
            "SELECT SUM(CASE WHEN tier = 'silver' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS silver, "
                    + "SUM(CASE WHEN tier = 'gold' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS gold FROM customers";

    /** The same answer with a column more: a different query, so a replacement rather than a no-op. */
    private static final String PER_TIER_V2 =
            "SELECT SUM(CASE WHEN tier = 'silver' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS silver, "
                    + "SUM(CASE WHEN tier = 'gold' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS gold, "
                    + "SUM(CASE WHEN region = 'EU' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS eu FROM customers";

    @TempDir
    Path checkpoints;

    private String table;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void createTable() {
        table = PgServer.unique("repl_customers");
        PgServer.sql(
                "CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, tier TEXT NOT NULL, region TEXT NOT NULL)",
                "ALTER TABLE " + table + " REPLICA IDENTITY FULL");
    }

    @AfterEach
    void closeEverything() {
        registries.forEach(QueryRegistry::close);
        PgServer.dropSlotQuietly(table);
    }

    @Test
    void aReplacementIsRefusedNamingTheSlotAndTheRunningVersionKeepsAnswering() {
        QueryRegistry registry = registry();
        RegisteredQuery running = registry.register("per_tier", PER_TIER, List.of(0), Principal.ANONYMOUS);
        PgServer.sql("INSERT INTO " + table + " SELECT g, CASE WHEN g <= 90 THEN 'silver' ELSE 'gold' END, 'EU' "
                + "FROM generate_series(1, 100) g");
        awaitTiers(running, 90, 10);

        Throwable refused = null;
        QueryReplacement.Status started = null;
        try {
            started = registry.replacements()
                    .replace("per_tier", PER_TIER_V2, List.of(0), Principal.ANONYMOUS, ReplacementOptions.defaults());
        } catch (PravahaException e) {
            refused = e;
        }
        if (started != null) {
            // What happens without the refusal, recorded so a regression says what it did.
            PgServer.sleep(20_000);
            QueryReplacement.Status after =
                    registry.replacements().of("per_tier").orElse(started);
            throw new AssertionError("the replacement was accepted and, 20s later, is " + after.state() + " ("
                    + after.failureCode() + ": " + after.failure() + "); slot " + table + " is "
                    + PgServer.scalar(
                            "SELECT active_pid::text FROM pg_replication_slots WHERE slot_name = '" + table + "'")
                    + "; the running version holds " + tiers(running));
        }

        assertThat(refused)
                .as("refused before anything opens, with the code a replacement's unreadable source has")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018")
                .hasMessageContaining("per_tier")
                .hasMessageContaining("'" + table + "'")
                .hasMessageContaining("replication slot");
        assertThat(registry.replacements().isReplacing("per_tier")).isFalse();

        // The running version never lost its slot.
        PgServer.sql("UPDATE " + table + " SET tier = 'gold' WHERE id = 7");
        awaitTiers(running, 89, 11);
        assertThat(PgServer.scalar("SELECT count(*) FROM pg_replication_slots WHERE slot_name LIKE '" + table + "%'"))
                .as("no second slot was made on the database for a replacement that was refused")
                .isEqualTo("1");
    }

    private QueryRegistry registry() {
        Map<String, String> options = PgServer.options(table);
        options.put("stream", "customers");
        PluginSourceFeeds feeds = new PluginSourceFeeds().bind(new SourceBinding("customers", "postgres-cdc", options));
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), CUSTOMERS)
                .feedingFrom(feeds)
                .checkpointingTo(
                        checkpoints,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "30s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private static Map<String, Long> tiers(RegisteredQuery query) {
        Map<String, Long> tiers = new TreeMap<>();
        for (Object[] row : query.view().scan()) {
            tiers.put("silver", row[0] == null ? 0L : ((Number) row[0]).longValue());
            tiers.put("gold", row[1] == null ? 0L : ((Number) row[1]).longValue());
        }
        return tiers;
    }

    private static void awaitTiers(RegisteredQuery query, long silver, long gold) {
        Map<String, Long> wanted = Map.of("silver", silver, "gold", gold);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!tiers(query).equals(wanted)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "expected " + wanted + ", the view holds " + tiers(query) + " (query " + query.state() + ")");
            }
            PgServer.sleep(50);
        }
    }
}
