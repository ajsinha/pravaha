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
package com.ash.messaging.pravaha.runtime.ingest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.lane.Lane;
import com.ash.messaging.pravaha.runtime.lane.LaneBackpressure;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6, part one: a lane whose consumer cannot keep up says so, in time and in rows.
 *
 * <p>Before this, the only backpressure number was {@code rejectedOffers} -- a count of refusals
 * with no duration behind it -- and {@code IngestPump.pausedNanos}, which nothing carried anywhere.
 * A lane stalled for a minute and a lane refused twice read the same on every dashboard there was.
 *
 * <p>The consumer here is deliberately slow: the lane thread blocks inside its processor on a
 * latch, so nothing drains and the inbox fills and stays full. That is the shape of the real
 * failure -- an operator too slow for its input -- reproduced without a timing race, because the
 * latch is held until the assertions have run.
 */
@Timeout(60)
class BackpressureMeasurementTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("name", Types.string())
                .build();
    }

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(16, 128)
                .withBatchSize(4)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("backpressure-test", true);
    }

    /** A source with more rows than anyone will take. */
    private static final class EndlessReader implements PartitionReader {
        private long produced;
        final List<String> events = new ArrayList<>();

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            for (int i = 0; i < maxRecords; i++) {
                RowWriter writer = sink.beginRow();
                writer.setLong(0, produced)
                        .setString(1, "row-" + produced)
                        .weight(1L)
                        .eventTimestampNanos(produced)
                        .sequence(produced)
                        .commit();
                produced++;
            }
            return maxRecords;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset("n=" + produced);
        }

        @Override
        public void pause() {
            events.add("pause");
        }

        @Override
        public void resume() {
            events.add("resume");
        }

        @Override
        public void close() {}
    }

    @Test
    void aSlowConsumerShowsBlockedTimeAndInboxDepth() throws Exception {
        MemoryAccess access = MemoryAccess.best();
        CountDownLatch release = new CountDownLatch(1);
        EndlessReader reader = new EndlessReader();

        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return count;
                });
                IngestPump pump = new IngestPump(reader, lane, schema(), BackpressurePolicy.defaults())
                        .attributedTo("slow_query")) {
            lane.start();

            // Fill it, then keep polling into a full inbox: one episode, however many polls.
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!pump.isBackpressured() && System.nanoTime() < deadline) {
                pump.pumpOnce(8);
            }
            assertThat(pump.isBackpressured())
                    .as("a lane that cannot drain must report its writer as waiting")
                    .isTrue();

            long waitsAfterFirstStall = pump.backpressureWaits();
            for (int i = 0; i < 200; i++) {
                pump.pumpOnce(8);
            }
            assertThat(pump.backpressureWaits())
                    .as("200 more polls into the same full inbox are still one episode, not 200")
                    .isEqualTo(waitsAfterFirstStall)
                    .isEqualTo(1);

            Thread.sleep(20);
            assertThat(pump.backpressureWaitNanos())
                    .as("and the episode in progress is counted while it is in progress, not only when it ends")
                    .isGreaterThan(Duration.ofMillis(10).toNanos());

            assertThat(lane.inboxDepth())
                    .as("the depth is in cells, which is the number an operator can act on")
                    .isGreaterThan(0)
                    .isLessThanOrEqualTo(lane.inboxCells());

            LaneBackpressure.Snapshot blocked = lane.backpressure().snapshot(lane.inboxDepth(), lane.inboxCells());
            assertThat(blocked.waits()).isEqualTo(1);
            assertThat(blocked.blockedFraction()).isGreaterThan(0).isLessThanOrEqualTo(1.0);
            assertThat(blocked.byQuery()).containsOnlyKeys("slow_query");
            assertThat(blocked.longestWaiter()).isPresent();
            assertThat(blocked.longestWaiter().orElseThrow().getKey()).isEqualTo("slow_query");

            release.countDown();
        }
    }

    @Test
    void theEpisodeClosesWhenThereIsRoomAgain() {
        MemoryAccess access = MemoryAccess.best();
        EndlessReader reader = new EndlessReader();

        // No lane thread at all, so nothing drains: the inbox fills on the first few polls.
        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> count);
                IngestPump pump =
                        new IngestPump(reader, lane, schema(), BackpressurePolicy.defaults()).attributedTo("q")) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!pump.isBackpressured() && System.nanoTime() < deadline) {
                pump.pumpOnce(4);
            }
            assertThat(pump.isBackpressured()).isTrue();
            assertThat(lane.backpressure()
                            .snapshot(lane.inboxDepth(), lane.inboxCells())
                            .blockedFraction())
                    .isGreaterThan(0);

            // Now drain it by hand, on this thread, and poll again.
            lane.start();
            assertThat(lane.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (pump.isBackpressured() && System.nanoTime() < deadline) {
                pump.pumpOnce(4);
            }

            assertThat(pump.isBackpressured())
                    .as("room again ends the episode rather than leaving it open for ever")
                    .isFalse();
            long settled = pump.backpressureWaitNanos();
            assertThat(settled).isPositive();
            LaneBackpressure.Snapshot blocked = lane.backpressure().snapshot(0, lane.inboxCells());
            assertThat(blocked.byQuery().get("q").waitNanos())
                    .as("the lane's copy of the closed episode stops growing too")
                    .isPositive();
        }
    }

    @Test
    void aLaneNobodyHasEverBlockedOnReportsNothingRatherThanAGuess() {
        try (Lane lane = new Lane(0, config(), MemoryAccess.best(), context -> (region, offsets, count) -> count)) {
            LaneBackpressure.Snapshot blocked = lane.backpressure().snapshot(lane.inboxDepth(), lane.inboxCells());
            assertThat(blocked.waits()).isZero();
            assertThat(blocked.waitNanos()).isZero();
            assertThat(blocked.blockedFraction()).isZero();
            assertThat(blocked.inboxDepth()).isZero();
            assertThat(blocked.byQuery()).isEmpty();
            assertThat(blocked.longestWaiter()).isEmpty();
        }
    }

    @Test
    void twoQueriesOnOneLaneAreToldApart() {
        LaneBackpressure lane = new LaneBackpressure();
        lane.waited("fast_query", Duration.ofMillis(1).toNanos());
        lane.waited("slow_query", Duration.ofMillis(50).toNanos());
        lane.waited("slow_query", Duration.ofMillis(70).toNanos());

        LaneBackpressure.Snapshot blocked = lane.snapshot(12, 64);
        assertThat(blocked.waits()).isEqualTo(3);
        assertThat(blocked.byQuery()).containsOnlyKeys("fast_query", "slow_query");
        assertThat(blocked.byQuery().get("slow_query").waits()).isEqualTo(2);
        assertThat(blocked.longestWaiter().orElseThrow().getKey())
                .as("the console's verdict names this one")
                .isEqualTo("slow_query");
        assertThat(blocked.inboxDepth()).isEqualTo(12);
        assertThat(blocked.inboxCells()).isEqualTo(64);
    }
}
