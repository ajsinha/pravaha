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
import java.util.Objects;
import java.util.Optional;
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
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CDCSLOT-1: a replication slot dropped while its query runs is detected, by name, and nothing after
 * it is silently skipped.
 *
 * <p>QA dropped the slot under a running {@code customers_live} the way an operator (or a cleanup
 * script) would -- terminate the walsender, then drop the slot, looped until the drop wins -- and the
 * query stayed RUNNING, the feed RUNNING and health UP for four minutes while every later change was
 * missing. The reader had fallen into a reconnect loop that treated the slot's {@code 42704} as one
 * more transient failure. These tests drop the slot the same way and require PRV-5117.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class PostgresCdcSlotDroppedTest {

    private static final StreamSchema CUSTOMERS = StreamSchema.builder("customers")
            .field("id", Types.int64())
            .field("tier", Types.string())
            .field("region", Types.string())
            .build();

    @TempDir
    Path checkpoints;

    private String table;
    private final List<AutoCloseable> open = new ArrayList<>();

    @BeforeEach
    void createTable() {
        table = PgServer.unique("slot_dropped");
        PgServer.sql(
                "CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, tier TEXT NOT NULL, region TEXT NOT NULL)",
                "ALTER TABLE " + table + " REPLICA IDENTITY FULL");
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : open.reversed()) {
            try {
                closeable.close();
            } catch (RuntimeException ignored) {
                // A reader that has already failed may refuse its own close; the slot is gone anyway.
            }
        }
        PgServer.dropSlotQuietly(table);
    }

    @Test
    void aSlotDroppedUnderARunningQueryStopsItsFeedWithPrv5117AndHealthSaysSo() {
        QueryRegistry registry = registry();
        RegisteredQuery query =
                registry.register("customers_live", "SELECT id, tier FROM customers", List.of(0), Principal.ANONYMOUS);
        PgServer.sql("INSERT INTO " + table + " VALUES (1, 'silver', 'EU'), (2, 'gold', 'US')");
        awaitRows(query, 2);

        dropSlotUnderTheReader();
        PgServer.sql("INSERT INTO " + table + " VALUES (6, 'pied piper', 'US'), (7, 'aviato', 'US')");

        String code = awaitStopped(query, Duration.ofSeconds(30));
        assertThat(code)
                .as(
                        "the feed stops with the code the source-postgres-cdc topic promises for a slot dropped while "
                                + "running, rather than reconnecting for ever (feed: %s)",
                        query.feedStatus())
                .isEqualTo("PRV-5117");
        assertThat(query.view().scan())
                .as("nothing after the drop reached the view, and nothing pretended it had")
                .hasSize(2);
    }

    @Test
    void aSlotDroppedAndRecreatedUnderTheSameNameIsNotResumedAfterTheGap() {
        Map<String, String> options = PgServer.options(table);
        PostgresCdcSourcePlugin plugin = PgServer.open(options);
        open.add(plugin);
        PartitionReader reader = plugin.createReader(new SourcePartition(table, 0, Map.of()), null);
        open.add(reader);
        Captured sink = new Captured(plugin.schema());
        PgServer.sql("INSERT INTO " + table + " VALUES (1, 'silver', 'EU')");
        Captured.pollUntil(reader, sink, 1024, () -> sink.texts().size() == 1, 20_000);

        dropSlotUnderTheReader();
        PgServer.sql("INSERT INTO " + table + " VALUES (2, 'lost', 'EU')");
        PgServer.sql("SELECT pg_create_logical_replication_slot('" + table + "', 'pgoutput')");
        PgServer.sql("INSERT INTO " + table + " VALUES (3, 'after', 'EU')");

        PravahaException failure = awaitPollFailure(reader, sink, Duration.ofSeconds(30));
        assertThat(failure.errorCode().code())
                .as("dropped (seen before the new slot existed) or recreated past where the reader stopped: "
                        + "either way the changes in between are gone, and that is PRV-5117, not a silent resume")
                .isEqualTo("PRV-5117");
        assertThat(sink.texts())
                .as("row 2 was in the gap; row 3 must not arrive as if nothing were missing")
                .containsExactly("+1 [1, silver, EU]");
    }

    @Test
    void theSourcesHealthIsUnhealthyOnceTheSlotIsGone() {
        PostgresCdcSourcePlugin plugin = PgServer.open(PgServer.options(table));
        open.add(plugin);
        PartitionReader reader = plugin.createReader(new SourcePartition(table, 0, Map.of()), null);
        open.add(reader);
        dropSlotUnderTheReader();
        awaitPollFailure(reader, new Captured(plugin.schema()), Duration.ofSeconds(30));
        PgServer.sleep(1_100); // the health is cached for a second
        assertThat(plugin.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);
        assertThat(plugin.health().detail()).contains("does not exist");
    }

    /**
     * What QA ran: end the walsender and drop the slot, looping until the drop lands between the
     * reader's reconnect attempts.
     */
    private void dropSlotUnderTheReader() {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            try {
                PgServer.sql(
                        "SELECT pg_terminate_backend(active_pid) FROM pg_replication_slots WHERE slot_name = '" + table
                                + "' AND active_pid IS NOT NULL",
                        "SELECT pg_drop_replication_slot('" + table + "')");
                return;
            } catch (IllegalStateException stillActive) {
                if (System.nanoTime() > deadline) {
                    throw stillActive;
                }
                PgServer.sleep(20);
            }
        }
    }

    private static PravahaException awaitPollFailure(PartitionReader reader, Captured sink, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                reader.poll(sink, 1024);
            } catch (PravahaException failed) {
                return failed;
            }
            PgServer.sleep(50);
        }
        throw new AssertionError("the reader never reported the slot gone; it delivered " + sink.texts());
    }

    private static String awaitStopped(RegisteredQuery query, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<FeedStatus.Source> stopped = query.feedStatus().firstStopped();
            if (stopped.isPresent()) {
                return Objects.requireNonNull(stopped.get().stop(), "a stopped source says why")
                        .code();
            }
            if (query.failure().isPresent()) {
                return query.failure().get().errorCode().code();
            }
            PgServer.sleep(100);
        }
        throw new AssertionError(
                "still " + query.state() + " with feed " + query.feedStatus() + " long after its slot was dropped");
    }

    private static void awaitRows(RegisteredQuery query, int rows) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (query.view().scan().size() != rows) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + rows + " rows, the view holds "
                        + query.view().scan().size());
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
        open.add(registry);
        return registry;
    }
}
