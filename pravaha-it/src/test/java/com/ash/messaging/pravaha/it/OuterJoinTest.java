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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code LEFT JOIN} between two streams, which a time bound makes possible.
 *
 * <p>Outer joins were refused for a reason that was correct at the time: an outer join must decide
 * when to give up on an unmatched left row, and without a window the honest answer is never -- the
 * row is held for the life of the process in case a match arrives. A stated time bound supplies the
 * moment. When the watermark passes the point where a match could still arrive, "has not matched"
 * and "will not match" become the same statement, and the null-padded row is emitted then.
 *
 * <p>That timing is why it is emitted once and never retracted. The alternative -- emit eagerly,
 * retract when a match arrives -- doubles the output for every unmatched row and holds it anyway.
 */
class OuterJoinTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.string())
            .field("amount", Types.int64())
            .field("placed_at", Types.timestamp())
            .eventTime("placed_at")
            .build();

    private static final StreamSchema PAYMENTS = StreamSchema.builder("payments")
            .field("order_id", Types.string())
            .field("channel", Types.string())
            .field("paid_at", Types.timestamp())
            .eventTime("paid_at")
            .build();

    private static final String LEFT_JOIN = "SELECT o.order_id, p.channel FROM orders o "
            + "LEFT JOIN payments p ON o.order_id = p.order_id "
            + "AND p.paid_at BETWEEN o.placed_at AND o.placed_at + INTERVAL '5' MINUTE";

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(ORDERS, PAYMENTS).plan(sql));
    }

    @Test
    void anUnmatchedLeftRowIsEmittedWithNullsOnceTheWindowHasPassed() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan(LEFT_JOIN);

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collecting(root, out))) {

            feedOrder(pipeline, arena, "o-unpaid", 100L, 0);

            // Nothing yet. The order might still be paid, and an outer join that emitted immediately
            // would have to retract that row the moment it was.
            assertThat(out).isEmpty();

            // Event time moves past the window. Now it definitively has no payment.
            pipeline.advanceWatermark(30 * MINUTE);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).asString(0)).isEqualTo("o-unpaid");
            assertThat(out.get(0).isNull(1)).isTrue();
            assertThat(out.get(0).weight()).isEqualTo(1);
        }
    }

    @Test
    void aMatchedLeftRowIsNotEmittedTwice() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan(LEFT_JOIN);

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collecting(root, out))) {

            feedOrder(pipeline, arena, "o-paid", 100L, 0);
            feedPayment(pipeline, arena, "o-paid", "card", 2 * MINUTE);
            assertThat(out).hasSize(1);

            // The order eventually ages out, but it matched, so it must not reappear null-padded.
            // Getting this wrong is the classic outer-join bug: every matched row emitted twice,
            // once correctly and once as if it had never matched.
            pipeline.advanceWatermark(30 * MINUTE);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).isNull(1)).isFalse();
            assertThat(out.get(0).asString(1)).isEqualTo("card");
        }
    }

    @Test
    void aPaymentOutsideTheWindowLeavesTheOrderUnmatched() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan(LEFT_JOIN);

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collecting(root, out))) {

            feedOrder(pipeline, arena, "o-late", 100L, 0);
            // Paid, but an hour later: outside the five minutes the query asked about.
            feedPayment(pipeline, arena, "o-late", "card", 60 * MINUTE);
            assertThat(out).isEmpty();

            pipeline.advanceWatermark(180 * MINUTE);

            // "Unpaid within five minutes" is what the query asked, and that is what it answers.
            assertThat(out).hasSize(1);
            assertThat(out.get(0).isNull(1)).isTrue();
        }
    }

    @Test
    void mixedMatchedAndUnmatchedRowsComeOutCorrectly() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan(LEFT_JOIN);

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collecting(root, out))) {

            feedOrder(pipeline, arena, "a", 10L, 0);
            feedOrder(pipeline, arena, "b", 20L, 0);
            feedOrder(pipeline, arena, "c", 30L, 0);
            feedPayment(pipeline, arena, "a", "card", MINUTE);
            feedPayment(pipeline, arena, "c", "cash", 2 * MINUTE);
            pipeline.advanceWatermark(60 * MINUTE);

            assertThat(out).hasSize(3);
            assertThat(out.stream().filter(row -> row.isNull(1)).count()).isEqualTo(1);
            assertThat(out.stream()
                            .filter(row -> row.isNull(1))
                            .findFirst()
                            .orElseThrow()
                            .asString(0))
                    .isEqualTo("b");
        }
    }

    @Test
    void aLeftJoinWithoutATimeBoundIsRefusedWithTheReason() {
        assertThatThrownBy(() -> plan("SELECT o.order_id, p.channel FROM orders o "
                        + "LEFT JOIN payments p ON o.order_id = p.order_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("needs a time bound")
                .hasMessageContaining("held for as long as the process lives");
    }

    @Test
    void aRightJoinSaysToSwapTheInputs() {
        assertThatThrownBy(() -> plan("SELECT o.order_id, p.channel FROM orders o "
                        + "RIGHT JOIN payments p ON o.order_id = p.order_id "
                        + "AND p.paid_at BETWEEN o.placed_at AND o.placed_at + INTERVAL '5' MINUTE"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("Swap the inputs and use LEFT");
    }

    private static RowOutput collecting(PhysicalOperator root, List<CapturingRowWriter.Captured> out) {
        return () -> new CapturingRowWriter(root.outputSchema(), out::add);
    }

    private static void feedOrder(InterpretedPipeline pipeline, RowArena arena, String id, long amount, long at) {
        RowLayout layout = RowLayout.of(ORDERS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id).setLong(1, amount).setLong(2, at);
        writer.weight(1L).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pipeline.accept("orders", view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private static void feedPayment(InterpretedPipeline pipeline, RowArena arena, String id, String channel, long at) {
        RowLayout layout = RowLayout.of(PAYMENTS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id).setString(1, channel).setLong(2, at);
        writer.weight(1L).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pipeline.accept("payments", view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }
}
