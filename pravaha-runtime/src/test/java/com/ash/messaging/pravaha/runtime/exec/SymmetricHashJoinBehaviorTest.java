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
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code SymmetricHashJoin}'s row, key and time-window arithmetic, exercised directly against the
 * operator (harness HJ5 of {@code docs/project/qa/cases/JOIN.md}) rather than through SQL.
 *
 * <p>Every test here is one or more numbered cases from that file: {@code pairsEmitted()},
 * {@code outsideWindow()}, {@code evicted()}, {@code rowsHeldLeft/Right()} and
 * {@code keysHeldLeft/Right()} are asserted as exact numbers per the file's vacuity kit (W1-W4), not
 * as "some output arrived" -- an empty answer from a join whose right side never joined looks exactly
 * like an empty answer from a join that correctly found no match, and the file's own preamble names
 * that as the commonest false pass in this area.
 */
class SymmetricHashJoinBehaviorTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long HOUR = 3_600 * SECOND;

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    private final List<Captured> out = new ArrayList<>();

    @AfterEach
    void tearDown() {
        arena.close();
    }

    /** One emitted row, copied out immediately since the join's arena block is reused after it. */
    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Captured(long weight, long eventTimeNanos, long sequence, Object[] values) {}

    // ---- Standing fixture: JOIN.md's "orders" (5 cols) and "users" (4 cols). ----

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .build();
    }

    private static StreamSchema users() {
        return StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("tier", Types.string())
                .field("event_time", Types.timestamp())
                .build();
    }

    /**
     * The right side's columns are always made nullable in the merged output, whatever the source
     * schema says: a real plan does the same for an outer join's output, and making it unconditional
     * here means one helper serves every test rather than a second one only outer-join cases need.
     */
    private static StreamSchema merged(StreamSchema left, StreamSchema right) {
        StreamSchema.Builder b = StreamSchema.builder("joined");
        for (int i = 0; i < left.fieldCount(); i++) {
            b.field("l_" + left.field(i).name(), left.field(i).type());
        }
        for (int i = 0; i < right.fieldCount(); i++) {
            b.field("r_" + right.field(i).name(), right.field(i).type().withNullable(true));
        }
        return b.build();
    }

    private JoinOperator join(StreamSchema left, StreamSchema right, List<Integer> leftKeys, List<Integer> rightKeys) {
        return new JoinOperator(
                ScanOperator.of(left.name(), left),
                ScanOperator.of(right.name(), right),
                leftKeys,
                rightKeys,
                merged(left, right),
                1_000_000);
    }

    private RowProcessor collector() {
        return row -> {
            Object[] values = new Object[row.schema().fieldCount()];
            for (int i = 0; i < values.length; i++) {
                values[i] = row.isNull(i) ? null : readAny(row, i);
            }
            out.add(new Captured(row.weight(), row.eventTimestampNanos(), row.sequence(), values));
        };
    }

    private static Object readAny(RowView row, int ordinal) {
        return switch (row.schema().field(ordinal).type().typeName()) {
            case STRING -> row.getString(ordinal);
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            default -> row.getLong(ordinal);
        };
    }

    /** Writes one row of {@code schema} with a string/long-typed value list, and feeds it to {@code into}. */
    private void feed(RowProcessor into, StreamSchema schema, long weight, long at, Object... values) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            PravahaType type = schema.field(i).type();
            if (v == null) {
                writer.setNull(i);
            } else if (v instanceof String s) {
                writer.setString(i, s);
            } else if (v instanceof Boolean bo) {
                writer.setBoolean(i, bo);
            } else {
                long lv = ((Number) v).longValue();
                switch (type.typeName()) {
                    case INT8 -> writer.setByte(i, (byte) lv);
                    case INT32, DATE -> writer.setInt(i, (int) lv);
                    default -> writer.setLong(i, lv);
                }
            }
        }
        writer.weight(weight).eventTimestampNanos(at).sequence(at).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        into.process(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    // ======================= JOIN-003: output columns are left's then right's, positionally =======================

    @Test
    void outputColumnsAreTheLeftInputsThenTheRightInputsInOrder() {
        // o3 x x1 is the discriminating row: column 2 (o.region) and column 6 (u.region) hold
        // different values, so a transposition of the two halves is visible in a way it would not be
        // on o1 x x1, where both region columns happen to read "eu".
        try (SymmetricHashJoin join = standardJoinOfJ1J2(List.of(1), List.of(0))) {
            assertThat(join.pairsEmitted()).isEqualTo(3);
        }
        Captured o3x1 = out.stream()
                .filter(row -> "us".equals(row.values()[2]))
                .findFirst()
                .orElseThrow();
        assertThat(o3x1.values())
                .as(
                        "orders' five columns verbatim, then users' four -- leftWidth() decides where the right half begins")
                .containsExactly(3L, "u1", "us", 200L, 3 * SECOND, "u1", "eu", "gold", 0L);
    }

    // ======================= JOIN-004: joined event time is max(left, right) =======================

    @Test
    void aJoinedRowsEventTimeIsTheLaterOfTheTwoInputs() {
        // Two independent joins, one per direction, so a second stored row on either side cannot
        // produce a second pair and confuse which pair's time is being read.
        JoinOperator plan = join(orders(), users(), List.of(1), List.of(0));

        try (SymmetricHashJoin rightEarlier = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(rightEarlier.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L); // x1 at 0s
            feed(rightEarlier.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND); // o1 at 1s
            assertThat(rightEarlier.pairsEmitted()).isEqualTo(1);
        }
        assertThat(out).hasSize(1);
        assertThat(out.get(0).eventTimeNanos()).as("max(1s, 0s) = 1s").isEqualTo(SECOND);

        out.clear();
        try (SymmetricHashJoin rightLater = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(rightLater.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND); // o1 at 1s
            feed(rightLater.rightInput(), users(), 1, 9 * SECOND, "u1", "eu", "gold", 9 * SECOND); // x1' at 9s
            assertThat(rightLater.pairsEmitted()).isEqualTo(1);
        }
        assertThat(out).hasSize(1);
        assertThat(out.get(0).eventTimeNanos())
                .as("max(1s, 9s) = 9s -- the right row arriving later makes the pair's time the right row's")
                .isEqualTo(9 * SECOND);
    }

    // ======================= JOIN-005: pair weight is the product of the two rows' =======================

    @Test
    void aPairsWeightIsTheProductOfTheTwoRowsWeights() {
        // x1 at +1; then o1 fed at +1, -1, +2 (identical field values each time, so these consolidate
        // into one left entry as they go); then a right row at the same key and +3.
        JoinOperator plan = join(orders(), users(), List.of(1), List.of(0));
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L); // x1, stored weight +1
            feed(join.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND); // +1 x +1 = +1
            feed(
                    join.leftInput(),
                    orders(),
                    -1,
                    SECOND,
                    1L,
                    "u1",
                    "eu",
                    100L,
                    SECOND); // -1 x +1 = -1 (left cancels to 0)
            feed(
                    join.leftInput(),
                    orders(),
                    2,
                    SECOND,
                    1L,
                    "u1",
                    "eu",
                    100L,
                    SECOND); // +2 x +1 = +2 (left now holds +2)
            feed(
                    join.rightInput(),
                    users(),
                    3,
                    0,
                    "u1",
                    "eu",
                    "gold",
                    0L); // +2 (stored left) x +3 (arriving right) = +6

            assertThat(join.pairsEmitted())
                    .as("a weight of 0 on either side emits nothing before allocating; every arrival here is non-zero")
                    .isEqualTo(4);
            assertThat(out.stream().map(Captured::weight).toList()).containsExactly(1L, -1L, 2L, 6L);
        }
    }

    @Test
    void aZeroWeightArrivalEmitsNothingBeforeAllocating() {
        // A row that cancels a stored entry to exactly zero, met immediately by a probe from the
        // other side, must not emit a phantom weight-0 pair -- emit() returns false on weight == 0
        // before it allocates anything.
        JoinOperator plan = join(orders(), users(), List.of(1), List.of(0));
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L);
            feed(join.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND); // +1
            feed(join.leftInput(), orders(), -1, SECOND, 1L, "u1", "eu", 100L, SECOND); // -1, left back to 0
            assertThat(join.pairsEmitted()).isEqualTo(2);
            assertThat(out.stream().map(Captured::weight).toList()).containsExactly(1L, -1L);
        }
    }

    // ======================= JOIN-006: the ceiling refuses loudly =======================

    @Test
    void theJoinCeilingRefusesLoudlyRatherThanEvicting() {
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("orders", orders()),
                ScanOperator.of("users", users()),
                List.of(1),
                List.of(0),
                merged(orders(), users()),
                3);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 1L, SECOND);
            feed(join.leftInput(), orders(), 1, SECOND, 2L, "u2", "eu", 1L, SECOND);
            feed(join.leftInput(), orders(), 1, SECOND, 3L, "u3", "eu", 1L, SECOND);
            assertThat(join.rowsHeldLeft()).isEqualTo(3);

            assertThatThrownBy(() -> feed(join.leftInput(), orders(), 1, SECOND, 4L, "u4", "eu", 1L, SECOND))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4001")
                    .hasMessageContaining("left side of Join")
                    .hasMessageContaining("holds 4 rows")
                    .hasMessageContaining("past the ceiling of 3");
        }
    }

    // ======================= §1: keys and row shapes (JOIN-007 .. JOIN-022) =======================

    /** Feeds J2 (three users) then J1 (four orders), the file's standard arrival order. */
    private SymmetricHashJoin standardJoinOfJ1J2(List<Integer> leftKeys, List<Integer> rightKeys) {
        JoinOperator plan = join(orders(), users(), leftKeys, rightKeys);
        SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64);
        feed(join.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L);
        feed(join.rightInput(), users(), 1, 0, "u2", "eu", "silver", 0L);
        feed(join.rightInput(), users(), 1, 0, "u3", "us", "bronze", 0L);
        feed(join.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND);
        feed(join.leftInput(), orders(), 1, 2 * SECOND, 2L, "u2", "eu", 50L, 2 * SECOND);
        feed(join.leftInput(), orders(), 1, 3 * SECOND, 3L, "u1", "us", 200L, 3 * SECOND);
        feed(join.leftInput(), orders(), 1, 4 * SECOND, 4L, "u9", "eu", 7L, 4 * SECOND);
        return join;
    }

    @Test
    void oneKeyGivesThreePairsFromFourAndThreeRows() {
        try (SymmetricHashJoin join = standardJoinOfJ1J2(List.of(1), List.of(0))) {
            assertThat(join.pairsEmitted()).isEqualTo(3);
            assertThat(join.rowsHeldLeft()).isEqualTo(4);
            assertThat(join.rowsHeldRight()).isEqualTo(3);
            assertThat(join.keysHeldLeft()).isEqualTo(3); // u1, u2, u9
            assertThat(join.keysHeldRight()).isEqualTo(3); // u1, u2, u3
        }
    }

    @Test
    void aSecondKeyNarrowsThreePairsToTwo() {
        try (SymmetricHashJoin join = standardJoinOfJ1J2(List.of(1, 2), List.of(0, 1))) {
            // o3 is (u1, us); x1 is (u1, eu): composite key differs, so the JOIN-007 pair is gone.
            assertThat(join.pairsEmitted()).isEqualTo(2);
        }
    }

    @Test
    void aCompositeKeyOfTwoEqualColumnsDoesNotCollideWithItsSwap() {
        // (eu,eu),(us,us),(eu,us),(us,eu): four distinct keys, not a self-cancelling or commutative mix.
        StreamSchema pair = StreamSchema.builder("p")
                .field("a", Types.string())
                .field("b", Types.string())
                .build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", pair),
                ScanOperator.of("r", pair),
                List.of(0, 1),
                List.of(0, 1),
                merged(pair, pair),
                1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            for (String[] key : new String[][] {{"eu", "eu"}, {"us", "us"}, {"eu", "us"}, {"us", "eu"}}) {
                feed(join.rightInput(), pair, 1, 0, key[0], key[1]);
            }
            for (String[] key : new String[][] {{"eu", "eu"}, {"us", "us"}, {"eu", "us"}, {"us", "eu"}}) {
                feed(join.leftInput(), pair, 1, 0, key[0], key[1]);
            }
            assertThat(join.pairsEmitted())
                    .as("one pair per distinct key, not 16 and not 8")
                    .isEqualTo(4);
            assertThat(join.keysHeldRight()).isEqualTo(4);
        }
    }

    @Test
    void everyListedKeyTypeJoinsCorrectlyAndSelects() {
        // JOIN-012: STRING, INT64, INT32, INT8, BOOLEAN, DATE, TIME, TIMESTAMP. One matching pair and
        // one non-matching row fed each time (W3), asserted per type.
        record Case(String label, PravahaType type, Object matchA, Object matchB, Object miss) {}
        List<Case> cases = List.of(
                new Case("STRING", Types.string(), "k1", "k1", "kX"),
                new Case("INT64", Types.int64(), 1L, 1L, 9L),
                new Case("INT32", Types.int32(), 1, 1, 9),
                new Case("INT8", Types.int8(), (byte) 1, (byte) 1, (byte) 9),
                new Case("BOOLEAN", Types.bool(), true, true, false),
                new Case("DATE", Types.date(), 19000, 19000, 1),
                new Case("TIME", Types.time(), 3600L, 3600L, 1L),
                new Case("TIMESTAMP", Types.timestamp(), 1_000_000_000L, 1_000_000_000L, 1L));
        for (Case c : cases) {
            out.clear();
            StreamSchema s = StreamSchema.builder("s" + c.label())
                    .field("k", c.type())
                    .field("tag", Types.string())
                    .build();
            JoinOperator plan = new JoinOperator(
                    ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
            try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
                feed(join.rightInput(), s, 1, 0, c.matchB(), "right");
                feed(join.rightInput(), s, 1, 0, c.miss(), "right-miss");
                feed(join.leftInput(), s, 1, 0, c.matchA(), "left");
                assertThat(join.pairsEmitted()).as("type %s", c.label()).isEqualTo(1);
                assertThat(join.rowsHeldRight())
                        .as("both right rows (the match and the miss) must be held -- W3", c.label())
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void noMatchingRowsAtAllIsDistinguishedFromAnIngestFailure() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            for (String k : List.of("u7", "u8", "u9")) {
                feed(join.leftInput(), s, 1, 0, k);
            }
            for (String k : List.of("u1", "u2", "u3")) {
                feed(join.rightInput(), s, 1, 0, k);
            }
            assertThat(join.pairsEmitted()).isZero();
            assertThat(join.rowsHeldLeft()).as("W1: both sides did arrive").isEqualTo(3);
            assertThat(join.rowsHeldRight()).isEqualTo(3);
            assertThat(join.keysHeldLeft()).isEqualTo(3);
            assertThat(join.keysHeldRight()).isEqualTo(3);
            assertThat(join.outsideWindow())
                    .as("nothing matched on the key, so nothing reached the time check")
                    .isZero();
        }
    }

    @Test
    void everyRowMatchingProducesTheFullCrossProductOfAHotKey() {
        // A second column that varies is what makes these three rows on each side distinct Z-set
        // elements rather than one element of weight 3 -- JoinSide.add consolidates rows whose fields
        // are identical, per StreamJoinTest's duplicateRowsAreCountedRatherThanCollapsed, and a
        // consolidated single entry would emit 3 pairs (one per probe), not 9, as seeding this test
        // without the tag column found directly (pairsEmitted() came back 3).
        StreamSchema s = StreamSchema.builder("s")
                .field("k", Types.string())
                .field("tag", Types.string())
                .build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            for (int i = 0; i < 3; i++) {
                feed(join.rightInput(), s, 1, 0, "u1", "r" + i);
            }
            for (int i = 0; i < 3; i++) {
                feed(join.leftInput(), s, 1, 0, "u1", "l" + i);
            }
            assertThat(join.pairsEmitted()).isEqualTo(9);
            assertThat(join.keysHeldLeft()).isEqualTo(1);
            assertThat(join.keysHeldRight()).isEqualTo(1);
            assertThat(join.rowsHeldLeft()).isEqualTo(3);
            assertThat(join.rowsHeldRight()).isEqualTo(3);
        }
    }

    @Test
    void arrivalOrderDoesNotChangeTheAnswer() {
        // Run A: J2 then J1 (standardJoinOfJ1J2's order). Run B: J1 then J2. Run C: interleaved.
        long a;
        out.clear();
        try (SymmetricHashJoin runA = standardJoinOfJ1J2(List.of(1), List.of(0))) {
            a = runA.pairsEmitted();
        }
        out.clear();
        JoinOperator plan = join(orders(), users(), List.of(1), List.of(0));
        long b;
        try (SymmetricHashJoin runB = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(runB.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND);
            feed(runB.leftInput(), orders(), 1, 2 * SECOND, 2L, "u2", "eu", 50L, 2 * SECOND);
            feed(runB.leftInput(), orders(), 1, 3 * SECOND, 3L, "u1", "us", 200L, 3 * SECOND);
            feed(runB.leftInput(), orders(), 1, 4 * SECOND, 4L, "u9", "eu", 7L, 4 * SECOND);
            feed(runB.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L);
            feed(runB.rightInput(), users(), 1, 0, "u2", "eu", "silver", 0L);
            feed(runB.rightInput(), users(), 1, 0, "u3", "us", "bronze", 0L);
            b = runB.pairsEmitted();
        }
        out.clear();
        long c;
        try (SymmetricHashJoin runC = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(runC.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND);
            feed(runC.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L);
            feed(runC.leftInput(), orders(), 1, 2 * SECOND, 2L, "u2", "eu", 50L, 2 * SECOND);
            feed(runC.rightInput(), users(), 1, 0, "u2", "eu", "silver", 0L);
            feed(runC.leftInput(), orders(), 1, 3 * SECOND, 3L, "u1", "us", 200L, 3 * SECOND);
            feed(runC.rightInput(), users(), 1, 0, "u3", "us", "bronze", 0L);
            feed(runC.leftInput(), orders(), 1, 4 * SECOND, 4L, "u9", "eu", 7L, 4 * SECOND);
            c = runC.pairsEmitted();
        }
        assertThat(a).isEqualTo(3);
        assertThat(b).isEqualTo(3);
        assertThat(c).isEqualTo(3);
    }

    @Test
    void oneSideEmptyProducesNoPairsUntilTheOtherArrives() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.leftInput(), s, 1, 0, "u1");
            feed(join.leftInput(), s, 1, 0, "u2");
            assertThat(join.pairsEmitted()).isZero();
            assertThat(join.rowsHeldLeft()).isEqualTo(2);
            assertThat(join.rowsHeldRight()).isZero();

            feed(join.rightInput(), s, 1, 0, "u1");
            assertThat(join.pairsEmitted())
                    .as("the left row was held and matched when its partner arrived")
                    .isEqualTo(1);
        }
    }

    @Test
    void bothSidesEmptyProducesNoOutputAndNoException() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            join.advanceWatermark(10 * SECOND);
            join.advanceWatermark(2 * HOUR);
            assertThat(join.pairsEmitted()).isZero();
            assertThat(join.evicted()).isZero();
        }
    }

    @Test
    void aNullKeyMatchesNothingIncludingAnotherNullKey() {
        StreamSchema s = StreamSchema.builder("s")
                .field("k", Types.string().withNullable(true))
                .build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 0, "u1");
            feed(join.rightInput(), s, 1, 0, (Object) null);
            feed(join.leftInput(), s, 1, 0, "u1");
            feed(join.leftInput(), s, 1, 0, (Object) null);

            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(join.rowsHeldLeft())
                    .as("the null-keyed row is not stored: it can never match, so holding it is a leak")
                    .isEqualTo(1);
            assertThat(join.rowsHeldRight()).isEqualTo(1);
        }
    }

    @Test
    void aCompositeKeyWithOneNullColumnIsAlsoUnmatchable() {
        StreamSchema s = StreamSchema.builder("s")
                .field("a", Types.string())
                .field("b", Types.string().withNullable(true))
                .build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0, 1), List.of(0, 1), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 0, "u1", "eu");
            feed(join.rightInput(), s, 1, 0, "u1", null);
            feed(join.leftInput(), s, 1, 0, "u1", "eu");
            feed(join.leftInput(), s, 1, 0, "u1", null);

            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(join.rowsHeldLeft()).isEqualTo(1);
            assertThat(join.rowsHeldRight()).isEqualTo(1);
        }
    }

    @Test
    void duplicateKeysMultiplyOnOneSideThenOnBoth() {
        // Variant (a): right side has a duplicate u1 key (x1, x1b -- distinct rows, different tier).
        JoinOperator plan = join(orders(), users(), List.of(1), List.of(0));
        try (SymmetricHashJoin a = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(a.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L);
            feed(a.rightInput(), users(), 1, 0, "u1", "eu", "platinum", 0L); // x1b: distinct row
            feed(a.rightInput(), users(), 1, 0, "u2", "eu", "silver", 0L);
            feed(a.rightInput(), users(), 1, 0, "u3", "us", "bronze", 0L);
            feed(a.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND);
            feed(a.leftInput(), orders(), 1, 2 * SECOND, 2L, "u2", "eu", 50L, 2 * SECOND);
            feed(a.leftInput(), orders(), 1, 3 * SECOND, 3L, "u1", "us", 200L, 3 * SECOND);
            feed(a.leftInput(), orders(), 1, 4 * SECOND, 4L, "u9", "eu", 7L, 4 * SECOND);
            // o1 x {x1,x1b}=2, o2 x x2=1, o3 x {x1,x1b}=2, o4 x {}=0 -> 5
            assertThat(a.pairsEmitted()).isEqualTo(5);
            assertThat(a.keysHeldRight()).isEqualTo(3);
            assertThat(a.rowsHeldRight()).isEqualTo(4);
        }
        out.clear();

        // Variant (b): both sides duplicated (o1b added on the left too).
        try (SymmetricHashJoin b = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(b.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L);
            feed(b.rightInput(), users(), 1, 0, "u1", "eu", "platinum", 0L);
            feed(b.rightInput(), users(), 1, 0, "u2", "eu", "silver", 0L);
            feed(b.rightInput(), users(), 1, 0, "u3", "us", "bronze", 0L);
            feed(b.leftInput(), orders(), 1, SECOND, 1L, "u1", "eu", 100L, SECOND);
            feed(b.leftInput(), orders(), 1, 5 * SECOND, 5L, "u1", "eu", 100L, 5 * SECOND); // o1b
            feed(b.leftInput(), orders(), 1, 2 * SECOND, 2L, "u2", "eu", 50L, 2 * SECOND);
            feed(b.leftInput(), orders(), 1, 3 * SECOND, 3L, "u1", "us", 200L, 3 * SECOND);
            feed(b.leftInput(), orders(), 1, 4 * SECOND, 4L, "u9", "eu", 7L, 4 * SECOND);
            // u1 orders {o1,o3,o1b}=3, u1 users {x1,x1b}=2 -> 6; plus o2 x x2=1 -> 7
            assertThat(b.pairsEmitted()).isEqualTo(7);
            assertThat(b.rowsHeldLeft()).isEqualTo(5);
            assertThat(b.rowsHeldRight()).isEqualTo(4);
        }
    }

    // ======================= §2: the match window and eviction (JOIN-023 .. JOIN-034) =======================

    @Test
    void theDefaultMatchWindowIsAnHourAndDecidesTheAnswer() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 0, "u1");
            feed(join.leftInput(), s, 1, SECOND, "u1"); // delta +1s -> inside
            feed(join.leftInput(), s, 1, HOUR, "u1"); // delta +1h -> inclusive, inside
            feed(join.leftInput(), s, 1, HOUR + SECOND, "u1"); // delta +1h+1s -> outside

            assertThat(join.pairsEmitted()).isEqualTo(2);
            assertThat(join.outsideWindow()).isEqualTo(1);
        }
    }

    @Test
    void theDefaultWindowIsSymmetric() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 2 * HOUR, "u1");
            feed(join.leftInput(), s, 1, HOUR, "u1"); // delta -1h -> inside (>= -1h)
            feed(join.leftInput(), s, 1, HOUR - SECOND, "u1"); // delta -1h-1s -> outside

            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(join.outsideWindow()).isEqualTo(1);
        }
    }

    @Test
    void aStatedBoundReplacesTheDefaultAndIsDirectional() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        long fiveMin = 300 * SECOND;
        JoinOperator plan = JoinOperator.withinRange(
                ScanOperator.of("l", s),
                ScanOperator.of("r", s),
                List.of(0),
                List.of(0),
                merged(s, s),
                1_000,
                -fiveMin,
                0);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 600 * SECOND, "u1");
            feed(join.leftInput(), s, 1, 299 * SECOND, "u1"); // delta -301s -> out
            feed(join.leftInput(), s, 1, 300 * SECOND, "u1"); // delta -300s -> in
            feed(join.leftInput(), s, 1, 600 * SECOND, "u1"); // delta 0 -> in
            feed(join.leftInput(), s, 1, 601 * SECOND, "u1"); // delta +1s -> out

            assertThat(join.pairsEmitted()).isEqualTo(2);
            assertThat(join.outsideWindow()).isEqualTo(2);
        }
    }

    @Test
    void aOneSidedBoundClosesAtZeroOnTheUnstatedSide() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        long thirtySec = 30 * SECOND;
        // >= r.t - 30s, no upper stated: lower -30s, upper falls back to max(0, lower) = 0.
        JoinOperator plan = JoinOperator.withinRange(
                ScanOperator.of("l", s),
                ScanOperator.of("r", s),
                List.of(0),
                List.of(0),
                merged(s, s),
                1_000,
                -thirtySec,
                Math.max(0, -thirtySec));
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 100 * SECOND, "u1");
            feed(join.leftInput(), s, 1, 69 * SECOND, "u1"); // -31s out
            feed(join.leftInput(), s, 1, 70 * SECOND, "u1"); // -30s in
            feed(join.leftInput(), s, 1, 100 * SECOND, "u1"); // 0 in
            feed(join.leftInput(), s, 1, 130 * SECOND, "u1"); // +30s out -- unstated upper is 0, not an hour

            assertThat(join.pairsEmitted()).isEqualTo(2);
            assertThat(join.outsideWindow()).isEqualTo(2);
        }
    }

    @Test
    void invertedBoundsAreRefusedAtConstruction() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        assertThatThrownBy(() -> JoinOperator.withinRange(
                        ScanOperator.of("l", s),
                        ScanOperator.of("r", s),
                        List.of(0),
                        List.of(0),
                        merged(s, s),
                        1_000,
                        60 * SECOND,
                        -60 * SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inverted")
                .hasMessageContaining("60000000000")
                .hasMessageContaining("-60000000000");
    }

    @Test
    void aZeroWidthBoundStillGetsAPositiveMatchWindow() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = JoinOperator.withinRange(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000, 0, 0);
        assertThat(plan.matchWithinNanos()).isEqualTo(1);
        assertThat(plan.matchLowerNanos()).isZero();
        assertThat(plan.matchUpperNanos()).isZero();

        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 100 * SECOND, "u1");
            feed(join.leftInput(), s, 1, 100 * SECOND, "u1"); // delta 0 -> emitted
            feed(join.leftInput(), s, 1, 100 * SECOND + 1, "u1"); // delta +1ns -> outside

            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(join.outsideWindow()).isEqualTo(1);

            join.advanceWatermark(100 * SECOND + 2);
            // Horizon is (100s+2) - 1 = 100s+1: both the right row (100s) and the matching left row
            // (100s) are older than it and go; the 1ns-later left row (100s+1) is not strictly older
            // and survives. Retention of 1ns makes eviction effectively immediate.
            assertThat(join.evicted()).isEqualTo(2);
        }
    }

    @Test
    void evictionReleasesRowsPastWatermarkMinusMatchWithin() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 0, "u1");
            feed(join.rightInput(), s, 1, 1800 * SECOND, "u2");
            feed(join.rightInput(), s, 1, 3600 * SECOND, "u3");

            join.advanceWatermark(3600 * SECOND); // horizon 0s: nothing < 0
            assertThat(join.evicted()).isZero();
            assertThat(join.rowsHeldRight()).isEqualTo(3);

            join.advanceWatermark(5400 * SECOND); // horizon 1800s: the 0s row goes
            assertThat(join.evicted()).isEqualTo(1);
            assertThat(join.rowsHeldRight()).isEqualTo(2);

            join.advanceWatermark(
                    7200 * SECOND); // horizon 3600s: the 1800s row goes; 3600s survives (3600 < 3600 is false)
            assertThat(join.evicted()).isEqualTo(2);
            assertThat(join.rowsHeldRight()).isEqualTo(1);
        }
    }

    @Test
    void anEvictedRowNoLongerMatchesAPartnerThatArrivesLater() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 0, "u1");
            join.advanceWatermark(2 * HOUR);
            assertThat(join.evicted()).isEqualTo(1);

            feed(join.leftInput(), s, 1, 0, "u1");
            assertThat(join.pairsEmitted()).isZero();
            assertThat(join.outsideWindow())
                    .as("the partner was gone, not rejected by the time check")
                    .isZero();
            assertThat(join.rowsHeldLeft()).isEqualTo(1);
        }
    }

    @Test
    void aRowWhosePartnerWasEvictedAndARowWhosePartnerNeverExistedAreIndistinguishable() {
        // JOIN-033: an enumeration case rather than a falsifiable one. Extends JOIN-031's sequence
        // with a second left row whose partner was never fed at all, then reads every counter the
        // operator exposes. The point is that the two left rows -- one whose partner was evicted, one
        // whose partner never existed -- read identically everywhere: a diagnosability gap, not a bug.
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 0, "u1"); // will be evicted before its partner arrives
            join.advanceWatermark(2 * HOUR);
            assertThat(join.evicted()).isEqualTo(1);

            feed(join.leftInput(), s, 1, 0, "u1"); // partner was evicted
            feed(join.leftInput(), s, 1, 0, "u2"); // partner never existed

            // Every counter the operator exposes, read after both rows have been fed. Both rows are
            // indistinguishable in all of them: pairsEmitted is 0 either way, outsideWindow is 0
            // either way (neither pair was ever a time-check candidate), and rowsHeldLeft simply
            // counts two held rows with no way to tell which kind either one is.
            assertThat(join.pairsEmitted()).isZero();
            assertThat(join.outsideWindow()).isZero();
            assertThat(join.evicted()).isEqualTo(1);
            assertThat(join.unmatchedEmitted())
                    .as("this is an inner join: there is no unmatched-row counter to even ask the "
                            + "question of, which is itself part of the gap")
                    .isZero();
            assertThat(join.rowsHeldLeft())
                    .as("two left rows held, with nothing in this count -- or any other -- separating "
                            + "'partner evicted' from 'partner never existed'")
                    .isEqualTo(2);
            assertThat(join.keysHeldLeft()).isEqualTo(2);
            assertThat(join.stateBytes()).isPositive();
        }
    }

    @Test
    void aLateArrivingRowStillMatchesInsideTheWindow() {
        JoinOperator plan = join(orders(), users(), List.of(1), List.of(0));
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.leftInput(), orders(), 1, 3 * SECOND, 3L, "u1", "us", 200L, 3 * SECOND);
            join.advanceWatermark(1800 * SECOND);
            feed(join.rightInput(), users(), 1, 0, "u1", "eu", "gold", 0L); // late by arrival, earlier by event time

            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(join.evicted())
                    .as("horizon at watermark 1800s is negative and saturates below every row's time")
                    .isZero();
            assertThat(out.get(0).eventTimeNanos()).isEqualTo(3 * SECOND);
        }
    }

    @Test
    void evictionSaturatesRatherThanWrappingAtTheBottomOfTheRange() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        JoinOperator plan = new JoinOperator(
                ScanOperator.of("l", s), ScanOperator.of("r", s), List.of(0), List.of(0), merged(s, s), 1_000);
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 0, "u1");
            feed(join.rightInput(), s, 1, SECOND, "u2");
            feed(join.rightInput(), s, 1, 2 * SECOND, "u3");

            join.advanceWatermark(Long.MIN_VALUE + 1000);
            join.advanceWatermark(Long.MIN_VALUE);

            assertThat(join.evicted()).isZero();
            assertThat(join.rowsHeldRight()).isEqualTo(3);
        }
    }

    // ======================= §5: outer-join specific eviction cases (JOIN-056, JOIN-057) =======================

    @Test
    void aLeftOuterJoinEmitsTheNullPaddedRowOnceAtEviction() {
        StreamSchema s = StreamSchema.builder("s").field("k", Types.string()).build();
        long fiveMin = 300 * SECOND;
        JoinOperator plan = JoinOperator.withinRange(
                        ScanOperator.of("l", s),
                        ScanOperator.of("r", s),
                        List.of(0),
                        List.of(0),
                        merged(s, s),
                        1_000,
                        -fiveMin,
                        0)
                .asLeftOuter();
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 600 * SECOND, "u1");
            feed(join.leftInput(), s, 1, 600 * SECOND, "u1"); // matches (delta 0)
            feed(join.leftInput(), s, 1, 600 * SECOND, "u9"); // never matches

            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(join.unmatchedEmitted()).as("nothing emitted eagerly").isZero();

            join.advanceWatermark(700 * SECOND); // horizon 400s: nothing evicts yet
            assertThat(join.unmatchedEmitted()).isZero();

            join.advanceWatermark(1000 * SECOND); // horizon 700s: both left rows evict
            assertThat(join.unmatchedEmitted())
                    .as("the matched row must not reappear null-padded")
                    .isEqualTo(1);
            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(join.evicted()).isEqualTo(3); // the two left rows and the one right row
        }
    }

    /**
     * J-1. A left row whose key is NULL matches nothing and, SQL says, must still appear —
     * null-padded on the right.
     *
     * <p>It did not. {@code JoinSide.add} dropped it on arrival because it could never match, so it
     * was not in state when eviction ran the outer-join callback: the row left no trace anywhere,
     * and the only visible consequence was {@code rowsHeldLeft()} being one lower than the number
     * of rows fed. This test asserted the drop. It now asserts the emission.
     */
    @Test
    void j1_aNullKeyedLeftRowIsEmittedNullPaddedLikeAnyOtherUnmatchedLeftRow() {
        StreamSchema s = StreamSchema.builder("s")
                .field("k", Types.string().withNullable(true))
                .build();
        long fiveMin = 300 * SECOND;
        JoinOperator plan = JoinOperator.withinRange(
                        ScanOperator.of("l", s),
                        ScanOperator.of("r", s),
                        List.of(0),
                        List.of(0),
                        merged(s, s),
                        1_000,
                        -fiveMin,
                        0)
                .asLeftOuter();
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 600 * SECOND, "u1");
            feed(join.leftInput(), s, 1, 600 * SECOND, "u1");
            feed(join.leftInput(), s, 1, 600 * SECOND, (Object) null);

            assertThat(join.rowsHeldLeft())
                    .as("both left rows are held, the null-keyed one included")
                    .isEqualTo(2);

            join.advanceWatermark(1000 * SECOND);
            assertThat(join.unmatchedEmitted())
                    .as("the null-keyed left row is the one unmatched left row, and it is emitted")
                    .isEqualTo(1);
        }
    }

    /** J-1's other half: a null key still matches nothing, including another null key. */
    @Test
    void j1_aNullKeyedRowStillJoinsWithNothingIncludingAnotherNullKey() {
        StreamSchema s = StreamSchema.builder("s")
                .field("k", Types.string().withNullable(true))
                .build();
        long fiveMin = 300 * SECOND;
        JoinOperator plan = JoinOperator.withinRange(
                        ScanOperator.of("l", s),
                        ScanOperator.of("r", s),
                        List.of(0),
                        List.of(0),
                        merged(s, s),
                        1_000,
                        -fiveMin,
                        0)
                .asLeftOuter();
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan, arena, collector(), 64)) {
            feed(join.rightInput(), s, 1, 600 * SECOND, (Object) null);
            feed(join.leftInput(), s, 1, 600 * SECOND, (Object) null);

            assertThat(out)
                    .as("NULL is not equal to NULL in a join; two null keys are not a pair")
                    .isEmpty();
            assertThat(join.rowsHeldRight())
                    .as("the right side keeps nothing null-keyed: nothing would ever read it")
                    .isZero();

            join.advanceWatermark(1000 * SECOND);
            assertThat(join.unmatchedEmitted()).isEqualTo(1);
        }
    }
}
