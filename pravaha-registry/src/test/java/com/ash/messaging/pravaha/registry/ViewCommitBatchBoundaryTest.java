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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewChange;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A view commit publishes whole lane batches, never part of one (VIEW-1).
 *
 * <p>An unwindowed aggregate keyed by its own output -- {@code COUNT(*)} keyed on the count -- updates
 * as a retraction of the old row and an insert of the new one. Both are written on the lane thread;
 * a commit comes from another thread (the feed's timer, a checkpoint, a caller). When the view took
 * each row as it was written, a commit landing between the two published the retraction alone: the
 * view was empty, and a subscriber was handed a batch that withdrew the answer.
 *
 * <p>The interleaving is forced, not hoped for. The lane is held at the start of the second row of
 * an emission -- after the retraction has been written, before the insert has -- while this thread
 * commits, reads the view and looks at what the subscriber was given. The wiring is the registry's:
 * the output the registry gives each lane, and the commit its {@code commitView} makes.
 */
@Timeout(60)
class ViewCommitBatchBoundaryTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private RowArena arena;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private QueryExecution execution;

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        if (execution != null) {
            execution.close();
        }
        arena.close();
    }

    @Test
    void aCommitBetweenAnUpdatesRetractionAndItsInsertPublishesNeither() throws Exception {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(ORDERS).plan("SELECT COUNT(*) AS n, SUM(amount) AS total FROM orders"));
        ServedView view = new ServedView("order_count", plan.outputSchema(), List.of(0), 100);
        ViewSink sink = new ViewSink(view, plan.outputSchema());
        List<List<ViewChange>> delivered = new CopyOnWriteArrayList<>();
        sink.onCommit((batch, frontier) -> delivered.add(batch));

        Gate gate = new Gate(sink::laneOutput);
        execution = QueryExecution.start(
                plan, 1, LaneConfig.defaults().withThreads("batch-boundary-lane", true), MemoryAccess.best(), gate);

        feed("eve", 10);
        feed("fay", 20);
        assertThat(execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        execution.publishContinuousAggregates();
        commit(sink);
        assertThat(rows(view)).as("the first answer").containsExactly(List.of(2L, 30L));
        delivered.clear();

        // The next emission retracts [2, 30] and inserts [2, 30]. Hold the lane before the insert.
        gate.holdAtTheSecondRowFromNow();
        Thread emitting = Thread.ofPlatform().daemon().start(execution::publishContinuousAggregates);
        assertThat(gate.held.await(10, TimeUnit.SECONDS))
                .as("the lane reached the gap between the retraction and the insert")
                .isTrue();

        commit(sink);
        List<List<Object>> inTheGap = rows(view);
        List<List<ViewChange>> deliveredInTheGap = new ArrayList<>(delivered);

        gate.release.countDown();
        emitting.join(Duration.ofSeconds(10).toMillis());
        commit(sink);

        assertThat(inTheGap)
                .as("a commit in the middle of the lane's emission published its retraction without its "
                        + "insert, and the answer vanished from the view")
                .containsExactly(List.of(2L, 30L));
        assertThat(deliveredInTheGap)
                .as("and a subscriber was handed half an update as a whole commit")
                .isEmpty();
        assertThat(rows(view))
                .as("the emission, once whole, is published whole")
                .containsExactly(List.of(2L, 30L));
        assertThat(delivered)
                .as("one commit carrying the retraction and the insert together")
                .hasSize(1);
        assertThat(delivered.get(0)).extracting(ViewChange::weight).containsExactly(-1L, 1L);
    }

    /**
     * The same race through a registered query, unforced: two threads committing, as the feed's
     * timer and a reader do. Every commit re-emits the unwindowed aggregate's answer on the lane as
     * a retraction and an insert, and another thread's commit may land between them.
     *
     * <p>Not the proof -- the test above is -- but the shape the defect was met in
     * ({@code MultiplexedRegistryTest} under load), and it must never see the answer missing.
     */
    @Test
    void aRegisteredCountNeverVanishesWhileTwoThreadsCommitIt() throws Exception {
        try (QueryRegistry registry = new QueryRegistry(new com.ash.messaging.pravaha.serving.ViewCatalog(), ORDERS)) {
            RegisteredQuery query = registry.register(
                    "order_count",
                    "SELECT COUNT(*) AS n, SUM(amount) AS total FROM orders",
                    List.of(0),
                    com.ash.messaging.pravaha.security.Principal.ANONYMOUS);
            RowLayout layout = RowLayout.of(ORDERS);
            for (String user : List.of("eve", "fay")) {
                BinaryRowWriter writer = new BinaryRowWriter(layout);
                long handle = arena.allocate(layout.rowSize(64));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setString(0, user).setLong(1, 15);
                writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
                arena.trimTo(handle, writer.sizeSoFar());
                query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
            query.commit();
            assertThat(query.view().scan()).hasSize(1);

            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            Thread timer = Thread.ofPlatform().daemon().start(() -> {
                while (!stop.get()) {
                    query.commit();
                }
            });
            int missing = 0;
            int reads = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    query.commit();
                    reads++;
                    if (query.view().scan().size() != 1) {
                        missing++;
                    }
                }
            } finally {
                stop.set(true);
                timer.join(Duration.ofSeconds(10).toMillis());
            }
            assertThat(missing)
                    .as("reads of %d that found the count missing: a commit published half an emission", reads)
                    .isZero();
        }
    }

    /** What the registry's commitView does. */
    private static void commit(ViewSink sink) {
        sink.commitApplied();
    }

    private static List<List<Object>> rows(ServedView view) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : view.scan()) {
            rows.add(List.of(row));
        }
        return rows;
    }

    private void feed(String user, long amount) {
        RowLayout layout = RowLayout.of(ORDERS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(execution.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
    }

    /**
     * The registry's lane output, with a gate in front of it: on request, the lane is held at the
     * start of a chosen row until the test lets it go.
     */
    private static final class Gate implements Supplier<RowOutput> {

        private final Supplier<RowOutput> wiring;
        private final AtomicInteger begun = new AtomicInteger();
        private volatile int holdAt = -1;
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Gate(Supplier<RowOutput> wiring) {
            this.wiring = wiring;
        }

        void holdAtTheSecondRowFromNow() {
            holdAt = begun.get() + 2;
        }

        @Override
        public RowOutput get() {
            RowOutput inner = wiring.get();
            return new RowOutput() {
                @Override
                public RowWriter begin() {
                    if (begun.incrementAndGet() == holdAt) {
                        held.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return inner.begin();
                }

                @Override
                public void endOfBatch() {
                    inner.endOfBatch();
                }
            };
        }
    }
}
