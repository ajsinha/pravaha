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
 * Stream-to-stream joins.
 *
 * <p>The bilinear rule of design section 9.3 executed a row at a time. Three things are worth
 * proving and the rest follows from them.
 *
 * <p><strong>Order does not matter.</strong> A join whose output depends on which side arrived first
 * is not a join, it is a lookup with a race in it. Every test here that asserts a result asserts the
 * same result for both arrival orders.
 *
 * <p><strong>Retraction is arithmetic.</strong> A retracted row must retract exactly the pairs it
 * formed -- no more, which would delete somebody else's output, and no fewer, which would leave a
 * row on a dashboard that the source has already said is wrong.
 *
 * <p><strong>State comes back.</strong> A row whose retraction has cancelled it must stop matching,
 * and its memory must be reusable. A join that holds retracted rows grows forever while its row
 * count looks flat, which is the failure that gets diagnosed as a leak in something else.
 */
class StreamJoinTest {

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    private static StreamSchema users() {
        return StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("country", Types.string())
                .build();
    }

    /** One row on one input, with its Z-set weight. */
    private record Input(String stream, long weight, Object[] values) {}

    private static Input order(long id, String user, long amount, long weight) {
        return new Input("orders", weight, new Object[] {id, user, amount});
    }

    private static Input user(String user, String country, long weight) {
        return new Input("users", weight, new Object[] {user, country});
    }

    @Test
    void aPairIsEmittedWhicheverSideArrivesFirst() {
        String sql = "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id";

        List<String> rightFirst = countriesFrom(sql, List.of(user("u1", "IN", 1), order(1, "u1", 100, 1)));
        List<String> leftFirst = countriesFrom(sql, List.of(order(1, "u1", 100, 1), user("u1", "IN", 1)));

        assertThat(rightFirst).containsExactly("IN");
        assertThat(leftFirst).isEqualTo(rightFirst);
    }

    @Test
    void oneSideMatchesEveryRowOnTheOther() {
        // Three orders for one user is three pairs, however they interleave with the user row.
        List<Input> rows = List.of(
                order(1, "u1", 10, 1),
                user("u1", "IN", 1),
                order(2, "u1", 20, 1),
                order(3, "u2", 30, 1),
                user("u2", "US", 1));

        assertThat(countriesFrom(
                        "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id", rows))
                .containsExactly("IN", "IN", "US");
    }

    @Test
    void anUnmatchedRowEmitsNothing() {
        assertThat(countriesFrom(
                        "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id",
                        List.of(order(1, "nobody", 10, 1), user("u1", "IN", 1))))
                .isEmpty();
    }

