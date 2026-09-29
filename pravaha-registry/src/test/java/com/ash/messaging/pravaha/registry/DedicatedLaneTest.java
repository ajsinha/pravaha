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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code WITH (lane = 'dedicated')}: a query that keeps a lane of its own whatever the registry's
 * lane-sharing mode, across a restart, and refused where it would join a computation already on a
 * shared lane.
 */
@Timeout(120)
class DedicatedLaneTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    @TempDir
    Path root;

    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    @Test
    void sharingOnADedicatedQueryOwnsALaneWhileTheOthersShare() {
        QueryRegistry registry = registry(null).multiplexingLanes(2, 300);
        run(registry, "CREATE CONTINUOUS QUERY a KEYED BY (user_id) AS SELECT user_id, amount FROM txn");
        run(
                registry,
                "CREATE CONTINUOUS QUERY b KEYED BY (user_id) WITH (lane = 'dedicated') "
                        + "AS SELECT user_id, amount FROM txn WHERE amount > 10");
        run(
                registry,
                "CREATE CONTINUOUS QUERY c KEYED BY (user_id) WITH (lane = 'shared') "
                        + "AS SELECT user_id, amount FROM txn WHERE amount > 20");

        assertThat(registry.sharedLaneOf("a")).isPresent();
        assertThat(registry.sharedLaneOf("b"))
                .as("dedicated: a lane of its own")
                .isEmpty();
        assertThat(registry.sharedLaneOf("c")).isPresent();
        assertThat(registry.require("b").dedicatedLane()).isTrue();
        assertThat(registry.require("a").dedicatedLane()).isFalse();
        assertThat(registry.queriesOnOwnLanes()).isEqualTo(1);
    }

    @Test
    void autoADedicatedQueryPastTheThresholdStillOwnsALane() {
        QueryRegistry registry = registry(null).multiplexingLanes(2, 300, 1);
        run(registry, "CREATE CONTINUOUS QUERY earlier KEYED BY (user_id) AS SELECT user_id, amount FROM txn");
        run(
                registry,
                "CREATE CONTINUOUS QUERY later KEYED BY (user_id) AS SELECT user_id, amount FROM txn "
                        + "WHERE amount > 10");
        run(
                registry,
                "CREATE CONTINUOUS QUERY kept_apart KEYED BY (user_id) WITH (lane = 'dedicated') "
                        + "AS SELECT user_id, amount FROM txn WHERE amount > 20");

        assertThat(registry.sharedLaneOf("earlier")).as("under the threshold").isEmpty();
        assertThat(registry.sharedLaneOf("later")).as("past it, sharing").isPresent();
        assertThat(registry.sharedLaneOf("kept_apart"))
                .as("past it, and dedicated")
                .isEmpty();
    }

    @Test
    void aRestartBringsADedicatedQueryBackOnItsOwnLaneAndCompactionKeepsIt() {
        QueryRegistry first = registry(root.resolve("registry.journal")).multiplexingLanes(2, 300);
        run(
                first,
                "CREATE CONTINUOUS QUERY solo KEYED BY (user_id) WITH (lane = 'dedicated', index = 'amount') "
                        + "AS SELECT user_id, amount FROM txn");
        run(
                first,
                "CREATE CONTINUOUS QUERY crowd KEYED BY (user_id) AS SELECT user_id, amount FROM txn WHERE amount > 1");
        RegistryJournal journal = first.journal();
        assertThat(journal.replay())
                .extracting(RegistryJournal.Entry::name, RegistryJournal.Entry::dedicatedLane)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("solo", true),
                        org.assertj.core.groups.Tuple.tuple("crowd", false));
        journal.compact(journal.replay());
        assertThat(journal.replay().get(0).dedicatedLane())
                .as("compaction keeps it")
                .isTrue();
        assertThat(journal.replay().get(0).indexed()).containsExactly(1);
        first.close();
        registries.remove(first);

        QueryRegistry second = registry(root.resolve("registry.journal")).multiplexingLanes(2, 300);
        assertThat(second.recover(id -> Optional.of(DANA)).recovered()).containsExactly("solo", "crowd");
        assertThat(second.sharedLaneOf("solo")).isEmpty();
        assertThat(second.require("solo").dedicatedLane()).isTrue();
        assertThat(second.sharedLaneOf("crowd")).isPresent();
    }

    @Test
    void theSameComputationAlreadyOnASharedLaneIsRefusedAndOnItsOwnLaneIsJoined() {
        String select = " AS SELECT user_id, amount FROM txn";
        QueryRegistry sharing = registry(null).multiplexingLanes(2, 300);
        run(sharing, "CREATE CONTINUOUS QUERY shared_one KEYED BY (user_id)" + select);
        assertThatThrownBy(() -> run(
                        sharing,
                        "CREATE CONTINUOUS QUERY wants_own KEYED BY (user_id) WITH (lane = 'dedicated')" + select))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8017")
                .hasMessageContaining("shared_one")
                .hasMessageContaining("never moved");
        assertThat(sharing.find("wants_own"))
                .as("refused before the name was taken")
                .isEmpty();

        QueryRegistry auto = registry(root.resolve("auto.journal")).multiplexingLanes(2, 300, 5);
        run(auto, "CREATE CONTINUOUS QUERY plain KEYED BY (user_id)" + select);
        run(auto, "CREATE CONTINUOUS QUERY kept KEYED BY (user_id) WITH (lane = 'dedicated')" + select);
        assertThat(auto.require("plain")).isSameAs(auto.require("kept"));
        assertThat(auto.require("plain").dedicatedLane()).isTrue();
        assertThat(auto.journal().replay())
                .as("both names journalled dedicated, so the first registrant replays onto its own lane")
                .allMatch(RegistryJournal.Entry::dedicatedLane);
    }

    @Test
    void createOrReplaceMovesARunningQueryOntoItsOwnLaneAndBackWithTheSameSql() {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < 12; i++) {
            log.append("u" + (i % 3), (long) (i + 1));
        }
        ViewCatalog views = new ViewCatalog();
        QueryRegistry registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .feedingFrom(log)
                .journalTo(new RegistryJournal(root.resolve("move.journal")))
                .multiplexingLanes(2, 300);
        registries.add(registry);
        String totals = "KEYED BY (bucket) AS SELECT 'all' AS bucket, SUM(amount) AS total FROM txn";
        run(registry, "CREATE CONTINUOUS QUERY totals " + totals);
        await(() -> totalsOf(views).equals(Map.of("all", 78L)));
        assertThat(registry.sharedLaneOf("totals")).isPresent();

        assertThatThrownBy(() -> run(registry, "CREATE OR REPLACE CONTINUOUS QUERY totals " + totals))
                .as("the same SQL on the same lane would cut over to itself")
                .hasMessageContaining("lane = 'dedicated'");

        run(registry, "CREATE OR REPLACE CONTINUOUS QUERY totals WITH (lane = 'dedicated') " + totals);
        await(() -> registry.replacements().of("totals").orElseThrow().state() == QueryReplacement.State.CAUGHT_UP);
        registry.replacements().cutOver("totals", DANA);
        assertThat(registry.sharedLaneOf("totals"))
                .as("moved onto a lane of its own")
                .isEmpty();
        assertThat(registry.require("totals").dedicatedLane()).isTrue();
        assertThat(registry.journal().replay().get(0).dedicatedLane()).isTrue();
        log.append("u0", 100L);
        await(() -> totalsOf(views).equals(Map.of("all", 178L)));

        registry.replacements().finish("totals", DANA);
        run(registry, "CREATE OR REPLACE CONTINUOUS QUERY totals WITH (lane = 'shared', cutover = 'auto') " + totals);
        await(() -> registry.sharedLaneOf("totals").isPresent());
        assertThat(registry.require("totals").dedicatedLane()).isFalse();
        assertThat(registry.journal().replay().get(0).dedicatedLane()).isFalse();
        assertThat(totalsOf(views)).isEqualTo(Map.of("all", 178L));
    }

    private static Map<String, Long> totalsOf(ViewCatalog views) {
        Map<String, Long> totals = new java.util.TreeMap<>();
        for (Object[] row :
                new ViewQuery(views).execute("SELECT bucket, total FROM totals").rows()) {
            totals.put((String) row[0], ((Number) row[1]).longValue());
        }
        return totals;
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached in 30s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private QueryRegistry registry(Path journal) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
        if (journal != null) {
            registry.journalTo(new RegistryJournal(journal));
        }
        registries.add(registry);
        return registry;
    }

    private static ViewQuery.Result run(QueryRegistry registry, String sql) {
        return new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .execute(ContinuousStatements.recognize(sql).orElseThrow(), DANA);
    }
}
