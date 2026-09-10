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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.ingest.PartitionedIngestPump;
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
    private final List<String> streams;
    private final List<IngestPump> pumps = new ArrayList<>();
    private final List<PartitionedIngestPump> partitionedPumps = new ArrayList<>();
    private final PhysicalOperator plan;
    private final MemoryAccess access;

    private QueryExecution(
            LaneGroup lanes,
            List<InterpretedPipeline> pipelines,
            StreamSchema inputSchema,
            List<String> streams,
            PhysicalOperator plan,
            MemoryAccess access) {
        this.streams = List.copyOf(streams);
        this.plan = plan;
        this.access = access;
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
        List<String> streams = streamsOf(plan);
        StreamSchema[] inputSchema = new StreamSchema[1];

        LaneGroup group = new LaneGroup(
                laneCount,
                LaneGroup.DEFAULT_VIRTUAL_PARTITIONS,
                config,
                access,
                context -> {
                    InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, sinkPerLane.get());
                    pipelines.add(pipeline);
                    inputSchema[0] = pipeline.inputSchema(streams.get(0));

                    // One view per input, because the two sides of a join have different layouts and
                    // a shared view would decode the right side's bytes against the left's schema.
                    BinaryRowView[] views = new BinaryRowView[streams.size()];
                    for (int i = 0; i < views.length; i++) {
                        views[i] = new BinaryRowView(RowLayout.of(pipeline.inputSchema(streams.get(i))));
                    }
                    return new LanePipeline(pipeline, views, streams);
                },
                streams.size());
        group.start();
        return new QueryExecution(group, pipelines, inputSchema[0], streams, plan, access);
    }

    /**
     * Feeds one lane from a source partition.
     *
     * <p>One pump per partition per lane, because a pump owns the backpressure decision for the
     * reader it holds: two pumps sharing a reader would pause it for one lane's fullness and resume
     * it for another's emptiness, which is not backpressure so much as a fight.
     */
    public IngestPump pumpInto(int laneIndex, PartitionReader reader, BackpressurePolicy policy) {
        if (streams.size() != 1) {
            throw new IllegalStateException("this query reads " + streams
                    + "; name the stream a reader feeds, because a join cannot guess which side a partition is");
        }
        return pumpInto(laneIndex, streams.get(0), reader, policy);
    }

    /**
     * Feeds one lane's named input from a source partition.
     *
     * <p>By stream name rather than by input number, because the caller has a topic and a reader,
     * not a plan. Which side of the join a stream is on is the planner's knowledge, and asking the
     * ingest layer to reproduce it is asking it to be wrong eventually.
     */
    public IngestPump pumpInto(int laneIndex, String streamName, PartitionReader reader, BackpressurePolicy policy) {
        refuseUnpartitionedJoin();
        int input = streams.indexOf(streamName);
        if (input < 0) {
            throw new IllegalArgumentException(
                    "'" + streamName + "' is not an input of this query; it reads " + streams);
        }
        IngestPump pump = new IngestPump(
                reader, lanes.lane(laneIndex), input, pipelines.get(laneIndex).inputSchema(streamName), policy);
        pumps.add(pump);
        return pump;
    }

    /**
     * Refuses to feed a multi-lane join from a reader that is not partitioned by the join key.
     *
     * <p>Every lane compiles its own pipeline, so a join on four lanes is four independent joins
     * with a quarter of the rows each. A left row and the right row it matches land on whichever
     * lanes their partitions happened to send them to, and unless that is the <em>same</em> lane
     * the pair is simply never formed. The query does not fail; it returns fewer rows than it
     * should, which is the worst way for an engine to be wrong.
     *
     * <p>{@link #pumpPartitionedInto} is the answer: it hashes each row's join key and routes it to
     * the lane that owns it, so both sides of a key meet. This refusal is what makes choosing the
     * wrong pump a message rather than quietly missing output.
     */
    private void refuseUnpartitionedJoin() {
        if (laneCount() > 1 && containsJoin(plan)) {
            throw new PravahaException(
                    RuntimeErrors.UNSUPPORTED_JOIN,
                    "this query contains a join and runs on " + laneCount() + " lanes, so a row and the rows it "
                            + "can match must land on the same lane. A plain pump writes to whichever lane it "
                            + "was given, which would leave most pairs unformed and the query quietly short of "
                            + "output. Use pumpPartitionedInto, which routes by the join key.");
        }
    }

    private static boolean containsJoin(PhysicalOperator operator) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.JoinOperator) {
            return true;
        }
        return operator.inputs().stream().anyMatch(QueryExecution::containsJoin);
    }

    /**
     * Feeds a stream across every lane, routing each row to the lane that owns its join key.
     *
     * <p>The shuffle, done at the edge. A source partition says nothing about where a row's key
     * belongs -- Kafka partitions by whatever the producer chose, a file not at all -- so rows are
     * redistributed on the way in, using the same hash the join looks them up with. Both sides of a
     * key therefore reach the same lane, which is the only thing that makes a multi-lane join
     * correct.
     *
     * <p>One pump per source partition, feeding all lanes, rather than one per lane: the routing
     * decision belongs to whoever read the row, and splitting it would mean each lane's pump reading
     * every partition and discarding what is not its own.
     */
    public PartitionedIngestPump pumpPartitionedInto(
            String streamName, PartitionReader reader, BackpressurePolicy policy) {
        int input = streams.indexOf(streamName);
        if (input < 0) {
            throw new IllegalArgumentException(
                    "'" + streamName + "' is not an input of this query; it reads " + streams);
        }
        int[] keyOrdinals = joinKeyOrdinalsFor(input);
        PartitionedIngestPump pump = new PartitionedIngestPump(
                reader,
                lanes.lanes(),
                lanes::laneFor,
                input,
                pipelines.get(0).inputSchema(streamName),
                keyOrdinals,
                policy,
                access);
        partitionedPumps.add(pump);
        return pump;
    }

    /**
     * The join key columns of one input, expressed in that input's own scan ordinals.
     *
     * <p>Walks down from the join, mapping ordinals through anything between it and the scan. A
     * projection renumbers columns, so taking the join's ordinals as the scan's would route rows by
     * whatever column happens to sit at that position -- correct-looking, and wrong.
     */
    private int[] joinKeyOrdinalsFor(int input) {
        com.ash.messaging.pravaha.runtime.plan.JoinOperator join = findJoin(plan);
        if (join == null) {
            throw new IllegalStateException("this query has no join, so there is no key to partition by; use pumpInto");
        }
        boolean left = input == 0;
        List<Integer> keys = left ? join.leftKeys() : join.rightKeys();
        PhysicalOperator side = left ? join.left() : join.right();
        int[] mapped = new int[keys.size()];
        for (int i = 0; i < mapped.length; i++) {
            mapped[i] = mapDownToScan(side, keys.get(i));
        }
        return mapped;
    }

    private int mapDownToScan(PhysicalOperator operator, int ordinal) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.ScanOperator) {
            return ordinal;
        }
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.ProjectOperator project) {
            return mapDownToScan(project.input(), project.sourceOrdinals().get(ordinal));
        }
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.FilterOperator filter) {
            return mapDownToScan(filter.input(), ordinal);
        }
        throw new PravahaException(
                RuntimeErrors.UNSUPPORTED_JOIN,
                "cannot work out which source column feeds this join key: it passes through "
                        + operator.label() + ", which changes what a column means. Run this query on one lane, "
                        + "where no partitioning is needed.");
    }

    private static com.ash.messaging.pravaha.runtime.plan.JoinOperator findJoin(PhysicalOperator operator) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.JoinOperator join) {
            return join;
        }
        for (PhysicalOperator input : operator.inputs()) {
            com.ash.messaging.pravaha.runtime.plan.JoinOperator found = findJoin(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The streams this query reads, in plan order: a join's left side first. */
    public List<String> streams() {
        return streams;
    }

    /** Walks a plan for its scans, in the order the pipeline will register them. */
    private static List<String> streamsOf(PhysicalOperator plan) {
        List<String> found = new ArrayList<>();
        collectStreams(plan, found);
        return found;
    }

    private static void collectStreams(PhysicalOperator operator, List<String> into) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.ScanOperator scan) {
            into.add(scan.streamName());
            return;
        }
        operator.inputs().forEach(input -> collectStreams(input, into));
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

    /**
     * Takes a checkpoint: every lane's state, paired with every source's offset.
     *
     * <p>Each lane snapshots its own state on its own thread, between batches. That is not caution
     * about locking -- there is no lock to take -- it is the only moment at which a lane's state is
     * a coherent thing to copy at all: mid-batch, an operator has seen some of a batch's rows and
     * not others, and the offset the pump would report has moved past all of them.
     *
     * <p>Lanes snapshot independently and not simultaneously, which is the honest description of
     * what this does. For a query whose lanes share no state and whose sources are partitioned per
     * lane -- everything the engine currently runs -- that is sufficient, because each lane's state
     * and its own offsets are consistent with each other. It stops being sufficient the moment rows
     * cross the exchange, since a row in flight belongs to neither lane's snapshot; aligned barriers
     * (ADR-008) are what makes that case correct and are not built.
     */
    public com.ash.messaging.pravaha.state.checkpoint.Checkpoint checkpoint(long id, Duration timeout) {
        java.util.Map<String, byte[]> state = new java.util.HashMap<>();
        java.util.Map<String, String> offsets = new java.util.HashMap<>();

        for (int index = 0; index < pipelines.size(); index++) {
            InterpretedPipeline pipeline = pipelines.get(index);
            if (!pipeline.isStateful()) {
                continue;
            }
            String operatorId = "lane-" + index;
            byte[][] captured = new byte[1][];
            Lane lane = lanes.lane(index);
            long ticket = lane.submitControlTask(() -> captured[0] = pipeline.snapshotState());
            if (!lane.awaitControlTask(ticket, timeout)) {
                throw new IllegalStateException("lane " + index + " did not take its snapshot within " + timeout
                        + "; a checkpoint that some lanes joined and others did not is worse than none, so "
                        + "this one is abandoned rather than stored partially complete.");
            }
            state.put(operatorId, captured[0]);
        }

        for (int index = 0; index < pumps.size(); index++) {
            offsets.put("partition-" + index, pumps.get(index).position().token());
        }
        return new com.ash.messaging.pravaha.state.checkpoint.Checkpoint(id, System.nanoTime(), offsets, state);
    }

    /**
     * Restores state from a checkpoint.
     *
     * <p>Before the lanes are fed anything, and the caller is responsible for creating readers at
     * the checkpoint's offsets -- restoring state without rewinding the sources double-counts every
     * record between the checkpoint and the failure, which is the exact failure the checkpoint
     * exists to prevent.
     */
    public void restore(com.ash.messaging.pravaha.state.checkpoint.Checkpoint checkpoint, Duration timeout) {
        for (int index = 0; index < pipelines.size(); index++) {
            InterpretedPipeline pipeline = pipelines.get(index);
            byte[] state = checkpoint.operatorState().get("lane-" + index);
            if (state == null) {
                continue;
            }
            Lane lane = lanes.lane(index);
            long ticket = lane.submitControlTask(() -> pipeline.restoreState(state));
            if (!lane.awaitControlTask(ticket, timeout)) {
                throw new IllegalStateException("lane " + index + " did not restore its state within " + timeout);
            }
            lane.checkHealth();
        }
    }

    /** Records too late to correct any window, across every lane. */
    public long lateRecords() {
        return pipelines.stream().mapToLong(InterpretedPipeline::lateRecords).sum();
    }

    /**
     * Stops without finishing: what a crash looks like from the inside.
     *
     * <p>{@link #close()} is a shutdown -- stateful operators emit what they are holding, because a
     * bounded source that ends should not lose its final windows. A crash does none of that, and the
     * difference matters more than it looks.
     *
     * <p>It was found by a recovery test that used {@code close()} as its crash. Every number was
     * right and every window before the checkpoint appeared twice: once emitted by the graceful
     * shutdown after the checkpoint was taken, and once by the recovered run, which had no way to
     * know. A test whose failure simulation flushes is testing a shutdown, not a failure -- and the
     * duplicates it produced were real, in the sense that this is exactly what a clean stop followed
     * by a restore from an older checkpoint would do.
     */
    public void abort() {
        pipelines.forEach(InterpretedPipeline::abandon);
        close();
    }

    @Override
    public void close() {
        pumps.forEach(IngestPump::close);
        partitionedPumps.forEach(PartitionedIngestPump::close);
        // Closing the group stops each lane, and each lane closes its processor on its own thread --
        // which is where the pipeline's end-of-input runs, so final windows are written into the
        // arena by the thread that owns it.
        lanes.close();
    }

    /** Adapts a lane's batch of row offsets to the pipeline's row-at-a-time interface. */
    private record LanePipeline(InterpretedPipeline pipeline, BinaryRowView[] views, List<String> streams)
            implements com.ash.messaging.pravaha.runtime.lane.LaneProcessor {

        @Override
        public int onBatch(com.ash.messaging.pravaha.common.memory.MemoryRegion region, long[] offsets, int count) {
            return onBatch(0, region, offsets, count);
        }

        @Override
        public int onBatch(
                int input, com.ash.messaging.pravaha.common.memory.MemoryRegion region, long[] offsets, int count) {
            BinaryRowView view = views[input];
            String stream = streams.get(input);
            for (int i = 0; i < count; i++) {
                // A flyweight over the lane's own inbox cell: the row is read in place and never
                // copied, which is the entire reason the inbox holds bytes rather than objects.
                pipeline.accept(stream, view.wrap(region, (int) offsets[i]));
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
