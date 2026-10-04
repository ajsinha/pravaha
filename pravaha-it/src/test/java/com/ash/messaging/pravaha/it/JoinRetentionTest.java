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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stream-to-stream join releases state as event time moves past its match window.
 *
 * <p>Before this, a join held every unmatched row until a row ceiling failed the query. That is not
 * a design -- a production system cannot answer unbounded growth by dying -- but nor could the rows
 * simply be dropped: evicting to stay under a ceiling silently loses matches the query <em>did</em>
 * ask for, which is worse than failing.
 *
 * <p>The resolution is that the bound belongs in the query's meaning. With a match window of
 * {@code T} the join means "rows that match and whose event times are within {@code T}", and a row
 * older than the watermark minus {@code T} cannot participate in any match it promises. Releasing it
 * honours the definition; that is what these tests are about.
 */
class JoinRetentionTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema LEFT = StreamSchema.builder("orders")
            .field("order_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final StreamSchema RIGHT = StreamSchema.builder("shipments")
            .field("order_id", Types.string())
            .field("carrier", Types.string())
            .build();

    private static PhysicalOperator plan() {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(LEFT, RIGHT)
                        .plan("SELECT o.order_id, o.amount, s.carrier FROM orders o "
                                + "JOIN shipments s ON o.order_id = s.order_id"));
    }

    private static void feed(
            InterpretedPipeline pipeline,
            RowArena arena,
            StreamSchema schema,
            String stream,
            Object[] values,
            long at) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, (String) values[0]);
        if (values[1] instanceof Long amount) {
            writer.setLong(1, amount);
        } else {
            writer.setString(1, (String) values[1]);
        }
        writer.weight(1L).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pipeline.accept(stream, view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    @Test
    void aJoinHasAMatchWindowEvenWhenTheQueryDoesNotMentionOne() {
        PhysicalOperator root = plan();
        JoinOperator join = findJoin(root);

        // Not unbounded by omission. A join without a stated window would hold every unmatched row
        // for as long as the process lives, so the default is a window rather than forever.
        assertThat(join).isNotNull();
        assertThat(join.matchWithinNanos()).isEqualTo(JoinOperator.DEFAULT_MATCH_WITHIN_NANOS);
        assertThat(join.matchWithinNanos()).isPositive();
    }

    @Test
    void rowsOlderThanTheMatchWindowAreReleasedAsTimeAdvances() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collectingInto(root, out))) {

            // An order that never ships. Without a bound it would be held forever.
            feed(pipeline, arena, LEFT, "orders", new Object[] {"o-1", 100L}, 0);
            assertThat(pipeline.joinRowsEvicted()).isZero();

            // Two hours of event time later, past the one-hour default window.
            pipeline.advanceWatermark(7_200 * SECOND);

            assertThat(pipeline.joinRowsEvicted())
                    .as("an order older than the match window can no longer match anything")
                    .isEqualTo(1);
        }
    }

    @Test
    void aMatchInsideTheWindowStillHappens() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collectingInto(root, out))) {

            feed(pipeline, arena, LEFT, "orders", new Object[] {"o-1", 100L}, 0);
            // Ten minutes later, well inside the hour.
            pipeline.advanceWatermark(600 * SECOND);
            feed(pipeline, arena, RIGHT, "shipments", new Object[] {"o-1", "DHL"}, 600 * SECOND);

            // The point of the whole exercise: releasing old state must not cost matches that are
            // still within what the query asked for.
            assertThat(out)
                    .as("a shipment inside the window must still find its order")
                    .hasSize(1);
        }
    }

    @Test
    void stateDoesNotGrowWithoutLimitOverALongRun() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 22, 32);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collectingInto(root, out))) {

            // Ten thousand unmatched orders spread over days of event time, with the watermark
            // following. Before the match window existed this hit the row ceiling and failed.
            for (int i = 0; i < 10_000; i++) {
                long at = i * 60L * SECOND;
                feed(pipeline, arena, LEFT, "orders", new Object[] {"o-" + i, (long) i}, at);
                if (i % 100 == 0) {
                    pipeline.advanceWatermark(at);
                }
            }
            pipeline.advanceWatermark(10_000 * 60L * SECOND);

            assertThat(pipeline.joinRowsEvicted()).isGreaterThan(9_000);
            assertThat(pipeline.joinRowsHeld())
                    .as("what is left is an hour of orders, not ten thousand")
                    .isLessThan(200);
        }
    }

    private static RowOutput collectingInto(PhysicalOperator plan, List<CapturingRowWriter.Captured> out) {
        return () -> new CapturingRowWriter(plan.outputSchema(), out::add);
    }

    private static @Nullable JoinOperator findJoin(PhysicalOperator operator) {
        if (operator instanceof JoinOperator join) {
            return join;
        }
        for (PhysicalOperator input : operator.inputs()) {
            JoinOperator found = findJoin(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
