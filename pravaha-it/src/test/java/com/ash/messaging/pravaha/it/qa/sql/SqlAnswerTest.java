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
package com.ash.messaging.pravaha.it.qa.sql;

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
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authored QA cases from {@code docs/project/qa/cases/AGG.md}, {@code JOIN.md} and the windowed and
 * bounded-read halves of {@code SQLX.md}, made executable.
 *
 * <p>Three shapes of query live here that a single-stream expression harness cannot reach.
 *
 * <ul>
 *   <li><b>The bounded read.</b> {@code PhysicalPlanBuilder.overBoundedInput()} is what
 *       {@code ViewQuery.physicalOf} calls, and it is the only path on which {@code SELECT DISTINCT}
 *       and an unwindowed keyed {@code GROUP BY} are legal at all. Over a stream both are refused,
 *       which {@code ExpressionMatrixTest} asserts; here they run and are checked against hand-
 *       computed groups.
 *   <li><b>The windowed aggregate.</b> Four defects in series once made this emit nothing, ever,
 *       silently (FINDINGS Q-1). Every number below is arithmetic over the fixture table.
 *   <li><b>The join.</b> Two streams, so the rows have to be fed by name --
 *       {@code InterpretedPipeline.accept(stream, row)} -- which is also what makes a self-join
 *       impossible and is why that refusal exists.
 * </ul>
 *
 * <p>Group emission order is a hash-map order and is not part of any contract, so a case's rows are
 * compared as a sorted multiset. The two places where order <em>is</em> the claim have their own
 * tests and compare in sequence.
 */
@org.junit.jupiter.api.Tag("qa")
class SqlAnswerTest {

    private static final long SECOND = 1_000_000_000L;

