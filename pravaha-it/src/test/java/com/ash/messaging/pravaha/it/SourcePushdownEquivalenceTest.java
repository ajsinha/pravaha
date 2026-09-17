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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
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
import com.ash.messaging.pravaha.sql.plan.SourcePushdown;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pushing a projection into the source must not change the answer, exactly as {@code
 * PushdownEquivalenceTest} proves for filters -- see that class for the property this one repeats
 * against a different kind of request.
 *
 * <p>The dangerous direction here is dropping a column the engine still reads. A source cannot be
 * trusted to notice it received too little, so the test simulates the worst well-behaved source:
 * one that returns every column {@link ReadRequest#columns()} named correctly and puts a plausibly
 * wrong value -- never the true one -- into every column it was not asked for. If {@link
 * SourcePushdown} ever asks for too little, the wrong value leaks into the answer and the
 * equivalence fails; a source that returns too much is already proven harmless by construction,
 * since the engine only ever reads a column it asked about.
 *
 * <p>Partial-aggregate pushdown is exercised separately, and only at the level of which aggregates
 * {@link SourcePushdown} decides are safe to describe -- see {@code
 * aPartialAggregateIsOfferedOnlyForCountAndSum} below and the report accompanying this change for
 * why no end-to-end equivalence test accompanies it: the engine has nowhere yet to feed a pre-combined
 * partial back into its own incremental accumulator, so there is no consumer to prove equivalent
 * against.
 */
class SourcePushdownEquivalenceTest {

    private static final SourceCapabilities PUSHES_PROJECTION = new SourceCapabilities(
            true, true, false, false, DeliveryGuarantee.AT_LEAST_ONCE, EnumSet.of(PushdownKind.PROJECT), null);

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .field("status", Types.string())
                .field("note", Types.string().withNullable(true))
                .build();
    }

    private record Row(long id, long amount, String status, String note) {}

    private static List<Row> data(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Row(i, (long) i * 7 % 200, i % 3 == 0 ? "DONE" : "PENDING", i % 5 == 0 ? null : "note-" + i));
        }
        return rows;
    }

    /** Query shapes chosen to cover: a plain projection, one behind a filter, and one needing
     * every column -- the case where nothing should be pushed at all. */
    private static final List<String> QUERIES = List.of(
            "SELECT id FROM txn",
            "SELECT amount FROM txn WHERE status = 'DONE'",
            "SELECT status, note FROM txn WHERE amount > 50",
            "SELECT id, amount, status, note FROM txn",
            "SELECT amount FROM txn WHERE amount > 10 AND status = 'DONE'",
            "SELECT note FROM txn WHERE note IS NOT NULL",
            "SELECT id, status FROM txn WHERE id <> 3");

    @Property(tries = 50)
    void aSourceThatReturnsOnlyThePushedColumnsAndGarbageElsewhereProducesTheSameAnswer(
            @ForAll @IntRange(min = 0, max = 6) int query, @ForAll @IntRange(min = 1, max = 60) int rowCount) {
        String sql = QUERIES.get(query);
        List<Row> rows = data(rowCount);

        assertThat(run(sql, rows, true))
                .as("pushing the projection for [%s] changed the answer", sql)
                .isEqualTo(run(sql, rows, false));
    }

    @Test
    void aQueryNeedingEveryColumnPushesNone() {
        assertThat(columnsFor("SELECT id, amount, status, note FROM txn")).isEmpty();
    }

    @Test
    void aQueryNeedingOneColumnPushesExactlyThatOne() {
        assertThat(columnsFor("SELECT id FROM txn")).containsExactlyInAnyOrder("id");
    }

    @Test
    void aWhereClauseColumnIsNeededEvenWhenNotSelected() {
        // status is not in the SELECT list, but the source must still be told to send it -- the
        // engine's own filter reads it regardless of what was pushed.
        assertThat(columnsFor("SELECT id FROM txn WHERE status = 'DONE'")).containsExactlyInAnyOrder("id", "status");
    }

    @Test
    void anAggregateIsNotWalkedForProjection() {
        // The whole point of an aggregate is to read every row; asking the source to narrow columns
        // here would need this class to know which columns the aggregate itself reads, which it
        // does for a global aggregate but chooses not to combine with column pushdown in this pass.
        assertThat(columnsFor("SELECT COUNT(*), SUM(amount) FROM txn WHERE status = 'DONE'"))
                .isEmpty();
    }

    @Test
    void aSourceThatHasNotDeclaredProjectPushdownIsSentNoColumns() {
        SourceCapabilities silent = new SourceCapabilities(
                true, true, false, false, DeliveryGuarantee.AT_LEAST_ONCE, EnumSet.noneOf(PushdownKind.class), null);
        assertThat(SourcePushdown.requestFor(plan("SELECT id FROM txn"), "txn", silent)
                        .columns())
                .isEmpty();
    }

    // ------------------------------------------------------------------ partial-aggregate decision

    private static final SourceCapabilities PUSHES_AGGREGATE = new SourceCapabilities(
            true,
            true,
            false,
            false,
            DeliveryGuarantee.AT_LEAST_ONCE,
            EnumSet.of(PushdownKind.PARTIAL_AGGREGATE),
            null);

    @Test
    void aGlobalCountAndSumArePushedAsAPartialAggregate() {
        ReadRequest request = SourcePushdown.requestFor(
                plan("SELECT COUNT(*), SUM(amount) FROM txn WHERE status = 'DONE'"), "txn", PUSHES_AGGREGATE);

        assertThat(request.aggregates()).hasSize(1);
        ReadRequest.PartialAggregate partial = request.aggregates().get(0);
        assertThat(partial.groupByColumns()).isEmpty();
        assertThat(partial.aggregates())
                .extracting(
                        ReadRequest.PartialAggregate.AggregateCall::kind,
                        ReadRequest.PartialAggregate.AggregateCall::column)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ReadRequest.PartialAggregate.Kind.COUNT, null),
                        org.assertj.core.groups.Tuple.tuple(ReadRequest.PartialAggregate.Kind.SUM, "amount"));
    }

    @Test
    void aMinOrMaxIsNeverPushedBecauseItCannotBeRetracted() {
        // PRV-3020's own reason: MIN and MAX compose forwards but a retraction needs the ordered
        // multiset the aggregate lift keeps, which a pre-combined partial has already thrown away.
        assertThat(SourcePushdown.requestFor(plan("SELECT MIN(amount) FROM txn"), "txn", PUSHES_AGGREGATE)
                        .aggregates())
                .isEmpty();
        assertThat(SourcePushdown.requestFor(plan("SELECT MAX(amount) FROM txn"), "txn", PUSHES_AGGREGATE)
                        .aggregates())
                .isEmpty();
    }

    @Test
    void aMixOfSafeAndUnsafeAggregatesPushesNeitherRatherThanHalf() {
        // SUM alone would be safe, but this aggregate node also computes a MAX, and
        // AggregateOperator#isFullyLinear is a property of the whole node, not of one call --
        // pushing only the SUM half would still leave the source deciding whether to compute the
        // MAX itself, which is a claim this analysis never asks it to make.
        assertThat(SourcePushdown.requestFor(plan("SELECT SUM(amount), MAX(amount) FROM txn"), "txn", PUSHES_AGGREGATE)
                        .aggregates())
                .isEmpty();
    }

    @Test
    void aSourceThatHasNotDeclaredPartialAggregatePushdownIsSentNone() {
        SourceCapabilities silent = new SourceCapabilities(
                true, true, false, false, DeliveryGuarantee.AT_LEAST_ONCE, EnumSet.noneOf(PushdownKind.class), null);
        assertThat(SourcePushdown.requestFor(plan("SELECT COUNT(*) FROM txn"), "txn", silent)
                        .aggregates())
                .isEmpty();
    }

    // ------------------------------------------------------------------ harness

    private static List<String> columnsFor(String sql) {
        return SourcePushdown.requestFor(plan(sql), "txn", PUSHES_PROJECTION).columns();
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(sql));
    }

    /** Runs the query, optionally starving every column the request did not ask for. */
    private static List<String> run(String sql, List<Row> rows, boolean pushDown) {
        PhysicalOperator plan = plan(sql);
        ReadRequest request =
                pushDown ? SourcePushdown.requestFor(plan, "txn", PUSHES_PROJECTION) : ReadRequest.NOTHING;
        Set<String> pushedColumns = new HashSet<>(request.columns());
        boolean pruning = !pushedColumns.isEmpty();

        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        RowLayout layout = RowLayout.of(schema());

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (Row row : rows) {
                long handle = feed.allocate(layout.rowSize(256));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, keepOrPoison(pruning, pushedColumns, "id", row.id()));
                writer.setLong(1, keepOrPoison(pruning, pushedColumns, "amount", row.amount()));
                writer.setString(2, pruning && !pushedColumns.contains("status") ? "WRONG" : row.status());
                if (pruning && !pushedColumns.contains("note")) {
                    writer.setString(3, "WRONG");
                } else if (row.note() == null) {
                    writer.setNull(3);
                } else {
                    writer.setString(3, row.note());
                }
                writer.weight(1L)
                        .eventTimestampNanos(row.id())
                        .sequence(row.id())
                        .commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return results.stream()
                .map(r -> java.util.Arrays.deepToString(r.values()))
                .toList();
    }

    /** A poisoned-but-plausible value for a column the source was not asked for -- never the true one. */
    private static long keepOrPoison(boolean pruning, Set<String> pushedColumns, String column, long trueValue) {
        return pruning && !pushedColumns.contains(column) ? trueValue + 999_000L : trueValue;
    }
}
