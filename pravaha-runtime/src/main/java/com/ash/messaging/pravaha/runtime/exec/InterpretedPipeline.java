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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.LookupJoinOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.runtime.plan.SinkOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowAssignOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;

/**
 * Compiles a physical plan into a chain of interpreted stages.
 *
 * <p>This is the interpreted half of design section 12.4's guarantee: <strong>every operator has a
 * correct, slow implementation, and correctness never depends on code generation succeeding</strong>.
 * Wave 3 adds the generated path; this one stays, both as the fallback when generation fails or a
 * stage exceeds the JIT's method-size limit, and as the independent implementation the differential
 * tests compare against.
 *
 * <p>Stages are built from the leaf upward and wired downward, so each holds a direct reference to
 * the next. No lookup, no dispatch table, no map.
 */
public final class InterpretedPipeline implements AutoCloseable {

    /**
     * How many slabs of join state one join may hold before the store refuses.
     *
     * <p>64 MB at a megabyte a slab. It is a backstop under the operator's own row ceiling, not the
     * primary bound: the ceiling fails with a row count and a suggestion, this fails with bytes.
     */
    private static final int MAX_JOIN_STATE_SLABS = 64;

    /**
     * ADR-037 item B2: a join whose state exceeds {@link #MAX_JOIN_STATE_SLABS} keeps running,
     * slower, by spilling instead of being refused, once {@link #configureSpill} has been called
     * with settings that enable it.
     *
     * <p>Disabled by default -- the same choice {@code pravaha.pgwire.enabled} makes for TLS, and
     * for the same reason: ADR-037 states plainly what this costs (a second tier a checkpoint must
     * stay consistent with, latency that becomes bimodal and therefore harder to reason about) and
     * asks that a deployment choose it rather than inherit it.
     *
     * <p>A static field rather than a parameter threaded through {@link #compile}, deliberately, and
     * for the same reason {@link #MAX_JOIN_STATE_SLABS} already is one: this is a process-wide,
     * node-level setting, not a per-query one, and every one of {@link #compile}'s many call sites
     * -- most of them tests that have no opinion on spilling at all -- would otherwise need a new
     * argument to say so. What changed from the first cut of this mechanism is <em>where the
     * setting comes from</em>: a system property read directly here was not deployment
     * configuration in the sense every other capability in this codebase has it, so it moved out --
     * see {@link SpillSettings} and {@code pravaha-server}'s {@code StateSpillProperties}, which
     * resolves {@code pravaha.state.spill.*} from {@code application.yaml} and calls {@link
     * #configureSpill} once, at startup.
     */
    private static volatile SpillSettings spillSettings = SpillSettings.DISABLED;

    private static volatile com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess overflowAccess;

    /**
     * Configures ADR-037 item B2's overflow tier for every join compiled from this call onward.
     * Called once, at process start-up, by whatever reads {@code pravaha.state.spill.*} out of
     * configuration; never called by anything in this module's own test suite, whose joins must
     * refuse at their in-memory ceiling exactly as they did before this mechanism existed, so that
     * a test asserting {@code PRV-4001} keeps meaning what it says regardless of what other tests in
     * the same process have configured.
     */
    public static synchronized void configureSpill(SpillSettings settings) {
        spillSettings = java.util.Objects.requireNonNull(settings, "settings");
        // One access for the whole node, so its byte quota (ADR-044) is the node's disk budget
        // across every query rather than a per-query one that multiplies with the query count.
        overflowAccess = settings.enabled()
                ? new com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess(
                        java.nio.file.Path.of(settings.directory()), settings.maxBytes())
                : null;
    }

    /**
     * Bytes of overflow slab mapped on this node now, across every query -- what counts against
     * {@code pravaha.state.spill.max-bytes}. Zero with spilling off.
     */
    public static long spillBytesMapped() {
        var access = overflowAccess;
        return access == null ? 0 : access.bytesMapped();
    }

    /** The settings {@link #configureSpill} was last called with, or {@link SpillSettings#DISABLED}. */
    public static SpillSettings spillSettings() {
        return spillSettings;
    }

    /**
     * Whether pipelines compiled from now on count rows, state and time per operator
     * ({@code pravaha.metrics.operators}).
     *
     * <p><strong>Off by default, and the default is a measurement rather than a preference.</strong>
     * Wrapping every stage costs two extra calls and two increments per row per operator;
     * {@code OperatorMetricsOverheadIT} puts that at about 8 % of a narrow query's throughput on an
     * idle reference machine -- and every reading taken on a busy one was higher -- which is over
     * the bar this project set for something that is on by default. With it off there is no wrapper at all -- not a wrapper that checks a flag -- so
     * a deployment that has not asked for the detail pays nothing for its existence.
     *
     * <p>Process-wide and read at compile time, exactly like {@link #configureSpill}: a query
     * already running when this is switched on does not gain counters, because its stages were
     * built without them. Turning it on and re-registering the query is how the detail is
     * obtained, and saying so is better than pretending the switch is live.
     */
    private static volatile boolean measureOperators;

    /** Turns per-operator measurement on or off for pipelines compiled from now on. */
    public static void measureOperators(boolean measure) {
        measureOperators = measure;
    }

    /** Whether per-operator measurement is on. */
    public static boolean measuringOperators() {
        return measureOperators;
    }

    /**
     * How many looked-up rows one lookup join may cache.
     *
     * <p>Bounded, and access-ordered underneath, because a lookup join's key distribution is
     * usually heavily skewed -- a few keys carry most of the traffic -- and an unbounded cache over
     * an unbounded key space is exactly the memory growth this operator exists to avoid.
     */
    private static final int MAX_LOOKUP_CACHE_ENTRIES = 10_000;

    private final RowArena arena;
    private final RowProcessor head;
    private final List<Runnable> finishers = new ArrayList<>();
    private final List<Runnable> continuousEmitters = new ArrayList<>();
    private final List<WindowedAggregate> windowed = new ArrayList<>();
    private final List<SymmetricHashJoin> joins = new ArrayList<>();
    private final List<LookupJoin> lookupJoins = new ArrayList<>();
    private final List<GlobalAggregate> globals = new ArrayList<>();

    /** Keyed aggregates, in build order, so a debug session can name one and read its groups. */
    private final List<KeyedAggregate> keyed = new ArrayList<>();

    /**
     * Where rows enter, by stream name.
     *
     * <p>One entry until a join appears, two after. Keyed by name rather than by position because
     * the caller has a stream and a row, not a plan: asking it to know which side of the join its
     * topic is on would push a planning detail into the ingest path.
     */
    private final Map<String, RowProcessor> inputs = new LinkedHashMap<>();

    /**
     * ADR-039 item 6: where a pre-combined partial aggregate enters, by the stream it summarises.
     *
     * <p>Populated only when an {@link com.ash.messaging.pravaha.runtime.plan.AggregateOperator}
     * sits directly over a chain of {@link FilterOperator}s and exactly one {@link ScanOperator} --
     * the identical shape {@code SourcePushdown.partialAggregateFor} (pravaha-sql) requires before it
     * will ever build a {@code ReadRequest.PartialAggregate} naming this stream, found the same way
     * that class finds it: by walking the plan, not by asking the aggregate what it was built from.
     * A plan with a computed column, a join, or a window between the scan and the aggregate has no
     * entry here, and {@link #acceptPartialAggregate} refuses by name rather than guessing which
     * aggregate a caller meant.
     */
    private final Map<String, PartialAggregateSink> partialAggregateTargets = new LinkedHashMap<>();

    /** The layout a partial for each of {@link #partialAggregateTargets}' streams arrives in. */
    private final Map<String, StreamSchema> partialAggregateSchemas = new LinkedHashMap<>();

    private final List<ScanOperator> scans;

    /** Where the terminal stage writes; told where each unit of work ends. */
    private final RowOutput output;

