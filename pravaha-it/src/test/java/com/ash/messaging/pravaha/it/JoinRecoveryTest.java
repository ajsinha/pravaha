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
package com.ash.messaging.pravaha.it;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A join that is interrupted and recovered must answer exactly what an uninterrupted one answers.
 *
 * <p>Join state is the hardest kind to lose quietly. A windowed aggregate that forgets its state
 * produces a visibly wrong number; a join that forgets one side produces <em>nothing</em> for the
 * rows it can no longer match, and nothing looks exactly like a source that had no matching data.
 * The query keeps running, the metrics stay healthy, and the missing pairs are found -- if ever --
 * by somebody reconciling against the source months later.
 *
 * <p>So the interruption here is a crash rather than a shutdown. Closing gracefully lets held state
 * flush on the way out, after the checkpoint was taken, and the recovered run then emits it again;
 * every number in the duplicates is right, which is exactly what makes that mistake hard to see.
 */
@Timeout(180)
class JoinRecoveryTest {

    private static final String SQL =
            "SELECT o.order_id, u.segment FROM orders o JOIN users u ON o.user_id = u.user_id";

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.int64())
                .build();
    }

    private static StreamSchema users() {
        return StreamSchema.builder("users")
                .field("user_id", Types.int64())
                .field("segment", Types.string())
                .build();
    }

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(512, 256)
                .withBatchSize(32)
                .withArena(1 << 20, 8)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("join-recovery", true);
    }

    /** One row on one of the two inputs. */
    private record Event(String stream, long a, long b, String text) {}

    private static Event user(long id) {
        return new Event("users", id, 0, "seg-" + (id % 3));
    }

    private static Event order(long id, long user) {
        return new Event("orders", id, user, null);
    }

    /**
     * The users, which is what the join holds across the interruption.
     *
     * <p>Users 0 and 1 arrive twice. Two identical arrivals are one Z-set element of weight 2, and
     * the pairs they form carry that weight -- so a checkpoint that stores rows without their
     * weights, or a restore that assumes every entry is a single row, shows up here and nowhere
     * else.
     */
    private static List<Event> users_() {
        List<Event> events = new ArrayList<>();
        for (long id = 0; id < 12; id++) {
            events.add(user(id));
            if (id < 2) {
                events.add(user(id));
            }
        }
        return events;
    }

    /** The orders, which can only be answered from state the interruption had better not lose. */
    private static List<Event> orders_() {
        List<Event> events = new ArrayList<>();
        for (long id = 0; id < 60; id++) {
            events.add(order(id, id % 12));
        }
        return events;
    }

    @Test
    void aJoinInterruptedAndRecoveredProducesTheSameAnswersAsAnUninterruptedOne(@TempDir Path dir) {
        List<String> uninterrupted = runToCompletion();
        assertThat(uninterrupted)
                .as("the control run produced nothing to compare")
                .hasSize(60);

        FileCheckpointStore store = new FileCheckpointStore(dir.resolve("checkpoints"));
        List<String> results = new ArrayList<>();
        List<Event> orders = orders_();

        Harness first = new Harness(results);
        first.feedAll(users_());
        // Settle before the orders, so every user is in state when the first order arrives. Without
        // this the two sides interleave, and a pair whose total weight is 2 may be delivered as two
        // increments in one run and as one row of weight 2 in another -- both correct as Z-sets,
        // and not comparable row for row. The interruption is what this test is about; arrival
        // interleaving is not, and letting it vary would make the test flaky for a reason that has
        // nothing to do with recovery.
        assertThat(first.execution.awaitQuiescent(Duration.ofSeconds(60))).isTrue();

        first.feedAll(orders.subList(0, 20));
        assertThat(first.execution.awaitQuiescent(Duration.ofSeconds(60))).isTrue();
        Checkpoint checkpoint = first.execution.checkpoint(1, Duration.ofSeconds(60));
        store.store(checkpoint);
        first.abort();

        try (Harness second = new Harness(results)) {
            second.execution.restore(store.latest().orElseThrow(), Duration.ofSeconds(60));
            second.feedAll(orders.subList(20, orders.size()));
            assertThat(second.execution.awaitQuiescent(Duration.ofSeconds(60))).isTrue();
        }

        assertThat(results.stream().sorted().toList())
                .as("the recovered run must agree with the uninterrupted one, exactly")
                .isEqualTo(uninterrupted.stream().sorted().toList());
    }

    @Test
    void withoutRestoringTheJoinSilentlyLosesEveryLaterMatch() {
        // The control. A recovery test that passes whether or not the state was restored is testing
        // nothing, and this is the shape of the failure it is guarding against: no error, no wrong
        // number, just forty output rows that never appear.
        List<String> withoutRestore = new ArrayList<>();
        List<Event> orders = orders_();

        Harness first = new Harness(new ArrayList<>());
        first.feedAll(users_());
        first.execution.awaitQuiescent(Duration.ofSeconds(60));
        first.feedAll(orders.subList(0, 20));
        first.execution.awaitQuiescent(Duration.ofSeconds(60));
        first.abort();

        try (Harness second = new Harness(withoutRestore)) {
            second.feedAll(orders.subList(20, orders.size()));
            second.execution.awaitQuiescent(Duration.ofSeconds(60));
        }

        assertThat(withoutRestore)
                .as("with the users forgotten, the remaining orders match nothing at all")
                .isEmpty();
    }

    private static List<String> runToCompletion() {
        List<String> results = new ArrayList<>();
        try (Harness harness = new Harness(results)) {
            harness.feedAll(users_());
            assertThat(harness.execution.awaitQuiescent(Duration.ofSeconds(60))).isTrue();
            harness.feedAll(orders_());
            assertThat(harness.execution.awaitQuiescent(Duration.ofSeconds(60))).isTrue();
        }
        return results;
    }

    /** A one-lane join execution writing into a shared list. */
    private static final class Harness implements AutoCloseable {
        final QueryExecution execution;
        private final RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        private final List<StreamSchema> schemas = List.of(orders(), users());

        Harness(List<String> results) {
            PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(orders(), users()).plan(SQL));
            this.execution = QueryExecution.start(
                    plan,
                    1,
                    config(),
                    MemoryAccess.best(),
                    () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                        synchronized (results) {
                            results.add(row.asLong(0) + ":" + row.values()[1] + " w=" + row.weight());
                        }
                    }));
        }

        void feedAll(List<Event> events) {
            events.forEach(this::feed);
        }

        void feed(Event event) {
            StreamSchema schema = schemas.stream()
                    .filter(s -> s.name().equals(event.stream()))
                    .findFirst()
                    .orElseThrow();
            RowLayout layout = RowLayout.of(schema);
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = feed.allocate(layout.rowSize(128));
            writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
            if (event.text() == null) {
                writer.setLong(0, event.a()).setLong(1, event.b());
            } else {
                writer.setLong(0, event.a()).setString(1, event.text());
            }
            writer.weight(1L).eventTimestampNanos(event.a()).sequence(event.a()).commit();
            int length = writer.sizeSoFar();

            int input = event.stream().equals("orders") ? 0 : 1;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!execution.lane(0).offer(input, feed.regionOf(handle), feed.offsetOf(handle), length)) {
                execution.checkHealth();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("lane 0 stopped accepting rows on input " + input);
                }
                Thread.onSpinWait();
            }
        }

        /** Stops as a crash would: nothing held is emitted on the way out. */
        void abort() {
            execution.abort();
            feed.close();
        }

        @Override
        public void close() {
            execution.close();
            feed.close();
        }
    }
}