    @Test
    void retractingARowRetractsExactlyThePairsItFormed() {
        List<CapturingRowWriter.Captured> out = run(
                "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id",
                List.of(
                        user("u1", "IN", 1),
                        order(1, "u1", 10, 1),
                        order(2, "u1", 20, 1),
                        // The user row is retracted: both pairs it formed must come back out as -1.
                        user("u1", "IN", -1)));

        assertThat(out).hasSize(4);
        assertThat(out.stream().map(CapturingRowWriter.Captured::weight).toList())
                .containsExactly(1L, 1L, -1L, -1L);
        // And the retractions name the same orders as the inserts, rather than some other pair. In
        // any order: the two negatives come out in whatever order the key's chain holds them, and a
        // Z-set has no order to be wrong about. Asserting a sequence here would be asserting an
        // implementation detail of the chain.
        assertThat(out.subList(0, 2).stream().map(row -> row.asLong(0)).toList())
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(out.subList(2, 4).stream().map(row -> row.asLong(0)).toList())
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void aRetractedRowStopsMatchingLaterArrivals() {
        // The state test. If the retraction only emitted negatives without removing the row, this
        // order would still find a user to match.
        assertThat(countriesFrom(
                        "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id",
                        List.of(user("u1", "IN", 1), user("u1", "IN", -1), order(1, "u1", 10, 1))))
                .isEmpty();
    }

    @Test
    void anUpdateIsARetractionAndAnInsert() {
        List<CapturingRowWriter.Captured> out = run(
                "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id",
                List.of(
                        user("u1", "IN", 1),
                        order(1, "u1", 10, 1),
                        // u1 moves country: retract the old row, insert the new one.
                        user("u1", "IN", -1),
                        user("u1", "US", 1)));

        assertThat(out.stream().map(row -> row.weight() + ":" + row.values()[1]).toList())
                .containsExactly("1:IN", "-1:IN", "1:US");
    }

    @Test
    void duplicateRowsAreCountedRatherThanCollapsed() {
        // Two identical user rows are one Z-set element of weight 2, and an order matching it must
        // produce a pair of weight 2 -- not one pair, and not two rows.
        List<CapturingRowWriter.Captured> out = run(
                "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id",
                List.of(user("u1", "IN", 1), user("u1", "IN", 1), order(1, "u1", 10, 1)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).weight()).isEqualTo(2);
    }

    @Test
    void duplicatesAreCountedOnTheOtherSideToo() {
        // The mirror of the test above. The two sides are separate code paths, and a weight dropped
        // in one of them is invisible to a test that only feeds the other.
        List<CapturingRowWriter.Captured> out = run(
                "SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id",
                List.of(order(1, "u1", 10, 1), order(1, "u1", 10, 1), user("u1", "IN", 1)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).weight()).isEqualTo(2);
    }

    @Test
    void cancelledRowsLeaveStateRatherThanSittingAtWeightZero() {
        // A cancelled entry that is not unlinked emits nothing and matches nothing, so every
        // assertion about output still passes while the join leaks a block per retracted row. The
        // only thing that sees it is the state itself.
        PhysicalOperator plan =
                plan("SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id");
        List<CapturingRowWriter.Captured> out = new ArrayList<>();

        try (Harness harness = new Harness(plan, List.of(orders(), users()), out)) {
            for (int round = 0; round < 20; round++) {
                for (int i = 0; i < 50; i++) {
                    harness.feed("users", 1, new Object[] {"u" + round + "_" + i, "IN"});
                }
                assertThat(harness.rowsHeld()).isEqualTo(50);
                for (int i = 0; i < 50; i++) {
                    harness.feed("users", -1, new Object[] {"u" + round + "_" + i, "IN"});
                }
                assertThat(harness.rowsHeld())
                        .as("round %d: retracted rows are still in state", round)
                        .isZero();
                // The index leaks separately from the rows. A key whose last row has gone must take
                // its bucket with it, or a query that sees a million keys holds a million empty
                // ones while the row count reads zero.
                assertThat(harness.keysHeld())
                        .as("round %d: keys with no rows are still indexed", round)
                        .isZero();
            }

            // And the memory comes back rather than being reserved again per round.
            assertThat(harness.stateBytes()).isLessThanOrEqualTo(2L << 20);
        }
    }

    @Test
    void aCompositeKeyMatchesOnAllOfItsColumns() {
        StreamSchema left = StreamSchema.builder("l")
                .field("a", Types.int64())
                .field("b", Types.string())
                .build();
        StreamSchema right = StreamSchema.builder("r")
                .field("a", Types.int64())
                .field("b", Types.string())
                .field("tag", Types.string())
                .build();

        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(left, right)
                        .plan("SELECT r.tag FROM l JOIN r ON l.a = r.a AND l.b = r.b"));

        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        try (Harness harness = new Harness(plan, List.of(left, right), out)) {
            harness.feed("r", 1, new Object[] {1L, "x", "match"});
            harness.feed("r", 1, new Object[] {1L, "y", "wrong-b"});
            harness.feed("r", 1, new Object[] {2L, "x", "wrong-a"});
            harness.feed("l", 1, new Object[] {1L, "x"});
        }

        assertThat(out.stream().map(row -> row.values()[0]).toList()).containsExactly("match");
    }

