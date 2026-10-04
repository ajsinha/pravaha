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
import java.util.Arrays;
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
 * ALLNULLAGG-1 through a planned windowed aggregate: a window group whose amounts are all NULL
 * publishes NULL for {@code SUM}, {@code AVG}, {@code MIN} and {@code MAX} (and 0 for {@code
 * COUNT(amount)}); the published NULL is carried by a checkpoint, so a restored pipeline's late
 * correction retracts the NULL row it published before inserting the corrected one.
 */
class AllNullWindowAggregateTest {

    private static final long SECOND = 1_000_000_000L;

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64().withNullable(true))
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static final String SQL = "SELECT window_start, window_end, user_id, SUM(amount), AVG(amount), "
            + "MIN(amount), MAX(amount), COUNT(amount), COUNT(*) FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    @SuppressWarnings("NullAway") // nulls passed on purpose
    @Test
    void anAllNullWindowGroupPublishesNullAndACheckpointCarriesIt() {
        List<String> emitted = new ArrayList<>();
        byte[] checkpoint;
        try (Harness harness = new Harness(emitted)) {
            harness.feed(100, null, SECOND);
            harness.feed(100, null, 2 * SECOND);
            harness.feed(200, 4L, 2 * SECOND);
            harness.advanceWatermark(15 * SECOND);
            assertThat(emitted).containsExactlyInAnyOrder("+1 100|null|null|null|null|0|2", "+1 200|4|4|4|4|1|1");
            checkpoint = harness.pipeline.snapshotState();
        }

        emitted.clear();
        try (Harness restored = new Harness(emitted)) {
            restored.pipeline.restoreState(checkpoint);
            // Late, within the thirty-second lateness: the window is corrected, and the answer the
            // restored pipeline retracts is the NULL one it published before the restart.
            restored.feed(100, 5L, 3 * SECOND);
            restored.advanceWatermark(16 * SECOND);
            assertThat(emitted).containsExactly("-1 100|null|null|null|null|0|2", "+1 100|5|5|5|5|1|3");
        }
    }

    /** Plans {@link #SQL} with thirty seconds of lateness and records each row as weight, user and aggregates. */
    private static final class Harness implements AutoCloseable {
        final InterpretedPipeline pipeline;
        private final RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        private final RowLayout inputLayout = RowLayout.of(schema());
        private final BinaryRowWriter inputWriter = new BinaryRowWriter(inputLayout);
        private final BinaryRowView inputView = new BinaryRowView(inputLayout);

        Harness(List<String> emitted) {
            WindowedAggregateOperator planned = (WindowedAggregateOperator) new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(schema()).plan(SQL));
            PhysicalOperator plan = new WindowedAggregateOperator(
                    planned.input(),
                    planned.outputSchema(),
                    planned.spec(),
                    planned.groupKeys(),
                    planned.aggregates(),
                    planned.windowStartOrdinal(),
                    planned.windowEndOrdinal(),
                    planned.maxSlices(),
                    30 * SECOND);
            this.pipeline = InterpretedPipeline.compile(
                    plan,
                    () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                        Object[] values = row.values();
                        String rest = String.join(
                                "|",
                                Arrays.stream(values, 2, values.length)
                                        .map(String::valueOf)
                                        .toList());
                        emitted.add((row.weight() > 0 ? "+" : "") + row.weight() + " " + rest);
                    }));
        }

        void feed(long user, Long amount, long eventTime) {
            long handle = feed.allocate(inputLayout.rowSize(128));
            inputWriter.begin(feed.regionOf(handle), feed.offsetOf(handle));
            inputWriter.setLong(0, user);
            if (amount == null) {
                inputWriter.setNull(1);
            } else {
                inputWriter.setLong(1, amount);
            }
            inputWriter
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
