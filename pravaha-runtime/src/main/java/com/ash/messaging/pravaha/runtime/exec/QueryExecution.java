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
package com.ash.messaging.pravaha.runtime.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.Lane;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.lane.LaneGroup;
import com.ash.messaging.pravaha.runtime.lane.LaneMetrics;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * A plan, running on lanes.
 *
 * <p>Until this existed the engine was two halves that never touched: a lane runtime with its own
 * threads, arenas, inboxes and backpressure, exercised only by its own tests; and a SQL path that
 * compiled queries into an {@link InterpretedPipeline} and drove it from whichever thread happened
 * to call it. Both worked. Neither was the engine.
 *
 * <p>This is the join: one {@link InterpretedPipeline} per lane, each with its own arena and its own
 * output, fed by that lane's inbox. Per lane rather than shared, and that is the whole point --
 * a pipeline holds mutable operator state, and one shared between lanes would need locking on every
 * record, which is exactly what the single-writer principle exists to avoid (design section 13.1).
 * The cost is that a stateful query's state is partitioned across lanes, which is why rows have to
 * reach the lane that owns their key; routing is the caller's business and the exchange (P2-07) is
 * how a row that lands on the wrong one gets there.
 *
 * <p><strong>End of input runs on the lane thread.</strong> Closing a lane closes its processor,
 * which is where {@code finish()} is called -- so final windows are emitted by the thread that owns
 * the arena they are written into. Calling it from outside would be a second writer touching a
 * lane's state while the lane is still running.
 */
public final class QueryExecution implements AutoCloseable {

    private final LaneGroup lanes;
    private final List<InterpretedPipeline> pipelines;
    private final StreamSchema inputSchema;
    private final List<IngestPump> pumps = new ArrayList<>();

    private QueryExecution(LaneGroup lanes, List<InterpretedPipeline> pipelines, StreamSchema inputSchema) {
        this.lanes = lanes;
        this.pipelines = pipelines;
        this.inputSchema = inputSchema;
    }

    /**
     * Compiles a plan onto {@code laneCount} lanes and starts them.
     *
     * @param sinkPerLane called once per lane. Each lane needs its own output for the same reason it
     *     needs its own arena: a sink shared between lane threads is a shared mutable object on the
     *     hot path, and the first thing anybody would do about that is add a lock.
     */
    public static QueryExecution start(
            PhysicalOperator plan,
            int laneCount,
            LaneConfig config,
            MemoryAccess access,
            Supplier<RowOutput> sinkPerLane) {

        List<InterpretedPipeline> pipelines = new ArrayList<>(laneCount);
        StreamSchema[] inputSchema = new StreamSchema[1];

        LaneGroup group = new LaneGroup(laneCount, config, access, context -> {
            InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, sinkPerLane.get());
            pipelines.add(pipeline);
            inputSchema[0] = pipeline.inputSchema();

            RowLayout layout = RowLayout.of(pipeline.inputSchema());
            BinaryRowView view = new BinaryRowView(layout);
            return new LanePipeline(pipeline, view);
        });
        group.start();
        return new QueryExecution(group, pipelines, inputSchema[0]);
    }

    /**
     * Feeds one lane from a source partition.
     *
     * <p>One pump per partition per lane, because a pump owns the backpressure decision for the
     * reader it holds: two pumps sharing a reader would pause it for one lane's fullness and resume
     * it for another's emptiness, which is not backpressure so much as a fight.
     */
    public IngestPump pumpInto(int laneIndex, PartitionReader reader, BackpressurePolicy policy) {
        IngestPump pump = new IngestPump(reader, lanes.lane(laneIndex), inputSchema, policy);
        pumps.add(pump);
        return pump;
    }

    /** Which lane a key belongs to, by the group's virtual-partition assignment. */
    public int laneFor(long keyHash) {
        return lanes.laneFor(keyHash);
    }

    public Lane lane(int index) {
        return lanes.lane(index);
    }

    public int laneCount() {
        return lanes.laneCount();
    }

    /** Advances event time on every lane, firing any window that has completed. */
    public void advanceWatermark(long watermarkNanos) {
        pipelines.forEach(pipeline -> pipeline.advanceWatermark(watermarkNanos));
    }

    /** Waits until every lane has drained what it was given. */
    public boolean awaitQuiescent(Duration timeout) {
        return lanes.awaitQuiescent(timeout);
    }

    /** Rethrows the first lane failure, if any lane died. */
    public void checkHealth() {
        lanes.checkHealth();
    }

    public List<LaneMetrics> metrics() {
        return lanes.metrics();
    }

    /** Records too late to correct any window, across every lane. */
    public long lateRecords() {
        return pipelines.stream().mapToLong(InterpretedPipeline::lateRecords).sum();
    }

    @Override
    public void close() {
        pumps.forEach(IngestPump::close);
        // Closing the group stops each lane, and each lane closes its processor on its own thread --
        // which is where the pipeline's end-of-input runs, so final windows are written into the
        // arena by the thread that owns it.
        lanes.close();
    }

    /** Adapts a lane's batch of row offsets to the pipeline's row-at-a-time interface. */
    private record LanePipeline(InterpretedPipeline pipeline, BinaryRowView view)
            implements com.ash.messaging.pravaha.runtime.lane.LaneProcessor {

        @Override
        public int onBatch(com.ash.messaging.pravaha.common.memory.MemoryRegion region, long[] offsets, int count) {
            for (int i = 0; i < count; i++) {
                // A flyweight over the lane's own inbox cell: the row is read in place and never
                // copied, which is the entire reason the inbox holds bytes rather than objects.
                pipeline.accept(view.wrap(region, (int) offsets[i]));
            }
            return count;
        }

        @Override
        public void close() {
            // On the lane thread, at shutdown: stateful operators emit what they were holding.
            pipeline.finish();
            pipeline.close();
        }
    }
}
