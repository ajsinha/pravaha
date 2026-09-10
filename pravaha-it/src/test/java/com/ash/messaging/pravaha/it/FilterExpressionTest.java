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
 * WHERE clauses that are more than a column against a literal.
 *
 * <p>Two separate things are being proved here, and they arrived together because looking hard at
 * the first exposed the second.
 *
 * <p>The first is arithmetic: {@code WHERE amount * 2 > 100} used to be refused at registration.
 * The refusal was honest but the hole was real, and the expression tree built for projections
 * closes it.
 *
 * <p>The second is negation over nulls, which was <em>wrong</em>, not merely missing. A comparison
 * against NULL is UNKNOWN in SQL, and Pravaha's predicates collapse UNKNOWN to false because a
 * WHERE clause drops the row either way. That collapse is safe until something inverts it: {@code
 * NOT (bonus > 1)} over a null bonus asked "is bonus > 1 false?", got yes, and kept a row that SQL
 * says must be dropped. Calcite does not push the negation down for us -- the plan really does
 * arrive as {@code NOT(bonus > 1)} -- so Pravaha pushes it down itself, at compile time, where it
 * costs nothing per row.
 */
class FilterExpressionTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .field("rate", Types.float64())
                .field("bonus", Types.int64().withNullable(true))
                .field("flagged", Types.bool().withNullable(true))
                .build();
    }

    /** id, amount, rate, and the two nullable columns. */
    private record Row(long id, long amount, double rate, Long bonus, Boolean flagged) {}

    private static Row row(long id, long amount) {
        return new Row(id, amount, 0d, null, null);
    }

    private static Row bonus(long id, Long bonus) {
        return new Row(id, 0, 0d, bonus, null);
    }

    @Test
    void arithmeticInAWhereClauseFiltersOnTheComputedValue() {
        assertThat(idsFrom("SELECT id FROM txn WHERE amount * 2 > 100", List.of(row(1, 60), row(2, 40), row(3, 50))))
                .containsExactly(1L);
    }

    @Test
    void oneColumnComparedAgainstAnother() {
        assertThat(idsFrom(
                        "SELECT id FROM txn WHERE amount > id",
                        List.of(row(1, 5), row(2, 1), new Row(3, 3, 0d, null, null))))
                .containsExactly(1L);
    }

    @Test
    void integerAndFloatingPointMixInAComparison() {
        assertThat(idsFrom(
                        "SELECT id FROM txn WHERE rate * 2 > amount",
                        List.of(new Row(1, 3, 2.0, null, null), new Row(2, 5, 2.0, null, null))))
                .containsExactly(1L);
    }

    @Test
    void aComputedComparisonOverNullDropsTheRow() {
        List<Row> rows = List.of(bonus(1, null), bonus(2, 5L), bonus(3, 0L));

        assertThat(idsFrom("SELECT id FROM txn WHERE bonus * 2 > 1", rows)).containsExactly(2L);

        // The comparison has to be the other way round as well. A null column reads as zero
        // underneath, so `> 1` drops the null row whether or not anything checks for null -- and a
        // missing null check only shows up when zero would have passed.
        assertThat(idsFrom("SELECT id FROM txn WHERE bonus * 2 < 1", rows)).containsExactly(3L);
    }

    @Test
    void anExplicitNarrowingCastTruncatesRatherThanRounds() {
        // SQL's CAST to an integer type truncates towards zero. Rounding would make 1.9 pass a
        // `> 1` filter, and the two only differ on values nobody puts in a test by accident.
        List<Row> rows = List.of(new Row(1, 0, 1.9, null, null), new Row(2, 0, 2.5, null, null));

        assertThat(idsFrom("SELECT id FROM txn WHERE CAST(rate AS BIGINT) > 1", rows))
                .containsExactly(2L);
    }

    @Test
    void negatingAComparisonOverNullStillDropsTheRow() {
        // The bug this test was written for: NOT(UNKNOWN) is UNKNOWN, not TRUE. Row 1 must not
        // appear, and inverting a filter is exactly the kind of wrong that looks plausible.
        assertThat(idsFrom(
                        "SELECT id FROM txn WHERE NOT (bonus > 1)",
                        // Row 4 sits on the boundary: NOT (1 > 1) is TRUE, so a negation that
                        // becomes < instead of <= loses it and no other row would notice.
                        List.of(bonus(1, null), bonus(2, 5L), bonus(3, 0L), bonus(4, 1L))))
                .containsExactly(3L, 4L);
    }

    @Test
    void negationIsPushedThroughAndOrAndNestedNots() {
        // Two independent columns on purpose. Written over one column -- NOT (bonus > 1 AND bonus >
        // 3) -- Calcite simplifies the conjunction away before Pravaha sees it, and the test passes
        // whether or not De Morgan is applied correctly. That version was written first and caught
        // nothing.
        List<Row> rows = List.of(bonus(1, null), bonus(2, 0L), bonus(3, 5L), bonus(4, 1L));

        // NOT (A AND B) is TRUE where either half is FALSE. Row 2 has bonus > 1 false and id < 3
        // true, so it survives here and would be lost if the conjunction were negated into another
        // conjunction. Row 1 is UNKNOWN AND TRUE, which is UNKNOWN, so it is dropped.
        assertThat(idsFrom("SELECT id FROM txn WHERE NOT (bonus > 1 AND id < 3)", rows))
                .containsExactly(2L, 3L, 4L);

        // NOT (A OR B) needs both halves FALSE.
        assertThat(idsFrom("SELECT id FROM txn WHERE NOT (bonus > 1 OR id < 3)", rows))
                .containsExactly(4L);

        assertThat(idsFrom("SELECT id FROM txn WHERE NOT (NOT (bonus > 1))", rows))
                .containsExactly(3L);
    }

    @Test
    void negatingANullCheckIsTheOtherNullCheck() {
        List<Row> rows = List.of(bonus(1, null), bonus(2, 5L));

        assertThat(idsFrom("SELECT id FROM txn WHERE NOT (bonus IS NULL)", rows))
                .containsExactly(2L);
        assertThat(idsFrom("SELECT id FROM txn WHERE NOT (bonus IS NOT NULL)", rows))
                .containsExactly(1L);
    }

    @Test
    void negatingANullableBooleanColumnDropsTheNullRow() {
        List<Row> rows =
                List.of(new Row(1, 0, 0d, null, null), new Row(2, 0, 0d, null, true), new Row(3, 0, 0d, null, false));

        assertThat(idsFrom("SELECT id FROM txn WHERE NOT flagged", rows)).containsExactly(3L);
    }

    @Test
    void textInsideALargerComparisonIsRefusedByName() {
        StreamSchema texts = StreamSchema.builder("txn")
                .field("a", Types.string())
                .field("b", Types.string())
                .build();

        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(texts).plan("SELECT a FROM txn WHERE a > b")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("text");
    }

    /** Plans, runs, and returns the surviving ids in order. */
    private static List<Long> idsFrom(String sql, List<Row> rows) {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(sql));
        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        RowLayout layout = RowLayout.of(schema());

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (Row row : rows) {
                long handle = feed.allocate(layout.rowSize(128));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, row.id()).setLong(1, row.amount()).setDouble(2, row.rate());
                if (row.bonus() == null) {
                    writer.setNull(3);
                } else {
                    writer.setLong(3, row.bonus());
                }
                if (row.flagged() == null) {
                    writer.setNull(4);
                } else {
                    writer.setBoolean(4, row.flagged());
                }
                writer.weight(1L)
                        .eventTimestampNanos(row.id())
                        .sequence(row.id())
                        .commit();
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return results.stream().map(captured -> captured.asLong(0)).toList();
    }
}