    /**
     * One entry per plan node, in {@link com.ash.messaging.pravaha.runtime.plan.PlanNodes} order,
     * or empty when per-operator measurement was off when this pipeline was compiled.
     */
    private final List<OperatorMetrics> operators;

    private InterpretedPipeline(RowArena arena, RowProcessor head, List<ScanOperator> scans, RowOutput output) {
        this(arena, head, scans, output, List.of());
    }

    private InterpretedPipeline(
            RowArena arena,
            RowProcessor head,
            List<ScanOperator> scans,
            RowOutput output,
            List<OperatorMetrics> operators) {
        this.arena = arena;
        this.head = head;
        this.scans = List.copyOf(scans);
        this.output = output;
        this.operators = List.copyOf(operators);
    }

    /**
     * What each operator of this pipeline has done, in plan-node order.
     *
     * <p>Empty when {@link #measureOperators} was off at compile time -- which is not the same as
     * every number being zero, and the caller has to be able to tell those apart.
     */
    public List<OperatorMetrics.Snapshot> operatorMetrics() {
        List<OperatorMetrics.Snapshot> out = new ArrayList<>(operators.size());
        for (OperatorMetrics each : operators) {
            out.add(each.snapshot());
        }
        return out;
    }

    /**
     * Ends one unit of this pipeline's work: everything written since the last call is whole.
     *
     * <p>Called by whatever drives the pipeline row by row -- the lane adapter, after each input
     * batch -- and by this class itself at the end of every other unit it runs on the lane: a
     * watermark advance, a continuous aggregate's emission, draining lookups, end of input. An update
     * is a retraction and an insert written one after the other, and an output read from another
     * thread must never be able to see the first without the second (VIEW-1).
     *
     * <p>Nothing is ended once the pipeline is abandoned: rows written by a pipeline that failed
     * part of the way through a unit are not an answer.
     */
    public void endOfBatch() {
        if (!abandoned) {
            output.endOfBatch();
            compactSpilledState();
            // Per batch, not per row. The operators count into lane-confined longs and this is the
            // one moment they are all consistent with each other, so it is the moment to publish
            // them -- the same bargain Lane strikes with its own counters.
            for (OperatorMetrics each : operators) {
                each.publish();
            }
        }
    }

    /**
     * Gives back overflow slabs that churn has left sparse (ADR-044).
     *
     * <p>Here, at the end of a unit, because this is the one moment the rule compaction needs holds:
     * no operator is in the middle of a row, so no handle into a state store is held anywhere but in
     * the operator's own index -- which is exactly what each operator presents to its store to be
     * rewritten. On the lane thread, like everything else that touches state. A store that has not
     * spilled, or is not fragmented past the threshold, answers from two counters and does nothing.
     *
     * @return how many overflow slabs were released
     */
    int compactSpilledState() {
        double threshold = spillSettings.compactionThreshold();
        int released = 0;
        for (WindowedAggregate aggregate : windowed) {
            released += aggregate.state().compactIfFragmented(threshold);
        }
        for (SymmetricHashJoin join : joins) {
            released += join.compactIfFragmented(threshold);
        }
        return released;
    }

    /**
     * The overflow tier's numbers across this pipeline's joins and windowed aggregates: what is on
     * disk, how much of it is live, and what compaction has done (ADR-044).
     */
    public com.ash.messaging.pravaha.state.SpillStatistics spillStatistics() {
        com.ash.messaging.pravaha.state.SpillStatistics total = com.ash.messaging.pravaha.state.SpillStatistics.NONE;
        for (WindowedAggregate aggregate : windowed) {
            total = total.plus(aggregate.state().spillStatistics());
        }
        for (SymmetricHashJoin join : joins) {
            total = total.plus(join.spillStatistics());
        }
        return total;
    }

    /**
     * Builds a pipeline.
     *
     * @param plan the physical plan, whose root must be a sink
     * @param sink where the terminal stage writes
     */
    /**
     * A slab sized for this plan's own rows rather than a flat megabyte.
     *
     * <p>It was {@code 1 << 20} with 64 slabs for every plan, which measured as 1,024 KiB per active
     * query -- half of everything a query holds off-heap, and a gigabyte of it at a thousand queries
     * (W9-7). A pipeline's arena holds rows it is building; what it needs is a batch of them, and a
     * narrow projection's batch is a few hundred kilobytes.
     *
     * <p>Two floors, both about not turning a memory saving into a refusal. A row larger than a slab
     * is refused at allocation whatever the total, so a slab must clear the widest plausible row --
     * and a schema cannot say how wide a variable-width value will be, so each is allowed 512 bytes.
     * Below 64 KiB the saving stops mattering and the risk starts to.
     *
     * <p>{@link #slabsFor} keeps the ceiling where it was, so this narrows what a query reserves and
     * not what it may grow into.
     */
    private static int slabFor(PhysicalOperator plan) {
        RowLayout layout = RowLayout.of(plan.outputSchema());
        long perRow = layout.rowSize(layout.variableFieldCount() * 512);
        long batch = perRow * com.ash.messaging.pravaha.runtime.lane.LaneConfig.DEFAULT_BATCH_SIZE;
        return (int) Math.max(64L * 1024, Math.min(batch, 1L << 20));
    }

    /** Slabs enough to keep the 64 MiB ceiling the flat megabyte gave, in whatever step size. */
    private static int slabsFor(PhysicalOperator plan) {
        long ceiling = 64L * (1L << 20);
        return (int) Math.max(4, Math.min(1024, ceiling / slabFor(plan)));
    }

    public static InterpretedPipeline compile(PhysicalOperator plan, RowOutput sink) {
        return compile(plan, sink, Map.of());
    }

    /**
     * Builds a pipeline whose lookup joins are bound to real dimension tables.
     *
     * @param lookups by the registered stream name the query joined against. A plan that names one
     *     this map does not have fails here, at start-up, rather than on the first record -- a
     *     lookup join that discovers its table is missing after an hour of running has already
     *     produced an hour of output that should not exist
     */
    public static InterpretedPipeline compile(
            PhysicalOperator plan, RowOutput sink, Map<String, LookupSourcePlugin> lookups) {
        return compile(plan, sink, lookups, false);
    }

    /**
     * Builds a pipeline that measures its operators whether or not the node asked for it (ADR-048).
     *
     * <p>{@code measured} false is every other caller and leaves {@link #measureOperators} to
     * decide, which is the node-wide setting. A debug fork passes true: a session exists to say
     * which operator did what to which row, and that answer cannot depend on whether an operator
     * happened to turn {@code pravaha.metrics.operators} on beforehand. It is one lane and one
     * person stepping, so the measurement costs nothing anybody is counting.
     */
    public static InterpretedPipeline compile(
            PhysicalOperator plan, RowOutput sink, Map<String, LookupSourcePlugin> lookups, boolean measured) {
        RowArena arena = new RowArena(MemoryAccess.best(), slabFor(plan), slabsFor(plan));
        Builder builder = new Builder(arena, sink, lookups, measured || measureOperators ? plan : null);
        RowProcessor built = builder.build(plan);
        RowProcessor head = builder.joins.isEmpty() ? built : null;
        InterpretedPipeline pipeline =
                new InterpretedPipeline(arena, head, builder.scans, sink, builder.operatorsInPlanOrder());
        pipeline.finishers.addAll(builder.finishers);
        pipeline.continuousEmitters.addAll(builder.continuousEmitters);
        pipeline.windowed.addAll(builder.windowed);
        pipeline.joins.addAll(builder.joins);
        pipeline.lookupJoins.addAll(builder.lookupJoins);
        pipeline.globals.addAll(builder.globals);
        pipeline.keyed.addAll(builder.keyed);
        pipeline.inputs.putAll(builder.heads);
        pipeline.partialAggregateTargets.putAll(builder.partialAggregateTargets);
        pipeline.partialAggregateSchemas.putAll(builder.partialAggregateSchemas);
        return pipeline;
    }

