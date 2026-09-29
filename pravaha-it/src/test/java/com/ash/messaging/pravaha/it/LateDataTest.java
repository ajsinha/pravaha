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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a record that arrives after its window has fired.
 *
 * <p>Design 15.4 gives three outcomes, and the middle one is the interesting one. A record still
 * within the allowed lateness does not just get added: the window it belongs to has already emitted
 * an answer, so the old answer is retracted at weight {@code -1} and the corrected one emitted --
 * which consolidates downstream to exactly the difference. A record beyond the lateness cannot do
 * that, because the state it would correct has been released, so it goes to the late output and is
 * counted rather than silently dropped.
 */
class LateDataTest {

    private static final long SECOND = 1_000_000_000L;

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static final String SQL = "SELECT window_start, window_end, user_id, SUM(amount) FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    /** One emitted row, copied out of the arena. */
    private record Emitted(long windowStart, long user, long sum, long weight) {}

    @Test
    void aLateRecordRetractsTheOldAnswerAndEmitsTheCorrectedOne() {
        Harness harness = new Harness(SQL, 30 * SECOND);

        harness.feed(100, 10, 1 * SECOND);
        harness.feed(100, 20, 2 * SECOND);
        harness.advanceWatermark(15 * SECOND); // window [0,10) closes and emits 30

        assertThat(harness.emitted).containsExactly(new Emitted(0, 100, 30, 1));

        // A record for the closed window, still inside the thirty-second lateness allowance.
        harness.feed(100, 5, 3 * SECOND);
        harness.advanceWatermark(16 * SECOND);

        assertThat(harness.emitted)
                .as("the old answer is retracted and the corrected one sent")
                .containsExactly(new Emitted(0, 100, 30, 1), new Emitted(0, 100, 30, -1), new Emitted(0, 100, 35, 1));
        assertThat(harness.pipeline.corrections()).isEqualTo(1);

        // The Z-set consolidates to the right answer: +30 -30 +35 = 35.
        long net = harness.emitted.stream()
                .filter(row -> row.user() == 100)
                .mapToLong(row -> row.sum() * row.weight())
                .sum();
        assertThat(net).isEqualTo(35);
        harness.close();
    }

    @Test
    void aRecordTooLateToCorrectAnythingGoesToTheLateOutput() {
        // Its state has been released. Accepting it would produce a result contradicting one already
        // sent, computed from state that no longer exists. Zero lateness is the planner's default,
        // chosen so the late counter moves and somebody decides the number rather than a silent
        // allowance deciding for them.
        Harness harness = new Harness(SQL, 0);

        harness.feed(100, 10, 1 * SECOND);
        harness.advanceWatermark(60 * SECOND); // well past [0,10) and its zero lateness

        assertThat(harness.emitted).containsExactly(new Emitted(0, 100, 10, 1));

        harness.feed(100, 999, 2 * SECOND);

        assertThat(harness.pipeline.lateRecords()).isEqualTo(1);
        assertThat(harness.late).as("routed, not dropped").hasSize(1);
        assertThat(harness.emitted)
                .as("and no result contradicting the one already sent")
                .containsExactly(new Emitted(0, 100, 10, 1));
        harness.close();
    }

    @Test
    void aCorrectionThatChangesNothingEmitsNothing() {
        // A late record whose weights cancel leaves the window's answer unchanged. Emitting a
        // retraction and an identical insertion would be arithmetically harmless and pure noise.
        Harness harness = new Harness(SQL, 30 * SECOND);

        harness.feed(100, 10, 1 * SECOND);
        harness.advanceWatermark(15 * SECOND);
        int afterFirstFire = harness.emitted.size();

        harness.feed(200, 50, 3 * SECOND); // a different key, so key 100's row is unchanged
        harness.advanceWatermark(16 * SECOND);

        assertThat(harness.emitted.subList(afterFirstFire, harness.emitted.size()))
                .as("only the new key's row, with no churn on the unchanged one")
                .containsExactly(new Emitted(0, 200, 50, 1));
        harness.close();
    }

    /**
     * EMIT-1: a fired window whose lateness has passed is never fired again.
     *
     * <p>Hopping windows of twenty seconds every ten, and no lateness. A row at 5 s belongs to
     * [-10, 10) and [0, 20). The watermark reaching 11 s fires [-10, 10) and releases no slice -- the
     * row's slice still belongs to [0, 20) -- so the fired window's published answer stayed held, and
     * a second row at 8 s, on time for [0, 20), marked [-10, 10) as corrected: the next advance fired
     * again a window closed with no lateness, revising an answer nothing may revise.
     */
    @Test
    void aWindowPastItsLatenessIsNotReFiredByARowOnTimeForALaterWindow() {
        String hop = "SELECT window_start, window_end, user_id, SUM(amount) FROM "
                + "TABLE(HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '20' SECOND)) "
                + "GROUP BY window_start, window_end, user_id";
        Harness harness = new Harness(hop, 0);

        harness.feed(100, 10, 5 * SECOND);
        harness.advanceWatermark(11 * SECOND);
        assertThat(harness.emitted).containsExactly(new Emitted(-10 * SECOND, 100, 10, 1));

        harness.feed(100, 5, 8 * SECOND);
        harness.advanceWatermark(12 * SECOND);
        assertThat(harness.pipeline.corrections()).isZero();
        assertThat(harness.emitted)
                .as("[-10, 10) closed with no lateness keeps the answer it published")
                .containsExactly(new Emitted(-10 * SECOND, 100, 10, 1));

        harness.advanceWatermark(21 * SECOND);
        assertThat(harness.emitted)
                .as("the second row counts in the window it was on time for")
                .containsExactly(new Emitted(-10 * SECOND, 100, 10, 1), new Emitted(0, 100, 15, 1));
        assertThat(harness.pipeline.retainedWindows())
                .as("no lateness: nothing is held to correct a window that can never be corrected")
                .isZero();
        harness.close();
    }

