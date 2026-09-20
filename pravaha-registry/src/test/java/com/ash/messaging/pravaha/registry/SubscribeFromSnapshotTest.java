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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A registered query's subscription that starts from the view and misses nothing after it (SUB-1).
 *
 * <p>The interleaving that lost a commit is made to happen, not waited for: rows are handed to the
 * query and applied, and nothing has committed them, when the subscriber attaches and reads. That
 * is the feed's twenty-millisecond publishing window held open.
 */
@Timeout(120)
class SubscribeFromSnapshotTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 22, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    private RegisteredQuery projection() {
        return registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);
    }

    private synchronized boolean offer(RegisteredQuery query, String user, long amount) {
        // The lane copies a row when it accepts it, so the arena is reused for every one.
        arena.reset();
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private void applied(RegisteredQuery query, String user, long amount) {
        assertThat(offer(query, user, amount)).isTrue();
        assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
    }

    /**
     * Waits until the subscriber has been handed its snapshot and everything committed after it.
     *
     * <p>The snapshot and every commit reach the consumer on the subscription's own thread, so
     * that a slow one cannot hold the thread that committed the view (STRM-8). Subscribing and
     * committing start the work; this is where a test that wants a definite answer waits for it.
     */
    private static void settle(Subscription subscription) {
        assertThat(subscription.awaitQuiet(Duration.ofSeconds(10)))
                .as("the subscriber was handed everything meant for it")
                .isTrue();
    }

    @Test
    void subscribingThenReadingLosesTheCommitInFlight() {
        // The defect, as a plain subscription still has it: kept so the documentation's word
        // "gapful" is a tested statement rather than a warning.
        RegisteredQuery query = projection();
        applied(query, "u1", 10);

        List<ViewChange> heard = new ArrayList<>();
        try (Subscription ignored = query.subscribe(heard::addAll)) {
            List<Object[]> read = query.view().scan();
            query.commit();

            assertThat(read).isEmpty();
            assertThat(heard).isEmpty();
            assertThat(query.view().scan()).hasSize(1);
        }
    }

    @Test
    void aSnapshotSubscriptionAttachedMidCommitStartsWithTheRowsInFlight() {
        RegisteredQuery query = projection();
        applied(query, "u1", 10);
        query.commit();
        applied(query, "u2", 20);

        Mirror mirror = new Mirror();
        try (Subscription subscription = query.subscribeFromSnapshot(mirror)) {
            settle(subscription);
            assertThat(mirror.snapshots)
                    .as("the subscription drew the commit boundary itself, so the snapshot is here")
                    .isEqualTo(1);
            assertThat(subscription.snapshotFrontier()).isPresent();
            assertThat(mirror.rows()).containsOnlyKeys(List.of("u1", 10L), List.of("u2", 20L));
            assertThat(query.subscriberCount()).isEqualTo(1);

            applied(query, "u3", 30);
            query.commit();
            settle(subscription);
        }
        assertThat(mirror.rows()).isEqualTo(zset(query.view().committedRows()));
        assertThat(query.subscriberCount()).isZero();
    }

    @Test
    void theSnapshotIsFilteredAndAnEmptyOneStillArrives() {
        RegisteredQuery query = projection();
        applied(query, "u1", 10);
        applied(query, "u2", 20);
        query.commit();

        Mirror mirror = new Mirror();
        SubscriptionFilter onlyU2 = SubscriptionFilter.matching(query.outputSchema(), Map.of("user_id", "u2"));
        try (Subscription subscription = query.subscribeFromSnapshot(SubscriptionOptions.DEFAULT, onlyU2, mirror)) {
            settle(subscription);
            assertThat(mirror.rows()).containsOnlyKeys(List.of("u2", 20L));
            assertThat(subscription.delivered()).isEqualTo(1);
        }

        Mirror nothing = new Mirror();
        SubscriptionFilter noOne = SubscriptionFilter.matching(query.outputSchema(), Map.of("user_id", "u9"));
        try (Subscription ignored = query.subscribeFromSnapshot(SubscriptionOptions.DEFAULT, noOne, nothing)) {
            settle(ignored);
            assertThat(nothing.snapshots).isEqualTo(1);
            assertThat(nothing.rows()).isEmpty();
        }
    }

    @Test
    void aListenerThatThrowsOnItsSnapshotIsDetachedAndSaysWhy() {
        RegisteredQuery query = projection();
        Subscription subscription = query.subscribeFromSnapshot(new SubscriptionListener() {
            @Override
            public void onSnapshot(List<ViewChange> rows, long frontier) {
                throw new IllegalStateException("no room");
            }

            @Override
            public void onCommit(List<ViewChange> changes, long frontier) {}
        });
        settle(subscription);
        assertThat(subscription.isClosed()).isTrue();
        assertThat(subscription.failure())
                .get()
                .extracting(Throwable::getMessage)
                .asString()
                .contains("no room");
    }

    @Test
    void aSubscriptionToADroppedQueryIsRefused() {
        RegisteredQuery query = projection();
        registry.drop("q");
        assertThatThrownBy(() -> query.subscribeFromSnapshot(new Mirror()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8003");
    }

    /**
     * Rows fed on one thread, commits from two, subscribers attaching at random moments to an
     * aggregate whose every publish is a retraction and an insert: each one's snapshot plus changes
     * is the view once everything has committed.
     */
    @Test
    void everySnapshotSubscriberOfABusyAggregateEndsHoldingTheView() throws Exception {
        RegisteredQuery query = registry.register(
                "totals", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn", List.of(0), Principal.ANONYMOUS);
        for (int round = 0; round < 5; round++) {
            AtomicBoolean stop = new AtomicBoolean();
            Thread feeder = Thread.ofPlatform().daemon().start(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                while (!stop.get()) {
                    if (!offer(query, "u" + random.nextInt(10), random.nextLong(100))) {
                        query.awaitApplied(Duration.ofMillis(10));
                    }
                }
            });
            Runnable committing = () -> {
                while (!stop.get()) {
                    query.commit();
                }
            };
            Thread timer = Thread.ofPlatform().daemon().start(committing);
            Thread reader = Thread.ofPlatform().daemon().start(committing);

            List<Mirror> mirrors = new CopyOnWriteArrayList<>();
            List<Subscription> subscriptions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Thread.sleep(ThreadLocalRandom.current().nextInt(5));
                Mirror mirror = new Mirror();
                mirrors.add(mirror);
                subscriptions.add(query.subscribeFromSnapshot(
                        SubscriptionOptions.of(Integer.MAX_VALUE, SubscriptionOptions.Overflow.FAIL),
                        SubscriptionFilter.none(),
                        mirror));
            }
            stop.set(true);
            feeder.join();
            timer.join();
            reader.join();
            assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
            query.commit();
            query.commit();

            // Delivery is each subscription's own thread since STRM-8, so the mirrors are behind
            // the view for as long as it takes them to drain. Waiting for that is not tolerance
            // of a race: awaitQuiet returns false if a subscriber is still behind at the
            // deadline, and the equality below is asserted exactly as before.
            for (Subscription subscription : subscriptions) {
                assertThat(subscription.awaitQuiet(Duration.ofSeconds(30)))
                        .as("round %d: the subscription drained", round)
                        .isTrue();
            }

            Map<List<Object>, Long> expected = zset(query.view().committedRows());
            assertThat(expected).hasSize(1);
            for (Mirror mirror : mirrors) {
                assertThat(mirror.snapshots).isEqualTo(1);
                assertThat(mirror.outOfOrder).isFalse();
                assertThat(mirror.rows()).as("round %d", round).isEqualTo(expected);
            }
            subscriptions.forEach(Subscription::close);
        }
    }

    /** A subscriber keeping a copy of the view as a Z-set. */
    static final class Mirror implements SubscriptionListener {

        volatile int snapshots;
        volatile boolean outOfOrder;
        private final List<ViewChange> all = new CopyOnWriteArrayList<>();

        @Override
        public void onSnapshot(List<ViewChange> rows, long frontier) {
            snapshots++;
            all.addAll(rows);
        }

        @Override
        public void onCommit(List<ViewChange> changes, long frontier) {
            // Recorded, not asserted: this runs on an engine thread, which would swallow the error.
            if (snapshots != 1) {
                outOfOrder = true;
            }
            all.addAll(changes);
        }

        Map<List<Object>, Long> rows() {
            return zset(all);
        }
    }

    static Map<List<Object>, Long> zset(List<ViewChange> changes) {
        Map<List<Object>, Long> sums = new HashMap<>();
        for (ViewChange change : changes) {
            sums.merge(Arrays.asList(change.values()), change.weight(), Long::sum);
        }
        sums.values().removeIf(weight -> weight == 0);
        return sums;
    }
}
