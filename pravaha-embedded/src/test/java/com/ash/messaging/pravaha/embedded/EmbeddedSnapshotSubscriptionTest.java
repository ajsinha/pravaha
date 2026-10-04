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
package com.ash.messaging.pravaha.embedded;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedded engine's gapless subscription: the view as it stands, then every commit (SUB-1).
 */
class EmbeddedSnapshotSubscriptionTest {

    private static final String TXN = "user_id:STRING,amount:INT64";

    /** Waits until the subscriber has been handed its snapshot and every commit after it. */
    private static void settle(Subscription subscription) {
        assertThat(subscription.awaitQuiet(java.time.Duration.ofSeconds(10)))
                .as("the subscriber was handed everything meant for it")
                .isTrue();
    }

    @Test
    void aSnapshotSubscriberStartsFromWhatWasPushedAndKeepsUp() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.declareStream("txn", TXN);
            engine.start();
            engine.register("big", "SELECT user_id, amount FROM txn WHERE amount > 100", "user_id");
            engine.push("txn", new Object[] {"u1", 300L}, new Object[] {"u2", 50L});

            Copy copy = new Copy();
            try (Subscription subscription = engine.subscribeFromSnapshot("big", copy)) {
                // The snapshot and every commit reach the consumer on the subscription's own
                // thread, so that a slow consumer cannot hold the thread that committed the view
                // (STRM-8). A test reading the copy waits for it to have been handed over.
                settle(subscription);
                assertThat(copy.snapshots).hasSize(1);
                assertThat(copy.snapshots.get(0)).isEqualTo(Map.of(Map.of("user_id", "u1", "amount", 300L), 1L));
                assertThat(subscription.snapshotFrontier()).isPresent();

                engine.push("txn", new Object[] {"u3", 400L});
                settle(subscription);
                assertThat(copy.rows())
                        .containsOnlyKeys(
                                Map.of("user_id", "u1", "amount", 300L), Map.of("user_id", "u3", "amount", 400L));
            }
        }
    }

    @Test
    void rowsAppliedButNotYetCommittedAreInTheSnapshot() {
        // The window SUB-1 was found in: a source has handed the query rows and nothing has
        // published them when the subscriber attaches. A plain subscription is in no audience for
        // them and a read beside it does not see them.
        StreamSchema txn = StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
        try (PravahaEngine engine = PravahaEngine.createDefault();
                RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 1)) {
            engine.declareStream(txn);
            engine.start();
            RegisteredQuery query = engine.register("all_txn", "SELECT user_id, amount FROM txn", "user_id");
            RowEncoder encoder = new RowEncoder(txn);
            assertThat(query.accept(java.util.Objects.requireNonNull(
                            encoder.write(encoder.validate(new Object[] {"u1", 10L}), arena, 1))))
                    .isTrue();
            assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
            assertThat(engine.query("SELECT * FROM all_txn").rows())
                    .as("applied, not committed")
                    .isEmpty();

            Copy copy = new Copy();
            try (Subscription ignored = engine.subscribeFromSnapshot("all_txn", copy)) {
                settle(ignored);
                assertThat(copy.rows()).containsOnlyKeys(Map.of("user_id", "u1", "amount", 10L));
            }
        }
    }

    /** A copy of the view kept from the snapshot and the changes after it. */
    static final class Copy implements RowChangeListener {

        final List<Map<Map<String, Object>, Long>> snapshots = new CopyOnWriteArrayList<>();
        private final Map<Map<String, Object>, Long> rows = new HashMap<>();

        @Override
        public synchronized void onSnapshot(List<RowChange> snapshot, long frontier) {
            Map<Map<String, Object>, Long> taken = new HashMap<>();
            snapshot.forEach(row -> taken.merge(Map.copyOf(row.values()), row.weight(), Long::sum));
            snapshots.add(taken);
            taken.forEach((row, weight) -> rows.merge(row, weight, Long::sum));
        }

        @Override
        public synchronized void onCommit(List<RowChange> changes, long frontier) {
            changes.forEach(change -> rows.merge(Map.copyOf(change.values()), change.weight(), Long::sum));
            rows.values().removeIf(weight -> weight == 0);
        }

        synchronized Map<Map<String, Object>, Long> rows() {
            return Map.copyOf(rows);
        }
    }
}
