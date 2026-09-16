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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
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

    /**
     * The query's own id on a shared lane, or null when this execution owns its lanes.
     *
     * <p>W9-8. A lane is owned by the execution that created it, so {@code close()} closes it — and
     * on a lane shared by three hundred queries that would stop the lane serving the other two
     * hundred and ninety-nine. Ownership has to move, and {@code close()} has to mean <em>drop my
     * pipeline</em> rather than <em>stop this lane</em>.
     *
     * <p>Non-null is the hosted case: the group was built by somebody else (the registry), its
     * processor is a {@link com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer} per lane, and
     * this execution contributed one pipeline to each. Closing removes those pipelines and leaves
     * every lane running.
     */
    private final String hostedQueryId;

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

    /**
     * What each partition's high-water mark was at the last tick that reported it.
     *
     * <p>The tick used to report every partition's retained high-water every time, whether or not
     * anything had arrived. {@code observe} takes that as activity and refreshes the partition's
     * clock -- so a partition that spoke once and went quiet never aged, and held the whole query's
     * watermark down for ever. Only a partition that had never spoken at all could go idle, which
     * is the one case the feature is not for.
     */
    private final Map<String, Long> lastReportedHighWater = new LinkedHashMap<>();

    private java.util.concurrent.ScheduledFuture<?> watermarkClock;
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
        this(lanes, pipelines, inputSchema, streams, plan, access, null);
    }

    private QueryExecution(
            LaneGroup lanes,
            List<InterpretedPipeline> pipelines,
            StreamSchema inputSchema,
            List<String> streams,
            PhysicalOperator plan,
            MemoryAccess access,
            String hostedQueryId) {
        this.hostedQueryId = hostedQueryId;
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
        return start(plan, laneCount, config, access, sinkPerLane, lookups, null);
    }

    /**
     * Starts a query whose lanes are driven by {@code runner}, or by threads of their own if it is
     * null.
     *
     * <p>ADR-027. A registry that hosts every query's lanes on one runner costs threads by its cores
     * rather than by its registrations, which is the difference between holding tens of continuous
     * queries and holding thousands.
     */
    public static QueryExecution start(
            PhysicalOperator plan,
            int laneCount,
            LaneConfig config,
            MemoryAccess access,
            Supplier<RowOutput> sinkPerLane,
            Map<String, com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin> lookups,
            com.ash.messaging.pravaha.runtime.lane.LaneRunner runner) {

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
        if (runner == null) {
            group.start();
        } else {
            group.startOn(runner);
        }
        return new QueryExecution(group, pipelines, inputSchema[0], streams, plan, access);
    }

    /**
     * Starts a query onto lanes somebody else owns, sharing them with other queries.
     *
     * <p>W9-8. The other {@code start} builds a {@link LaneGroup} per query, so a lane runs one
     * query and closing the query closes the lane. This one contributes a pipeline to each lane of
     * an existing group whose processor is a {@link com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer},
     * and {@link #close()} removes those pipelines and leaves every lane running for the queries
     * still on it.
     *
     * <p>The group must already be started and every lane's processor must be a multiplexer;
     * neither is checked lazily, because a query that registered into the wrong kind of processor
     * would fail at the first row rather than at the call that was wrong.
     *
     * <p><strong>Used by the registry when {@code pravaha.lane.multiplex} is on.</strong> Both of
     * the halves this paragraph used to name as unsettled are settled: a row carries the identity
     * of the stream it came from (W9-9), and a watermark advance is a level rather than a cut, so
     * it no longer clamps the lane's batch (W9-10) — which was the cost that made hundreds of
     * pipelines on one lane untenable.
     *
     * <p>What is still missing, and why the registry's switch defaults to off, is <em>admission
     * control</em>: nothing decides which lane a registration lands on, so every hosted query shares
     * one lane's budget. {@code LaneMultiplexer} bounds a heavy query's effect on latency ordering
     * by running pipelines in ascending order of lane time consumed, but that is fair ordering, not
     * a ceiling.
     */
    public static QueryExecution startOn(
            LaneGroup group,
            String queryId,
            PhysicalOperator plan,
            Supplier<RowOutput> sinkPerLane,
            Map<String, com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin> lookups,
            MemoryAccess access) {
        java.util.Objects.requireNonNull(queryId, "queryId");
        List<String> streams = streamsOf(plan);
        List<InterpretedPipeline> pipelines = new ArrayList<>(group.laneCount());
        StreamSchema inputSchema = null;

        for (com.ash.messaging.pravaha.runtime.lane.Lane lane : group.lanes()) {
            if (!(lane.processor() instanceof com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer multiplexer)) {
                throw new IllegalArgumentException("lane " + lane.laneId() + " is not multiplexed, so query '"
                        + queryId + "' cannot be hosted on it: startOn needs a group whose processor is a "
                        + "LaneMultiplexer, and this one runs "
                        + lane.processor().getClass().getSimpleName());
            }
            InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, sinkPerLane.get(), lookups);
            pipelines.add(pipeline);
            inputSchema = pipeline.inputSchema(streams.get(0));

            // One view per input: the two sides of a join have different layouts, and a shared view
            // would decode the right side's bytes against the left's schema.
            BinaryRowView[] views = new BinaryRowView[streams.size()];
            for (int i = 0; i < views.length; i++) {
                views[i] = new BinaryRowView(RowLayout.of(pipeline.inputSchema(streams.get(i))));
            }
            multiplexer.register(new com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer.Pipeline(
                    queryId, inputSchema.streamId(), new LanePipeline(pipeline, views, streams)));
        }
        return new QueryExecution(group, pipelines, inputSchema, streams, plan, access, queryId);
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
     * Hands one row to the query, by stream name.
     *
     * <p>The seam a caller needs when it already has a row and no source plugin: a registry fed by
     * something else, an embedder pushing from its own loop, a test. A pump is the right shape when
     * there is a source to poll; this is the right shape when the rows arrive by other means.
     *
     * <p>Copies into the lane's inbox and returns. <strong>The row is processed on the lane's
     * thread, not this one</strong>, so a caller that reads the view immediately afterwards may see
     * the state before this row. That is the engine being what it is rather than a wart: work is
     * done by the thread that owns the state, which is what removes the locks. {@link
     * #awaitQuiescent} is how a caller that needs the row applied waits for it.
     *
     * @return false if the lane's inbox is full, which is backpressure and not an error. The caller
     *     decides whether to retry, drop or slow down, because only it knows which its source
     *     permits
     */
    /**
     * Hands one row to a query that reads exactly one stream.
     *
     * <p>Refused when there are several, because a row arriving with no stream named is a row whose
     * side of a join nobody knows, and guessing produces a query quietly short of output rather
     * than an error.
     */
    public boolean accept(RowView row) {
        if (streams.size() != 1) {
            throw new IllegalStateException(
                    "this query reads " + streams + "; use accept(streamName, row) to say which side a row arrived on");
        }
        return accept(streams.get(0), row);
    }

    public boolean accept(String streamName, RowView row) {
        int input = streams.indexOf(streamName);
        if (input < 0) {
            throw new IllegalArgumentException(
                    "'" + streamName + "' is not an input of this query; it reads " + streams);
        }
        if (!(row instanceof BinaryRowView binary)) {
            throw new IllegalArgumentException("this engine moves rows as binary frames, and was handed a "
                    + row.getClass().getSimpleName() + ". Write through a RowWriter rather than implementing "
                    + "RowView, or the bytes have no layout to copy.");
        }
        // One lane, chosen by the row's key where the query needs that and zero otherwise. Keyed
        // aggregates are refused on more than one lane (see refuseUnpartitionedAggregate), so
        // anything reaching here with several lanes is a join, which is fed through a partitioned
        // pump rather than through this method.
        Lane lane = lanes.lane(0);
        return lane.offer(input, binary.region(), binary.offset(), binary.length());
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
        refuseUnpartitionedAggregate();
        int input = streams.indexOf(streamName);
        if (input < 0) {
            throw new IllegalArgumentException(
                    "'" + streamName + "' is not an input of this query; it reads " + streams);
        }
        IngestPump pump = new IngestPump(
                reader, lanes.lane(laneIndex), input, pipelines.get(laneIndex).inputSchema(streamName), policy);
        trackEventTimeOf(streamName, laneIndex, pump::observeEventTimeWith);
        pumps.add(pump);
        return pump;
    }

    /**
     * Registers one source partition with the watermark tracker and wires its event-time observer.
     *
     * <p>Extracted so that both pumps use it. It lived inline in {@code pumpInto} and {@code
     * pumpPartitionedInto} never had it, which is an asymmetry neither class could show you: event
     * time did not advance from a partitioned source, so windows never closed and join state never
     * evicted -- and because no partition was registered, the source was not in the minimum either,
     * so a query mixing the two advanced its watermark without accounting for the partitioned side
     * and could drop its rows as late. Sharing the code is the fix that also stops it recurring.
     *
     * @param observes accepts the observer to install -- a method reference to the pump's own
     *     {@code observeEventTimeWith}, since the two pump types share no supertype
     */
    private void trackEventTimeOf(
            String streamName, int laneIndex, java.util.function.Consumer<java.util.function.LongConsumer> observes) {
        if (watermarks == null) {
            return;
        }
        // One partition per pump, named so an idle one can be identified and excluded rather
        // than left holding the whole query's watermark down.
        // Separated, because concatenating two integers made (1,0) and (10,anything) the same
        // string -- and two partitions sharing a name means one silently replaces the other in the
        // tracker, so the minimum-across-partitions rule is computed over the wrong set. Mine.
        String partition = streamName + "#" + laneIndex + "/" + pumps.size() + ":" + partitionedPumps.size();
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
        observes.accept(nanos -> highest.accumulateAndGet(nanos, Math::max));
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
        // The process's clock, not one of this query's own. This was a
        // newSingleThreadScheduledExecutor per execution -- a platform thread per registered query,
        // and after ADR-027 removed the lane's, the binding constraint on how many a node holds.
        //
        // SharedClock keeps time on one thread for the JVM and fires each tick on a virtual thread,
        // because a tick is mostly waiting: advanceWatermarkQuietly submits a control task to every
        // lane and awaits each with a ten-second timeout. Sharing a single *worker* would let one
        // slow lane stall every other query's clock, which is a worse failure than the threads it
        // saves.
        this.watermarkClock = SharedClock.every(tick, "watermark tick", this::advanceWatermarkQuietly);
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
                if (seen == Long.MIN_VALUE) {
                    return;
                }
                // Reported only when it has moved. Re-reporting an unchanged mark is not news, and
                // the tracker reads every report as a sign of life.
                Long previous = lastReportedHighWater.put(partition, seen);
                if (previous == null || previous != seen) {
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

    /**
     * The watermark this execution has reached, or empty when there is not one yet.
     *
     * <p>Empty covers two different things and both must stay empty. An execution that derives no
     * watermarks at all has none; and one that derives them but has seen no row yet holds
     * {@link WatermarkGenerator#NOT_YET}, which is a sentinel and not a time. Returning the sentinel
     * as though it were a watermark would make a query that has never seen a row report a lag, and
     * {@code PravahaMetricsTest} is right to insist it reports none: zero lag on a silent query
     * shows it as perfectly up to date, which is the opposite of what is true.
     *
     * <p>Found by TIME-12's fix breaking that test. The registry now reads this method, so a
     * sentinel leaking out of here reaches the only watermark gauge there is.
     */
    public java.util.OptionalLong watermarkNanos() {
        if (watermarks == null) {
            return java.util.OptionalLong.empty();
        }
        long current = watermarks.watermark();
        return current == WatermarkGenerator.NOT_YET
                ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(current);
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

    /**
     * Refuses a keyed aggregate spread across lanes with nothing routing its key.
     *
     * <p>The same hazard as the join guard above and a worse failure, because a join short of pairs
     * produces too little and this produces too much: a key that lands on two lanes is kept twice,
     * each lane holding a partial sum, and both are emitted. Eighteen keys come out as thirty-six
     * rows of half-answers, and nothing errors -- a consumer reading one row per key silently gets
     * half of it.
     *
     * <p>There is no {@code pumpPartitionedInto} for an aggregate to point at, because partitioning
     * by a grouping key is not built: that pump routes by <em>join</em> keys and refuses a query
     * without a join. So this refuses the combination rather than suggesting a fix that does not
     * exist, and says plainly that the parallel form is unbuilt. A keyed aggregate is single-lane
     * until it is.
     */
    private void refuseUnpartitionedAggregate() {
        if (laneCount() > 1 && containsKeyedAggregate(plan)) {
            throw new PravahaException(
                    RuntimeErrors.UNSUPPORTED_AGGREGATE,
                    "this query groups by a key and runs on " + laneCount() + " lanes, and nothing routes a "
                            + "row to the lane that owns its group. Every lane would keep its own partial "
                            + "total for a key it happens to see, and emit it -- so one group comes out as "
                            + "several rows of partial answers, with no error to say so. Partitioning by a "
                            + "grouping key is not built (pumpPartitionedInto routes by join keys and needs a "
                            + "join), so run this query on one lane.");
        }
    }

    private static boolean containsKeyedAggregate(PhysicalOperator operator) {
        // Both shapes. A windowed aggregate keyed only by the window boundaries is still safe on
        // one lane and unsafe on several, because two lanes both holding the same window each keep
        // their own running total for it.
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator) {
            return true;
        }
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.AggregateOperator aggregate
                && !aggregate.groupKeyOrdinals().isEmpty()) {
            return true;
        }
        return operator.inputs().stream().anyMatch(QueryExecution::containsKeyedAggregate);
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
        // Lane 0 names the partition and reads the schema; a partitioned pump feeds every lane, and
        // every lane's pipeline was compiled from the same plan, so any of them gives the same
        // lateness. What matters is that the partition is registered at all.
        trackEventTimeOf(streamName, 0, pump::observeEventTimeWith);
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

    /**
     * The physical plan this execution is running.
     *
     * <p>Exposed for pushdown: whatever opens a source needs the plan to work out which of the
     * query's predicates that source could evaluate itself, and it is the execution that holds it.
     */
    public PhysicalOperator plan() {
        return plan;
    }

    /** How long a watermark advance waits for the lanes to apply it before giving up on this tick. */
    private static final Duration WATERMARK_ADVANCE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Advances event time on every lane, firing any window that has completed.
     *
     * <p>Submitted to each lane rather than run here, because firing a window allocates the result
     * row in that lane's arena and pushes it downstream -- both of which belong to the thread that
     * owns them. This ran on the watermark clock's own thread, which was a latent violation for as
     * long as nothing else touched the arena concurrently.
     *
     * <p>Then something did: reclaiming the pipeline arena at each batch boundary put the lane
     * thread on the same memory, and the two together corrupted results rather than merely racing.
     * A windowed SUM over 100,000 rows returned 3,525,325,093,794 against a true 99,999, the view
     * held 996 to 1,001 rows where 1,000 existed, and it differed run to run -- from about ten
     * thousand rows upward, with the query reporting RUNNING throughout.
     *
     * <p>Restoring a checkpoint already went through the lane for exactly this reason. This now does
     * too, which is the fix for both.
     */
    public void advanceWatermark(long watermarkNanos) {
        long[] tickets = new long[pipelines.size()];
        for (int index = 0; index < pipelines.size(); index++) {
            InterpretedPipeline pipeline = pipelines.get(index);
            // A level, not a cut (W9-10). Queue order still guarantees this runs after every row
            // handed over before it, which is what "advance it over the rows I had already been
            // given" means; what it no longer does is stop the lane's batch at that exact point.
            // Applying a watermark one batch further along closes a window a little later and is
            // never wrong -- where a checkpoint applied one batch later is a double count.
            tickets[index] = lanes.lane(index).submitLevelTask(() -> pipeline.advanceWatermark(watermarkNanos));
        }
        // Waited for, not fired and forgotten. Moving this onto the lane made it asynchronous, and a
        // caller that advances event time and then reads the result is entitled to see the windows
        // that advance closed -- two subscription tests said so immediately. Restoring a checkpoint
        // waits on its ticket for the same reason.
        for (int index = 0; index < tickets.length; index++) {
            lanes.lane(index).awaitControlTask(tickets[index], WATERMARK_ADVANCE_TIMEOUT);
        }
    }

    /**
     * Waits until every lane has drained what it was given, or until one of them dies.
     *
     * <p>Checking health while waiting, rather than waiting and then reporting a timeout, and the
     * difference is the whole value of this method. A lane that has failed cannot drain, so
     * quiescence never arrives and the caller waits out the entire timeout -- five minutes, for
     * {@code pravaha run} -- and is then told the query "did not finish; the lane is still working
     * or stuck". Meanwhile the real cause, an {@code ArithmeticException} from a division by zero or
     * an overflow, sat in the lane's {@code failure} field the whole time, unread.
     *
     * <p>So: poll in short slices and rethrow the moment a lane reports a failure. A wrong query now
     * fails in milliseconds, saying what was wrong with it.
     */
    public boolean awaitQuiescent(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Duration slice = Duration.ofMillis(50);
        while (System.nanoTime() < deadline) {
            checkHealth();
            if (lanes.awaitQuiescent(slice)) {
                // Once more after draining: the failure may have been the last thing a lane did.
                checkHealth();
                return true;
            }
        }
        checkHealth();
        return false;
    }

    /** Rethrows the first lane failure, if any lane died. */
    public void checkHealth() {
        lanes.checkHealth();
    }

    /**
     * Publishes every unwindowed aggregate's running answer, on the lanes' own threads.
     *
     * <p>Submitted as a control task rather than run here, because emission writes into the lane's
     * arena and pushes a row downstream -- both of which belong to the thread that owns them. Doing
     * it on the caller's thread is the mistake that put a ConcurrentModificationException into the
     * serving view earlier in this work.
     *
     * <p>Driven by whoever commits: the ingest feed's publish timer, or an embedder pushing its own
     * rows. Tying it to the watermark tick alone would leave a push-based query silent, because a
     * query with no pump has no watermark partitions and so never ticks.
     *
     * <p>Returns once the lanes have emitted, so a caller that commits straight afterwards commits
     * the answer this call produced rather than the one before it.
     */
    public void publishContinuousAggregates() {
        long[] tickets = new long[pipelines.size()];
        java.util.Arrays.fill(tickets, -1L);
        for (int i = 0; i < pipelines.size(); i++) {
            InterpretedPipeline pipeline = pipelines.get(i);
            if (pipeline.hasContinuousAggregates()) {
                tickets[i] = lanes.lane(i).submitControlTask(pipeline::emitContinuousAggregates);
            }
        }
        // Waited for, because the caller is about to commit. Submitting and returning meant the
        // emission landed after the commit that asked for it, so an unwindowed aggregate's answer
        // appeared one commit late -- and a query that received a single batch and was then read
        // showed nothing at all, for ever, which is indistinguishable from the aggregate never
        // emitting. The old behaviour was written down ("a caller may see the previous answer
        // once") rather than fixed; it should not have been.
        for (int i = 0; i < tickets.length; i++) {
            if (tickets[i] >= 0) {
                lanes.lane(i).awaitControlTask(tickets[i], WATERMARK_ADVANCE_TIMEOUT);
            }
        }
    }

    /** The first lane failure, without throwing it. */
    public java.util.Optional<Throwable> laneFailure() {
        return lanes.failure();
    }

    /**
     * Off-heap this execution holds, named by the part holding it.
     *
     * <p>W9-7 measured 2,068 KiB per active query from the JVM's buffer pool and could not say what
     * 1,044 of it was, because a pool total is a sum with no names in it. This is the same number
     * with names.
     */
    public java.util.Map<String, Long> offHeapBytes() {
        java.util.Map<String, Long> total = new java.util.LinkedHashMap<>();
        for (int i = 0; i < lanes.laneCount(); i++) {
            lanes.lane(i).offHeapBytes().forEach((part, bytes) -> total.merge(part, bytes, Long::sum));
        }
        // The pipeline's own arena, which is not the lane's. Two arenas per query, and only one of
        // them was ever visible to anything (W9-7).
        long pipelineArenas = 0;
        for (InterpretedPipeline pipeline : pipelines) {
            pipelineArenas += pipeline.arenaBytes();
        }
        total.merge("pipeline-arena", pipelineArenas, Long::sum);
        return total;
    }

    /**
     * State this execution holds, against the ceiling it would be refused at.
     *
     * <p>Summed across lanes. What an operator watches so that {@code PRV-4001} stops being the
     * first they hear of it (ADR-037).
     */
    public InterpretedPipeline.StateUsage stateUsage() {
        long held = 0;
        long ceiling = 0;
        for (InterpretedPipeline pipeline : pipelines) {
            InterpretedPipeline.StateUsage usage = pipeline.stateUsage();
            held += usage.held();
            ceiling += usage.ceiling();
        }
        return new InterpretedPipeline.StateUsage(held, ceiling);
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
     * <p><strong>Aligned.</strong> The cut is one point in the query's input, not one point per
     * lane. Every source is held between rows for the length of phase one; inside that, each
     * source's offset is read and each lane is given a marker at the position its producers have
     * reached. Only then is any lane waited on. A lane cuts its batch at the marker rather than
     * finishing the batch it was in, so the state it snapshots covers exactly the rows the recorded
     * offsets exclude -- which is what "exactly-once state" has to mean to be worth saying.
     *
     * <p>Sinks are a different matter and unchanged: a row emitted before the cut and re-emitted
     * after a restore is a duplicate this cannot prevent, which is why the guarantee is
     * effectively-once output (ADR-008, design section 14.4).
     *
     * <p>What is still not cut is the exchange. See {@link #refuseWhileRowsCrossTheExchange}.
     */
    /** The key a served view's contents travel under inside a checkpoint's operator state. */
    public static final String SERVED_VIEW_STATE = "served-view";

    private java.util.function.Supplier<byte[]> viewSnapshot;

    private java.util.function.Consumer<byte[]> viewRestore;

    /**
     * Includes a served view's committed contents in this execution's checkpoints.
     *
     * <p>Without this a checkpoint held operator accumulators and source offsets, and the view was
     * in neither. For a filter or a projection there are no accumulators -- the view <em>is</em> the
     * whole answer -- so a restart resumed the source past everything it had already read, restored
     * nothing, and served an empty view under a query reporting RUNNING. Every row it had ever
     * produced, gone, with no error anywhere.
     *
     * <p>Passed in rather than reached for: the execution does not know about serving, and should
     * not start to.
     */
    public QueryExecution checkpointingViewWith(
            java.util.function.Supplier<byte[]> snapshot, java.util.function.Consumer<byte[]> restore) {
        this.viewSnapshot = snapshot;
        this.viewRestore = restore;
        return this;
    }

    /** The key a shuffling pump's source offset travels under. Keeps "partition-N" for the plain ones. */
    private static final String SHUFFLED_OFFSET_PREFIX = "shuffled-partition-";

    public com.ash.messaging.pravaha.state.checkpoint.Checkpoint checkpoint(long id, Duration timeout) {
        refuseWhileRowsCrossTheExchange();

        java.util.Map<String, byte[]> state = new java.util.HashMap<>();
        java.util.Map<String, String> offsets = new java.util.HashMap<>();
        java.util.List<long[]> tickets = new ArrayList<>();
        Map<Integer, byte[][]> captures = new LinkedHashMap<>();

        // Phase one, with every source held between rows: read each source's offset and hand each
        // lane its marker. Nothing is waited for in here. Waiting inside the freeze would hold the
        // sources for as long as the slowest lane takes to reach its marker, which is a stall on
        // ingest proportional to how busy the query is -- and worse, a lane that has to drain to
        // its marker cannot do so while the coordinator is holding the thread that would refill it.
        long frozen = freezeSources(timeout);
        try {
            for (int index = 0; index < pumps.size(); index++) {
                offsets.put("partition-" + index, pumps.get(index).position().token());
            }
            for (int index = 0; index < partitionedPumps.size(); index++) {
                // Recorded at all, which they were not: a shuffling pump's offset was left out of
                // every checkpoint it appeared in, so a multi-lane query restored its operator
                // state and then had nothing to rewind its source with. Every row since the
                // checkpoint was replayed on top of state that had already counted it.
                offsets.put(
                        SHUFFLED_OFFSET_PREFIX + index,
                        partitionedPumps.get(index).position().token());
            }
            for (int index = 0; index < pipelines.size(); index++) {
                InterpretedPipeline pipeline = pipelines.get(index);
                if (!pipeline.isStateful()) {
                    continue;
                }
                byte[][] captured = new byte[1][];
                // Every lane is given its marker before any lane is waited on. Submitting to lane 0,
                // waiting for it, and only then submitting to lane 1 is what made a multi-lane
                // checkpoint a set of unrelated snapshots: lane 1's cut was taken however long lane
                // 0's snapshot took, and a whole query's worth of rows, later.
                long ticket = lanes.lane(index).submitControlTask(() -> captured[0] = pipeline.snapshotState());
                tickets.add(new long[] {index, ticket});
                captures.put(index, captured);
            }
        } finally {
            thawSources(frozen);
        }

        // Phase two: collect. The sources are running again, and each lane is holding its own input
        // at its own marker until it has answered.
        for (long[] each : tickets) {
            int index = (int) each[0];
            Lane lane = lanes.lane(index);
            if (!lane.awaitControlTask(each[1], timeout)) {
                lane.checkHealth();
                throw new IllegalStateException("lane " + index + " did not take its snapshot within " + timeout
                        + "; a checkpoint that some lanes joined and others did not is worse than none, so "
                        + "this one is abandoned rather than stored partially complete.");
            }
            lane.checkHealth();
            byte[] captured = captures.remove(index)[0];
            if (captured == null) {
                // The wait said the task had run and it had not produced a snapshot. Storing the
                // null is what made a ticket bug into a data-loss bug: restore found no state under
                // this key, skipped the operator, and reported success over an empty one.
                throw new IllegalStateException("lane " + index + " reported its snapshot as taken but produced "
                        + "nothing. Storing that would be a checkpoint this operator is absent from, and a "
                        + "restore from it would resume with no history and no error.");
            }
            state.put("lane-" + index, captured);
        }

        if (viewSnapshot != null) {
            state.put(SERVED_VIEW_STATE, viewSnapshot.get());
        }

        return new com.ash.messaging.pravaha.state.checkpoint.Checkpoint(id, System.nanoTime(), offsets, state);
    }

    /**
     * Holds every source between rows, so the cut is one point rather than one point per lane.
     *
     * <p>The offsets and the markers are read inside this. That is the whole of what makes the
     * checkpoint aligned: with the producers stopped, a lane's marker sits at the end of everything
     * it has been handed, the offset says exactly which rows those were, and no row can arrive at
     * one lane while another lane is still being marked.
     *
     * <p>Fails the checkpoint rather than proceeding without a source, and unwinds what it has
     * already taken. A checkpoint over some of the sources is the partial checkpoint this method
     * exists to prevent, one level up.
     *
     * @return how many pumps were frozen, to pass to {@link #thawSources}
     */
    private long freezeSources(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int frozen = 0;
        try {
            for (IngestPump pump : pumps) {
                if (!pump.freezeIngest(remaining(deadline))) {
                    throw new IllegalStateException("source " + frozen + " was still inside a poll after " + timeout
                            + ", so its offset cannot be read at a point between rows. A checkpoint taken "
                            + "anyway would record an offset that does not match the state the lanes hold, "
                            + "which is silent loss in one direction and a silent double count in the other; "
                            + "this one is abandoned instead.");
                }
                frozen++;
            }
            for (PartitionedIngestPump pump : partitionedPumps) {
                if (!pump.freezeIngest(remaining(deadline))) {
                    throw new IllegalStateException("shuffling source " + (frozen - pumps.size())
                            + " was still inside a poll after " + timeout + ", so its offset cannot be read at "
                            + "a point between rows; this checkpoint is abandoned rather than stored with an "
                            + "offset the lanes' state does not match.");
                }
                frozen++;
            }
            return frozen;
        } catch (RuntimeException e) {
            thawSources(frozen);
            throw e;
        }
    }

    private static Duration remaining(long deadline) {
        long left = deadline - System.nanoTime();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    private void thawSources(long frozen) {
        for (long i = frozen - 1; i >= 0; i--) {
            if (i < pumps.size()) {
                pumps.get((int) i).thawIngest();
            } else {
                partitionedPumps.get((int) (i - pumps.size())).thawIngest();
            }
        }
    }

    /**
     * Refuses a checkpoint with a row sitting in the exchange.
     *
     * <p>A barrier across the inboxes cuts every stream entering the query. It does not cut the
     * rings lanes send to each other: a row that lane 0 has sent and lane 1 has not yet drained is
     * in neither lane's snapshot and in no source offset either, so it is simply lost. Cutting
     * those too means forwarding the marker along each ring and aligning on it -- Chandy-Lamport
     * proper -- and that is not built.
     *
     * <p>No pipeline this engine compiles sends on the exchange, so this never fires today. It is
     * here because the first one that does must find a refusal rather than a checkpoint that
     * quietly drops rows in flight, which is the failure the whole of ADR-008 exists to prevent.
     */
    private void refuseWhileRowsCrossTheExchange() {
        var exchange = lanes.exchange();
        if (exchange.isPresent() && exchange.get().inFlight() > 0) {
            throw new IllegalStateException("this query has rows in flight between lanes, and a barrier across "
                    + "the inboxes does not cut the exchange: a row already sent and not yet received belongs "
                    + "to neither lane's snapshot and to no source offset, so a checkpoint taken now would "
                    + "lose it silently. Forwarding the marker along the exchange rings is not built, so this "
                    + "checkpoint is refused rather than stored incomplete.");
        }
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
                if (pipeline.isStateful()) {
                    // A stateful operator with nothing to restore is not a no-op. Skipping it
                    // resumes with empty accumulators beside restored source offsets, so every row
                    // before the checkpoint is gone and the query reports RUNNING over the gap.
                    throw new PravahaException(
                            RuntimeErrors.LANE_FAILED,
                            "the checkpoint holds no state for lane " + index + ", and this plan's lane " + index
                                    + " is stateful. Restoring the offsets without the accumulators would resume "
                                    + "past every row the checkpoint covered and answer from an empty operator.");
                }
                continue;
            }
            Lane lane = lanes.lane(index);
            long ticket = lane.submitControlTask(() -> pipeline.restoreState(state));
            if (!lane.awaitControlTask(ticket, timeout)) {
                throw new IllegalStateException("lane " + index + " did not restore its state within " + timeout);
            }
            lane.checkHealth();
        }
        byte[] view = checkpoint.operatorState().get(SERVED_VIEW_STATE);
        if (view != null && viewRestore != null) {
            // After the lanes, so a view restored beside operator state is restored beside state
            // that is already back -- not beside state still arriving on another thread.
            viewRestore.accept(view);
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
            // Before the lanes stop, so a tick cannot arrive at a closed pipeline. Cancelling this
            // query's schedule rather than shutting a clock down: the clock is the process's and
            // every other query is still using it.
            watermarkClock.cancel(true);
        }
        pumps.forEach(IngestPump::close);
        partitionedPumps.forEach(PartitionedIngestPump::close);
        if (hostedQueryId != null) {
            // W9-8. Hosted: the lanes belong to whoever built the group, and other queries are
            // still running on them. Dropping this query's pipelines is the whole of what closing
            // means here.
            //
            // The end-of-input that a lane's close would have run is deliberately not run: finish()
            // fires a stateful query's final windows, and on a shared lane there is no moment at
            // which the *lane* is ending. That is W9-8's remaining half and is recorded there
            // rather than approximated here -- emitting final windows from the dropping thread
            // would write into an arena owned by the lane thread, which is the one thing
            // confinement forbids.
            for (com.ash.messaging.pravaha.runtime.lane.Lane lane : lanes.lanes()) {
                if (lane.processor() instanceof com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer mux) {
                    mux.drop(hostedQueryId);
                }
            }
            return;
        }
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
            // The batch is done and everything it produced has been pushed downstream, so the rows
            // this pipeline allocated are unreachable. Without this the arena only ever grew.
            pipeline.resetArena();
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