    /**
     * The stream this pipeline reads.
     *
     * @throws IllegalStateException if there is more than one, which a caller assuming a single
     *     source needs to hear about rather than be given an arbitrary one of them
     */
    public String sourceStream() {
        return only().streamName();
    }

    /** The schema rows must arrive in, for a single-source pipeline. */
    public StreamSchema inputSchema() {
        return only().outputSchema();
    }

    /** Every stream this pipeline reads, in plan order: left before right for a join. */
    public List<String> sourceStreams() {
        return scans.stream().map(ScanOperator::streamName).toList();
    }

    /** The schema rows of one named stream must arrive in. */
    public StreamSchema inputSchema(String streamName) {
        return scans.stream()
                .filter(s -> s.streamName().equals(streamName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "this pipeline does not read '" + streamName + "'; it reads " + sourceStreams()))
                .outputSchema();
    }

    private ScanOperator only() {
        if (scans.size() != 1) {
            throw new IllegalStateException("this pipeline reads " + sourceStreams()
                    + "; use the stream-qualified form to say which one a row belongs to");
        }
        return scans.get(0);
    }

    /** Feeds one row through a single-source pipeline. */
    public void accept(RowView row) {
        if (head == null) {
            throw new IllegalStateException("this pipeline reads " + sourceStreams()
                    + "; use accept(streamName, row) to say which side a row arrived on");
        }
        head.process(row);
    }

    /** Feeds one row in on a named stream. */
    public void accept(String streamName, RowView row) {
        RowProcessor input = inputs.get(streamName);
        if (input == null) {
            throw new IllegalArgumentException(
                    "'" + streamName + "' is not an input of this pipeline; it reads " + sourceStreams());
        }
        input.process(row);
    }

    /**
     * ADR-039 item 6: whether this pipeline has an aggregate eligible to receive a pre-combined
     * partial for {@code streamName} -- the same question {@code SourcePushdown.partialAggregateFor}
     * answers before it ever builds a {@code ReadRequest} naming this stream. A caller holding a
     * {@code PartitionReader} that honoured a partial-aggregate request should check this before
     * calling {@link #acceptPartialAggregate}, exactly as it already knows which stream a row
     * arrived on before calling {@link #accept(String, RowView)}.
     */
    public boolean acceptsPartialAggregateFor(String streamName) {
        return partialAggregateTargets.containsKey(streamName);
    }

    /**
     * The layout a partial for {@code streamName} is written in: the aggregate's own output schema,
     * group keys then one column per call -- what a reader honouring a partial-aggregate request
     * is handed a writer for.
     *
     * @throws IllegalStateException when {@link #acceptsPartialAggregateFor} would say false
     */
    public StreamSchema partialAggregateSchema(String streamName) {
        StreamSchema schema = partialAggregateSchemas.get(streamName);
        if (schema == null) {
            throw new IllegalStateException("'" + streamName + "' has no aggregate eligible for a partial in this "
                    + "pipeline, so there is no partial layout to write");
        }
        return schema;
    }

    /**
     * Folds a pre-combined partial into the aggregate reading {@code streamName}, exactly as if the
     * rows it summarises had each arrived with this weight -- see {@code ReadRequest.PartialAggregate}
     * and {@link GlobalAggregate#processPartial}/{@link KeyedAggregate#processPartial} for the
     * arithmetic. {@code weight} negative retracts the partial's own contribution, which is the
     * property restricting this to {@code COUNT} and {@code SUM} exists to preserve: both are
     * invertible by subtraction, so undoing a whole partial is the same arithmetic as undoing one
     * row, scaled.
     *
     * @param partial one column per {@link com.ash.messaging.pravaha.runtime.plan.AggregateOperator}
     *     group-key ordinal (in order), then one column per aggregate call (in {@code
     *     operator.aggregates()} order) -- the same layout the aggregate's own {@code emit} writes,
     *     since a partial is exactly a miniature aggregate result
     * @throws IllegalStateException if {@link #acceptsPartialAggregateFor} would say false for this
     *     stream -- a partial arriving for a plan shape that never asked for one is a bug in
     *     whichever plugin sent it, not something to silently drop or misattribute
     */
    public void acceptPartialAggregate(String streamName, RowView partial, long weight) {
        PartialAggregateSink target = partialAggregateTargets.get(streamName);
        if (target == null) {
            throw new IllegalStateException("'" + streamName + "' has no aggregate eligible for a partial in this "
                    + "pipeline; acceptsPartialAggregateFor(streamName) would have said so before this was called");
        }
        target.accept(partial, weight);
    }

    /** Where {@link #acceptPartialAggregate} delivers to -- {@code GlobalAggregate::processPartial}
     * or {@code KeyedAggregate::processPartial}, never anything that also reads raw rows. */
    @FunctionalInterface
    private interface PartialAggregateSink {
        void accept(RowView partial, long weight);
    }

    private volatile boolean abandoned;

    /**
     * Signals end of input, so stateful stages emit.
     *
     * <p>Does nothing once {@link #abandon()} has been called. End of input is a statement that no
     * more records will arrive and the held state should be reported; a process that is dying is
     * making no such statement, and emitting on the way out would produce results that the last
     * checkpoint does not know about.
     */
    public void finish() {
        if (abandoned) {
            return;
        }
        finishers.forEach(Runnable::run);
        endOfBatch();
    }

    /**
     * Publishes the current answer of every unwindowed aggregate.
     *
     * <p>Called on the watermark tick, on the lane's own thread. Each emitter retracts the answer it
     * published last and inserts the new one, so the view holds one row rather than one per tick.
     */
    public void emitContinuousAggregates() {
        if (abandoned) {
            return;
        }
        continuousEmitters.forEach(Runnable::run);
        // One unit: each emitter's retraction of its last answer and insert of the new one reach
        // the output together or not at all (VIEW-1).
        endOfBatch();
    }

    /** Whether this pipeline has anything to publish on a tick. */
    public boolean hasContinuousAggregates() {
        return !continuousEmitters.isEmpty();
    }

    /**
     * The pieces of state in this pipeline that can be looked at, and how much each holds (ADR-048).
     *
     * <p>Ids are positional within a kind -- {@code aggregate#0}, {@code join#1.left} -- and are
     * stable for the life of a pipeline because the builder wires the plan in a fixed order. They
     * are not the plan's operator ids: the plan has operators with no state at all, and a join has
     * two indexes rather than one.
     *
     * <p>Called on the lane's own thread. Everything it reads is written there, and reading it from
     * anywhere else is a second thread on a lane's state -- which is the rule this whole runtime is
     * built around. See {@link OperatorStateReader}.
     */
    public List<OperatorState.Slot> stateSlots() {
        List<OperatorState.Slot> slots = new ArrayList<>();
        for (int index = 0; index < keyed.size(); index++) {
            slots.add(new OperatorState.Slot(
                    "aggregate#" + index,
                    "aggregate",
                    "groups",
                    keyed.get(index).groupCount()));
        }
        for (int index = 0; index < globals.size(); index++) {
            slots.add(new OperatorState.Slot("global#" + index, "global", "accumulators", 1));
        }
        for (int index = 0; index < windowed.size(); index++) {
            slots.add(new OperatorState.Slot(
                    "window#" + index,
                    "window",
                    "windows retained",
                    windowed.get(index).retainedWindows()));
        }
        for (int index = 0; index < joins.size(); index++) {
            SymmetricHashJoin join = joins.get(index);
            slots.add(new OperatorState.Slot(
                    "join#" + index + ".left", "join", join.label() + " left", join.distinctRowsHeld(true)));
            slots.add(new OperatorState.Slot(
                    "join#" + index + ".right", "join", join.label() + " right", join.distinctRowsHeld(false)));
        }
        return List.copyOf(slots);
    }

