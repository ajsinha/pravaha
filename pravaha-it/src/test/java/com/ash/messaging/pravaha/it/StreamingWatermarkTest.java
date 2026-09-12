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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PartitionReader.RecordSink;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.time.WatermarkGenerator;
import com.ash.messaging.pravaha.runtime.time.WatermarkTracker;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A stream that does not end, in memory that does not grow.
 *
 * <p>Until watermarks were generated, every bound in this engine was armed and never fired. Windows
 * closed from {@code finish()} at end of input; joins evicted at {@code watermark - matchWithin} and
 * the watermark never moved; views forgot rows older than the committed frontier and the frontier
 * only advances when a watermark does. Over a file that is invisible, because the end of input fires
 * everything. Over a source that does not stop, it is unbounded growth with no error.
 *
 * <p>So the property under test is not "windows fire" but "the query runs indefinitely and its state
 * does not grow". That is the only claim a person deploying this actually needs.
 */
class StreamingWatermarkTest {

    private static final long SECOND = 1_000_000_000L;

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static final String WINDOWED = "SELECT window_start, window_end, user_id, SUM(amount) FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(1024, 128)
                .withBatchSize(32)
                .withArena(1 << 22, 8)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("watermark-lane", true);
    }

    /** A source that never ends: every poll produces more rows, at an ever-later event time. */
    private static final class EndlessSource implements PartitionReader {
        private final AtomicLong produced = new AtomicLong();

        @Override
        public int poll(RecordSink sink, int max) {
            int count = Math.min(max, 8);
            for (int i = 0; i < count; i++) {
                long n = produced.getAndIncrement();
                // One row per millisecond of event time, and only 16 distinct keys -- so an
                // unbounded run has bounded cardinality per window and any growth is the windows
                // accumulating rather than the key space.
                long at = n * 1_000_000L;
                RowWriter writer = sink.beginRow();
                writer.setLong(0, n % 16)
                        .setLong(1, 1)
                        .setLong(2, at)
                        .weight(1L)
                        .eventTimestampNanos(at)
                        .sequence(n)
                        .commit();
            }
            return count;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(Long.toString(produced.get()));
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    @Test
    @Timeout(120)
    void anEndlessStreamClosesWindowsAndDoesNotGrow() throws Exception {
        List<CapturingRowWriter.Captured> out = java.util.Collections.synchronizedList(new ArrayList<>());
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(WINDOWED));

        try (QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {

            // Rows are in order and on time, so no allowance is needed beyond a little slack.
            execution.generatingWatermarks(
                    () -> WatermarkGenerator.boundedOutOfOrderness(10 * SECOND),
                    Duration.ofSeconds(5),
                    Duration.ofMillis(50));

            var pump = execution.pumpInto(0, new EndlessSource(), BackpressurePolicy.defaults());

            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            long pumped = 0;
            while (System.nanoTime() < deadline) {
                pumped += pump.pumpOnce(8);
                execution.checkHealth();
                // Let the lane drain. Without this the test is an arena-exhaustion test rather
                // than a watermark test: the source is infinitely fast and nothing downstream is.
                Thread.sleep(5);
            }

            assertThat(pumped)
                    .as("the source never stops, so the pump never runs dry")
                    .isGreaterThan(10_000);

            // The claim. Windows closed while the stream was still running -- not at the end,
            // because there was no end.
            assertThat(out)
                    .as("windows must fire from watermarks, not from end of input")
                    .isNotEmpty();
            assertThat(execution.watermarkNanos()).isPresent();
            assertThat(execution.watermarkNanos().orElseThrow())
                    .as("event time advanced")
                    .isGreaterThan(0);
        }
    }

    @Test
    @Timeout(60)
    void withoutWatermarksNothingEverFires() throws Exception {
        // The behaviour this replaced, kept as a test so the difference is legible rather than
        // remembered: the same endless source, the same query, and no watermark generation.
        List<CapturingRowWriter.Captured> out = java.util.Collections.synchronizedList(new ArrayList<>());
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(WINDOWED));

        try (QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {

            var pump = execution.pumpInto(0, new EndlessSource(), BackpressurePolicy.defaults());
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                pump.pumpOnce(8);
                execution.checkHealth();
                Thread.sleep(5);
            }

            // Thousands of rows in, every window still open, nothing out, and no error. This is
            // what an unbounded source looked like before: not a crash, a silence.
            assertThat(out).isEmpty();
            assertThat(execution.watermarkNanos()).isEmpty();
        }
    }

    @Test
    void aStreamDeclaresItsOwnLateness() {
        // Lateness belongs to the source. A topic fed by mobile clients over a flaky network and a
        // scan of data already at rest have nothing in common here, so one engine-wide number has
        // to be wrong for one of them.
        StreamSchema tolerant = StreamSchema.builder("mobile")
                .field("user_id", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .outOfOrderness(Duration.ofMinutes(2))
                .build();

        assertThat(tolerant.outOfOrderness()).isEqualTo(Duration.ofMinutes(2));
        // And a stream that says nothing gets the default rather than zero, because zero is a
        // claim of strict ordering that the engine would then hold the source to.
        assertThat(schema().outOfOrderness()).isEqualTo(StreamSchema.DEFAULT_OUT_OF_ORDERNESS);
        assertThat(StreamSchema.DEFAULT_OUT_OF_ORDERNESS).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aDeclaredLatenessHoldsTheWatermarkBack() {
        // The point of declaring it: a tolerant stream waits longer before calling a window
        // complete, which is what stops a late row being dropped rather than corrected.
        long generous = Duration.ofSeconds(30).toNanos();
        long strict = Duration.ofSeconds(1).toNanos();

        WatermarkGenerator patient = WatermarkGenerator.boundedOutOfOrderness(generous);
        WatermarkGenerator hasty = WatermarkGenerator.boundedOutOfOrderness(strict);
        patient.observe(100 * SECOND);
        hasty.observe(100 * SECOND);

        assertThat(patient.watermark()).isEqualTo(100 * SECOND - generous);
        assertThat(hasty.watermark()).isEqualTo(100 * SECOND - strict);
        assertThat(patient.watermark())
                .as("the tolerant stream is still waiting for rows the strict one has given up on")
                .isLessThan(hasty.watermark());
    }

    @Test
    void aNegativeLatenessIsRefused() {
        assertThatThrownBy(() -> StreamSchema.builder("bad")
                        .field("event_time", Types.timestamp())
                        .eventTime("event_time")
                        .outOfOrderness(Duration.ofSeconds(-1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void anIdlePartitionDoesNotPinEventTime() {
        // Tested at the tracker rather than through a running query, deliberately. The property is
        // the tracker's, and driving it through two lanes turned up an unrelated defect in
        // multi-lane windowed aggregation ("window_start is NOT NULL and cannot be set null"),
        // which would have made this test fail for a reason that has nothing to do with idleness.
        // That defect is worth its own investigation and is not this one.
        //
        // The failure this guards is the one the tracker's own javadoc calls the most common
        // streaming incident: a partition goes quiet, holds the watermark at whatever it last saw,
        // every window stops firing, and it presents as a hang rather than an error.
        WatermarkTracker tracker = new WatermarkTracker(Duration.ofMillis(200).toNanos());
        long start = System.nanoTime();
        tracker.addPartition("busy", WatermarkGenerator.boundedOutOfOrderness(0), start);
        tracker.addPartition("quiet", WatermarkGenerator.boundedOutOfOrderness(0), start);

        tracker.observe("busy", 10 * SECOND, start);
        tracker.observe("quiet", 1 * SECOND, start);

        // Both live: the minimum wins, and the quiet one is holding everybody at 1s.
        assertThat(tracker.advance(start)).isEqualTo(1 * SECOND);

        tracker.observe("busy", 20 * SECOND, start + Duration.ofMillis(100).toNanos());
        assertThat(tracker.advance(start + Duration.ofMillis(100).toNanos()))
                .as("still inside the idle timeout, so the quiet partition still counts")
                .isEqualTo(1 * SECOND);

        // Past the timeout the quiet partition stops counting, and time moves again.
        long later = start + Duration.ofMillis(500).toNanos();
        tracker.observe("busy", 30 * SECOND, later);
        assertThat(tracker.advance(later))
                .as("an idle partition must be excluded, not allowed to stop the query")
                .isEqualTo(30 * SECOND);
        assertThat(tracker.isIdle("quiet")).isTrue();

        // And it rejoins the moment it speaks again, rather than being gone for good.
        long back = later + Duration.ofMillis(50).toNanos();
        tracker.observe("quiet", 25 * SECOND, back);
        assertThat(tracker.isIdle("quiet")).isFalse();
        assertThat(tracker.advance(back))
                .as("the watermark never moves backwards, even when a partition rejoins behind it")
                .isEqualTo(30 * SECOND);
    }
}
