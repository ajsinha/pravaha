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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.exec.SpillSettings;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-037 item B2's overflow tier for windowed aggregates, proven through the real vertical stack:
 * SQL text, {@code PhysicalPlanBuilder}, and {@link InterpretedPipeline#configureSpill}, the same
 * path a deployment actually runs -- {@code SlicedAggregateStateSpillTest} (pravaha-runtime) proves
 * the mechanism directly; this proves the wiring reaches it.
 *
 * <p>{@link InterpretedPipeline#configureSpill} sets process-wide static state, which every test
 * here resets afterward for the same reason {@code InterpretedPipelineSpillWiringTest} does: a
 * stray {@code PRV-3020}/{@code PRV-2050} assertion elsewhere in this module must not start failing
 * because of test order.
 */
class WindowedAggregateSpillTest {

    private static final long SECOND = 1_000_000_000L;

    @AfterEach
    void resetSpillConfiguration() {
        InterpretedPipeline.configureSpill(SpillSettings.DISABLED);
    }

    private static StreamSchema txn() {
        return StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static final String SQL = "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total "
            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    private static final String SQL_DISTINCT = "SELECT window_start, window_end, COUNT(DISTINCT user_id) AS n "
            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end";

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(txn()).plan(sql));
    }

    /**
     * This does not assert {@code windowedStateHasSpilled()}: the planner's own slice ceiling
     * ({@code PhysicalPlanBuilder.DEFAULT_MAX_SLICES}, 2,000,000) sizes the RAM tier proportionally
     * (see {@code SlicedAggregateState}'s own {@code OffHeapAccumulators.ramSlabsFor}), so a
     * realistic number of test rows never actually reaches the overflow tier through a real SQL
     * plan's default ceiling -- proving that the overflow tier engages at all is
     * {@code SlicedAggregateStateSpillTest}'s job, against a small, directly-controlled {@code
     * maxSlices}. What this proves instead is that the wiring from configuration through to a real
     * compiled query does not change the answer for an ordinary case.
     */
    @Test
    void aWindowedGroupByWithManyDistinctKeysAnswersTheSameWithSpillConfigured(@TempDir Path dir) {
        InterpretedPipeline.configureSpill(new SpillSettings(true, dir.toString(), 16));

        PhysicalOperator plan = plan(SQL);
        ServedView view = new ServedView("v", plan.outputSchema(), List.of(2), 1_000_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());
        RowLayout layout = RowLayout.of(txn());
        int userCount = 3_000;
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 16);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            for (int user = 0; user < userCount; user++) {
                long handle = arena.allocate(layout.rowSize(256));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setLong(0, user).setLong(1, user * 10L).setLong(2, SECOND);
                writer.weight(1L).eventTimestampNanos(SECOND).sequence(user).commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            pipeline.advanceWatermark(10 * SECOND);
        }

        assertThat(sink.rowsApplied()).as("one fired row per distinct user").isEqualTo(userCount);
    }

    /**
     * It was refused here at compile time with the tier on (it kept its distinct sets on the heap and
     * had nowhere to spill them). ADR-044 moved them into a {@code RowStore}: the same query now
     * compiles, runs and answers.
     */
    @Test
    void windowedCountDistinctWithSpillConfiguredCompilesAndAnswers(@TempDir Path dir) {
        InterpretedPipeline.configureSpill(new SpillSettings(true, dir.toString(), 16));
        assertThat(countDistinctUsers(40)).as("one row, for the one window").isEqualTo(1);
    }

    @Test
    void windowedCountDistinctWithoutSpillConfiguredStillWorks() {
        // Unconfigured is SpillSettings.DISABLED by default: the same off-heap state, refusing at its
        // memory ceiling rather than spilling, which is every deployment that has not opted in.
        assertThat(countDistinctUsers(5)).isEqualTo(1);
    }

    /**
     * Feeds {@code users} distinct users, each twice, into one ten-second window of {@link
     * #SQL_DISTINCT} and fires it; asserts the window's answer is {@code users} and returns how many
     * rows the view was sent.
     */
    private static long countDistinctUsers(int users) {
        PhysicalOperator plan = plan(SQL_DISTINCT);
        ServedView view = new ServedView("v", plan.outputSchema(), List.of(0, 1), 1_000_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());
        RowLayout layout = RowLayout.of(txn());
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 16);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            for (int copy = 0; copy < 2; copy++) {
                for (int user = 0; user < users; user++) {
                    long handle = arena.allocate(layout.rowSize(256));
                    writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                    writer.setLong(0, user).setLong(1, 0L).setLong(2, SECOND);
                    writer.weight(1L).eventTimestampNanos(SECOND).sequence(user).commit();
                    arena.trimTo(handle, writer.sizeSoFar());
                    pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
                }
            }
            pipeline.advanceWatermark(10 * SECOND);
        }
        sink.commitApplied();
        List<Object[]> rows = view.scan();
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[2]).longValue())
                .as("each user counted once, however often it appeared")
                .isEqualTo(users);
        return sink.rowsApplied();
    }
}
