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

import java.util.List;

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
import com.ash.messaging.pravaha.serving.Consistency;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewResult;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A query whose answer is read rather than shipped.
 *
 * <p>The claim design section 17 makes is that there is no second system in the call: the aggregate
 * a query maintains already is a keyed table, so a point lookup is a hash probe against it rather
 * than a write to Redis and a read back. This test is that claim end to end -- SQL in, a key
 * lookup out, nothing in between.
 */
class ServedQueryTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .build();
    }

    private record Txn(String user, long amount, long eventTime) {}

    private static final long SECOND = 1_000_000_000L;

    private static final String SQL = "SELECT user_id, SUM(amount) AS total "
            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY user_id, window_start, window_end";

    @Test
    void aWindowedAggregateIsReadableByKeyAsSoonAsItCommits() {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));

        // The view is keyed on user_id, which is the query's own grouping key -- the point being
        // that no separate index had to be built for it.
        ServedView view = new ServedView("user_volume", plan.outputSchema(), List.of(0), 10_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());

        List<Txn> input = List.of(
                new Txn("u1", 100, 1 * SECOND),
                new Txn("u2", 50, 2 * SECOND),
                new Txn("u1", 200, 3 * SECOND),
                // Past the window, so the first window fires.
                new Txn("u3", 7, 30 * SECOND));

        RowLayout layout = RowLayout.of(schema());
        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            for (Txn txn : input) {
                long handle = feed.allocate(layout.rowSize(128));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setString(0, txn.user())
                        .setLong(1, txn.amount())
                        .setLong(2, txn.eventTime())
                        .weight(1L)
                        .eventTimestampNanos(txn.eventTime())
                        .sequence(txn.eventTime())
                        .commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(reader.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.advanceWatermark(20 * SECOND);
            pipeline.finish();
        }

        // Nothing is readable until the frontier commits: an aggregate mid-batch is a number that
        // never existed at any point in the input.
        assertThat(view.get("u1").found()).isFalse();
        assertThat(view.pendingChanges()).isPositive();

        sink.commit(sink.appliedFrontier());

        ViewResult u1 = view.get("u1");
        assertThat(u1.found()).isTrue();
        assertThat(u1.values().orElseThrow()[1])
                .as("u1's two transactions in the first window")
                .isEqualTo(300L);
        assertThat(u1.frontierComplete()).isTrue();
        assertThat(u1.stalenessNanos()).isZero();

        assertThat(view.get("u2").values().orElseThrow()[1]).isEqualTo(50L);
        assertThat(view.get("nobody").found()).isFalse();
    }

    @Test
    void aLateRecordCorrectsWhatTheViewServes() {
        // The case a view has to get right and an append-only sink does not. A late record does not
        // add to a closed window -- the window already emitted -- so the query retracts what it said
        // and states the new total. A view that treated the retraction as another insert would end
        // up serving the old number, the new number, or their sum, depending on map ordering, and
        // every one of those is a number the store never held.
        PhysicalOperator plan = withLateness(
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL)), 30 * SECOND);
        ServedView view = new ServedView("user_volume", plan.outputSchema(), List.of(0), 10_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());

        RowLayout layout = RowLayout.of(schema());
        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            feedOne(feed, writer, reader, pipeline, layout, "u1", 100, 1 * SECOND);

            // Close the window: the view now serves 100.
            pipeline.advanceWatermark(20 * SECOND);
            sink.commit(sink.appliedFrontier());
            assertThat(view.get("u1").values().orElseThrow()[1]).isEqualTo(100L);

            // A record for that window, arriving late but within the allowance.
            feedOne(feed, writer, reader, pipeline, layout, "u1", 25, 5 * SECOND);
            pipeline.advanceWatermark(25 * SECOND);
            sink.commit(sink.appliedFrontier());
        }

        assertThat(view.get("u1").values().orElseThrow()[1])
                .as("the view must serve the corrected total, not the old one and not the sum")
                .isEqualTo(125L);
        assertThat(view.size())
                .as("a correction revises a key rather than adding one")
                .isEqualTo(1);
    }

    /**
     * Sets allowed lateness, which SQL has nowhere to say yet (design 11.2's {@code EMIT CHANGES
     * WITH (...)} is not parsed).
     *
     * <p>Rebuilds through whatever sits above the aggregate rather than casting the root to it:
     * this query groups by the window boundaries and projects them away, so the root is a
     * projection. Assuming otherwise is how the first version of this test failed.
     */
    private static PhysicalOperator withLateness(PhysicalOperator plan, long allowedLatenessNanos) {
        if (plan instanceof com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator aggregate) {
            return new com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator(
                    aggregate.input(),
                    aggregate.outputSchema(),
                    aggregate.spec(),
                    aggregate.groupKeys(),
                    aggregate.aggregates(),
                    aggregate.windowStartOrdinal(),
                    aggregate.windowEndOrdinal(),
                    aggregate.maxSlices(),
                    allowedLatenessNanos);
        }
        if (plan instanceof com.ash.messaging.pravaha.runtime.plan.ProjectOperator project) {
            return new com.ash.messaging.pravaha.runtime.plan.ProjectOperator(
                    withLateness(project.input(), allowedLatenessNanos),
                    project.outputSchema(),
                    project.sourceOrdinals());
        }
        throw new IllegalStateException("no windowed aggregate under " + plan.label());
    }

    private static void feedOne(
            RowArena feed,
            BinaryRowWriter writer,
            BinaryRowView reader,
            InterpretedPipeline pipeline,
            RowLayout layout,
            String user,
            long amount,
            long eventTime) {
        long handle = feed.allocate(layout.rowSize(128));
        writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
        writer.setString(0, user)
                .setLong(1, amount)
                .setLong(2, eventTime)
                .weight(1L)
                .eventTimestampNanos(eventTime)
                .sequence(eventTime)
                .commit();
        feed.trimTo(handle, writer.sizeSoFar());
        pipeline.accept(reader.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
    }

    @Test
    void aReadCanChooseToSeeWorkThatHasNotCommitted() {
        // A dashboard would rather have the newest number and be told it is provisional; a
        // reconciliation would rather wait. Both read the same view.
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));
        ServedView view = new ServedView("user_volume", plan.outputSchema(), List.of(0), 10_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());

        RowLayout layout = RowLayout.of(schema());
        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            long handle = feed.allocate(layout.rowSize(128));
            writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
            writer.setString(0, "u1")
                    .setLong(1, 42)
                    .setLong(2, SECOND)
                    .weight(1L)
                    .eventTimestampNanos(SECOND)
                    .sequence(SECOND)
                    .commit();
            feed.trimTo(handle, writer.sizeSoFar());
            pipeline.accept(reader.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            pipeline.finish();
        }

        assertThat(view.get(new Consistency.Consistent(), java.time.Duration.ZERO, "u1")
                        .found())
                .as("a consistent read waits for the commit")
                .isFalse();

        ViewResult latest = view.get(new Consistency.Latest(), java.time.Duration.ZERO, "u1");
        assertThat(latest.found()).isTrue();
        assertThat(latest.values().orElseThrow()[1]).isEqualTo(42L);
        assertThat(latest.frontierComplete())
                .as("and it is told the number is provisional")
                .isFalse();
    }
}
