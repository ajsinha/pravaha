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
import java.util.List;
import java.util.Map;

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
 * ADR-039 item 6, closed: pushing a partial aggregate into the source must not change the answer,
 * the same property {@code SourcePushdownEquivalenceTest} proves for filters and projections --
 * except that here, unlike those two, the property is not free. A filter or a projected column the
 * engine keeps its own copy of regardless of what a source does; a partial aggregate replaces the
 * rows entirely, so if the source computed it wrong there is nothing downstream left to notice.
 * That is exactly the trade {@code docs/guides/CONNECTORS.md} section 6 names: the capability is the
 * contract, and a source that claims {@code PARTIAL_AGGREGATE} must get it right. This test plays
 * the honest source -- one that computes its partial correctly over what it decided to include --
 * and proves the engine folds it in exactly as it would have folded in the rows.
 *
 * <p>Two sources play it. {@link #PUSHES_PARTIAL_AGGREGATE} and the hand-built partial rows are this
 * test's own, the same role {@code PushdownEquivalenceTest}'s inline filter-honouring loop plays for
 * {@code FILTER}. And since the JDBC plugin now declares {@code PARTIAL_AGGREGATE}, {@link
 * #theRealJdbcSourcesPartialsGiveTheAnswerItsRowsDo} runs the shipped plugin against H2 through a
 * real execution -- pushed and unpushed in lockstep over the same table, through inserts, updates
 * the poll sees again, and deletes it cannot see -- which is the end-to-end claim.
 */
class PartialAggregatePushdownEquivalenceTest {

    /**
     * FILTER as well: this query has a WHERE clause, and a partial replaces the rows the engine's own
     * filter would have run against, so a source that could not apply the filter must not be asked
     * for a partial at all (SourcePushdown#filtersAllPushable).
     */
    private static final SourceCapabilities PUSHES_PARTIAL_AGGREGATE = new SourceCapabilities(
            true,
            true,
            false,
            false,
            DeliveryGuarantee.AT_LEAST_ONCE,
            EnumSet.of(PushdownKind.FILTER, PushdownKind.PARTIAL_AGGREGATE),
            null);

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("status", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    private record Row(long id, String status, long amount) {}

    private static List<Row> data(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Row(i, i % 3 == 0 ? "DONE" : "PENDING", (long) (i * 7 % 200)));
        }
        return rows;
    }

    private static final String SQL = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE status = 'DONE'";

    private static PhysicalOperator plan() {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));
    }

    @Property(tries = 50)
    void aSourceThatComputesItsPartialCorrectlyProducesTheSameAnswer(
            @ForAll @IntRange(min = 1, max = 60) int rowCount, @ForAll @IntRange(min = 1, max = 5) int batches) {
        List<Row> rows = data(rowCount);

        assertThat(runPushedDown(rows, batches))
                .as(
                        "pushing the partial aggregate for %d rows split into %d batches changed the answer",
                        rowCount, batches)
                .isEqualTo(runOrdinary(rows));
    }

    @Test
    void requestForBuildsExactlyOnePartialAggregateNamingCountAndSum() {
        ReadRequest request = SourcePushdown.requestFor(plan(), "txn", PUSHES_PARTIAL_AGGREGATE);

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
    void aSourceThatCannotApplyTheFilterIsNeverAskedForAPartial() {
        SourceCapabilities partialOnly = new SourceCapabilities(
                true,
                true,
                false,
                false,
                DeliveryGuarantee.AT_LEAST_ONCE,
                EnumSet.of(PushdownKind.PARTIAL_AGGREGATE),
                null);
        // The partial would be computed over every row and the WHERE clause would be applied by
        // nobody: no rows reach the engine's filter.
        assertThat(SourcePushdown.requestFor(plan(), "txn", partialOnly).aggregates())
                .isEmpty();
    }

    @Test
    void aPredicateThatCannotBePushedMeansNoPartial() {
        PhysicalOperator withLike = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(schema())
                        .plan("SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE status LIKE 'D%'"));
        PhysicalOperator withOr = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(schema())
                        .plan("SELECT COUNT(*) AS n FROM txn WHERE status = 'DONE' OR amount > 5"));
        // Either would be pushed as rows with the engine filtering after; as a partial, the part
        // that could not be pushed would never be applied.
        assertThat(SourcePushdown.requestFor(withLike, "txn", PUSHES_PARTIAL_AGGREGATE)
                        .aggregates())
                .isEmpty();
        assertThat(SourcePushdown.requestFor(withOr, "txn", PUSHES_PARTIAL_AGGREGATE)
                        .aggregates())
                .isEmpty();
    }

    // ------------------------------------------------------------------ the shipped JDBC plugin

    /** Shapes the JDBC plugin is asked to pre-combine. The last groups by text, which it declines
     * unless the deployment says the collation is binary. */
    private static final List<String> JDBC_QUERIES = List.of(
            "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE region = 1",
            "SELECT region, COUNT(*) AS n, SUM(amount) AS total, COUNT(amount) AS present FROM txn "
                    + "WHERE amount > 10 GROUP BY region",
            "SELECT region, SUM(amount) AS total FROM txn GROUP BY region",
            "SELECT status, COUNT(*) AS n FROM txn WHERE amount >= 50 GROUP BY status");

    @Property(tries = 24)
    void theRealJdbcSourcesPartialsGiveTheAnswerItsRowsDo(
            @ForAll @IntRange(min = 0, max = 3) int query,
            @ForAll @IntRange(min = 0, max = 10_000) int seed,
            @ForAll boolean binaryCollation)
            throws Exception {
        String sql = JDBC_QUERIES.get(query);
        try (JdbcPushdownHarness h2 =
                new JdbcPushdownHarness(Map.of("collation.binary", String.valueOf(binaryCollation)))) {
            h2.insert(1, 60, seed);
            PhysicalOperator plan = h2.plan(sql);
            try (JdbcPushdownHarness.Run pushed = h2.run(plan, true);
                    JdbcPushdownHarness.Run plain = h2.run(plan, false)) {
                assertThat(pushed.request.aggregates())
                        .as("[%s] is the shape a partial is asked for", sql)
                        .hasSize(1);
                boolean partials = query != 3 || binaryCollation;
                assertThat(pushed.reader.deliversPartialAggregate())
                        .as("[%s] with collation.binary=%s", sql, binaryCollation)
                        .isEqualTo(partials);

                pushed.drain();
                plain.drain();

                // New rows, updates the poll sees again as new rows, and deletes it cannot see.
                h2.insert(60, 110, seed + 1L);
                for (long id = 3; id < 60; id += 11) {
                    h2.update(id, (id * 31 + seed) % 300);
                }
                h2.delete(2);
                h2.delete(40);
                pushed.drain();
                plain.drain();

                if (partials) {
                    assertThat(pushed.pump.rowsPumped())
                            .as("a partial per group per page must cross into the lane, not a row per row")
                            .isLessThan(plain.pump.rowsPumped());
                }
                assertThat(pushed.finish())
                        .as("pushing [%s] into the database changed the answer (seed %d)", sql, seed)
                        .isEqualTo(plain.finish());
            }
        }
    }

    /**
     * The deployment path: a registered query, a {@code jdbc} binding, {@code PluginSourceFeeds}
     * choosing what to push. An unwindowed GROUP BY cannot be registered (PRV-2050), so the
     * aggregate a continuous query can pre-combine at the source is the global one -- asked here
     * twice, with partials and with rows, of the same table.
     */
    @Test
    void aRegisteredQueryOverTheJdbcSourceIsFedPartialsAndAnswersAsItsRowsWould() throws Exception {
        String sql = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE region = 1";
        try (JdbcPushdownHarness h2 = new JdbcPushdownHarness(Map.of())) {
            h2.insert(1, 200, 7);
            java.util.Map<Boolean, Long> fed = new java.util.HashMap<>();
            for (boolean partials : List.of(true, false)) {
                com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds feeds =
                        new com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds()
                                .bind(new com.ash.messaging.pravaha.bindings.ingest.SourceBinding(
                                        "txn",
                                        "jdbc",
                                        // This table is only ever inserted into, so its updated_at is
                                        // set once and no poll reads a row twice; without saying so the
                                        // aggregate is refused (PRV-2042, SCAN-1).
                                        h2.binding(Map.of(
                                                "pushdown.partial.aggregate",
                                                String.valueOf(partials),
                                                "watermark.moves.on.update",
                                                "false"))));
                com.ash.messaging.pravaha.serving.ViewCatalog views =
                        new com.ash.messaging.pravaha.serving.ViewCatalog();
                try (com.ash.messaging.pravaha.registry.QueryRegistry registry =
                        new com.ash.messaging.pravaha.registry.QueryRegistry(views, h2.schema())
                                .feedingFrom(feeds)
                                .generatingWatermarks(
                                        java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(50))) {
                    com.ash.messaging.pravaha.registry.RegisteredQuery query = registry.register(
                            "region_one", sql, List.of(0), com.ash.messaging.pravaha.security.Principal.ANONYMOUS);
                    assertThat(query.feed().describe().contains("partial aggregate"))
                            .as(
                                    "describe() says whether the source pre-combines: %s",
                                    query.feed().describe())
                            .isEqualTo(partials);

                    awaitAnswer(query, h2.ask("SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM txn WHERE region = 1"));
                    // Rows written after registration arrive as later partials, not a restart.
                    h2.insert(200, 260, 8);
                    awaitAnswer(query, h2.ask("SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM txn WHERE region = 1"));
                    fed.put(partials, query.rowsIn());
                }
            }
            assertThat(fed.get(true))
                    .as("partials cross into the lane, one per page, where rows crossed one per row")
                    .isLessThan(fed.get(false));
        }
    }

    private static void awaitAnswer(com.ash.messaging.pravaha.registry.RegisteredQuery query, long[] expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        List<Object> want = List.of(expected[0], expected[1]);
        List<Object> have = List.of();
        while (System.nanoTime() < deadline) {
            query.commit();
            List<Object[]> rows = query.view().scan();
            if (rows.size() == 1) {
                have = List.of(rows.get(0)[0], rows.get(0)[1]);
                if (have.equals(want)) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        assertThat(have).as("the view's (n, total)").isEqualTo(want);
    }

    @Test
    void aPipelineWithThisPlanAcceptsAPartialForTxn() {
        try (InterpretedPipeline pipeline = InterpretedPipeline.compile(plan(), noopSink())) {
            assertThat(pipeline.acceptsPartialAggregateFor("txn")).isTrue();
            assertThat(pipeline.acceptsPartialAggregateFor("nonexistent")).isFalse();
        }
    }

    /**
     * The retraction {@code docs/design/adr/039}'s own restriction to {@code COUNT}/{@code SUM} exists to
     * preserve: a whole partial withdrawn must undo exactly what it added, with no separate code
     * path, the same property the row-at-a-time accumulator already has.
     */
    @Test
    void retractingAWholePartialUndoesExactlyWhatItAdded() {
        StreamSchema partialSchema = plan().outputSchema();
        RowLayout partialLayout = RowLayout.of(partialSchema);
        List<Long> counts = new ArrayList<>();
        List<Long> sums = new ArrayList<>();

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan(), (RowOutput)
                        () -> new CapturingRowWriter(partialSchema, captured -> {
                            counts.add(captured.asLong(0));
                            sums.add(captured.asLong(1));
                        }))) {
            feedPartial(arena, partialLayout, pipeline, 10, 500, 1L);
            pipeline.finish();
            assertThat(counts.get(counts.size() - 1)).isEqualTo(10);
            assertThat(sums.get(sums.size() - 1)).isEqualTo(500);

            feedPartial(arena, partialLayout, pipeline, 10, 500, -1L);
            pipeline.finish();
            assertThat(counts.get(counts.size() - 1))
                    .as("the only partial ever added was just retracted in full")
                    .isEqualTo(0);
            assertThat(sums.get(sums.size() - 1)).isEqualTo(0);
        }
    }

    private static RowOutput noopSink() {
        StreamSchema outputSchema = plan().outputSchema();
        return () -> new CapturingRowWriter(outputSchema, captured -> {});
    }

    /** Feeds every row through the ordinary path: the engine's own filter and its own row-at-a-time
     * accumulator, ending with {@code finish()} so the bounded aggregate emits. */
    private static List<Long> runOrdinary(List<Row> rows) {
        PhysicalOperator plan = plan();
        StreamSchema schema = schema();
        RowLayout layout = RowLayout.of(schema);
        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (Row row : rows) {
                long handle = arena.allocate(layout.rowSize(128));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setLong(0, row.id()).setString(1, row.status()).setLong(2, row.amount());
                writer.weight(1L)
                        .eventTimestampNanos(row.id())
                        .sequence(row.id())
                        .commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return finalAnswer(results);
    }

    /** Feeds no rows at all: only {@code batches} partials, each computed correctly over its own
     * slice of {@code rows} -- the honest source this test plays. */
    private static List<Long> runPushedDown(List<Row> rows, int batches) {
        PhysicalOperator plan = plan();
        StreamSchema partialSchema = plan.outputSchema();
        RowLayout partialLayout = RowLayout.of(partialSchema);
        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(partialSchema, results::add))) {
            assertThat(pipeline.acceptsPartialAggregateFor("txn")).isTrue();
            int batchSize = Math.max(1, (rows.size() + batches - 1) / batches);
            for (int start = 0; start < rows.size(); start += batchSize) {
                List<Row> batch = rows.subList(start, Math.min(rows.size(), start + batchSize));
                long count = 0;
                long sum = 0;
                for (Row row : batch) {
                    if ("DONE".equals(row.status())) {
                        count++;
                        sum += row.amount();
                    }
                }
                feedPartial(arena, partialLayout, pipeline, count, sum, 1L);
            }
            pipeline.finish();
        }
        return finalAnswer(results);
    }

    private static void feedPartial(
            RowArena arena, RowLayout partialLayout, InterpretedPipeline pipeline, long count, long sum, long weight) {
        BinaryRowWriter writer = new BinaryRowWriter(partialLayout);
        BinaryRowView view = new BinaryRowView(partialLayout);
        long handle = arena.allocate(partialLayout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, count).setLong(1, sum);
        writer.weight(weight).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pipeline.acceptPartialAggregate("txn", view.wrap(arena.regionOf(handle), arena.offsetOf(handle)), weight);
    }

    /** The last emitted (count, sum): a bounded global aggregate emits once, at {@code finish()}. */
    private static List<Long> finalAnswer(List<CapturingRowWriter.Captured> results) {
        if (results.isEmpty()) {
            return List.of(0L, 0L);
        }
        CapturingRowWriter.Captured last = results.get(results.size() - 1);
        return List.of(last.asLong(0), last.asLong(1));
    }
}
