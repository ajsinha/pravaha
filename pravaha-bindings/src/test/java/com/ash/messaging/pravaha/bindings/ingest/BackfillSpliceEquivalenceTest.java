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
package com.ash.messaging.pravaha.bindings.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryReplacement;
import com.ash.messaging.pravaha.registry.ReplacementOptions;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A blue/green replacement over a real source: the backfill reads the file, splices onto the live
 * tail, and the answer is the one the new query would have had all along (ADR-046).
 *
 * <p>This is the same property the splice's own tests assert, through the whole stack instead of
 * against a list: a plugin, its partitions, the pushdown it is offered, a pump, a lane, the view,
 * and the cutover that moves the name. What is asserted is arithmetic rather than a comparison of
 * two views -- the total and the count of every record in the file -- because a wrong answer that
 * two versions agree on is still wrong.
 */
class BackfillSpliceEquivalenceTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SCHEMA_SPEC = "user_id:STRING,amount:INT64";

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String V1 = "SELECT 'all' AS bucket, SUM(amount) AS total FROM txn";
    private static final String V2 = "SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS payments FROM txn";

    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    @Test
    void theNewVersionHoldsTheAnswerItWouldHaveHadIfItHadAlwaysBeenRunning(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("txn.csv");
        long history = append(data, 1, 300);

        QueryRegistry registry = registry(data);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        // Slow enough that records are still arriving on the live tail while the history is read,
        // which is the case the seam exists for.
        registry.replacements()
                .replace(
                        "orders",
                        V2,
                        List.of(0),
                        DANA,
                        ReplacementOptions.defaults().withRateLimit(400));
        long live = append(data, 301, 380);

        awaitCaughtUp(registry, "orders");
        QueryReplacement.Status status = registry.replacements().of("orders").orElseThrow();
        assertThat(status.progress().historyRows())
                .as("the history was read once: the records that existed when the replacement started")
                .isGreaterThanOrEqualTo(300);
        registry.replacements().cutOver("orders", DANA);

        long total = history + live;
        await(() -> {
            List<Object[]> rows = registry.find("orders").orElseThrow().view().scan();
            return rows.size() == 1 && Long.valueOf(380L).equals(rows.get(0)[2]);
        });
        Object[] answer = registry.find("orders").orElseThrow().view().scan().get(0);
        assertThat(answer[2])
                .as("every record once: 380 of them, none lost at the seam and none counted twice")
                .isEqualTo(380L);
        assertThat(answer[1]).as("and their total").isEqualTo(total);
    }

    @Test
    void aSourceThatCannotBeReplayedIsRefusedByNameBeforeAnythingStarts(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("txn.csv");
        append(data, 1, 3);
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "counting-scan",
                        Map.of("schema", SCHEMA_SPEC, "path", data.toString(), "ordered", "false")));
        // The scan plugin's position names where a pass began rather than the record it was taken
        // after, so there is no offset a history and a live stream could meet at.
        assertThat(feeds.backfillRefusal("txn")).isPresent();
        assertThat(feeds.backfillRefusal("nothing-bound"))
                .hasValueSatisfying(why -> assertThat(why).contains("nothing is bound"));
    }

    @Test
    void aBackfillKeepsItsOwnReaderRatherThanJoiningTheSharedOne(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("txn.csv");
        append(data, 1, 20);
        QueryRegistry registry = registry(data);
        registry.register("orders", V1, List.of(0), DANA);
        awaitRows(registry, "orders", 1);

        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        awaitCaughtUp(registry, "orders");
        // A shared reader hands a joiner the fan-out first and its history afterwards, which
        // duplicates the overlap for that consumer. A replacement's whole claim is that it does
        // not, so it reads on its own.
        assertThat(registry.find("orders").orElseThrow().feed().describe()).contains("txn");
        registry.replacements().cutOver("orders", DANA);
        await(() -> {
            List<Object[]> rows = registry.find("orders").orElseThrow().view().scan();
            return rows.size() == 1 && Long.valueOf(20L).equals(rows.get(0)[2]);
        });
    }

    @Test
    void anUnboundStreamCannotBeBackfilledAndTheReplacementSaysSoRatherThanStarting(@TempDir Path directory) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN).feedingFrom(new PluginSourceFeeds());
        registries.add(registry);
        registry.register("orders", V1, List.of(0), DANA);
        assertThatThrownBy(() ->
                        registry.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4018");
    }

    private QueryRegistry registry(Path data) {
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn", "filesystem", Map.of("path", data.toString(), "schema", SCHEMA_SPEC, "follow", "true")));
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN).feedingFrom(feeds);
        registries.add(registry);
        return registry;
    }

    /** Appends records {@code from}..{@code to} and returns what they add to the total. */
    private static long append(Path data, int from, int to) throws IOException {
        StringBuilder text = new StringBuilder();
        long total = 0;
        for (int i = from; i <= to; i++) {
            text.append("u").append(i % 7).append(',').append(i).append('\n');
            total += i;
        }
        Files.writeString(
                data, text.toString(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return total;
    }

    private static void awaitRows(QueryRegistry registry, String name, int rows) {
        await(() -> registry.find(name).orElseThrow().view().size() == rows);
    }

    private static void awaitCaughtUp(QueryRegistry registry, String name) {
        await(() -> registry.replacements().of(name).orElseThrow().state() == QueryReplacement.State.CAUGHT_UP);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(condition.getAsBoolean())
                .as("the condition did not hold in 60 seconds")
                .isTrue();
    }
}