    /**
     * One page of one operator's state, filtered by key (ADR-048).
     *
     * <p>Bounded in memory as well as in what it returns: entries outside the page are counted and
     * discarded as the walk goes, so paging a join holding a million rows costs the walk and a page,
     * not a million rendered rows.
     *
     * @param keyFilter a key to show, or null or blank for every key
     */
    public OperatorState.Page inspectState(String id, String keyFilter, int offset, int limit) {
        String wanted = keyFilter == null || keyFilter.isBlank() ? null : keyFilter;
        List<OperatorState.Entry> page = new ArrayList<>();
        long[] seen = {0};
        java.util.function.BiConsumer<String, Map<String, String>> collector = (key, values) -> {
            if (wanted != null && !wanted.equals(key)) {
                return;
            }
            long index = seen[0]++;
            if (index >= offset && page.size() < limit) {
                page.add(new OperatorState.Entry(key, values));
            }
        };
        String kind = describeInto(id, collector);
        return new OperatorState.Page(id, kind, wanted, offset, limit, seen[0], page);
    }

    /** Routes {@code id} to the operator that holds it, returning its kind. */
    private String describeInto(String id, java.util.function.BiConsumer<String, Map<String, String>> collector) {
        if (id != null && id.startsWith("aggregate#")) {
            keyed.get(ordinalOf(id, "aggregate#", keyed.size())).describe(collector);
            return "aggregate";
        }
        if (id != null && id.startsWith("global#")) {
            globals.get(ordinalOf(id, "global#", globals.size())).describe(collector);
            return "global";
        }
        if (id != null && id.startsWith("window#")) {
            windowed.get(ordinalOf(id, "window#", windowed.size())).describe(collector);
            return "window";
        }
        if (id != null && id.startsWith("join#")) {
            boolean left = !id.endsWith(".right");
            String ordinal = id.substring("join#".length()).replace(".left", "").replace(".right", "");
            joins.get(ordinalOf("join#" + ordinal, "join#", joins.size())).describe(left, collector);
            return "join";
        }
        throw new IllegalArgumentException("'" + id + "' is not a piece of state in this query. It holds "
                + stateSlots().stream().map(OperatorState.Slot::id).toList());
    }

    private static int ordinalOf(String id, String prefix, int count) {
        int ordinal;
        try {
            ordinal = Integer.parseInt(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + id + "' does not name a " + prefix + "N operator");
        }
        if (ordinal < 0 || ordinal >= count) {
            throw new IllegalArgumentException("'" + id + "' is out of range: this query has " + count + " of them");
        }
        return ordinal;
    }

    /** Rows this pipeline's joins have released for falling outside their match window. */
    public long joinRowsEvicted() {
        return joins.stream().mapToLong(SymmetricHashJoin::evicted).sum();
    }

    /**
     * Distinct rows currently held by this pipeline's joins, both sides together.
     *
     * <p>Distinct, because that is what costs memory: two identical arrivals are one entry of
     * weight 2 in one block.
     *
     * <p>Exposed because a join's most dangerous failure is invisible in its output. An entry whose
     * weight has cancelled to zero emits nothing and matches nothing -- the results stay correct --
     * but if it is not unlinked it holds its block forever, and the query dies of memory exhaustion
     * hours later with no wrong answer to point at. This number is what a test, or an operator, can
     * watch to see that retracted rows actually leave.
     */
    public long joinRowsHeld() {
        long total = 0;
        for (SymmetricHashJoin join : joins) {
            total += join.rowsHeldLeft() + join.rowsHeldRight();
        }
        return total;
    }

    /**
     * Distinct join keys currently indexed, across this pipeline's joins.
     *
     * <p>Separate from {@link #joinRowsHeld()} because the index leaks separately. Unlinking a key's
     * last row without removing the key leaves an empty bucket behind, and a query that sees a
     * million keys over its lifetime then holds a million buckets whose rows are all long gone --
     * with the row count reading zero the whole time.
     */
    public long joinKeysHeld() {
        long total = 0;
        for (SymmetricHashJoin join : joins) {
            total += join.keysHeldLeft() + join.keysHeldRight();
        }
        return total;
    }

    /** Bytes this pipeline's joins have taken for state. */
    public long joinStateBytes() {
        long total = 0;
        for (SymmetricHashJoin join : joins) {
            total += join.stateBytes();
        }
        return total;
    }

    /**
     * Whether any of this pipeline's joins has spilled to its overflow tier (ADR-037 item B2).
     * False when spilling was never configured, exactly as it is when a join has simply not
     * reached its ceiling -- both mean the same thing to an operator: nothing to look at yet.
     */
    public boolean joinsHaveSpilled() {
        for (SymmetricHashJoin join : joins) {
            if (join.hasSpilled()) {
                return true;
            }
        }
        return false;
    }

