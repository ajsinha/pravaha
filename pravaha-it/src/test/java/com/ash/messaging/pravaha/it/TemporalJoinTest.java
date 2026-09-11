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
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Time bounds written in the query govern the join.
 *
 * <p>Before this, a temporal predicate was refused outright and the bound that actually governed the
 * join was an hour, chosen inside the engine and invisible in the SQL. That put it on the wrong side
 * of the project's own rule: a bound that changes the answer belongs in the query's meaning, and
 * only a bound that protects the machine belongs in configuration. Which rows match is unarguably
 * the answer.
 *
 * <p>The bound has to do two jobs, and doing only the first is the subtle failure. It decides how
 * long state is kept -- and it decides which pairs are in the result. A join that used it only for
 * eviction would return every pair still in state, so a query asking for matches within five minutes
 * would get matches within an hour with nothing to indicate it.
 */
class TemporalJoinTest {

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

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(ORDERS, PAYMENTS).plan(sql));
    }

    private static JoinOperator joinIn(PhysicalOperator root) {
        if (root instanceof JoinOperator join) {
            return join;
        }
        for (PhysicalOperator input : root.inputs()) {
            JoinOperator found = joinIn(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aStatedWindowReplacesTheDefault() {
        JoinOperator join = joinIn(plan("SELECT o.order_id, p.channel FROM orders o JOIN payments p "
                + "ON o.order_id = p.order_id "
                + "AND o.placed_at BETWEEN p.paid_at - INTERVAL '5' MINUTE AND p.paid_at"));

        assertThat(join).isNotNull();
        // An order may be up to five minutes older than its payment, and never newer.
        assertThat(join.matchLowerNanos()).isEqualTo(-5 * MINUTE);
        assertThat(join.matchUpperNanos()).isZero();
        // State is kept for the longer of the two directions.
        assertThat(join.matchWithinNanos()).isEqualTo(5 * MINUTE);
    }

    @Test
    void directionIsKeptRatherThanCollapsedToAWidth() {
        JoinOperator after = joinIn(plan("SELECT o.order_id, p.channel FROM orders o JOIN payments p "
                + "ON o.order_id = p.order_id "
                + "AND p.paid_at BETWEEN o.placed_at AND o.placed_at + INTERVAL '10' MINUTE"));

        // "The payment came after the order" and "the two were within ten minutes" are different
        // questions. A join that treats them alike answers the wrong one silently.
        assertThat(after.matchLowerNanos()).isEqualTo(-10 * MINUTE);
        assertThat(after.matchUpperNanos()).isZero();
    }

    @Test
    void aQueryWithNoTimePredicateStillGetsABound() {
        JoinOperator join =
                joinIn(plan("SELECT o.order_id, p.channel FROM orders o JOIN payments p ON o.order_id = p.order_id"));

        // Silence means the default, not "unbounded". A join with no bound holds every unmatched row
        // for as long as the process lives.
        assertThat(join.matchWithinNanos()).isEqualTo(JoinOperator.DEFAULT_MATCH_WITHIN_NANOS);
    }

    @Test
    void pairsOutsideTheStatedWindowAreNotInTheAnswer() {
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        PhysicalOperator root = plan("SELECT o.order_id, p.channel FROM orders o JOIN payments p "
                + "ON o.order_id = p.order_id "
                + "AND o.placed_at BETWEEN p.paid_at - INTERVAL '5' MINUTE AND p.paid_at");

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(root, collectingInto(root, out))) {

            // Two orders with the same id: one two minutes before its payment, one thirty minutes.
            feedOrder(pipeline, arena, "o-1", 100L, 0);
            feedOrder(pipeline, arena, "o-2", 200L, 0);
            feedPayment(pipeline, arena, "o-1", "card", 2 * MINUTE);
            feedPayment(pipeline, arena, "o-2", "card", 30 * MINUTE);

            // Both are still in state -- the retention horizon is well beyond thirty minutes -- so a
            // join that used the bound only for eviction would emit both pairs.
            assertThat(out).hasSize(1);
            assertThat(out.get(0).asString(0)).isEqualTo("o-1");
        }
    }

    @Test
    void aBoundThatCannotBeSatisfiedIsRefusedRatherThanReturningNothing() {
        // Lower above upper: no pair of rows can satisfy it, so the query can only ever return
        // nothing. Better to say so than to run for a week and be asked why the output is empty.
        assertThatThrownBy(() -> JoinOperator.withinRange(
                        null, null, List.of(0), List.of(0), ORDERS, 1000, 10 * MINUTE, 5 * MINUTE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inverted");
    }

    @Test
    void aConditionThatIsNeitherAnEqualityNorATimeBoundStillSaysWhy() {
        assertThatThrownBy(() -> plan("SELECT o.order_id, p.channel FROM orders o JOIN payments p "
                        + "ON o.amount > 100 AND o.order_id = p.order_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("move anything else into a WHERE clause");
    }

    @Test
    void aWindowInMonthsIsNotAccepted() {
        // A month has no fixed length, so it cannot become a number of nanoseconds without knowing
        // which month, and guessing thirty days would be wrong twice a year.
        assertThatThrownBy(() -> plan("SELECT o.order_id, p.channel FROM orders o JOIN payments p "
                        + "ON o.order_id = p.order_id "
                        + "AND o.placed_at BETWEEN p.paid_at - INTERVAL '1' MONTH AND p.paid_at"))
                .isInstanceOf(PravahaException.class);
    }

    private static RowOutput collectingInto(PhysicalOperator root, List<CapturingRowWriter.Captured> out) {
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
