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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.ArrayList;
import java.util.List;

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
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.PlanNodes;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6: what each operator of a running plan has done.
 *
 * <p>The claims under test are the ones a console will draw on: the counters land on the right
 * boxes (which is a statement about ids, not about arithmetic), a scan's rows in is the query's
 * rows in, the root's rows out is the query's rows out, an operator's state bytes is the same
 * number the query-level state gauges are summed from, and the whole apparatus disappears when it
 * is switched off rather than reporting zeros.
 *
 * <p>{@link InterpretedPipeline#measureOperators} is process-wide static state, so every test here
 * puts it back: a test elsewhere that measures throughput must not silently be measuring the
 * instrumented path because this class ran first.
 */
class OperatorMetricsTest {

    @BeforeEach
    void measure() {
        InterpretedPipeline.measureOperators(true);
    }

    @AfterEach
    void stopMeasuring() {
        InterpretedPipeline.measureOperators(false);
    }

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    private static StreamSchema justId() {
        return StreamSchema.builder("orders").field("id", Types.int64()).build();
    }

    /** {@code SELECT id FROM orders WHERE amount > 100}: project over filter over scan. */
    private static PhysicalOperator filterThenProject() {
        return new ProjectOperator(
                new FilterOperator(
                        ScanOperator.of("orders", orders()),
                        new Predicate.CompareLong(1, "amount", Predicate.Op.GT, 100)),
                justId(),
                List.of(0));
    }

    @Test
    void theNodeIdsAreThePlanGraphsOwnAndTheCountersLandOnThem() {
        PhysicalOperator plan = filterThenProject();
        List<PhysicalOperator> nodes = PlanNodes.preOrder(plan);

        List<Object[]> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, sink(justId(), out))) {
            for (long amount : new long[] {50, 150, 250, 10}) {
                feed(arena, pipeline, orders(), amount);
            }
            pipeline.endOfBatch();

            List<OperatorMetrics.Snapshot> operators = pipeline.operatorMetrics();
            assertThat(operators).hasSize(nodes.size()).hasSize(3);
            assertThat(operators).extracting(OperatorMetrics.Snapshot::nodeId).containsExactly("n0", "n1", "n2");
            assertThat(operators)
                    .extracting(OperatorMetrics.Snapshot::operator)
                    .containsExactly("Project", "Filter", "Scan");

            // Four rows in at the scan, four out of it, four into the filter, two past it, two
            // into the projection and two out of the query. Exact, because the numbers are counts
            // rather than estimates.
            OperatorMetrics.Snapshot project = operators.get(0);
            OperatorMetrics.Snapshot filter = operators.get(1);
            OperatorMetrics.Snapshot scan = operators.get(2);
            assertThat(scan.rowsIn()).isEqualTo(4);
            assertThat(scan.rowsOut()).isEqualTo(4);
            assertThat(filter.rowsIn()).isEqualTo(4);
            assertThat(filter.rowsOut()).isEqualTo(2);
            assertThat(project.rowsIn()).isEqualTo(2);
            assertThat(project.rowsOut()).isEqualTo(2);
            assertThat(out).as("and the query really did emit two rows").hasSize(2);
        }
    }

    @Test
    void theScansRowsInIsTheQuerysRowsInAndTheRootsRowsOutIsItsAnswer() {
        List<Object[]> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(filterThenProject(), sink(justId(), out))) {
            int fed = 0;
            for (long amount = 0; amount < 500; amount++) {
                feed(arena, pipeline, orders(), amount);
                fed++;
            }
            pipeline.endOfBatch();

            List<OperatorMetrics.Snapshot> operators = pipeline.operatorMetrics();
            assertThat(operators.get(operators.size() - 1).rowsIn())
                    .as("the scan is handed every row the query is handed")
                    .isEqualTo(fed);
            assertThat(operators.get(0).rowsOut())
                    .as("the root emits exactly what the query answered")
                    .isEqualTo(out.size())
                    .isEqualTo(399); // amounts 101..499
        }
    }

    @Test
    void aJoinsStateBytesAreThePipelinesJoinStateBytes() {
        StreamSchema left = StreamSchema.builder("left")
                .field("id", Types.int64())
                .field("key", Types.int64())
                .build();
        StreamSchema right = StreamSchema.builder("right")
                .field("key", Types.int64())
                .field("value", Types.int64())
                .build();
        StreamSchema joined = StreamSchema.builder("joined")
                .field("l_id", Types.int64())
                .field("l_key", Types.int64())
                .field("r_key", Types.int64().withNullable(true))
                .field("r_value", Types.int64().withNullable(true))
                .build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("left", left),
                ScanOperator.of("right", right),
                List.of(1),
                List.of(0),
                joined,
                10_000_000);

        List<Object[]> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, sink(joined, out))) {
            for (long key = 0; key < 40; key++) {
                feed(arena, pipeline, left, key);
                feed(arena, pipeline, right, key);
            }
            pipeline.endOfBatch();

            List<OperatorMetrics.Snapshot> operators = pipeline.operatorMetrics();
            OperatorMetrics.Snapshot join = operators.get(0);
            assertThat(join.operator()).isEqualTo("Join");
            assertThat(join.rowsIn()).as("both sides, counted once each").isEqualTo(80);
            assertThat(join.stateBytes())
                    .as("the operator's own state bytes are the pipeline's, because it is the only one holding any")
                    .isNotNull()
                    .isEqualTo(pipeline.joinStateBytes())
                    .isGreaterThan(0);
            assertThat(operators.get(1).stateBytes())
                    .as("a scan holds no state, and says so rather than reporting zero bytes")
                    .isNull();
        }
    }

    @Test
    void anAdvanceGivesEveryOperatorTheWatermarkItHasReached() {
        List<Object[]> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(filterThenProject(), sink(justId(), out))) {
            assertThat(pipeline.operatorMetrics())
                    .extracting(OperatorMetrics.Snapshot::watermarkNanos)
                    .as("before any advance there is no watermark, which is not the same as zero")
                    .containsOnlyNulls();

            feed(arena, pipeline, orders(), 500);
            pipeline.advanceWatermark(1_700_000_000_000_000_000L);

            assertThat(pipeline.operatorMetrics())
                    .extracting(OperatorMetrics.Snapshot::watermarkNanos)
                    .containsOnly(1_700_000_000_000_000_000L);
        }
    }

    @Test
    void switchedOffThereAreNoCountersAtAllRatherThanCountersReadingZero() {
        InterpretedPipeline.measureOperators(false);
        List<Object[]> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(filterThenProject(), sink(justId(), out))) {
            feed(arena, pipeline, orders(), 500);
            pipeline.endOfBatch();

            assertThat(out).as("the query still answers").hasSize(1);
            assertThat(pipeline.operatorMetrics())
                    .as("and reports no operators, so a caller can tell 'off' from 'idle'")
                    .isEmpty();
        }
    }

    @Test
    void selfTimeIsSampledAndTheSampleCountSaysHowMuchOfIt() {
        List<Object[]> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(filterThenProject(), sink(justId(), out))) {
            int rows = OperatorClock.SAMPLE_EVERY * 4;
            for (int i = 0; i < rows; i++) {
                feed(arena, pipeline, orders(), 500);
            }
            pipeline.endOfBatch();

            List<OperatorMetrics.Snapshot> operators = pipeline.operatorMetrics();
            OperatorMetrics.Snapshot scan = operators.get(operators.size() - 1);
            assertThat(scan.sampledRows())
                    .as("one row in SAMPLE_EVERY is timed, at the operator every row passes through")
                    .isEqualTo(4);
            assertThat(scan.selfNanos()).isGreaterThan(0);
            assertThat(OperatorTelemetry.bottleneck(operators))
                    .as("with time measured there is an operator to name")
                    .isPresent();
            assertThat(OperatorTelemetry.totalSelfNanos(operators))
                    .as("and the parts add up to the whole rather than counting a subtree twice")
                    .isGreaterThanOrEqualTo(scan.selfNanos());
        }
    }

    @Test
    void twoLanesCopiesOfOnePlanAddUp() {
        List<Object[]> first = new ArrayList<>();
        List<Object[]> second = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline one = InterpretedPipeline.compile(filterThenProject(), sink(justId(), first));
                InterpretedPipeline two = InterpretedPipeline.compile(filterThenProject(), sink(justId(), second))) {
            for (long amount = 0; amount < 10; amount++) {
                feed(arena, one, orders(), 200 + amount);
            }
            for (long amount = 0; amount < 7; amount++) {
                feed(arena, two, orders(), 200 + amount);
            }
            one.endOfBatch();
            two.endOfBatch();

            List<OperatorMetrics.Snapshot> merged = OperatorTelemetry.merge(List.of(one, two));
            assertThat(merged).hasSize(3);
            assertThat(merged.get(merged.size() - 1).rowsIn()).isEqualTo(17);
            assertThat(merged.get(0).rowsOut()).isEqualTo(17);
            assertThat(merged.get(0).nodeId()).isEqualTo("n0");
        }
    }

    private static RowOutput sink(StreamSchema schema, List<Object[]> into) {
        return () -> new ValueRowWriter(schema, into::add);
    }

    private static void feed(RowArena arena, InterpretedPipeline pipeline, StreamSchema schema, long second) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, second);
        writer.setLong(1, second);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pipeline.accept(schema.name(), new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }
}