    @Test
    void aNullKeyMatchesNothingIncludingOtherNulls() {
        StreamSchema left = StreamSchema.builder("l")
                .field("k", Types.string().withNullable(true))
                .build();
        StreamSchema right = StreamSchema.builder("r")
                .field("k", Types.string().withNullable(true))
                .field("tag", Types.string())
                .build();

        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(left, right).plan("SELECT r.tag FROM l JOIN r ON l.k = r.k"));

        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        try (Harness harness = new Harness(plan, List.of(left, right), out)) {
            harness.feed("r", 1, new Object[] {null, "null-key"});
            harness.feed("l", 1, new Object[] {(Object) null});
        }

        assertThat(out).as("NULL = NULL is not true, so these rows do not join").isEmpty();
    }

    @Test
    void anOuterJoinIsRefusedWithTheReasonRatherThanJustUnsupported() {
        assertThatThrownBy(() -> plan(
                        "SELECT o.order_id, u.country FROM orders o " + "LEFT JOIN users u ON o.user_id = u.user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("as long as a match could still arrive");
    }

    @Test
    void aNonEquiJoinIsRefusedWithTheReasonRatherThanJustUnsupported() {
        assertThatThrownBy(() -> plan("SELECT o.order_id FROM orders o JOIN users u ON o.user_id > u.user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("cross product");
    }

    @Test
    void aJoinedQueryRefusesToCheckpointRatherThanLoseItsState() {
        // A snapshot that omits the join's rows restores a query that has forgotten them while its
        // offsets claim they were consumed. Saying so is the only honest answer until the format
        // carries them.
        PhysicalOperator plan =
                plan("SELECT o.order_id, u.country FROM orders o JOIN users u ON o.user_id = u.user_id");
        List<CapturingRowWriter.Captured> out = new ArrayList<>();

        try (Harness harness = new Harness(plan, List.of(orders(), users()), out)) {
            assertThatThrownBy(harness::snapshot)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3021")
                    .hasMessageContaining("cannot be checkpointed yet");
        }
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(orders(), users()).plan(sql));
    }

    private static List<String> countriesFrom(String sql, List<Input> rows) {
        return run(sql, rows).stream()
                .map(row -> String.valueOf(row.values()[1]))
                .toList();
    }

    private static List<CapturingRowWriter.Captured> run(String sql, List<Input> rows) {
        PhysicalOperator plan = plan(sql);
        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        try (Harness harness = new Harness(plan, List.of(orders(), users()), results)) {
            for (Input row : rows) {
                harness.feed(row.stream(), row.weight(), row.values());
            }
        }
        return results;
    }

    /** Feeds rows into a two-input pipeline, encoding each one for the schema of its own stream. */
    private static final class Harness implements AutoCloseable {
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        private final InterpretedPipeline pipeline;
        private final List<StreamSchema> schemas;
        private long sequence;

        Harness(PhysicalOperator plan, List<StreamSchema> schemas, List<CapturingRowWriter.Captured> into) {
            this.schemas = schemas;
            this.pipeline = InterpretedPipeline.compile(
                    plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), into::add));
        }

        void feed(String stream, long weight, Object[] values) {
            StreamSchema schema = schemas.stream()
                    .filter(s -> s.name().equals(stream))
                    .findFirst()
                    .orElseThrow();
            RowLayout layout = RowLayout.of(schema);
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value == null) {
                    writer.setNull(i);
                } else if (value instanceof Long longValue) {
                    writer.setLong(i, longValue);
                } else {
                    writer.setString(i, String.valueOf(value));
                }
            }
            sequence++;
            writer.weight(weight)
                    .eventTimestampNanos(sequence)
                    .sequence(sequence)
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            pipeline.accept(stream, new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }

        long rowsHeld() {
            return pipeline.joinRowsHeld();
        }

        long keysHeld() {
            return pipeline.joinKeysHeld();
        }

        long stateBytes() {
            return pipeline.joinStateBytes();
        }

        byte[] snapshot() {
            return pipeline.snapshotState();
        }

        @Override
        public void close() {
            pipeline.close();
            arena.close();
        }
    }
}