    /**
     * EMIT-2: a correction is published when the query commits, not when the watermark next moves.
     *
     * <p>A watermark that moves only with later rows left the late row applied to the window's
     * state and its correction unpublished until some later row arrived.
     */
    @Test
    void aCorrectionIsPublishedAtTheNextCommitWithoutWaitingForTheWatermark() {
        Harness harness = new Harness(SQL, 30 * SECOND);
        harness.feed(100, 10, 1 * SECOND);
        harness.advanceWatermark(15 * SECOND);
        assertThat(harness.emitted).containsExactly(new Emitted(0, 100, 10, 1));

        harness.feed(100, 5, 3 * SECOND);
        // What a commit asks every pipeline to do before it publishes the view.
        harness.pipeline.emitContinuousAggregates();

        assertThat(harness.emitted)
                .containsExactly(new Emitted(0, 100, 10, 1), new Emitted(0, 100, 10, -1), new Emitted(0, 100, 15, 1));
        assertThat(harness.pipeline.corrections()).isEqualTo(1);

        harness.advanceWatermark(16 * SECOND);
        assertThat(harness.emitted).as("and not again at the advance").hasSize(3);
        harness.close();
    }

    /** EMIT-1: what a fired window holds for its corrections goes when its lateness does. */
    @Test
    void aFiredWindowsPublishedAnswerIsReleasedWhenItsLatenessPasses() {
        Harness harness = new Harness(SQL, 30 * SECOND);
        harness.feed(100, 10, 1 * SECOND);
        harness.feed(200, 10, 2 * SECOND);
        harness.advanceWatermark(15 * SECOND);
        assertThat(harness.pipeline.retainedWindows())
                .as("[0,10) correctable until 40 s")
                .isEqualTo(1);

        harness.advanceWatermark(39 * SECOND);
        assertThat(harness.pipeline.retainedWindows()).isEqualTo(1);
        harness.advanceWatermark(40 * SECOND);
        assertThat(harness.pipeline.retainedWindows()).isZero();
        harness.close();
    }

    /**
     * Sets the allowed lateness on a planned windowed aggregate.
     *
     * <p>SQL has nowhere to say it yet -- design 11.2's {@code EMIT CHANGES WITH ('allowed.lateness'
     * = '30s')} is not parsed -- and the planner's default is deliberately zero, so a test of
     * lateness has to set it directly. Better than a default chosen to make a test pass.
     */
    private static PhysicalOperator withLateness(PhysicalOperator plan, long allowedLatenessNanos) {
        WindowedAggregateOperator aggregate = (WindowedAggregateOperator) plan;
        return new WindowedAggregateOperator(
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

    /** Plans the SQL and feeds rows through a live pipeline, copying everything that comes out. */
    private static final class Harness implements AutoCloseable {
        final List<Emitted> emitted = new ArrayList<>();
        final List<Long> late = new ArrayList<>();
        final InterpretedPipeline pipeline;
        private final RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        private final RowLayout inputLayout = RowLayout.of(schema());
        private final BinaryRowWriter inputWriter = new BinaryRowWriter(inputLayout);
        private final BinaryRowView inputView = new BinaryRowView(inputLayout);

        Harness(String sql, long allowedLatenessNanos) {
            PhysicalOperator plan = withLateness(
                    new PhysicalPlanBuilder()
                            .build(SqlPlanner.withStreams(schema()).plan(sql)),
                    allowedLatenessNanos);
            // Copy on the way out: results are flyweights into the pipeline's arena and do not
            // survive the next batch, let alone the end of the test.
            this.pipeline = InterpretedPipeline.compile(
                    plan,
                    () -> new CapturingRowWriter(
                            plan.outputSchema(),
                            row -> emitted.add(
                                    new Emitted(row.asLong(0), row.asLong(2), row.asLong(3), row.weight()))));
            pipeline.lateOutput(row -> late.add(row.getLong(0)));
        }

        void feed(long user, long amount, long eventTime) {
            long handle = feed.allocate(inputLayout.rowSize(128));
            inputWriter.begin(feed.regionOf(handle), feed.offsetOf(handle));
            inputWriter
                    .setLong(0, user)
                    .setLong(1, amount)
                    .setLong(2, eventTime)
                    .weight(1L)
                    .eventTimestampNanos(eventTime)
                    .sequence(eventTime)
                    .commit();
            pipeline.accept(inputView.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
        }

        void advanceWatermark(long watermark) {
            pipeline.advanceWatermark(watermark);
        }

        @Override
        public void close() {
            pipeline.close();
            feed.close();
        }
    }
}
