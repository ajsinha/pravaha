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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KEYEDWT-1: a consumer that sums a subscription's weights holds exactly the view, through upserts --
 * when it subscribes to the answer.
 *
 * <p>A keyed view over a stream that only inserts -- the latest row per key -- hands a changelog
 * subscriber what the lanes applied: {@code +1} for each new row of a key and nothing for the row it
 * replaced, because the view keeps both and shows the newer (VIEWW-1). CONCEPTS §4 told a consumer
 * keeping its own copy to sum those weights, and the copy grew a row per upsert while the view showed
 * one per key. The first case pins that drift, which stays the changelog's documented contract (its
 * weights pass through verbatim, STRM-008 to STRM-012). {@link SubscriptionOptions#followingTheAnswer()}
 * hands each commit as the rows that left the answer at {@code -1} and the rows that entered it at
 * {@code +1}, as ADR-056's answer-following already was, and the rest hold it to the view.
 */
class KeyedWeightsSubscriptionTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private QueryRegistry registry;
    private RowArena arena;
    private RegisteredQuery latest;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 22, 8);
        // Keyed by user: the view is each user's latest row, an upsert per arrival.
        latest = registry.register("latest", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    private static final SubscriptionOptions ANSWER = SubscriptionOptions.DEFAULT.followingTheAnswer();

    @Test
    void summingTheChangelogsWeightsThroughUpsertsDriftsFromTheView() {
        List<ViewChange> heard = new CopyOnWriteArrayList<>();
        try (Subscription subscription = latest.subscribe(heard::addAll)) {
            feed("u1", 10, 1);
            latest.commit();
            feed("u1", 20, 1);
            latest.commit();
            settle(subscription);
        }

        // The finding, reproduced: two rows at weight 1 where the view shows one.
        assertThat(zset(heard)).containsOnly(Map.entry(List.of("u1", 10L), 1L), Map.entry(List.of("u1", 20L), 1L));
        assertThat(shown(latest)).containsOnly(Map.entry(List.of("u1", 20L), 1L));
    }

    @Test
    void summingAnAnswerSubscriptionsWeightsThroughUpsertsHoldsExactlyTheView() {
        List<ViewChange> heard = new CopyOnWriteArrayList<>();
        try (Subscription subscription = latest.subscribe(ANSWER, heard::addAll)) {
            feed("u1", 10, 1);
            latest.commit();
            feed("u1", 20, 1);
            feed("u2", 5, 1);
            latest.commit();
            feed("u1", 30, 1);
            latest.commit();
            // A retraction of the row the key shows brings the one behind it back (VIEWW-1).
            feed("u1", 30, -1);
            latest.commit();
            settle(subscription);
        }

        assertThat(positive(zset(heard))).isEqualTo(shown(latest));
        assertThat(zset(heard))
                .as("summed weights are the view: one row per key at weight 1, nothing left over")
                .containsOnly(Map.entry(List.of("u1", 20L), 1L), Map.entry(List.of("u2", 5L), 1L));
    }

    @Test
    void anUpsertArrivesAsTheReplacedRowWithdrawnAndTheNewOneAdded() {
        feed("u1", 10, 1);
        latest.commit();
        List<ViewChange> heard = new CopyOnWriteArrayList<>();
        try (Subscription subscription = latest.subscribe(ANSWER, heard::addAll)) {
            feed("u1", 20, 1);
            latest.commit();
            settle(subscription);
        }

        assertThat(heard)
                .containsExactly(
                        new ViewChange(new Object[] {"u1", 10L}, -1), new ViewChange(new Object[] {"u1", 20L}, 1));
    }

    @Test
    void aSnapshotSubscriptionStartsFromTheRowsTheViewShowsAndStaysEqualToIt() {
        feed("u1", 10, 1);
        feed("u1", 20, 1);
        latest.commit();

        SubscribeFromSnapshotTest.Mirror mirror = new SubscribeFromSnapshotTest.Mirror();
        try (Subscription subscription = latest.subscribeFromSnapshot(ANSWER, SubscriptionFilter.none(), mirror)) {
            settle(subscription);
            // The view holds u1 twice (VIEWW-1) and shows 20; the snapshot is what it shows.
            assertThat(mirror.rows()).containsOnly(Map.entry(List.of("u1", 20L), 1L));

            feed("u1", 20, -1);
            latest.commit();
            feed("u2", 7, 1);
            feed("u2", 8, 1);
            latest.commit();
            settle(subscription);
        }

        assertThat(mirror.rows()).isEqualTo(shown(latest));
        assertThat(mirror.outOfOrder).isFalse();
    }

    @Test
    void theChangelogOfAQueryOverTheViewIsTheViewsAnswerSoItsWeightsSumToTheView() {
        // What CONCEPTS §4 tells a client on the wire to do: a query over a query is fed the
        // upstream's answer changing (ADR-056), so a plain subscription to it sums to the upstream.
        RegisteredQuery copy =
                registry.register("latest_copy", "SELECT user_id, amount FROM latest", List.of(0), Principal.ANONYMOUS);
        List<ViewChange> heard = new CopyOnWriteArrayList<>();
        try (Subscription subscription = copy.subscribe(heard::addAll)) {
            feed("u1", 10, 1);
            latest.commit();
            feed("u1", 20, 1);
            feed("u2", 5, 1);
            latest.commit();
            feed("u1", 20, -1);
            latest.commit();

            Map<List<Object>, Long> expected = Map.of(List.of("u1", 10L), 1L, List.of("u2", 5L), 1L);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!zset(heard).equals(expected) && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
            }
            assertThat(zset(heard)).isEqualTo(expected);
            assertThat(shown(latest)).isEqualTo(expected);
        }
    }

    @Test
    void anAnswerSnapshotAttachedMidCommitStartsWithTheRowsInFlight() {
        feed("u1", 10, 1);
        latest.commit();
        feed("u1", 20, 1);

        SubscribeFromSnapshotTest.Mirror mirror = new SubscribeFromSnapshotTest.Mirror();
        try (Subscription subscription = latest.subscribeFromSnapshot(ANSWER, SubscriptionFilter.none(), mirror)) {
            settle(subscription);
            assertThat(mirror.snapshots).isEqualTo(1);
            assertThat(mirror.rows()).containsOnly(Map.entry(List.of("u1", 20L), 1L));

            feed("u1", 30, 1);
            latest.commit();
            settle(subscription);
        }
        assertThat(mirror.rows()).isEqualTo(shown(latest));
    }

    @Test
    void aCommitThatChangesNothingInTheAnswerIsNotDelivered() {
        feed("u1", 10, 1);
        latest.commit();
        List<ViewChange> heard = new ArrayList<>();
        try (Subscription subscription = latest.subscribe(ANSWER, changes -> heard.addAll(changes))) {
            // The same row again: the view's weight for it moves, what a reader sees does not.
            feed("u1", 10, 1);
            latest.commit();
            settle(subscription);
        }
        assertThat(heard).isEmpty();
    }

    private void feed(String user, long amount, long weight) {
        arena.reset();
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(weight).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(latest.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        assertThat(latest.awaitApplied(Duration.ofSeconds(10))).isTrue();
    }

    private static void settle(Subscription subscription) {
        assertThat(subscription.awaitQuiet(Duration.ofSeconds(10))).isTrue();
    }

    /** What a reader of the view sees: each row it shows, once. */
    private static Map<List<Object>, Long> shown(RegisteredQuery query) {
        Map<List<Object>, Long> rows = new HashMap<>();
        for (Object[] row : query.view().scan()) {
            rows.merge(Arrays.asList(row), 1L, Long::sum);
        }
        return rows;
    }

    private static Map<List<Object>, Long> zset(List<ViewChange> changes) {
        Map<List<Object>, Long> sums = new HashMap<>();
        for (ViewChange change : changes) {
            sums.merge(Arrays.asList(change.values()), change.weight(), Long::sum);
        }
        sums.values().removeIf(weight -> weight == 0);
        return sums;
    }

    private static Map<List<Object>, Long> positive(Map<List<Object>, Long> zset) {
        Map<List<Object>, Long> present = new HashMap<>(zset);
        present.values().removeIf(weight -> weight <= 0);
        return present;
    }
}