    /** Whether any of this pipeline's windowed aggregates has spilled to its overflow tier
     * (ADR-037 item B2) -- accumulators or, since ADR-044, {@code COUNT(DISTINCT ...)}'s values. */
    public boolean windowedStateHasSpilled() {
        for (WindowedAggregate aggregate : windowed) {
            if (aggregate.hasSpilled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finishes any outstanding lookups without ending the query.
     *
     * <p>For a lane that has run out of work: a record parked on a network round trip would
     * otherwise wait for the next arrival to push it out, so a stream that goes quiet leaves its
     * last few records unanswered for as long as the quiet lasts. The same latency bug as a
     * watermark that only advances when something arrives.
     */
    public void drainPending() {
        for (LookupJoin join : lookupJoins) {
            join.drain();
        }
        endOfBatch();
    }

    /** Lookups served from cache rather than from the store. */
    public long lookupCacheHits() {
        long total = 0;
        for (LookupJoin join : lookupJoins) {
            total += join.cacheHitCount();
        }
        return total;
    }

    /** Lookups that reached the store. */
    public long lookupCalls() {
        long total = 0;
        for (LookupJoin join : lookupJoins) {
            total += join.lookupCount();
        }
        return total;
    }

    /** Records whose key matched nothing in the dimension table. */
    public long lookupMisses() {
        long total = 0;
        for (LookupJoin join : lookupJoins) {
            total += join.unmatchedCount();
        }
        return total;
    }

    /**
     * The most lookups outstanding at once.
     *
     * <p>The number that says whether the waits are actually overlapping. One means every lookup
     * finished before the next began, which is the shape this operator exists to avoid and is
     * invisible in any assertion about output.
     */
    public long peakLookupsInFlight() {
        long peak = 0;
        for (LookupJoin join : lookupJoins) {
            peak = Math.max(peak, join.peakInFlight());
        }
        return peak;
    }

    /** Marks this pipeline as failed rather than finished: nothing more is emitted. */
    public void abandon() {
        abandoned = true;
    }

    /**
     * Advances event time, firing any window that has completed.
     *
     * <p>Separate from {@link #finish()} because they answer different questions: a watermark says
     * "no earlier record will arrive", and end of input says "no record will arrive at all". A
     * continuous query only ever gets the first; a file source gets both.
     */
    public void advanceWatermark(long watermarkNanos) {
        // Outstanding lookups first, and this ordering is load-bearing. A record parked on a
        // network round trip has been consumed but not yet placed in a window; letting a watermark
        // past it fires the window without it, and the record then arrives as late data for a
        // window that has already closed. With zero allowed lateness -- the default -- it is
        // dropped outright, so an enriched query would quietly lose exactly the records whose
        // lookups were slowest. Found by the end-to-end Aerospike test, where every record was in
        // flight when the watermark advanced and the query produced nothing at all.
        drainPending();
        windowed.forEach(aggregate -> aggregate.advanceWatermark(watermarkNanos));
        // Joins too, and for the same reason windows need it: a watermark is what says a row can no
        // longer be part of any match, which is the only thing that makes a stream-to-stream join
        // survivable. Without this the join held every unmatched row until a size ceiling failed
        // the query.
        joins.forEach(join -> join.advanceWatermark(watermarkNanos));
        // Every operator, because an advance reaches all of them in this one call: a plan's nodes
        // cannot hold different watermarks, and publishing one per node is what lets a console
        // show the figure beside the operator an operator is looking at rather than only at the
        // top of the page. Recorded after the advance, so a node that threw does not claim it.
        for (OperatorMetrics each : operators) {
            each.reachedWatermark(watermarkNanos);
        }
        // Every window this advance closed, with any correction it retracted, as one unit.
        endOfBatch();
    }

    /**
     * Where records too late to correct any window are sent.
     *
     * <p>A named side output rather than a drop, because "0.2 % of records arrived after their
     * window was released" is a diagnosis and a missing record is not. This is the seam the
     * dead-letter queue attaches to (design section 15.6).
     */
    public void lateOutput(java.util.function.Consumer<com.ash.messaging.pravaha.api.data.RowView> sink) {
        windowed.forEach(aggregate -> aggregate.lateOutput(sink));
    }

    /**
     * Snapshots every stateful operator in this pipeline.
     *
     * <p>Must be called on the thread that owns the pipeline -- the lane thread, between batches --
     * for the same reason a lane's arena is not shared: reading state from elsewhere while the lane
     * mutates it produces a snapshot of no moment in particular.
     */
    /**
     * Identifies a snapshot as ours.
     *
     * <p>Without it, bytes that were not a snapshot at all would be read as row counts and lengths,
     * and the engine would resume from whatever that produced. A checkpoint the engine believes is
     * worse than one it refuses, which is the same reasoning {@code CheckpointStore} uses about
     * half-written files.
     */
    private static final int SNAPSHOT_MAGIC = 0x50565354;

    /**
     * The operator snapshot layout.
     *
     * <p>Bumped when any operator's serialised form changes. Version 2 added the per-row matched flag
     * that outer joins need. An older snapshot is refused rather than read: the fields would parse,
     * in the wrong places, and the query would resume from state that is wrong without looking wrong.
     */
    /**
     * Version 3: the windowed aggregate's {@code emitted} map dropped its 64-bit key field.
     *
     * <p>It wrote the group twice -- once as a fold of two digests and once as the key columns
     * themselves -- and the fold is gone, so the layout changed. A version 2 snapshot is refused
     * rather than read, which is what this field is for: the alternative is parsing one field as
     * another and resuming from state that is wrong without being obviously wrong.
     */
    /**
     * Version 4: unwindowed aggregates, after the joins.
     *
     * <p>They were in no snapshot at all, and a pipeline holding only one reported itself stateless,
     * so its lane put nothing in a checkpoint: a restart restored the view and resumed the source
     * past every row the view counted, with the accumulators at zero (CKPT-2).
     *
     * <p>A version 3 snapshot is still read, since its layout is version 4's without the aggregates
     * section -- but only into a plan with no unwindowed aggregate. Into one with an aggregate it
     * would restore that aggregate empty, which is the defect version 4 exists to close.
     */
    /**
     * Version 5: a windowed aggregate's own section changed (ADR-044). Its accumulators carry their
     * non-null counts, and {@code COUNT(DISTINCT)}'s values follow them as a section of their own
     * instead of an on-heap set inside each accumulator -- which is what lets them spill.
     *
     * <p>Only the windowed sections changed, so a version 4 or 3 snapshot is still read into a plan
     * with no windowed aggregate (and, for version 3, no unwindowed one either), where the layouts are
     * the same bytes. Into a windowed plan it is refused here, by version, rather than further in by
     * the aggregate's own format check.
     */
    private static final int SNAPSHOT_VERSION = 5;

    /** The last layout with the old windowed-aggregate section, readable into a plan with no windowed aggregate. */
    private static final int SNAPSHOT_VERSION_WITH_OLD_WINDOWS = 4;

    /** The last layout without unwindowed aggregates, readable into a plan that has neither those nor windows. */
    private static final int SNAPSHOT_VERSION_WITHOUT_GLOBALS = 3;

    public byte[] snapshotState() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeInt(SNAPSHOT_VERSION);
            out.writeInt(windowed.size());
            for (WindowedAggregate aggregate : windowed) {
                aggregate.writeTo(out);
            }
            out.writeInt(joins.size());
            for (SymmetricHashJoin join : joins) {
                join.writeTo(out);
            }
            out.writeInt(globals.size());
            for (GlobalAggregate aggregate : globals) {
                aggregate.writeTo(out);
            }
        } catch (java.io.IOException e) {
            throw new PravahaException(RuntimeErrors.LANE_FAILED, "cannot snapshot this pipeline's state: " + e, e);
        }
        return bytes.toByteArray();
    }

    /**
     * Restores state written by {@link #snapshotState()}.
     *
     * <p>Refuses a snapshot whose operator count differs, rather than restoring what it can. A
     * partially-restored pipeline resumes with some operators holding history and others empty,
     * which produces answers that are wrong in a way no downstream check would catch.
     */
    public void restoreState(byte[] snapshot) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(snapshot))) {
            int magic = in.readInt();
            if (magic != SNAPSHOT_MAGIC) {
                throw new PravahaException(
                        RuntimeErrors.LANE_FAILED,
                        "this is not a Pravaha operator snapshot. Restoring it would read whatever bytes "
                                + "these are as rows and weights, and the result would be believed rather "
                                + "than rejected.");
            }
            int version = in.readInt();
            boolean withoutGlobals =
                    version == SNAPSHOT_VERSION_WITHOUT_GLOBALS && globals.isEmpty() && windowed.isEmpty();
            boolean oldWindowsButNone = version == SNAPSHOT_VERSION_WITH_OLD_WINDOWS && windowed.isEmpty();
            if (version != SNAPSHOT_VERSION && !withoutGlobals && !oldWindowsButNone) {
                throw new PravahaException(
                        RuntimeErrors.LANE_FAILED,
                        "this snapshot is version " + version + " and this engine writes version " + SNAPSHOT_VERSION
                                + ". The layouts differ, so restoring it would parse one field as another and "
                                + "resume from state that is wrong without being obviously wrong. Replay the "
                                + "stream from a source offset instead.");
            }
            int operators = in.readInt();
            if (operators != windowed.size()) {
                throw new PravahaException(
                        RuntimeErrors.LANE_FAILED,
                        "the checkpoint holds " + operators + " stateful operators and this plan has "
                                + windowed.size() + ": the query changed since the checkpoint was taken, and "
                                + "restoring part of it would resume with some operators holding history and "
                                + "others empty.");
            }
            for (WindowedAggregate aggregate : windowed) {
                aggregate.readFrom(in);
            }

            int joinCount = in.readInt();
            if (joinCount != joins.size()) {
                throw new PravahaException(
                        RuntimeErrors.LANE_FAILED,
                        "the checkpoint holds " + joinCount + " joins and this plan has " + joins.size()
                                + ": the query changed since the checkpoint was taken, and a join restored "
                                + "against a different plan would match rows against the wrong side.");
            }
            for (SymmetricHashJoin join : joins) {
                join.readFrom(in);
            }

            int globalCount = withoutGlobals ? 0 : in.readInt();
            if (globalCount != globals.size()) {
                throw new PravahaException(
                        RuntimeErrors.LANE_FAILED,
                        "the checkpoint holds " + globalCount + " unwindowed aggregates and this plan has "
                                + globals.size() + ": the query changed since the checkpoint was taken, and an "
                                + "aggregate resumed from nothing beside a restored view publishes its next "
                                + "answer next to the one the view already holds.");
            }
            for (GlobalAggregate aggregate : globals) {
                aggregate.readFrom(in);
            }
        } catch (java.io.IOException e) {
            throw new PravahaException(RuntimeErrors.LANE_FAILED, "cannot restore pipeline state: " + e, e);
        }
    }

    /**
     * How much state this pipeline holds, against what it is allowed.
     *
     * <p>The operators knew these numbers all along -- {@code SlicedAggregateState.liveSlices()} and
     * {@code maxSlices()} have always been there -- and nothing carried them anywhere. So an
     * operator met the number for the first time in the message saying their query was dead, and a
     * query sitting at nine tenths of its ceiling was invisible. For the one failure that arrives as
     * a surprise, that is exactly the wrong way round (ADR-037).
     */
    public StateUsage stateUsage() {
        long held = 0;
        long ceiling = 0;
        for (WindowedAggregate aggregate : windowed) {
            held += aggregate.state().liveSlices();
            ceiling += aggregate.state().maxSlices();
        }
        for (SymmetricHashJoin join : joins) {
            held += join.rowsHeldLeft() + join.rowsHeldRight();
            ceiling += 2L * join.rowCeilingPerSide();
        }
        return new StateUsage(held, ceiling);
    }

    /**
     * What a query holds and what it may hold, in the units the ceiling is expressed in.
     *
     * @param held accumulators and join rows currently live
     * @param ceiling what those would be refused at; zero where a plan has no bounded state at all
     */
    public record StateUsage(long held, long ceiling) {

        /** How full, from 0 to 1. Zero where there is no ceiling, which is not the same as empty. */
        public double fraction() {
            return ceiling == 0 ? 0 : (double) held / ceiling;
        }
    }

    /**
     * Off-heap this pipeline's own arena holds.
     *
     * <p>A pipeline has an arena of its own, separate from the lane's, and until W9-7 went looking
     * nothing reported it: the lane's accounting does not see it, so a node's per-query memory had a
     * megabyte in it that no component would claim.
     */
    public long arenaBytes() {
        return arena.bytesAllocated();
    }

    /** Whether this pipeline holds any state worth checkpointing. */
    public boolean isStateful() {
        return !windowed.isEmpty() || !joins.isEmpty() || !globals.isEmpty();
    }

    /** Records dropped as too late, across every windowed operator in this pipeline. */
    public long lateRecords() {
        return windowed.stream().mapToLong(WindowedAggregate::lateRecords).sum();
    }

    /** Windows re-emitted because a late record corrected them. */
    public long corrections() {
        return windowed.stream().mapToLong(WindowedAggregate::corrections).sum();
    }

    /** The arena stages allocate their output rows in. */
    public RowArena arena() {
        return arena;
    }

    /**
     * Reclaims the rows this pipeline allocated while processing a batch.
     *
     * <p>Every stage that produces a row -- project, compute, aggregate, join output -- allocates it
     * here and pushes it downstream within the same call, so once a batch has been processed none of
     * that memory is reachable. Nothing ever reclaimed it: this arena is created in {@code compile}
     * and the lane resets a different one, so a query accumulated its own output until the arena was
     * exhausted. Measured at 933,033 rows for a projection and 600,129 for a wider one -- 64 MiB
     * either way -- after which the lane died and the query went on reporting RUNNING.
     *
     * <p>Safe at a batch boundary and nowhere else. Anything that must outlive a batch already
     * copies: the join keeps its own region, the lookup join parks records in a separate one, and
     * {@code LookupJoin}'s own javadoc describes this arena as the one "which rewinds at the end of
     * every batch" -- which it did not.
     */
    public void resetArena() {
        if (!abandoned) {
            arena.reset();
        }
    }

    @Override
    public void close() {
        joins.forEach(SymmetricHashJoin::close);
        // ADR-037 item B2: a windowed aggregate owns off-heap state -- COUNT(DISTINCT ...)'s values
        // included since ADR-044 -- and, like a join's, it has to be released rather than left to
        // the collector. Before B2, WindowedAggregate held nothing that needed it.
        windowed.forEach(WindowedAggregate::close);
        lookupJoins.forEach(LookupJoin::close);
        arena.close();
    }

    /** Wires the stages, leaf first. */
    private static final class Builder {
        private final RowArena arena;
        private final RowOutput sink;
        private final List<Runnable> finishers = new ArrayList<>();
        private final List<Runnable> continuousEmitters = new ArrayList<>();
        private final List<WindowedAggregate> windowed = new ArrayList<>();
        private final List<SymmetricHashJoin> joins = new ArrayList<>();
        private final List<LookupJoin> lookupJoins = new ArrayList<>();
        private final List<GlobalAggregate> globals = new ArrayList<>();
        private final List<KeyedAggregate> keyed = new ArrayList<>();
        private final List<ScanOperator> scans = new ArrayList<>();
        private final Map<String, RowProcessor> heads = new LinkedHashMap<>();
        private final Map<String, PartialAggregateSink> partialAggregateTargets = new LinkedHashMap<>();
        private final Map<String, StreamSchema> partialAggregateSchemas = new LinkedHashMap<>();

        private final Map<String, LookupSourcePlugin> lookups;

        /**
         * A counter block per plan node, by identity, or empty when measurement is off.
         *
         * <p>By identity rather than by value because a physical operator is a record: two filters
         * with the same predicate over the same schema are {@code equals}, and a value-keyed map
         * would merge their counters into one box on the graph.
         */
        private final java.util.IdentityHashMap<PhysicalOperator, OperatorMetrics> counters =
                new java.util.IdentityHashMap<>();

        /** The same blocks in plan-node order, which is the order their ids are assigned in. */
        private final List<OperatorMetrics> ordered = new ArrayList<>();

        /** Null when measurement is off, which is what removes the wrappers entirely. */
        private final OperatorClock clock;

        Builder(RowArena arena, RowOutput sink, Map<String, LookupSourcePlugin> lookups, PhysicalOperator measured) {
            this.arena = arena;
            this.sink = sink;
            this.lookups = lookups;
            this.clock = measured == null ? null : new OperatorClock();
            if (measured != null) {
                List<PhysicalOperator> nodes = com.ash.messaging.pravaha.runtime.plan.PlanNodes.preOrder(measured);
                for (int i = 0; i < nodes.size(); i++) {
                    PhysicalOperator node = nodes.get(i);
                    OperatorMetrics metrics = new OperatorMetrics(
                            com.ash.messaging.pravaha.runtime.plan.PlanNodes.idOf(i), kindOf(node), node.label());
                    ordered.add(metrics);
                    // putIfAbsent, because a plan that reuses one operator instance in two places
                    // is one box on the graph and must be one counter here too.
                    counters.putIfAbsent(node, metrics);
                }
            }
        }

        List<OperatorMetrics> operatorsInPlanOrder() {
            return ordered;
        }

        private static String kindOf(PhysicalOperator operator) {
            String simple = operator.getClass().getSimpleName();
            return simple.endsWith("Operator") && simple.length() > "Operator".length()
                    ? simple.substring(0, simple.length() - "Operator".length())
                    : simple;
        }

        /** The rows {@code operator} emits, counted. The downstream itself when measurement is off. */
        private RowProcessor leaving(PhysicalOperator operator, RowProcessor downstream) {
            OperatorMetrics metrics = counters.get(operator);
            return metrics == null ? downstream : metrics.leaving(downstream);
        }

        /** The rows {@code operator} consumes, counted. {@code self} itself when measurement is off. */
        private RowProcessor entering(PhysicalOperator operator, RowProcessor self) {
            OperatorMetrics metrics = counters.get(operator);
            return metrics == null ? self : metrics.entering(self, clock, operator instanceof ScanOperator);
        }

        /** Says where {@code operator}'s state bytes are read from, when it holds any. */
        private void holdsState(PhysicalOperator operator, java.util.function.LongSupplier bytes) {
            OperatorMetrics metrics = counters.get(operator);
            if (metrics != null) {
                metrics.holdsStateIn(bytes);
            }
        }

        RowProcessor build(PhysicalOperator operator) {
            return switch (operator) {
                case WindowAssignOperator w -> {
                    RowProcessor terminal = row -> copyInto(sink, row, w.outputSchema());
                    yield buildInput(w, terminal);
                }
                case WindowedAggregateOperator w -> {
                    RowProcessor terminal = row -> copyInto(sink, row, w.outputSchema());
                    yield buildInput(w, terminal);
                }
                case SinkOperator s -> {
                    RowProcessor terminal = row -> copyInto(sink, row, s.outputSchema());
                    yield buildInput(s, terminal);
                }
                case ScanOperator s -> buildInput(s, row -> {});
                default -> {
                    // A plan whose root is not a sink still has to run for tests and EXPLAIN; treat
                    // the root's output as the result.
                    RowProcessor terminal = row -> copyInto(sink, row, operator.outputSchema());
                    yield buildInput(operator, terminal);
                }
            };
        }

        /**
         * Builds {@code operator} pushing into {@code downstream}, returning the chain's head.
         *
         * <p>Each case computes the stage itself and then recurses into its input with that stage
         * as the downstream. The two counting wrappers hang off exactly those two points: {@link
         * #leaving} on what the stage pushes into, {@link #entering} on the stage as its input sees
         * it. With measurement off both are the identity and the tree is the one it always was.
         */
        private RowProcessor buildInput(PhysicalOperator operator, RowProcessor into) {
            RowProcessor downstream = leaving(operator, into);
            return switch (operator) {
                case ScanOperator s -> {
                    RowProcessor entry = entering(s, downstream);
                    registerScan(s, entry);
                    yield entry;
                }
                case FilterOperator f -> {
                    RowProcessor self = row -> {
                        if (f.predicate().test(row)) {
                            downstream.process(row);
                        }
                    };
                    yield buildInput(f.input(), entering(f, self));
                }
                case ProjectOperator p -> {
                    RowProcessor self = projector(p, downstream);
                    yield buildInput(p.input(), entering(p, self));
                }
                case ComputeOperator c -> {
                    RowProcessor self = computer(c, downstream);
                    yield buildInput(c.input(), entering(c, self));
                }
                case AggregateOperator a -> {
                    // Keyed and unkeyed are different operators rather than one with a branch: the
                    // unkeyed case is a fixed row of state and the keyed case is a map, and pretending
                    // they are the same is how GlobalAggregate came to silently ignore group keys.
                    if (a.groupKeyOrdinals().isEmpty()) {
                        GlobalAggregate aggregate = new GlobalAggregate(a, arena, downstream);
                        // Two cadences, not one. finishers run when the input ends, which is what a
                        // bounded read needs; continuous emitters run on a tick, which is the only
                        // thing that makes an unwindowed aggregate over a stream produce anything at
                        // all. Registered continuously, this query used to report RUNNING for ever
                        // and emit nothing, because a stream has no end to trigger a finisher.
                        finishers.add(aggregate::emit);
                        continuousEmitters.add(aggregate::emitIncremental);
                        globals.add(aggregate);
                        onlyStreamOf(a.input()).ifPresent(stream -> {
                            partialAggregateTargets.put(stream, aggregate::processPartial);
                            partialAggregateSchemas.put(stream, a.outputSchema());
                        });
                        yield buildInput(a.input(), entering(a, aggregate));
                    }
                    KeyedAggregate aggregate = new KeyedAggregate(
                            a, a.input().outputSchema(), arena, downstream, KeyedAggregate.DEFAULT_MAX_GROUPS);
                    finishers.add(aggregate::emit);
                    keyed.add(aggregate);
                    onlyStreamOf(a.input()).ifPresent(stream -> {
                        partialAggregateTargets.put(stream, aggregate::processPartial);
                        partialAggregateSchemas.put(stream, a.outputSchema());
                    });
                    yield buildInput(a.input(), entering(a, aggregate));
                }
                case WindowAssignOperator w -> {
                    WindowAssign assign = new WindowAssign(w, arena, downstream);
                    yield buildInput(w.input(), entering(w, assign));
                }
                case WindowedAggregateOperator w -> {
                    WindowedAggregate aggregate = overflowAccess == null
                            ? new WindowedAggregate(w, arena, downstream)
                            : new WindowedAggregate(
                                    w, arena, downstream, overflowAccess, spillSettings.maxOverflowSlabs());
                    // A bounded source must not leave its final windows unemitted: that looks
                    // exactly like the query being wrong about its last period.
                    finishers.add(aggregate::finish);
                    windowed.add(aggregate);
                    // Off-heap only, which is what there is a byte count for: the accumulators and,
                    // since ADR-044, COUNT(DISTINCT)'s values. The slice bookkeeping above them is
                    // on the heap and has no number that is not a guess.
                    holdsState(w, () -> aggregate.state().offHeapBytesAllocated());
                    yield buildInput(w.input(), entering(w, aggregate));
                }
                case SinkOperator s -> buildInput(s.input(), entering(s, downstream));
                case LookupJoinOperator l -> {
                    LookupSourcePlugin table = lookups.get(l.lookupStream());
                    if (table == null) {
                        throw new PravahaException(
                                RuntimeErrors.UNSUPPORTED_JOIN,
                                "this query looks rows up in '" + l.lookupStream()
                                        + "', which is not among the dimension tables this execution was given ("
                                        + lookups.keySet() + "). Register it as a lookup source before starting "
                                        + "the query.");
                    }
                    LookupJoin join = new LookupJoin(l, table, arena, downstream, MAX_LOOKUP_CACHE_ENTRIES);
                    lookupJoins.add(join);
                    // Outstanding lookups must finish before the query claims to be done: a record
                    // parked on a round trip has been consumed and not yet answered, and dropping
                    // it at shutdown loses output that the offsets say was processed.
                    finishers.add(join::drain);
                    yield buildInput(l.input(), entering(l, join));
                }
                case JoinOperator j -> {
                    // A join is where the plan stops being a chain. Both sides are built with the
                    // join as their downstream, and each side's scan registers its own entry point;
                    // there is no single head to hand back, so anything above the join reaches its
                    // inputs by name rather than by holding a processor.
                    MemoryAccess overflow = overflowAccess;
                    SymmetricHashJoin join = overflow == null
                            ? new SymmetricHashJoin(j, arena, downstream, MAX_JOIN_STATE_SLABS)
                            : new SymmetricHashJoin(
                                    j,
                                    arena,
                                    downstream,
                                    MAX_JOIN_STATE_SLABS,
                                    overflow,
                                    spillSettings.maxOverflowSlabs());
                    joins.add(join);
                    holdsState(j, join::stateBytes);
                    // Both sides through the same counter: a join's rows in is what it was handed,
                    // and which side a row arrived on is already in rowsPerLane and the plan.
                    buildInput(j.left(), entering(j, join.leftInput()));
                    buildInput(j.right(), entering(j, join.rightInput()));
                    yield row -> {
                        throw new IllegalStateException("rows must enter a join's inputs by stream name");
                    };
                }
            };
        }

        /**
         * The one stream {@code operator} reads, if it is nothing but a chain of single-input
         * operators (a filter, a projection, a computed column -- any shape SQL planning would put
         * between an aggregate and its scan) over a single {@link ScanOperator} --
         * {@code Optional.empty()} the moment something with zero or two-or-more inputs appears (a
         * join, most notably). This is deliberately more permissive than requiring a specific chain
         * of types: the only question this answers is "does this subtree read exactly one stream",
         * which a projection or a computed column never changes the answer to, and enumerating the
         * types that do not affect it would just be re-deriving that fact one type at a time.
         *
         * <p>{@code SourcePushdown.partialAggregateFor} (pravaha-sql) asks the same question, for
         * the same reason, on the plan builder's side of the boundary; independently implemented
         * here because pravaha-runtime does not depend on pravaha-sql (ADR-002).
         */
        private static java.util.Optional<String> onlyStreamOf(PhysicalOperator operator) {
            if (operator instanceof ScanOperator s) {
                return java.util.Optional.of(s.streamName());
            }
            List<PhysicalOperator> inputs = operator.inputs();
            return inputs.size() == 1 ? onlyStreamOf(inputs.get(0)) : java.util.Optional.empty();
        }

        private void registerScan(ScanOperator scan, RowProcessor entry) {
            scans.add(scan);
            RowProcessor existing = heads.put(scan.streamName(), entry);
            if (existing != null) {
                // Both sides reading one stream is a self-join. It needs the same stream's rows fed
                // into two different entry points, which a name cannot distinguish -- so it is
                // refused here rather than silently feeding one side.
                throw new UnsupportedOperationException("stream '" + scan.streamName()
                        + "' appears on both sides of this plan; " + "self-joins are not supported yet");
            }
        }

        /**
         * Evaluates an expression per output column.
         *
         * <p>The evaluators are resolved once, here, rather than per row: an expression tree is a
         * chain of virtual calls, and re-deciding which branch to take for every column of every
         * row would put the interpreter's dispatch cost on top of the arithmetic it is performing.
         */
        private RowProcessor computer(ComputeOperator compute, RowProcessor downstream) {
            RowLayout layout = RowLayout.of(compute.outputSchema());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            List<Expression> expressions = compute.expressions();

            return row -> {
                long handle = arena.allocate(layout.rowSize(1024));
                if (handle == ArenaHandle.NULL) {
                    throw new PravahaException(
                            RuntimeErrors.ARENA_EXHAUSTED,
                            "the compute stage's arena is full; raise pravaha.lane.arena.slab-bytes or reduce pravaha.lane.batch-size");
                }
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                for (int out = 0; out < expressions.size(); out++) {
                    Expression expression = expressions.get(out);
                    if (expression.isNull(row)) {
                        // SQL's rule, not Java's: null in, null out. Writing a zero here would make
                        // a downstream SUM produce a number that looks entirely reasonable.
                        writer.setNull(out);
                        continue;
                    }
                    writeComputed(writer, out, expression, row, compute.outputSchema());
                }
                writer.weight(row.weight())
                        .eventTimestampNanos(row.eventTimestampNanos())
                        .sequence(row.sequence())
                        .commit();
                arena.trimTo(handle, writer.sizeSoFar());
                downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            };
        }

        /**
         * Writes one computed column.
         *
         * <p>A plain column reference is copied rather than evaluated. It began as a correctness
         * fix -- the expression tree could produce only numbers, so a projection mixing {@code ts +
         * INTERVAL '10' SECOND} with a text column beside it wrote the string's bytes as a long and
         * failed at the writer, which is where the README's own query landed. The tree can evaluate
         * text now, so the short-circuit is what its name says instead: {@link
         * InterpretedPipeline#copyField} moves the bytes across, where evaluating the column would
         * decode them into a {@code String} and immediately encode them back.
         */
        private static void writeComputed(
                RowWriter writer, int ordinal, Expression expression, RowView row, StreamSchema schema) {
            if (expression instanceof Expression.Column column) {
                InterpretedPipeline.copyField(row, column.ordinal(), writer, ordinal, schema);
                return;
            }
            switch (schema.field(ordinal).type().typeName()) {
                case BOOLEAN -> writer.setBoolean(ordinal, expression.evaluateLong(row) != 0);
                case INT8 -> writer.setByte(ordinal, (byte) expression.evaluateLong(row));
                case INT16 -> writer.setShort(ordinal, (short) expression.evaluateLong(row));
                case INT32, DATE -> writer.setInt(ordinal, (int) expression.evaluateLong(row));
                case FLOAT32 -> writer.setFloat(ordinal, (float) expression.evaluateDouble(row));
                case FLOAT64 -> writer.setDouble(ordinal, expression.evaluateDouble(row));
                case STRING -> writer.setString(ordinal, expression.evaluateString(row));
                default -> writer.setLong(ordinal, expression.evaluateLong(row));
            }
        }

        private RowProcessor projector(ProjectOperator project, RowProcessor downstream) {
            RowLayout layout = RowLayout.of(project.outputSchema());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            List<Integer> ordinals = project.sourceOrdinals();

            return row -> {
                long handle = arena.allocate(layout.rowSize(1024));
                if (handle == ArenaHandle.NULL) {
                    throw new PravahaException(
                            RuntimeErrors.ARENA_EXHAUSTED,
                            "the projection's arena is full; raise pravaha.lane.arena.slab-bytes or reduce pravaha.lane.batch-size");
                }
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                for (int out = 0; out < ordinals.size(); out++) {
                    copyField(row, ordinals.get(out), writer, out, project.outputSchema());
                }
                writer.weight(row.weight())
                        .eventTimestampNanos(row.eventTimestampNanos())
                        .sequence(row.sequence())
                        .commit();
                arena.trimTo(handle, writer.sizeSoFar());
                downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            };
        }
    }

    /** Copies one field, preserving nulls. */
    static void copyField(RowView from, int fromOrdinal, RowWriter to, int toOrdinal, StreamSchema toSchema) {
        if (from.isNull(fromOrdinal)) {
            to.setNull(toOrdinal);
            return;
        }
        TypeName type = toSchema.field(toOrdinal).type().typeName();
        switch (type) {
            case BOOLEAN -> to.setBoolean(toOrdinal, from.getBoolean(fromOrdinal));
            case INT8 -> to.setByte(toOrdinal, from.getByte(fromOrdinal));
            case INT16 -> to.setShort(toOrdinal, from.getShort(fromOrdinal));
            case INT32, DATE -> to.setInt(toOrdinal, from.getInt(fromOrdinal));
            case INT64, TIME, TIMESTAMP_LTZ -> to.setLong(toOrdinal, from.getLong(fromOrdinal));
            case FLOAT32 -> to.setFloat(toOrdinal, from.getFloat(fromOrdinal));
            case FLOAT64 -> to.setDouble(toOrdinal, from.getDouble(fromOrdinal));
            case DECIMAL -> to.setDecimal(toOrdinal, from.getDecimalHigh(fromOrdinal), from.getDecimalLow(fromOrdinal));
            case STRING -> to.setString(toOrdinal, from.getString(fromOrdinal));
            // BYTES had no case, so it fell to the default and was read as text. A projection of a
            // binary column therefore produced a String, and the Arrow writer -- correctly
            // expecting byte[] for the type the schema declares -- threw ClassCastException at
            // serialisation. A BYTES column could not cross a projection, which is every query.
            case BYTES -> {
                com.ash.messaging.pravaha.api.data.MutableSlice slice =
                        new com.ash.messaging.pravaha.api.data.MutableSlice();
                from.getBytes(fromOrdinal, slice);
                byte[] bytes = new byte[slice.length()];
                if (bytes.length > 0 && from instanceof com.ash.messaging.pravaha.common.row.BinaryRowView binary) {
                    binary.region().getBytes(slice.offset(), bytes, 0, bytes.length);
                }
                to.setBytes(toOrdinal, bytes);
            }
            default ->
                // Refused rather than stringified. Reading an unhandled type as text is what turned
                // BYTES into a String here and hid the gap: the copy succeeded and the failure
                // surfaced two layers away, as a cast error naming neither the column nor the type.
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "column '" + toSchema.field(toOrdinal).name() + "' is " + type
                                + ", which this engine cannot yet copy between rows. It is declared, and moving "
                                + "it through a projection is not built.");
        }
    }

    private static void copyInto(RowOutput output, RowView row, StreamSchema schema) {
        RowWriter writer = output.begin();
        for (int i = 0; i < schema.fieldCount(); i++) {
            copyField(row, i, writer, i, schema);
        }
        writer.weight(row.weight())
                .eventTimestampNanos(row.eventTimestampNanos())
                .sequence(row.sequence())
                .commit();
    }
}
