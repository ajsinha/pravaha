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
package com.ash.messaging.pravaha.it.qa.state;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

/**
 * Shared fixtures for the STATE cases: H-CS, H-PRJ, H-WIN and H-JOIN as {@code docs/qa/cases/STATE.md}
 * defines them, plus the "direct checkpointer" pattern (a raw {@link QueryExecution}, bypassing
 * {@link QueryRegistry} so {@code checkpointingViewWith} is never wired -- see the package Javadoc).
 */
abstract class StateTestSupport {

    static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    /** H-PRJ's stream: {@code user_id STRING, amount INT64}, no event-time column. */
    static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    /** H-WIN/H-JOIN's stream: adds an event-time column {@code ts}. */
    static final StreamSchema TXN_T = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    /** H-JOIN's second stream. */
    static final StreamSchema LKP = StreamSchema.builder("lkp")
            .field("user_id", Types.string())
            .field("tier", Types.string())
            .build();

    static final String WIN_SQL =
            "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)";

    static final String JOIN_SQL = "SELECT t.user_id, t.amount, l.tier FROM txn t JOIN lkp l ON t.user_id = l.user_id";

    static LaneConfig laneConfig() {
        return LaneConfig.defaults()
                .withInbox(1024, 128)
                .withBatchSize(32)
                .withArena(1 << 20, 4)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("state-qa-lane", true);
    }

    // ---------------------------------------------------------------------------------------
    // H-CS: the store alone, checkpoints built by hand.
    // ---------------------------------------------------------------------------------------

    static Checkpoint cp(long id, String... kv) {
        Map<String, String> offsets = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            offsets.put(kv[i], kv[i + 1]);
        }
        return new Checkpoint(id, 1_700_000_000_000_000_000L + id, offsets, Map.of());
    }

    static Checkpoint cpState(long id, String operator, byte[] bytes) {
        return new Checkpoint(id, 1_700_000_000_000_000_000L + id, Map.of(), Map.of(operator, bytes));
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------
    // Raw QueryExecution: H-PRJ/H-WIN/H-JOIN's plan, driven directly, no QueryRegistry involved.
    // ---------------------------------------------------------------------------------------

    /** A raw execution over H-WIN's plan (the windowed aggregate), stateful, no served-view wiring. */
    static RawExecution rawWindowed() {
        return raw(TXN_T, WIN_SQL);
    }

    /** A raw execution over H-PRJ's plan (a plain projection), not stateful. */
    static RawExecution rawProjection() {
        return raw(TXN, "SELECT user_id, amount FROM txn");
    }

    static RawExecution raw(StreamSchema schema, String sql) {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema).plan(sql));
        List<CapturingRowWriter.Captured> emitted = new java.util.ArrayList<>();
        QueryExecution execution = QueryExecution.start(
                plan,
                1,
                laneConfig(),
                MemoryAccess.best(),
                () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                    synchronized (emitted) {
                        emitted.add(row);
                    }
                }));
        return new RawExecution(execution, schema, emitted);
    }

    /** A raw execution, plus enough plumbing (arena, layout) to feed it rows directly. */
    static final class RawExecution implements AutoCloseable {
        final QueryExecution execution;
        final List<CapturingRowWriter.Captured> emitted;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        private final RowLayout layout;

        RawExecution(QueryExecution execution, StreamSchema schema, List<CapturingRowWriter.Captured> emitted) {
            this.execution = execution;
            this.layout = RowLayout.of(schema);
            this.emitted = emitted;
        }

        /** Feeds one row: (user_id, amount) for TXN, or (user_id, amount, ts) for TXN_T. */
        void feedAt(String user, long amount, long tsNanos) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = arena.allocate(layout.rowSize(128));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setString(0, user).setLong(1, amount);
            if (layout.schema().fieldCount() > 2) {
                writer.setLong(2, tsNanos);
            }
            writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
            offer(handle, writer.sizeSoFar());
        }

        void feed(String user, long amount) {
            feedAt(user, amount, 0L);
        }

        private void offer(long handle, int length) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!execution.lane(0).offer(arena.regionOf(handle), arena.offsetOf(handle), length)) {
                execution.checkHealth();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("lane 0 stopped accepting rows");
                }
                Thread.onSpinWait();
            }
        }

        void advanceWatermark(long nanos) {
            execution.advanceWatermark(nanos);
        }

        /** Stops as a crash would -- nothing held is emitted on the way out. See STATE-064. */
        void abort() {
            execution.abort();
            arena.close();
        }

        @Override
        public void close() {
            execution.close();
            arena.close();
        }
    }

    // ---------------------------------------------------------------------------------------
    // H-PRJ / H-WIN / H-JOIN via QueryRegistry, for cases that need the registry itself.
    // ---------------------------------------------------------------------------------------

    static void feed(RegisteredQuery query, RowArena arena, StreamSchema schema, String user, long amount) {
        feedAt(query, arena, schema, user, amount, 0L);
    }

    static void feedAt(
            RegisteredQuery query, RowArena arena, StreamSchema schema, String user, long amount, long tsNanos) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        if (layout.schema().fieldCount() > 2) {
            writer.setLong(2, tsNanos);
        }
        writer.weight(1L).eventTimestampNanos(tsNanos).sequence(tsNanos).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    static void feedLkp(RegisteredQuery query, RowArena arena, String user, String tier) {
        RowLayout layout = RowLayout.of(LKP);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setString(1, tier);
        writer.weight(1L).eventTimestampNanos(0L).sequence(0L).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("lkp", view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    /**
     * Reflective access to a {@code RegisteredQuery}'s checkpointer field, for tests that must force
     * a checkpoint directly or read its in-memory {@code Stats} without touching the filesystem.
     */
    static com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
