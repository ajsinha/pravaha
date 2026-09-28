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
package com.ash.messaging.pravaha.it.qa.sql;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Queries over queries end to end (ADR-056): a real node, every statement over Flight SQL through
 * the Java SDK, a journal and checkpoints, and a restart.
 *
 * <p>{@code cleaned} filters a stream and is keyed by user, so a second row for a user replaces the
 * first -- an update -- and a retraction removes it -- a delete. {@code by_region} aggregates
 * {@code cleaned}; {@code big_regions} is an alert condition over {@code by_region}. The rows are
 * pushed into {@code cleaned} by hand, because that is the one way into this engine that can say
 * "delete" without a database behind it; everything downstream of that is the engine's.
 */
@Timeout(180)
class QueriesOnQueriesEndToEndTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("amount", Types.int64())
            .build();

    @TempDir
    Path dir;

    private RowArena arena;
    private long sequence = 1;

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    @Test
    void qoq001_aChainFollowsUpdatesAndDeletesRefusesTheDropAndTheLoopAndSurvivesARestart() throws Exception {
        PravahaNode node = node();
        node.start();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url(node))) {
            run(
                    client,
                    "CREATE CONTINUOUS QUERY cleaned KEYED BY (user_id) "
                            + "AS SELECT user_id, region, amount FROM txn WHERE amount > 0");
            run(
                    client,
                    "CREATE CONTINUOUS QUERY by_region KEYED BY (region) "
                            + "AS SELECT region, SUM(amount) AS total, COUNT(*) AS n FROM cleaned GROUP BY region");
            run(
                    client,
                    "CREATE CONTINUOUS QUERY big_regions KEYED BY (region) "
                            + "AS SELECT region, total FROM by_region WHERE total > 100");

            RegisteredQuery cleaned = registry(node).require("cleaned");
            push(cleaned, "u1", "eu", 100, 1);
            push(cleaned, "u2", "eu", 50, 1);
            push(cleaned, "u3", "us", 70, 1);
            cleaned.commit();
            awaitAnswer(client, "SELECT region, total, n FROM by_region", Map.of("eu", "150,2", "us", "70,1"));
            awaitAnswer(client, "SELECT region, total FROM big_regions", Map.of("eu", "150"));

            push(cleaned, "u1", "eu", 30, 1); // an update: the view replaces u1's row
            push(cleaned, "u2", "eu", 50, -1); // a delete
            push(cleaned, "u4", "us", 90, 1);
            cleaned.commit();
            awaitAnswer(client, "SELECT region, total, n FROM by_region", Map.of("eu", "30,1", "us", "160,2"));
            awaitAnswer(client, "SELECT region, total FROM big_regions", Map.of("us", "160"));

            assertThatThrownBy(() -> run(client, "DROP CONTINUOUS QUERY cleaned"))
                    .hasMessageContaining("PRV-8024")
                    .hasMessageContaining("by_region");
            assertThatThrownBy(() -> run(
                            client,
                            "CREATE OR REPLACE CONTINUOUS QUERY cleaned KEYED BY (region) "
                                    + "AS SELECT region, total FROM big_regions"))
                    .hasMessageContaining("PRV-8025")
                    .hasMessageContaining("cleaned reads big_regions reads by_region reads cleaned");
            assertThatThrownBy(() -> run(
                            client,
                            "CREATE CONTINUOUS QUERY top_region KEYED BY (region) "
                                    + "AS SELECT region, MAX(total) AS top FROM by_region GROUP BY region"))
                    .hasMessageContaining("PRV-2075");
            assertThat(registry(node).dependantsOf("cleaned")).containsExactly("by_region");
            assertThat(registry(node).readsFrom("big_regions")).containsExactly("by_region");

            awaitCheckpointed(registry(node), "cleaned", "by_region", "big_regions");
        } finally {
            node.stop();
        }

        PravahaNode restarted = node();
        restarted.start();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url(restarted))) {
            assertThat(registry(restarted).names()).containsExactly("cleaned", "by_region", "big_regions");
            awaitAnswer(client, "SELECT region, total, n FROM by_region", Map.of("eu", "30,1", "us", "160,2"));
            awaitAnswer(client, "SELECT region, total FROM big_regions", Map.of("us", "160"));

            RegisteredQuery cleaned = registry(restarted).require("cleaned");
            push(cleaned, "u4", "us", 90, -1);
            push(cleaned, "u5", "eu", 200, 1);
            cleaned.commit();
            awaitAnswer(client, "SELECT region, total, n FROM by_region", Map.of("eu", "230,2", "us", "70,1"));
            awaitAnswer(client, "SELECT region, total FROM big_regions", Map.of("eu", "230"));

            run(client, "DROP CONTINUOUS QUERY big_regions");
            run(client, "DROP CONTINUOUS QUERY by_region");
            run(client, "DROP CONTINUOUS QUERY cleaned");
            assertThat(registry(restarted).names()).isEmpty();
        } finally {
            restarted.stop();
        }
    }

    // ------------------------------------------------------------------ helpers

    private PravahaNode node() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(dir.resolve("registry.journal").toString());
        persistence.getCheckpoint().setDirectory(dir.resolve("checkpoints").toString());
        persistence.getCheckpoint().setInterval(Duration.ofMillis(200));
        return PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(new SourceBindingProperties())
                .withSinks(new SinkBindingProperties())
                .withDeclaredStreams(new StreamDeclarationProperties())
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("qoq-e2e")
                .build();
    }

    private static String url(PravahaNode node) {
        return "grpc://127.0.0.1:" + node.flightPort().orElseThrow();
    }

    private static QueryRegistry registry(PravahaNode node) {
        return node.registry().orElseThrow();
    }

    private static List<Object[]> run(PravahaFlightClient client, String sql) {
        try (QueryResult result = client.query(sql)) {
            return result.toList();
        }
    }

    /** Waits for the read to answer exactly {@code expected}: first column to the rest, joined by commas. */
    private static void awaitAnswer(PravahaFlightClient client, String sql, Map<String, String> expected)
            throws InterruptedException {
        Supplier<Map<String, String>> read = () -> {
            Map<String, String> answer = new TreeMap<>();
            for (Object[] row : run(client, sql)) {
                StringBuilder rest = new StringBuilder();
                for (int i = 1; i < row.length; i++) {
                    rest.append(i == 1 ? "" : ",").append(row[i]);
                }
                answer.put(String.valueOf(row[0]), rest.toString());
            }
            return answer;
        };
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!read.get().equals(new TreeMap<>(expected)) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(read.get()).as(sql).isEqualTo(new TreeMap<>(expected));
    }

    /** Waits until each query has stored a checkpoint after this call, so the restart restores one. */
    private static void awaitCheckpointed(QueryRegistry registry, String... names) throws InterruptedException {
        java.time.Instant after = java.time.Instant.now();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (String name : names) {
            RegisteredQuery query = registry.require(name);
            while (query.lastCheckpoint().map(at -> !at.isAfter(after)).orElse(true) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(query.lastCheckpoint()).as(name + " checkpointed").isPresent();
        }
    }

    private void push(RegisteredQuery query, String user, String region, long amount, long weight) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setString(1, region).setLong(2, amount);
        long at = sequence++ * 1_000_000L;
        writer.weight(weight).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        query.awaitApplied(Duration.ofSeconds(10));
    }
}