    /** SQLX Fixture S, and AGG's {@code txn} in all but the column names. */
    private static StreamSchema txn() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("price", Types.float64())
                .field("status", Types.string().withNullable(true))
                .field("flagged", Types.bool())
                .field("event_time", Types.timestamp())
                // Declared, not merely present: a windowed query over a stream with no declared
                // event time is refused (TIME-6), because no watermark advances over it and no
                // window it opens could ever close.
                .eventTime("event_time")
                .build();
    }

    /** JOIN.md's {@code orders}: five columns, so a right-side column i is output column 5 + i. */
    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.string().withNullable(true))
                .field("region", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .build();
    }

    /** JOIN.md's {@code users}: four columns, so a joined row is nine wide. */
    private static StreamSchema users() {
        return StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("tier", Types.string())
                .field("event_time", Types.timestamp())
                .build();
    }

    /**
     * SQLX Fixture D1 on {@code txn}, six rows.
     *
     * <pre>
     * txn_id user_id  amount price status    flagged event_time  window (TUMBLE 10s)
     * 1      u1       100    2.5   ok        true     1s         W1 [0s, 10s)
     * 2      u2       250    4.0   NULL      false    2s         W1
     * 3      u1       -50    1.0   ok        false    3s         W1
     * 4      u3       0      0.5   flagged   true     4s         W1
     * 5      u2       7      1.5   ok        false   12s         W2 [10s, 20s)
     * 6      ünïcødé  7      0.25  ok        true    13s         W2
     * </pre>
     *
     * <p>Hand-computed once. Globally: {@code COUNT(*) = 6}, {@code COUNT(status) = 5},
     * {@code SUM(amount) = 314}. W1: four rows, {@code SUM = 100 + 250 - 50 + 0 = 300},
     * {@code MIN = -50}, {@code MAX = 250}, {@code AVG = 300 / 4 = 75}. W2: two rows,
     * {@code SUM = 14}, {@code AVG = 7}. Keyed: u1 → {100, -50}, u2 → {250, 7}, u3 → {0},
     * ünïcødé → {7}.
     */
    private static final Object[][] D1 = {
        {1L, "u1", 100L, 2.5, "ok", true, 1 * SECOND},
        {2L, "u2", 250L, 4.0, null, false, 2 * SECOND},
        {3L, "u1", -50L, 1.0, "ok", false, 3 * SECOND},
        {4L, "u3", 0L, 0.5, "flagged", true, 4 * SECOND},
        {5L, "u2", 7L, 1.5, "ok", false, 12 * SECOND},
        {6L, "ünïcødé", 7L, 0.25, "ok", true, 13 * SECOND}
    };

    /** JOIN.md's J2, the right side. Fed first, so every left row probes a populated side. */
    private static final String[][] J2 = {
        {"u1", "eu", "gold", "0"}, {"u2", "eu", "silver", "0"}, {"u3", "us", "bronze", "0"}
    };

    /** JOIN.md's J1, the left side. o4's key u9 matches nothing, and x3's u3 matches nothing. */
    private static final Object[][] J1 = {
        {1L, "u1", "eu", 100L, 1L}, {2L, "u2", "eu", 50L, 2L},
        {3L, "u1", "us", 200L, 3L}, {4L, "u9", "eu", 7L, 4L}
    };

    private record Case(String id, String sql, List<String> answer) {
        static Case answers(String id, String sql, String... rows) {
            return new Case(id, sql, List.of(rows));
        }
    }

    // ===========================================================================================
    // The bounded read: SELECT DISTINCT and an unwindowed keyed GROUP BY, both of which are
    // refused over a stream and legal here. SQLX §1 and §4, AGG §1 and §3.
    // ===========================================================================================

    private static final List<Case> BOUNDED = List.of(
            Case.answers(
                    "SQLX-024 DISTINCT returns the set, not the rows",
                    "SELECT DISTINCT user_id FROM txn",
                    "u1",
                    "u2",
                    "u3",
                    "ünïcødé"),
            Case.answers(
                    "SQLX-025 DISTINCT treats NULL as a value, not as a row that vanishes",
                    "SELECT DISTINCT status FROM txn",
                    "NULL",
                    "flagged",
                    "ok"),
            Case.answers(
                    "SQLX-026 multi-column DISTINCT",
                    "SELECT DISTINCT user_id, status FROM txn",
                    "u1|ok",
                    "u2|NULL",
                    "u2|ok",
                    "u3|flagged",
                    "ünïcødé|ok"),
            Case.answers(
                    "SQLX-028 DISTINCT composed with WHERE",
                    "SELECT DISTINCT user_id FROM txn WHERE amount > 0",
                    "u1",
                    "u2",
                    "ünïcødé"),
            // SQLX-116 and AGG-013 in one row each. COUNT(status) differs from COUNT(*) for u2
            // only, which is the whole discriminator: r2's status is NULL and r5's is not.
            Case.answers(
                    "SQLX-116/AGG-013 a keyed GROUP BY over a bounded read",
                    "SELECT user_id, COUNT(*) AS a, COUNT(status) AS b, SUM(amount) AS s, "
                            + "MIN(amount) AS mn, MAX(amount) AS mx, AVG(amount) AS av "
                            + "FROM txn GROUP BY user_id",
                    "u1|2|2|50|-50|100|25",
                    "u2|2|1|257|7|250|128",
                    "u3|1|1|0|0|0|0",
                    "ünïcødé|1|1|7|7|7|7"),
            // SQLX-117 and AGG-056: NULL is a group, not a discarded row. 4 + 1 + 1 = 6.
            Case.answers(
                    "SQLX-117/AGG-056 NULL is a group over a bounded read",
                    "SELECT status, COUNT(*) AS n, SUM(amount) AS s FROM txn GROUP BY status",
                    "NULL|1|250",
                    "flagged|1|0",
                    "ok|4|64"),
            Case.answers(
                    "SQLX-118 multiple group columns",
                    "SELECT user_id, status, COUNT(*) AS n FROM txn GROUP BY user_id, status",
                    "u1|ok|2",
                    "u2|NULL|1",
                    "u2|ok|1",
                    "u3|flagged|1",
                    "ünïcødé|ok|1"),
            // AGG-108: group counts are u1=2, u2=2, u3=1, ünïcødé=1, so `> 1` keeps two.
            Case.answers(
                    "AGG-108/SQLX-153 HAVING on a keyed read",
                    "SELECT user_id, COUNT(*) AS n FROM txn GROUP BY user_id HAVING COUNT(*) > 1",
                    "u1|2",
                    "u2|2"),
            // AGG-024: keyed COUNT(DISTINCT) over a STRING column. The `ok` group holds u1, u1, u2
            // and ünïcødé, so three distinct users out of four rows -- 3 != 4 is the assertion.
            Case.answers(
                    "AGG-024 keyed COUNT(DISTINCT) over a STRING column",
                    "SELECT status, COUNT(DISTINCT user_id) AS d, COUNT(*) AS n FROM txn GROUP BY status",
                    "NULL|1|1",
                    "flagged|1|1",
                    "ok|3|4"),
            Case.answers(
                    "SQLX-027 SELECT DISTINCT * where every row is distinct",
                    "SELECT DISTINCT txn_id, user_id FROM txn",
                    "1|u1",
                    "2|u2",
                    "3|u1",
                    "4|u3",
                    "5|u2",
                    "6|ünïcødé"));

    @Test
    void everyBoundedReadCaseProducesItsHandComputedGroups() {
        assertEvery(BOUNDED, sql -> answerOf(sql, true, 0L));
    }

    // ===========================================================================================
    // The windowed aggregate. SQLX §4, AGG §1, §2, §3 and §6.
    // ===========================================================================================

    private static final String TUMBLE = " FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end";

    private static final List<Case> WINDOWED = List.of(
            // SQLX-101 and AGG-003. Boundaries first: W1 is [0s, 10s) and W2 is [10s, 20s), in
            // nanoseconds, and a tumble that silently used the slide as the size would give four.
            Case.answers(
                    "SQLX-101/AGG-003 TUMBLE: two windows, boundaries and sums",
                    "SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS s" + TUMBLE,
                    "0|10000000000|4|300",
                    "10000000000|20000000000|2|14"),
            // SQLX-100 and AGG-017/018: COUNT(col) must skip the NULL in the windowed operator too.
            // FINDINGS records the COUNT fix landing in GlobalAggregate only; this is the other one.
            Case.answers(
                    "SQLX-100/AGG-017 windowed COUNT(*) against windowed COUNT(col)",
                    "SELECT window_start, COUNT(*) AS a, COUNT(status) AS b" + TUMBLE,
                    "0|4|3",
                    "10000000000|2|2"),
            // SQLX-105 and AGG-042/096. AVG over W1 is 300 / 4 = 75: AGG-096 predicted the windowed
            // AVG would emit the sum undivided, and it does not.
            Case.answers(
                    "SQLX-105/AGG-096 SUM, MIN, MAX and AVG over one window",
                    "SELECT window_start, MIN(amount) AS lo, MAX(amount) AS hi, SUM(amount) AS t, "
                            + "AVG(amount) AS m, COUNT(amount) AS c" + TUMBLE,
                    "0|-50|250|300|75|4",
                    "10000000000|7|7|14|7|2"),
            // SQLX-104: slide 5s, size 10s. Every window spans 10s and every row lands in two of
            // them, so the four windows account for 12 row-window pairs from six rows. Swapping
            // size and slide would give 5s windows and one window per row.
            Case.answers(
                    "SQLX-104 HOP: size and slide are not swapped",
                    "SELECT window_start, window_end, COUNT(*) AS n "
                            + "FROM TABLE(HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '5' SECOND, "
                            + "INTERVAL '10' SECOND)) GROUP BY window_start, window_end",
                    "-5000000000|5000000000|4",
                    "0|10000000000|4",
                    "10000000000|20000000000|2",
                    "5000000000|15000000000|2"),
            Case.answers(
                    "SQLX-110/AGG-103 an aggregate over an expression, windowed",
                    "SELECT window_start, SUM(amount * 2) AS s" + TUMBLE,
                    "0|600",
                    "10000000000|28"),
            // SQLX-111 and AGG-107: W1 has n = 4 and W2 has n = 2, so `> 2` keeps exactly one.
            Case.answers(
                    "SQLX-111/AGG-107 HAVING on a windowed aggregate",
                    "SELECT window_start, COUNT(*) AS n" + TUMBLE + " HAVING COUNT(*) > 2",
                    "0|4"),
            Case.answers(
                    "SQLX-102 TUMBLE keyed by a column",
                    "SELECT window_start, user_id, SUM(amount) AS s, COUNT(*) AS n "
                            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                            + "GROUP BY window_start, window_end, user_id",
                    "0|u1|50|2",
                    "0|u2|250|1",
                    "0|u3|0|1",
                    "10000000000|u2|7|1",
                    "10000000000|ünïcødé|7|1"),
            // AGG-022: COUNT(DISTINCT) over an INT64 column is the one of the three the windowed
            // operator gets right, because its scratch really does hold the value.
            Case.answers(
                    "AGG-022 windowed COUNT(DISTINCT) over an INT64 column",
                    "SELECT window_start, COUNT(DISTINCT amount) AS d, COUNT(*) AS n" + TUMBLE,
                    "0|4|4",
                    "10000000000|1|2"),
            // AGG-058: a NULL-bearing group key in the windowed operator. One group of n = 1 for
            // the NULL, not a discarded row and not three groups of one.
            Case.answers(
                    "AGG-058 windowed GROUP BY a NULL-bearing column",
                    "SELECT window_start, status, COUNT(*) AS n "
                            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                            + "GROUP BY window_start, window_end, status",
                    "0|NULL|1",
                    "0|flagged|1",
                    "0|ok|2",
                    "10000000000|ok|2"),
            Case.answers(
                    "AGG-058 windowed GROUP BY an INT64 column with a repeated value",
                    "SELECT window_start, amount, COUNT(*) AS n "
                            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                            + "GROUP BY window_start, window_end, amount",
                    "0|-50|1",
                    "0|0|1",
                    "0|100|1",
                    "0|250|1",
                    "10000000000|7|2"));

    @Test
    void everyWindowedCaseProducesItsHandComputedWindows() {
        assertEvery(WINDOWED, sql -> answerOf(sql, false, 0L));
    }

    // ===========================================================================================
    // Joins. JOIN §1, §2 and §5.
    // ===========================================================================================

    @Test
    void theJoinedOutputIsLeftColumnsThenRightColumns() {
        // JOIN-003 and JOIN-007. Three pairs from four left rows and three right rows: o1×x1,
        // o2×x2, o3×x1. o4 (u9) and x3 (u3) exist precisely so that "three" is a statement about
        // selection rather than about how many rows were fed.
        assertThat(joinAnswerOf("SELECT * FROM orders o JOIN users u ON o.user_id = u.user_id", J2, J1, 0L))
                .containsExactly(
                        "1|u1|eu|100|1000000000|u1|eu|gold|0",
                        "2|u2|eu|50|2000000000|u2|eu|silver|0",
                        "3|u1|us|200|3000000000|u1|eu|gold|0");
    }

    @Test
    void aSecondKeyColumnRemovesAPair() {
        // JOIN-008. o3 is (u1, us) and x1 is (u1, eu), so the composite keys differ and the pair
        // that JOIN-007 emitted is gone. Two pairs, and the one that disappeared is named.
        assertThat(joinAnswerOf(
                        "SELECT o.order_id, u.tier FROM orders o JOIN users u "
                                + "ON o.user_id = u.user_id AND o.region = u.region",
                        J2,
                        J1,
                        0L))
                .containsExactly("1|gold", "2|silver");
    }

    @Test
    void theEqualityMayBeWrittenInEitherOrder() {
        // JOIN-010. `u.user_id = o.user_id` must mean what `o.user_id = u.user_id` means; a side
        // mix-up here silently joins the wrong columns rather than failing.
        assertThat(joinAnswerOf(
                        "SELECT o.order_id, u.tier FROM orders o JOIN users u ON u.user_id = o.user_id", J2, J1, 0L))
                .containsExactly("1|gold", "2|silver", "3|gold");
    }

    @Test
    void aNullJoinKeyMatchesNothingIncludingAnotherNullKey() {
        // JOIN-020. o4's key is NULL rather than u9. For an inner join that is exactly SQL: the
        // row is neither stored nor probed, so the answer is unchanged at three pairs -- not four,
        // which is what a NULL-as-a-value implementation would produce against a null-keyed user.
        Object[][] nullKeyed = {
            {1L, "u1", "eu", 100L, 1L}, {2L, "u2", "eu", 50L, 2L},
            {3L, "u1", "us", 200L, 3L}, {4L, null, "eu", 7L, 4L}
        };
        assertThat(joinAnswerOf(
                        "SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id",
                        J2,
                        nullKeyed,
                        0L))
                .containsExactly("1|gold", "2|silver", "3|gold");
    }

    @Test
    void duplicateKeysOnTheRightMultiplyThePairs() {
        // JOIN-022(a). J2d adds a second u1 user. o1 × {x1, x1b} = 2, o2 × x2 = 1,
        // o3 × {x1, x1b} = 2, o4 × {} = 0 -- five pairs, not three and not four.
        String[][] withDuplicate = {
            {"u1", "eu", "gold", "0"}, {"u2", "eu", "silver", "0"},
            {"u3", "us", "bronze", "0"}, {"u1", "eu", "platinum", "0"}
        };
        assertThat(joinAnswerOf(
                        "SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id",
                        withDuplicate,
                        J1,
                        0L))
                .containsExactlyInAnyOrder("1|gold", "1|platinum", "2|silver", "3|gold", "3|platinum");
    }

    @Test
    void noMatchingRowsIsZeroPairsRatherThanAnError() {
        // JOIN-015. Every left key is absent from the right side. Zero pairs is also what a join
        // whose right side never arrived looks like, so the fixture keeps both sides populated and
        // only the keys disjoint.
        String[][] disjoint = {{"z1", "eu", "gold", "0"}, {"z2", "eu", "silver", "0"}};
        assertThat(joinAnswerOf(
                        "SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id",
                        disjoint,
                        J1,
                        0L))
                .isEmpty();
    }

    @Test
    void aLeftJoinEmitsItsNullPaddedRowWhenTheWatermarkPassesTheBound() {
        // JOIN-056. o4 (u9) matches nothing. With a stated time bound the join knows when to give
        // up on it, and the null-padded row appears once, at eviction -- not at feed time, which is
        // the assertion in the pair of runs below.
        String sql = "SELECT o.order_id, u.tier FROM orders o LEFT JOIN users u "
                + "ON o.user_id = u.user_id AND o.event_time BETWEEN u.event_time "
                + "AND u.event_time + INTERVAL '5' MINUTE";
        assertThat(joinAnswerOf(sql, J2, J1, 0L))
                .as("before the watermark moves, only the matched pairs exist")
                .containsExactly("1|gold", "2|silver", "3|gold");
        assertThat(joinAnswerOf(sql, J2, J1, 7200 * SECOND))
                .as("and o4 is declared unmatched exactly once")
                .containsExactly("1|gold", "2|silver", "3|gold", "4|NULL");
    }

    @Test
    void aOneSidedTimeBoundClosesAtZeroOnTheUnstatedSideAndSilentlyMatchesNothing() {
        // JOIN-026, and the most surprising answer in this file. `o.event_time >= u.event_time -
        // INTERVAL '30' SECOND` states a lower bound of -30s; the upper falls back to max(0, -30s)
        // = 0. Every J1 order is *after* its user row, so every delta is positive and every pair is
        // outside the window. The query plans, runs, reports success and returns nothing.
        // SqlSupportMatrixTest has this statement as a plan-only Case.ok, which is exactly the
        // shape of green that hides it.
        assertThat(joinAnswerOf(
                        "SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id "
                                + "AND o.event_time >= u.event_time - INTERVAL '30' SECOND",
                        J2,
                        J1,
                        0L))
                .as("a one-sided bound is not the half-open window a reader expects")
                .isEmpty();
    }

    @Test
    void theJoinRefusalsCarryTheirCodesAndSayWhatToDoInstead() {
        // JOIN-054, JOIN-055, JOIN-059. Each of these is a refusal a user can reach by writing
        // ordinary SQL, so each must arrive with a code and an alternative rather than a stack.
        assertThat(joinRefusalOf("SELECT * FROM orders o RIGHT JOIN users u ON o.user_id = u.user_id"))
                .startsWith("PRV-2020")
                .contains("RIGHT join")
                .contains("use LEFT");
        assertThat(joinRefusalOf("SELECT * FROM orders o FULL JOIN users u ON o.user_id = u.user_id"))
                .startsWith("PRV-2020")
                .contains("FULL join");
        assertThat(joinRefusalOf("SELECT * FROM orders o LEFT JOIN users u ON o.user_id = u.user_id"))
                .startsWith("PRV-2020")
                .contains("needs a time bound");
        assertThat(joinRefusalOf("SELECT * FROM orders o CROSS JOIN users u"))
                .startsWith("PRV-2020")
                .contains("neither an equality");
    }

    @Test
    void aSelfJoinPairsEveryMatchingRowOnBothSides() {
        // JOIN-060 recorded this refused when the pipeline was compiled, with no PRV code. It runs:
        // J1 joined to itself on user_id pairs o1 and o3 (u1) four ways, and o2 (u2) and o4 (u9)
        // each with itself.
        assertThat(joinAnswerOf(
                        "SELECT a.order_id, b.order_id FROM orders a JOIN orders b ON a.user_id = b.user_id",
                        new String[0][],
                        J1,
                        0L))
                .containsExactlyInAnyOrder("1|1", "2|2", "3|1", "1|3", "3|3", "4|4");
    }

    // ===========================================================================================
    // Defects. Written as the case says the engine should behave, and disabled, so the suite stays
    // green while the defect stays visible.
    // ===========================================================================================

    @Test
    void windowedCountDistinctOverAStringColumn() {
        // W1 holds u1, u2, u1, u3 -- three distinct users in four rows. W2 holds u2 and ünïcødé.
        assertThat(answerOf("SELECT window_start, COUNT(DISTINCT user_id) AS d, COUNT(*) AS n" + TUMBLE, false, 0L))
                .containsExactly("0|3|4", "10000000000|2|2");
    }

    @Test
    void windowedCountDistinctExcludesNull() {
        // W1's statuses are ok, NULL, ok, flagged. SQL counts distinct non-null values: {ok,
        // flagged} = 2. W2's are ok and ok, so 1.
        assertThat(answerOf("SELECT window_start, COUNT(DISTINCT status) AS d, COUNT(*) AS n" + TUMBLE, false, 0L))
                .containsExactly("0|2|4", "10000000000|1|2");
    }

    @Test
    void keyedCountDistinctOverAnInt64Column() {
        // u1's amounts are {100, -50}, u2's are {250, 7}, u3's is {0}, ünïcødé's is {7}.
        assertThat(answerOf("SELECT user_id, COUNT(DISTINCT amount) AS d FROM txn GROUP BY user_id", true, 0L))
                .containsExactly("u1|2", "u2|2", "u3|1", "ünïcødé|1");
    }

    @Test
    void globalCountDistinctOverABoundedRead() {
        // Four distinct user_ids over six rows; 4 != 6 is the assertion.
        assertThat(answerOf("SELECT COUNT(DISTINCT user_id) AS d, COUNT(*) AS n FROM txn", true, 0L))
                .containsExactly("4|6");
    }

    // ===========================================================================================
    // Harness.
    // ===========================================================================================

    private static void assertEvery(List<Case> cases, java.util.function.Function<String, List<String>> run) {
        List<String> wrong = new ArrayList<>();
        for (Case testCase : cases) {
            List<String> actual;
            try {
                actual = new ArrayList<>(run.apply(testCase.sql()));
            } catch (RuntimeException e) {
                wrong.add(testCase.id() + ": threw " + e.getMessage());
                continue;
            }
            java.util.Collections.sort(actual);
            List<String> expected = new ArrayList<>(testCase.answer());
            java.util.Collections.sort(expected);
            if (!actual.equals(expected)) {
                wrong.add(testCase.id() + ":%n    expected %s%n    produced %s".formatted(expected, actual));
            }
        }
        assertThat(String.join(System.lineSeparator(), wrong))
                .as("an aggregate that returns the wrong number is invisible: the status is green, "
                        + "the row count is plausible, and only the value is wrong")
                .isEmpty();
    }

    /** Feeds D1 through a real pipeline and renders each output row as its values joined by '|'. */
    private static List<String> answerOf(String sql, boolean bounded, long watermarkNanos) {
        PhysicalPlanBuilder builder = new PhysicalPlanBuilder();
        if (bounded) {
            builder = builder.overBoundedInput();
        }
        PhysicalOperator plan = builder.build(SqlPlanner.withStreams(txn()).plan(sql));
        List<CapturingRowWriter.Captured> captured = new ArrayList<>();
        RowLayout layout = RowLayout.of(txn());

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), captured::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (int i = 0; i < D1.length; i++) {
                Object[] r = D1[i];
                long handle = feed.allocate(layout.rowSize(256));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, (Long) r[0])
                        .setString(1, (String) r[1])
                        .setLong(2, (Long) r[2])
                        .setDouble(3, (Double) r[3]);
                if (r[4] == null) {
                    writer.setNull(4);
                } else {
                    writer.setString(4, (String) r[4]);
                }
                writer.setBoolean(5, (Boolean) r[5])
                        .setLong(6, (Long) r[6])
                        .weight(1L)
                        .eventTimestampNanos((Long) r[6])
                        .sequence(i + 1L)
                        .commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            if (watermarkNanos > 0) {
                pipeline.advanceWatermark(watermarkNanos);
            }
            pipeline.finish();
        }
        return captured.stream().map(SqlAnswerTest::render).toList();
    }

    /**
     * Feeds the right side, then the left, and renders the pairs.
     *
     * <p>Rows enter by stream name, which is the whole reason a self-join cannot work: one name
     * cannot say which side of the join a row belongs to.
     */
    private static List<String> joinAnswerOf(String sql, String[][] right, Object[][] left, long watermarkNanos) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(orders(), users()).plan(sql));
        List<CapturingRowWriter.Captured> captured = new ArrayList<>();
        RowLayout leftLayout = RowLayout.of(orders());
        RowLayout rightLayout = RowLayout.of(users());

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), captured::add))) {
            BinaryRowWriter rightWriter = new BinaryRowWriter(rightLayout);
            BinaryRowView rightView = new BinaryRowView(rightLayout);
            long sequence = 0;
            for (String[] u : right) {
                long eventTime = Long.parseLong(u[3]) * SECOND;
                long handle = feed.allocate(rightLayout.rowSize(256));
                rightWriter.begin(feed.regionOf(handle), feed.offsetOf(handle));
                rightWriter
                        .setString(0, u[0])
                        .setString(1, u[1])
                        .setString(2, u[2])
                        .setLong(3, eventTime)
                        .weight(1L)
                        .eventTimestampNanos(eventTime)
                        .sequence(++sequence)
                        .commit();
                feed.trimTo(handle, rightWriter.sizeSoFar());
                pipeline.accept("users", rightView.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            BinaryRowWriter leftWriter = new BinaryRowWriter(leftLayout);
            BinaryRowView leftView = new BinaryRowView(leftLayout);
            for (Object[] o : left) {
                long eventTime = (Long) o[4] * SECOND;
                long handle = feed.allocate(leftLayout.rowSize(256));
                leftWriter.begin(feed.regionOf(handle), feed.offsetOf(handle));
                leftWriter.setLong(0, (Long) o[0]);
                if (o[1] == null) {
                    leftWriter.setNull(1);
                } else {
                    leftWriter.setString(1, (String) o[1]);
                }
                leftWriter
                        .setString(2, (String) o[2])
                        .setLong(3, (Long) o[3])
                        .setLong(4, eventTime)
                        .weight(1L)
                        .eventTimestampNanos(eventTime)
                        .sequence(++sequence)
                        .commit();
                feed.trimTo(handle, leftWriter.sizeSoFar());
                pipeline.accept("orders", leftView.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            if (watermarkNanos > 0) {
                pipeline.advanceWatermark(watermarkNanos);
            }
            pipeline.finish();
        }
        return captured.stream().map(SqlAnswerTest::render).toList();
    }

    /** The refusal message for a two-stream statement, or null if it planned and compiled. */
    private static @Nullable String joinRefusalOf(String sql) {
        try {
            PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(orders(), users()).plan(sql));
            try (InterpretedPipeline _ = InterpretedPipeline.compile(plan, () -> {
                throw new UnsupportedOperationException("no row is fed while a refusal is being checked");
            })) {
                return null;
            }
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).replace('\n', ' ');
        }
    }

    private static String render(CapturingRowWriter.Captured row) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < row.values().length; i++) {
            if (i > 0) {
                text.append('|');
            }
            text.append(row.isNull(i) ? "NULL" : String.valueOf(row.values()[i]));
        }
        return text.toString();
    }
}
