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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A replacement across a restart (ADR-046).
 *
 * <p>A backfill can run for hours and a node can restart during one, so the decision is that a
 * replacement <strong>survives</strong> rather than being lost: the journal records it as pending,
 * the name comes back as the version that was serving it -- the journal never said otherwise -- and
 * the candidate is started again, resuming from its own checkpoints where it has them.
 *
 * <p>After a cutover the name comes back as the new version, checkpointing into the directory it
 * backfilled into. The directory travels with the registration rather than the files being moved,
 * because a move leaves a window in which a crash has the name pointing at a directory holding the
 * other version's state.
 */
class ReplacementRestartTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String V1 = "SELECT 'all' AS bucket, SUM(amount) AS total FROM txn";
    private static final String V2 = "SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS payments FROM txn";

    @TempDir
    Path directory;

    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    @Test
    void aReplacementStillBackfillingWhenTheNodeRestartsIsStartedAgainFromTheJournal() {
        ReplayableLog log = log(300);
        QueryRegistry first = registry(log);
        first.register("orders", V1, List.of(0), DANA);
        awaitRows(first, "orders", 1);
        // A rate low enough that the backfill is certainly still reading when the node goes, and
        // high enough that it finishes in a test after it comes back.
        first.replacements()
                .replace(
                        "orders",
                        V2,
                        List.of(0),
                        DANA,
                        ReplacementOptions.defaults().withRateLimit(200));
        assertThat(first.replacements().of("orders").orElseThrow().state())
                .isEqualTo(QueryReplacement.State.BACKFILLING);
        first.close();

        QueryRegistry second = registry(log);
        QueryRegistry.Recovery recovery = second.recover(id -> Optional.of(DANA));
        assertThat(recovery.recovered()).containsExactly("orders");
        assertThat(recovery.refused()).isEmpty();

        // The name is the version that was serving it: a replacement that had not cut over never
        // owned it, and a restart does not hand it over.
        assertThat(second.find("orders").orElseThrow().sql()).isEqualTo(V1);
        QueryReplacement.Status resumed = second.replacements().of("orders").orElseThrow();
        assertThat(resumed.state()).isIn(QueryReplacement.State.BACKFILLING, QueryReplacement.State.CAUGHT_UP);
        assertThat(resumed.sql()).isEqualTo(V2);

        assertThat(resumed.options().rateLimit())
                .as("the options it was started with came back with it")
                .isEqualTo(200);

        // And it finishes: the replacement that survived the restart cuts over like any other.
        awaitCaughtUp(second, "orders");
        second.replacements().cutOver("orders", DANA);
        assertThat(second.find("orders").orElseThrow().sql()).isEqualTo(V2);
    }

    @Test
    void afterACutoverTheNameComesBackAsTheNewVersionWithTheStateItBackfilled() {
        ReplayableLog log = log(40);
        QueryRegistry first = registry(log);
        first.register("orders", V1, List.of(0), DANA);
        awaitRows(first, "orders", 1);
        first.replacements().replace("orders", V2, List.of(0), DANA, ReplacementOptions.defaults());
        awaitCaughtUp(first, "orders");
        first.replacements().cutOver("orders", DANA);
        long total = (long) first.find("orders").orElseThrow().view().scan().get(0)[1];
        long payments = (long) first.find("orders").orElseThrow().view().scan().get(0)[2];
        // A checkpoint of the new version, so the restart has its state rather than only its SQL.
        first.find("orders").orElseThrow().checkpointNow();
        first.close();

        QueryRegistry second = registry(log);
        assertThat(second.recover(id -> Optional.of(DANA)).recovered()).containsExactly("orders");
        assertThat(second.find("orders").orElseThrow().sql()).isEqualTo(V2);
        assertThat(second.replacements().of("orders"))
                .as("the replacement is over: nothing is in flight, and there is no rollback across a restart")
                .isEmpty();
        awaitRows(second, "orders", 1);
        Object[] restored = second.find("orders").orElseThrow().view().scan().get(0);
        assertThat(restored[1])
                .as("the state it backfilled, restored from the directory that travelled with the name")
                .isEqualTo(total);
        assertThat(restored[2]).isEqualTo(payments);
    }

    private ReplayableLog log(int rows) {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < rows; i++) {
            log.append("u" + (i % 3), (long) (i + 1));
        }
        return log;
    }

    private QueryRegistry registry(ReplayableLog log) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .feedingFrom(log)
                .journalTo(new RegistryJournal(directory.resolve("registry.journal")))
                .checkpointingTo(
                        directory.resolve("checkpoints"),
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    private static void awaitRows(QueryRegistry registry, String name, int rows) {
        await(() -> registry.find(name).orElseThrow().view().size() == rows);
    }

    private static void awaitCaughtUp(QueryRegistry registry, String name) {
        await(() -> registry.replacements().of(name).orElseThrow().state() == QueryReplacement.State.CAUGHT_UP);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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
                .as("the condition did not hold in 30 seconds")
                .isTrue();
    }
}
