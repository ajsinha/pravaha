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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every stateful operator a registered query can hold resumes, after a checkpoint and a restart,
 * from the state it checkpointed -- and the first answer after the restart replaces the one the
 * restored view holds rather than sitting beside it (CKPT-2).
 *
 * <p>The unwindowed aggregate was the one that did not. Its accumulators, and the answer it had
 * last published, were in no checkpoint: the view came back holding {@code [2, 350]}, the
 * aggregate came back empty, and the next row published {@code [1, 75]} without retracting
 * anything -- two rows, both wrong, in a view that was supposed to hold one. A sink downstream
 * received the same.
 */
class AggregateRestartTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final StreamSchema PROFILES = StreamSchema.builder("profiles")
            .field("user_id", Types.string())
            .field("tier", Types.string())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    @TempDir
    Path root;

    private RowArena arena;
    private final List<QueryRegistry> registries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
        arena.close();
    }

    @Test
    void aGlobalAggregateResumesFromItsCheckpointAndReplacesTheRestoredAnswer() {
        String sql = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";
        QueryRegistry first = registry();
        RegisteredQuery query = first.register("spend_totals", sql, List.of(0), DANA);
        txn(query, "u1", 100, 1);
        txn(query, "u2", 250, 2);
        query.commit();
        assertThat(rows(query)).containsExactly(List.of(2L, 350L));
        Checkpoint taken = checkpointerOf(query).checkpointNow();
        restart(first);

        QueryRegistry second = registry();
        RegisteredQuery restarted = second.register("spend_totals", sql, List.of(0), DANA);
        assertThat(rows(restarted)).as("the view came back").containsExactly(List.of(2L, 350L));

        txn(restarted, "u3", 75, 3);
        restarted.commit();

        assertThat(rows(restarted))
                .as("one row, counting all three: an aggregate restarted from zero publishes [1, 75] beside the "
                        + "restored [2, 350], retracting nothing it had not itself published")
                .containsExactly(List.of(3L, 425L));
        assertThat(taken.operatorState())
                .as("the aggregate's accumulators are in the checkpoint beside the view")
                .containsKeys("lane-0", QueryExecution.SERVED_VIEW_STATE);
    }

    @Test
    void aGlobalMinMaxAndAverageResumeFromTheirCheckpoint() {
        String sql = "SELECT MIN(amount) AS low, MAX(amount) AS high, AVG(amount) AS mean, COUNT(amount) AS c FROM txn";
        QueryRegistry first = registry();
        RegisteredQuery query = first.register("spend_range", sql, List.of(3), DANA);
        txn(query, "u1", 40, 1);
        txn(query, "u2", 80, 2);
        query.commit();
        assertThat(rows(query)).containsExactly(List.of(40L, 80L, 60L, 2L));
        checkpointerOf(query).checkpointNow();
        restart(first);

        QueryRegistry second = registry();
        RegisteredQuery restarted = second.register("spend_range", sql, List.of(3), DANA);
        txn(restarted, "u3", 60, 3);
        restarted.commit();

        assertThat(rows(restarted))
                .as("the extremes and the average's running sum survived the restart: restarted empty, this is "
                        + "[60, 60, 60, 1] beside the restored row")
                .containsExactly(List.of(40L, 80L, 60L, 3L));
    }

    @Test
    void aGlobalAggregateCheckpointedTwiceAcrossTwoRestartsStillHoldsOneRow() {
        String sql = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";
        QueryRegistry first = registry();
        RegisteredQuery query = first.register("spend_totals", sql, List.of(0), DANA);
        txn(query, "u1", 10, 1);
        query.commit();
        checkpointerOf(query).checkpointNow();
        restart(first);

        QueryRegistry second = registry();
        RegisteredQuery again = second.register("spend_totals", sql, List.of(0), DANA);
        txn(again, "u2", 20, 2);
        again.commit();
        checkpointerOf(again).checkpointNow();
        restart(second);

        QueryRegistry third = registry();
        RegisteredQuery last = third.register("spend_totals", sql, List.of(0), DANA);
        txn(last, "u3", 30, 3);
        last.commit();

        assertThat(rows(last)).containsExactly(List.of(3L, 60L));
    }

    @Test
    void aWindowedAggregateRestartedMidWindowFinishesItWithEveryRow() {
        String sql = "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)";
        QueryRegistry first = registry();
        RegisteredQuery query = first.register("window_spend", sql, List.of(0), DANA);
        txn(query, "u1", 100, 1_000_000_000L);
        txn(query, "u1", 102, 2_000_000_000L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        restart(first);

        QueryRegistry second = registry();
        RegisteredQuery restarted = second.register("window_spend", sql, List.of(0), DANA);
        txn(restarted, "u1", 5, 3_000_000_000L);
        restarted.advanceWatermark(11_000_000_000L);

        assertThat(restarted.view().get("u1").values().orElseThrow()[1])
                .as("207 = 100 + 102 + 5; 5 had the open window been lost")
                .isEqualTo(207L);
    }

    @Test
    void aStreamJoinRestartedMatchesARowItHeldAcrossTheRestart() {
        String sql = "SELECT t.user_id, t.amount, p.tier FROM txn t JOIN profiles p ON t.user_id = p.user_id";
        QueryRegistry first = registry(TXN, PROFILES);
        RegisteredQuery query = first.register("txn_tiers", sql, List.of(0), DANA);
        txn(query, "u1", 300, 1);
        query.commit();
        assertThat(query.view().size()).isZero();
        checkpointerOf(query).checkpointNow();
        restart(first);

        QueryRegistry second = registry(TXN, PROFILES);
        RegisteredQuery restarted = second.register("txn_tiers", sql, List.of(0), DANA);
        profile(restarted, "u1", "gold");
        restarted.commit();

        assertThat(restarted.view().size()).isEqualTo(1);
        Object[] row = restarted.view().get("u1").values().orElseThrow();
        assertThat(row[1]).isEqualTo(300L);
        assertThat(row[2]).isEqualTo("gold");
    }

    /**
     * Found by LANE-2's restart property: a restored view is committed at the frontier it was
     * checkpointed with, and a commit of "what has been applied" before any row had arrived asked
     * for {@code Long.MIN_VALUE} -- refused as a frontier going backwards, on the shared reader's
     * publish thread, which the refusal killed for every query on the binding.
     */
    @Test
    void aRestoredViewCommitsBeforeItsFirstNewRowWithoutItsFrontierGoingBackwards() {
        String sql = "SELECT user_id, amount FROM txn";
        QueryRegistry first = registry();
        RegisteredQuery query = first.register("spend_rows", sql, List.of(0), DANA);
        txn(query, "u1", 10, 5_000_000_000L);
        query.commit();
        checkpointerOf(query).checkpointNow();
        restart(first);

        QueryRegistry second = registry();
        RegisteredQuery restarted = second.register("spend_rows", sql, List.of(0), DANA);
        restarted.commit();

        assertThat(rows(restarted)).containsExactly(List.of("u1", 10L));
        txn(restarted, "u2", 20, 6_000_000_000L);
        restarted.commit();
        assertThat(rows(restarted)).containsExactly(List.of("u1", 10L), List.of("u2", 20L));
    }

    private void restart(QueryRegistry registry) {
        registry.close();
        registries.remove(registry);
    }

    private QueryRegistry registry(StreamSchema... streams) {
        QueryRegistry registry = new QueryRegistry(
                        new ViewCatalog(), streams.length == 0 ? new StreamSchema[] {TXN} : streams)
                .checkpointingTo(
                        root,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1h")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build());
        registries.add(registry);
        return registry;
    }

    /** The view's rows as lists, in key order, so a stray second row is visible in the failure. */
    private static List<List<Object>> rows(RegisteredQuery query) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : query.view().scan()) {
            rows.add(List.of(row));
        }
        rows.sort(Comparator.comparing(Object::toString));
        return rows;
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }

    private void txn(RegisteredQuery query, String user, long amount, long tsNanos) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount).setLong(2, tsNanos);
        writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private void profile(RegisteredQuery query, String user, String tier) {
        RowLayout layout = RowLayout.of(PROFILES);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setString(1, tier);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("profiles", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}
