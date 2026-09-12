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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.common.config.Configuration;
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
import com.ash.messaging.pravaha.runtime.time.WatermarkGenerator;
import com.ash.messaging.pravaha.runtime.time.WatermarkTracker;

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

    private static final System.Logger LOG = System.getLogger(QueryExecution.class.getName());

    private final LaneGroup lanes;
    private final List<InterpretedPipeline> pipelines;
    private final StreamSchema inputSchema;
    private final List<String> streams;
    private final List<IngestPump> pumps = new ArrayList<>();

    /**
     * Event time, derived from the rows going past.
     *
     * <p>This is what turns a bounded run into a stream. Without it a window closes only when the
     * input ends, which is correct over a file and never happens on a source that does not stop --
     * so joins never evict, views never forget, and state grows until the process dies.
     *
     * <p>Null until {@link #generatingWatermarks} is called. A caller that supplies watermarks
     * itself, or that is reading a bounded source and relying on {@code finish()}, keeps the old
     * behaviour and pays nothing.
     */
    private WatermarkTracker watermarks;

    private Supplier<WatermarkGenerator> generator;

    private final Map<String, AtomicLong> partitionHighWater = new LinkedHashMap<>();

    private ScheduledExecutorService watermarkClock;
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
        return start(plan, laneCount, config, access, sinkPerLane, Map.of());
    }

    /**
     * Starts a query whose lookup joins are bound to dimension tables.
     *
     * @param lookups by the registered stream name the query joined against. Shared across lanes,
     *     unlike everything else here: a dimension table is a client to something outside the
     *     process, and one per lane would multiply its connections by the lane count for no benefit.
     *     The SPI requires them to be thread-safe for exactly this reason.
     */
    public static QueryExecution start(
            PhysicalOperator plan,
            int laneCount,
            LaneConfig config,
            MemoryAccess access,
            Supplier<RowOutput> sinkPerLane,
            Map<String, com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin> lookups) {

        List<InterpretedPipeline> pipelines = new ArrayList<>(laneCount);
        List<String> streams = streamsOf(plan);
        StreamSchema[] inputSchema = new StreamSchema[1];

        LaneGroup group = new LaneGroup(
                laneCount,
                LaneGroup.DEFAULT_VIRTUAL_PARTITIONS,
                config,
                access,
                context -> {
                    InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, sinkPerLane.get(), lookups);
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
        if (watermarks != null) {
            // One partition per pump, named so an idle one can be identified and excluded rather
            // than left holding the whole query's watermark down.
            String partition = streamName + "#" + laneIndex + "/" + pumps.size();
            // The stream's own lateness, not one number for the whole engine. A topic fed by
            // mobile clients and a scan of data already at rest have nothing in common here, and
            // whichever single value were chosen would be wrong for one of them.
            Duration lateness = pipelines.get(laneIndex).inputSchema(streamName).outOfOrderness();
            watermarks.addPartition(
                    partition,
                    generator == null ? WatermarkGenerator.boundedOutOfOrderness(lateness.toNanos()) : generator.get(),
                    System.nanoTime());

            // The pump stores its highest event time and nothing more; the tracker is read and
            // written only by the watermark thread.
            //
            // WatermarkTracker documents itself as owned by one lane and confined to its thread,
            // and calling observe() from each pump while the timer called advance() broke that
            // immediately -- a ConcurrentModificationException on the first tick. A lock would
            // have fixed it and put a lock on the per-row path, which is the one path in this
            // engine that must not have one. An atomic maximum costs a compare-and-set per row
            // and gives the timer the same number: the highest event time this partition has seen
            // is all boundedOutOfOrderness needs.
            AtomicLong highest = new AtomicLong(Long.MIN_VALUE);
            partitionHighWater.put(partition, highest);
            pump.observeEventTimeWith(nanos -> highest.accumulateAndGet(nanos, Math::max));
        }
        pumps.add(pump);
        return pump;
    }

    /**
     * Derives watermarks from the rows arriving, and advances event time on a timer.
     *
     * <p>Call this before {@code pumpInto} to run against a source that does not end. Each pump
     * becomes a partition of the query's watermark; the tracker takes the minimum across them and
     * excludes any that have gone quiet, and the result is pushed into every lane on a fixed tick.
     *
     * <p><strong>The tick is not decoration.</strong> A watermark derived only from arriving rows
     * cannot notice that a partition has stopped arriving, so without a clock a quiet source pins
     * event time and every window stops firing -- the failure that looks like a hang rather than a
     * bug. The timer is what lets idleness be detected at all.
     *
     * @param generator supplies a per-partition strategy, usually {@link
     *     WatermarkGenerator#boundedOutOfOrderness} with however late this source's rows really are
     * @param idleAfter how long a partition may produce nothing before it stops holding the
     *     watermark back
     * @param tick how often event time is advanced. Shorter closes windows sooner and costs a pass
     *     over the lanes; the default in {@link #generatingWatermarks(Supplier)} is a second
     */
    public QueryExecution generatingWatermarks(
            Supplier<WatermarkGenerator> generator, Duration idleAfter, Duration tick) {
        if (watermarks != null) {
            throw new IllegalStateException("this execution already derives watermarks");
        }
        if (!pumps.isEmpty()) {
            throw new IllegalStateException(
                    "call generatingWatermarks before pumpInto: a pump created earlier would not be a "
                            + "partition of the watermark, and its stream would advance event time for "
                            + "everybody else while contributing nothing of its own");
        }
        if (tick.compareTo(idleAfter) > 0) {
            throw new IllegalArgumentException("the watermark tick (" + tick + ") is longer than the idle "
                    + "timeout (" + idleAfter + "), so a partition could not be noticed idle until long "
                    + "after it was. Idleness is detected on the tick; the tick has to be the finer of the two.");
        }
        this.generator = generator;
        // Bounds are the tracker's, and it refuses rather than clamps: a timeout quietly changed to
        // something the operator did not ask for is how a tuned value becomes a mystery later.
        this.watermarks = new WatermarkTracker(idleAfter.toNanos());
        this.watermarkClock = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pravaha-watermark");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1, tick.toMillis());
        watermarkClock.scheduleWithFixedDelay(this::advanceWatermarkQuietly, period, period, TimeUnit.MILLISECONDS);
        return this;
    }

    /**
     * How long a partition may produce nothing before it stops holding the watermark back.
     *
     * <p>Thirty seconds suits a source that speaks at least every few seconds, which is most of
     * them. A quieter one -- a desk that trades in business hours, a key range written once a day
     * -- needs this raised, and the rule is that it should sit comfortably above the longest normal
     * gap on the quietest partition and comfortably below how long windows may go without closing.
     */
    public static final Duration DEFAULT_IDLE_AFTER = Duration.ofSeconds(30);

    /** How often event time advances, which is also what makes idleness detectable. */
    public static final Duration DEFAULT_TICK = Duration.ofSeconds(1);

    /** With a one-second tick and a thirty-second idle timeout. */
    public QueryExecution generatingWatermarks(Supplier<WatermarkGenerator> generator) {
        return generatingWatermarks(generator, DEFAULT_IDLE_AFTER, DEFAULT_TICK);
    }

    /**
     * Derives watermarks with everything read from configuration.
     *
     * <p>Reads {@code pravaha.watermark.idle-after} and {@code pravaha.watermark.tick}. Lateness is
     * not read here, because it belongs to each stream rather than to the deployment; the
     * configuration key of the same name is only the default a stream falls back to.
     *
     * @throws IllegalArgumentException if the configured idle timeout is outside {@link
     *     WatermarkTracker#MINIMUM_IDLE_TIMEOUT} and {@link WatermarkTracker#MAXIMUM_IDLE_TIMEOUT},
     *     because a value outside those does harm rather than merely being unusual
     */
    public QueryExecution generatingWatermarks(Configuration configuration) {
        return generatingWatermarks(
                null,
                configuration.getDuration("pravaha.watermark.idle-after").orElse(DEFAULT_IDLE_AFTER),
                configuration.getDuration("pravaha.watermark.tick").orElse(DEFAULT_TICK));
    }

    /**
     * Derives watermarks, taking each stream's own declared lateness.
     *
     * <p>The form to reach for. {@code StreamSchema.outOfOrderness()} is where a source says how
     * out of order it is, defaulting to ten seconds, so a query over three streams gets three
     * different tolerances without the caller having to know any of them.
     *
     * <p>Pass an explicit generator instead only to override every stream at once, or to use a
     * strategy other than bounded out-of-orderness.
     */
    public QueryExecution generatingWatermarks() {
        return generatingWatermarks(null, DEFAULT_IDLE_AFTER, DEFAULT_TICK);
    }

    private void advanceWatermarkQuietly() {
        try {
            long now = System.nanoTime();
            // Feed the tracker what each partition has seen since the last tick, on this thread.
            // A partition that produced nothing contributes nothing and, after the idle timeout,
            // stops holding the watermark back.
            partitionHighWater.forEach((partition, highest) -> {
                long seen = highest.get();
                if (seen != Long.MIN_VALUE) {
                    watermarks.observe(partition, seen, now);
                }
            });
            long watermark = watermarks.advance(now);
            if (watermark != Long.MIN_VALUE) {
                advanceWatermark(watermark);
            }
        } catch (RuntimeException failure) {
            // Never let the clock die: a watermark that stops advancing stops every window in the
            // query, and it does it silently.
            LOG.log(System.Logger.Level.WARNING, "could not advance the watermark: " + failure);
        }
    }

    /** The watermark this execution has reached, or empty when it derives none. */
    public java.util.OptionalLong watermarkNanos() {
        return watermarks == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(watermarks.watermark());
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
        if (watermarkClock != null) {
            // Before the lanes stop, so a tick cannot arrive at a closed pipeline.
            watermarkClock.shutdownNow();
        }
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
        public void onIdle() {
            // Nothing arriving means nothing will push a parked record out, so anything waiting on
            // a lookup is finished here instead of waiting for the stream to resume.
            pipeline.drainPending();
        }

        @Override
        public void close() {
            // On the lane thread, at shutdown: stateful operators emit what they were holding.
            pipeline.finish();
            pipeline.close();
        }
    }
}
