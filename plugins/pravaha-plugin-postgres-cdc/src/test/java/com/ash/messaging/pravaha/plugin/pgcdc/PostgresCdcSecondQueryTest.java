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
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two different queries over one {@code postgres-cdc} binding, against a real PostgreSQL (CDCREPL-2).
 *
 * <p>A binding names one replication slot, and PostgreSQL streams a slot to one connection at a time.
 * The second, different registration used to be accepted, open a reader of its own on the same slot
 * and fail {@code PRV-5117} ("is active for PID") about fifteen seconds later. It is refused at
 * registration now ({@code PRV-8028}), naming the query that holds the binding; the running query is
 * untouched, and once it is dropped the binding is free again.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class PostgresCdcSecondQueryTest {

    private static final StreamSchema CUSTOMERS = StreamSchema.builder("customers")
            .field("id", Types.int64())
            .field("tier", Types.string())
            .field("region", Types.string())
            .build();

    private static final String PER_TIER =
            "SELECT SUM(CASE WHEN tier = 'silver' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS silver, "
                    + "SUM(CASE WHEN tier = 'gold' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS gold FROM customers";

    /** A different question over the same table: a second computation, not a second name for the first. */
    private static final String EU_COUNT =
            "SELECT SUM(CASE WHEN region = 'EU' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS eu, "
                    + "SUM(CASE WHEN region = 'US' THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END) AS us FROM customers";

    @TempDir
    Path checkpoints;

    private String table;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void createTable() {
        table = PgServer.unique("second_customers");
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
    void aSecondDifferentQueryIsRefusedAtRegistrationNamingTheHolder() {
        QueryRegistry registry = registry();
        RegisteredQuery running = registry.register("per_tier", PER_TIER, List.of(0), Principal.ANONYMOUS);
        PgServer.sql("INSERT INTO " + table + " SELECT g, CASE WHEN g <= 90 THEN 'silver' ELSE 'gold' END, 'EU' "
                + "FROM generate_series(1, 100) g");
        awaitPair(running, 90, 10);

        long started = System.nanoTime();
        Throwable refused = null;
        RegisteredQuery second = null;
        try {
            second = registry.register("eu_count", EU_COUNT, List.of(0), Principal.ANONYMOUS);
        } catch (PravahaException e) {
            refused = e;
        }
        if (second != null) {
            // What happens without the refusal, recorded so a regression says what it did.
            PgServer.sleep(20_000);
            throw new AssertionError("the second query was accepted and, 20s later, is " + second.state() + " ("
                    + second.feedStatus() + "); the running query holds " + pair(running));
        }
        assertThat(refused)
                .as("refused at registration, by name, not after the slot's fifteen-second wait")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8028")
                .hasMessageContaining("eu_count")
                .hasMessageContaining("per_tier")
                .hasMessageContaining("replication slot '" + table + "'");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(registry.names()).containsExactly("per_tier");

        // The running query never lost its slot.
        PgServer.sql("UPDATE " + table + " SET tier = 'gold' WHERE id = 7");
        awaitPair(running, 89, 11);

        // Dropped, the binding is free: the claim goes with the feed.
        registry.drop("per_tier");
        RegisteredQuery now = registry.register("eu_count", EU_COUNT, List.of(0), Principal.ANONYMOUS);
        PgServer.sql("INSERT INTO " + table + " VALUES (101, 'gold', 'US')");
        // Whether it also replays what the slot has not confirmed is the slot's business (the
        // checkpoint interval here is an hour); the US row arriving is what shows it is reading.
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (pair(now).getOrDefault("second", 0L) != 1L) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "the query registered after the drop holds " + pair(now) + " (" + now.state() + ")");
            }
            PgServer.sleep(50);
        }
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

    private static Map<String, Long> pair(RegisteredQuery query) {
        Map<String, Long> pair = new TreeMap<>();
        for (Object[] row : query.view().scan()) {
            pair.put("first", row[0] == null ? 0L : ((Number) row[0]).longValue());
            pair.put("second", row[1] == null ? 0L : ((Number) row[1]).longValue());
        }
        return pair;
    }

    private static void awaitPair(RegisteredQuery query, long first, long second) {
        Map<String, Long> wanted = Map.of("first", first, "second", second);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!pair(query).equals(wanted)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "expected " + wanted + ", the view holds " + pair(query) + " (query " + query.state() + ")");
            }
            PgServer.sleep(50);
        }
    }
}
