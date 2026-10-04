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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;

/** An administrator's lane rebalance: room under auto-from goes to queries already sharing, when asked. */
@Timeout(120)
class LaneRebalanceTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal ADMIN = new Principal("root", "public", Set.of("admin"), Map.of());

    @TempDir
    Path root;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private QueryRegistry registry;

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void roomLeftByADroppedQueryGoesToTheOldestSharedOneOnlyWhenAskedAndItsAnswerSurvives() throws Exception {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < 12; i++) {
            log.append("u" + (i % 3), (long) (i + 1));
        }
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .feedingFrom(log)
                .journalTo(new RegistryJournal(root.resolve("rebalance.journal")))
                .multiplexingLanes(1, 300, 2);
        run("CREATE CONTINUOUS QUERY first_own KEYED BY (user_id) AS SELECT user_id, amount FROM txn");
        run(
                "CREATE CONTINUOUS QUERY second_own KEYED BY (user_id) AS SELECT user_id, amount FROM txn WHERE amount > 1");
        run(
                "CREATE CONTINUOUS QUERY totals KEYED BY (bucket) AS SELECT 'all' AS bucket, SUM(amount) AS total FROM txn");
        run("CREATE CONTINUOUS QUERY later KEYED BY (user_id) AS SELECT user_id, amount FROM txn WHERE amount > 2");
        assertThat(registry.sharedLaneOf("totals")).isPresent();
        assertThat(registry.sharedLaneOf("later")).isPresent();
        await(() -> total(views) == 78L);

        LaneRebalance rebalancer = new LaneRebalance();
        assertThat(rebalancer.plan(registry).moves())
                .as("no room: nothing to do")
                .isEmpty();

        registry.drop("first_own");
        assertThat(registry.sharedLaneOf("totals"))
                .as("dropping a query moves nothing by itself")
                .isPresent();

        LaneRebalance.Plan plan = rebalancer.plan(registry);
        assertThat(plan.mode()).isEqualTo("auto");
        assertThat(plan.room()).isEqualTo(1);
        assertThat(plan.moves()).extracting(LaneRebalance.Move::name).containsExactly("totals");
        assertThat(plan.moves().get(0).status()).isEqualTo("planned");
        assertThat(registry.sharedLaneOf("totals"))
                .as("a dry run changes nothing")
                .isPresent();

        rebalancer.start(registry, ADMIN);
        rebalancer.awaitIdle(Duration.ofSeconds(60));

        LaneRebalance.Plan done = rebalancer.status(registry);
        assertThat(done.running()).isFalse();
        assertThat(done.moves().get(0).status())
                .as(done.moves().get(0).detail())
                .isEqualTo("moved");
        assertThat(registry.sharedLaneOf("totals"))
                .as("on a lane of its own now")
                .isEmpty();
        assertThat(registry.require("totals").dedicatedLane())
                .as("moved, not pinned")
                .isFalse();
        assertThat(registry.sharedLaneOf("later")).isPresent();
        assertThat(total(views)).isEqualTo(78L);
        log.append("u0", 100L);
        await(() -> total(views) == 178L);
        assertThat(rebalancer.plan(registry).moves()).as("the room is used up").isEmpty();
    }

    private void run(String sql) {
        new ContinuousQueryStatements(registry, SecurityPolicy.PERMISSIVE, AuditSink.NONE)
                .execute(ContinuousStatements.recognize(sql).orElseThrow(), ADMIN);
    }

    private static long total(ViewCatalog views) {
        var rows = new ViewQuery(views).execute("SELECT total FROM totals").rows();
        return rows.isEmpty() ? -1 : ((Number) rows.get(0)[0]).longValue();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached in 30s");
            }
            Thread.sleep(10);
        }
    }
}
