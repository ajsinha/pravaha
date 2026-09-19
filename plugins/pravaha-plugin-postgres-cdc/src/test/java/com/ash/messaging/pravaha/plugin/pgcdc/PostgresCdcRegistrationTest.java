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

import java.lang.reflect.Field;
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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worked example of {@code docs/CONNECTORS.md} section 5, run through the path a node runs.
 *
 * <p>A {@code postgres-cdc} binding feeds a registered {@code GROUP BY tier} through {@link
 * PluginSourceFeeds} -- plugin discovery by name, the pump, the registry's checkpoints -- and a
 * customer moving from silver to gold walks silver from 900 to 899. That section used to end "a
 * source that dropped the before image would leave silver at 900 for ever" and point at a plugin that
 * did not exist. The number moving is the proof that it exists now.
 *
 * <p>Then the part only a changelog source has to get right: a checkpoint confirms the slot at the
 * checkpoint's own position, and a restart restores the view from that checkpoint and replays from
 * that LSN -- the change delivered after the checkpoint once more, the change made while the node was
 * down once, and nothing before the checkpoint again.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class PostgresCdcRegistrationTest {

    private static final StreamSchema CUSTOMERS = StreamSchema.builder("customers")
            .field("id", Types.int64())
            .field("tier", Types.string())
            .field("region", Types.string())
            .build();

    /**
     * Section 5's {@code GROUP BY tier}, written as the one-row aggregate the engine accepts over an
     * unwindowed stream: a keyed {@code GROUP BY} there is refused as unbounded state (PRV-2050),
     * while a global aggregate is one row by construction. The weights do the same work either way
     * -- the update's {@code -1} of the silver row is what takes silver from 900 to 899.
     *
     * <p>The arms are cast to {@code BIGINT} because {@code SUM} over an {@code INT} expression plans
     * an {@code INT32} output column and then writes an {@code INT64} into it, which fails the lane
     * ("field 0 ('silver') is INT32, not INT64"). That is an engine defect, not this source's.
     */
    private static final String PER_TIER =
            "SELECT SUM(CASE WHEN tier = 'silver' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS silver, "
                    + "SUM(CASE WHEN tier = 'gold' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS gold FROM customers";

    @TempDir
    Path checkpoints;

    private String table;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void createTable() {
        table = PgServer.unique("crm_customers");
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
    void silverMovesFrom900WhenACustomerIsUpdatedAndARestartResumesFromTheCheckpointedLsn() throws Exception {
        QueryRegistry first = registry();
        RegisteredQuery query = first.register("per_tier", PER_TIER, List.of(0), Principal.ANONYMOUS);

        for (int batch = 0; batch < 10; batch++) {
            int from = batch * 100 + 1;
            PgServer.sql("INSERT INTO " + table + " SELECT g, CASE WHEN g <= 900 THEN 'silver' ELSE 'gold' END, 'EU' "
                    + "FROM generate_series(" + from + ", " + (from + 99) + ") g");
        }
        awaitTiers(query, 900, 100);

        PgServer.sql("UPDATE " + table + " SET tier = 'gold' WHERE id = 42");
        awaitTiers(query, 899, 101);

        PgServer.sql("DELETE FROM " + table + " WHERE id = 950");
        awaitTiers(query, 899, 100);

        Checkpoint checkpoint = checkpointerOf(query).checkpointNow();
        long checkpointed = CdcOffset.parse(new com.ash.messaging.pravaha.api.plugin.SourceOffset(
                        checkpoint.offsets().get("partition-0")))
                .lsn();
        awaitConfirmed(checkpointed);

        // After the checkpoint: delivered to the view, and not in the checkpoint.
        PgServer.sql("UPDATE " + table + " SET tier = 'gold' WHERE id = 43");
        awaitTiers(query, 898, 101);
        first.close();
        registries.remove(first);
        assertThat(PgServer.confirmedFlush(table))
                .as("nothing after the checkpoint was confirmed, so the slot still has it")
                .isEqualTo(checkpointed);

        // While nothing is reading.
        PgServer.sql("UPDATE " + table + " SET tier = 'gold' WHERE id = 44");

        QueryRegistry second = registry();
        RegisteredQuery restarted = second.register("per_tier", PER_TIER, List.of(0), Principal.ANONYMOUS);
        awaitTiers(restarted, 897, 102);
        PgServer.sleep(1_000);
        assertThat(tiers(restarted))
                .as("restored at 899/100 and replayed from the checkpoint's LSN: the update to 43 once more, "
                        + "the update to 44 once, and nothing before the checkpoint twice")
                .isEqualTo(Map.of("silver", 897L, "gold", 102L));
        assertThat(restarted.view().scan()).as("one answer, revised in place").hasSize(1);
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

    private void awaitConfirmed(long lsn) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (PgServer.confirmedFlush(table) != lsn && System.nanoTime() < deadline) {
            PgServer.sleep(50);
        }
        assertThat(CdcOffset.format(PgServer.confirmedFlush(table)))
                .as("the slot is confirmed at exactly the checkpoint's position once the checkpoint is durable")
                .isEqualTo(CdcOffset.format(lsn));
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) throws ReflectiveOperationException {
        Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
        field.setAccessible(true);
        return (PeriodicCheckpointer) field.get(query);
    }
}
