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
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source end of backpressure.
 *
 * <p>{@link #aStalledLanePausesTheSourceQuickly} is the story's acceptance criterion, and the reason
 * it is worth asserting rather than assuming is that the failure is invisible: without the pause,
 * rows are still refused and still not lost -- the plugin simply keeps fetching them and throwing
 * them away, the lag never appears in a source-side metric, and the first sign of trouble is a
 * consumer complaining about data that was read and discarded.
 */
@Timeout(60)
class IngestPumpTest {

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
                .withThreads("pump-test", true);
    }

    /** A reader that produces rows on demand and records every pause and resume. */
    private static final class ScriptedReader implements PartitionReader {
        private final int total;
        private int produced;
        final List<String> events = new ArrayList<>();

        ScriptedReader(int total) {
            this.total = total;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && produced < total) {
                RowWriter writer = sink.beginRow();
                writer.setLong(0, produced)
                        .setString(1, "row-" + produced)
                        .weight(1L)
                        .eventTimestampNanos(produced)
                        .sequence(produced)
                        .commit();
                produced++;
                emitted++;
            }
            return emitted;
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
    void rowsGoFromThePluginStraightIntoTheLane() {
        MemoryAccess access = MemoryAccess.best();
        List<Long> seen = new ArrayList<>();
        ScriptedReader reader = new ScriptedReader(50);

        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> {
                    for (int i = 0; i < count; i++) {
                        // The row landed in the inbox cell the plugin wrote into: no copy between.
                        seen.add(region.getLong((int) offsets[i]));
                    }
                    return count;
                });
                IngestPump pump = new IngestPump(reader, lane, schema(), BackpressurePolicy.defaults())) {
            lane.start();
            while (pump.rowsPumped() < 50) {
                pump.pumpOnce(8);
            }
            assertThat(lane.awaitQuiescent(Duration.ofSeconds(10))).isTrue();

            assertThat(pump.rowsPumped()).isEqualTo(50);
            assertThat(seen).hasSize(50);
            assertThat(seen).isSorted();
            assertThat(pump.position().token()).isEqualTo("n=50");
        }
    }

    @Test
    void aStalledLanePausesTheSourceQuickly() throws Exception {
        // The acceptance criterion: a lane that cannot keep up must slow the *source*, not quietly
        // discard what the source produces.
        MemoryAccess access = MemoryAccess.best();
        CountDownLatch release = new CountDownLatch(1);
        ScriptedReader reader = new ScriptedReader(10_000);

        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return count;
                });
                IngestPump pump = new IngestPump(reader, lane, schema(), BackpressurePolicy.defaults())) {
            lane.start();

            long start = System.nanoTime();
            while (!pump.isPaused()
                    && Duration.ofNanos(System.nanoTime() - start).toMillis() < 5_000) {
                pump.pumpOnce(8);
            }
            long millisToPause = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(pump.isPaused())
                    .as("the source must be paused, not merely refused")
                    .isTrue();
            assertThat(millisToPause)
                    .as("design 13.5 asks for the source to be paused within 200 ms of the stall")
                    .isLessThan(200L);
            assertThat(reader.events).containsExactly("pause");
            assertThat(pump.pausedNanos()).isPositive();

            release.countDown();
        }
    }

    @Test
    void nothingIsBufferedBetweenTheReaderAndTheLane() {
        // NFR-9 at this boundary. The pump asks for no more than the inbox can hold, so there is no
        // queue between the two to grow -- the bound is structural, not a limit somebody enforces.
        MemoryAccess access = MemoryAccess.best();
        CountDownLatch release = new CountDownLatch(1);
        ScriptedReader reader = new ScriptedReader(100_000);

        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return count;
                });
                IngestPump pump = new IngestPump(reader, lane, schema(), BackpressurePolicy.defaults())) {
            lane.start();
            for (int i = 0; i < 1_000; i++) {
                pump.pumpOnce(64);
            }

            assertThat(pump.rowsPumped())
                    .as("a stalled lane cannot have absorbed more than its inbox holds, plus the batch in flight")
                    .isLessThanOrEqualTo((long) lane.inboxCells() + config().batchSize());
            release.countDown();
        }
    }

    @Test
    void resumingWaitsForRealDrainageRatherThanTheNextPoll() {
        // The hysteresis. With one threshold, a source at capacity pauses and resumes on alternate
        // polls -- more expensive than the backpressure it provides, and a metric that reads as a
        // fault.
        MemoryAccess access = MemoryAccess.best();
        ScriptedReader reader = new ScriptedReader(10_000);

        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> count);
                IngestPump pump = new IngestPump(reader, lane, schema(), BackpressurePolicy.defaults())) {
            // The lane is deliberately not started, so nothing drains and the inbox only fills.
            // Deadline-bounded: a regression that never pauses must fail here, not spin. @Timeout
            // cannot rescue a loop like this -- it interrupts, and nothing here observes interrupts.
            pumpUntilPaused(pump);
            assertThat(reader.events).containsExactly("pause");

            // Polling again while still full must not produce a second pause, or a resume.
            for (int i = 0; i < 20; i++) {
                pump.pumpOnce(4);
            }
            assertThat(reader.events)
                    .as("pause and resume are edge-triggered, not repeated every poll")
                    .containsExactly("pause");
            assertThat(pump.pauseCount()).isEqualTo(1);
            assertThat(pump.resumeCount()).isZero();
        }
    }

    /** Pumps until the source is paused, or fails saying it never was. */
    private static void pumpUntilPaused(IngestPump pump) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!pump.isPaused()) {
            pump.pumpOnce(4);
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the source was never paused after 10 s of pumping into a lane that is "
                        + "not draining: rows are being refused without the source being told, which is "
                        + "backpressure that stops at the engine boundary");
            }
        }
    }

    @Test
    void watermarksThatWouldOscillateAreRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BackpressurePolicy(0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternate polls");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BackpressurePolicy(0.4, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * LANE-2. On a shared lane several pumps write into one inbox, each sizing its poll to the free
     * cells it saw -- which the others may take first. A pump that claimed straight into cells then
     * found none mid-poll and failed its feed with BACKPRESSURED; one sharing its inbox stages each
     * row and waits for a cell, so every row arrives and nothing fails.
     */
    @Test
    void pumpsSharingOneInboxWaitForRoomRatherThanFailingMidPoll() throws Exception {
        MemoryAccess access = MemoryAccess.best();
        java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
        try (Lane lane = new Lane(0, config(), access, context -> (region, offsets, count) -> {
            java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
            seen.addAndGet(count);
            return count;
        })) {
            lane.start();
            List<Thread> producers = new ArrayList<>();
            List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
            for (int p = 0; p < 4; p++) {
                IngestPump pump = new IngestPump(new ScriptedReader(300), lane, schema(), BackpressurePolicy.defaults())
                        .sharingItsInbox();
                producers.add(Thread.ofPlatform().start(() -> {
                    try {
                        int moved = 0;
                        while (moved < 300) {
                            moved += pump.pumpOnce(64);
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }));
            }
            for (Thread producer : producers) {
                producer.join(Duration.ofSeconds(30).toMillis());
            }
            assertThat(failures).isEmpty();
            assertThat(lane.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            assertThat(seen.get()).isEqualTo(1_200);
        }
    }
}
