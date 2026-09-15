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

    /**
     * Where rows enter, by stream name.
     *
     * <p>One entry until a join appears, two after. Keyed by name rather than by position because
     * the caller has a stream and a row, not a plan: asking it to know which side of the join its
     * topic is on would push a planning detail into the ingest path.
     */
    private final Map<String, RowProcessor> inputs = new LinkedHashMap<>();

    private final List<ScanOperator> scans;

    private InterpretedPipeline(RowArena arena, RowProcessor head, List<ScanOperator> scans) {
        this.arena = arena;
        this.head = head;
        this.scans = List.copyOf(scans);
    }

    /**
     * Builds a pipeline.
     *
     * @param plan the physical plan, whose root must be a sink
     * @param sink where the terminal stage writes
     */
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
        RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
        Builder builder = new Builder(arena, sink, lookups);
        RowProcessor built = builder.build(plan);
        RowProcessor head = builder.joins.isEmpty() ? built : null;
        InterpretedPipeline pipeline = new InterpretedPipeline(arena, head, builder.scans);
        pipeline.finishers.addAll(builder.finishers);
        pipeline.continuousEmitters.addAll(builder.continuousEmitters);
        pipeline.windowed.addAll(builder.windowed);
        pipeline.joins.addAll(builder.joins);
        pipeline.lookupJoins.addAll(builder.lookupJoins);
        pipeline.inputs.putAll(builder.heads);
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
    }

    /** Whether this pipeline has anything to publish on a tick. */
    public boolean hasContinuousAggregates() {
        return !continuousEmitters.isEmpty();
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
    private static final int SNAPSHOT_VERSION = 3;

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
            if (version != SNAPSHOT_VERSION) {
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
        } catch (java.io.IOException e) {
            throw new PravahaException(RuntimeErrors.LANE_FAILED, "cannot restore pipeline state: " + e, e);
        }
    }

    /** Whether this pipeline holds any state worth checkpointing. */
    public boolean isStateful() {
        return !windowed.isEmpty() || !joins.isEmpty();
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
        private final List<ScanOperator> scans = new ArrayList<>();
        private final Map<String, RowProcessor> heads = new LinkedHashMap<>();

        private final Map<String, LookupSourcePlugin> lookups;

        Builder(RowArena arena, RowOutput sink, Map<String, LookupSourcePlugin> lookups) {
            this.arena = arena;
            this.sink = sink;
            this.lookups = lookups;
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
                    yield buildInput(s.inputs().get(0), terminal);
                }
                case ScanOperator s -> {
                    registerScan(s, row -> {});
                    yield row -> {};
                }
                default -> {
                    // A plan whose root is not a sink still has to run for tests and EXPLAIN; treat
                    // the root's output as the result.
                    RowProcessor terminal = row -> copyInto(sink, row, operator.outputSchema());
                    yield buildInput(operator, terminal);
                }
            };
        }

        /** Builds {@code operator} pushing into {@code downstream}, returning the chain's head. */
        private RowProcessor buildInput(PhysicalOperator operator, RowProcessor downstream) {
            return switch (operator) {
                case ScanOperator s -> {
                    registerScan(s, downstream);
                    yield downstream;
                }
                case FilterOperator f -> {
                    RowProcessor self = row -> {
                        if (f.predicate().test(row)) {
                            downstream.process(row);
                        }
                    };
                    yield buildInput(f.input(), self);
                }
                case ProjectOperator p -> {
                    RowProcessor self = projector(p, downstream);
                    yield buildInput(p.input(), self);
                }
                case ComputeOperator c -> {
                    RowProcessor self = computer(c, downstream);
                    yield buildInput(c.input(), self);
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
                        yield buildInput(a.input(), aggregate);
                    }
                    KeyedAggregate aggregate = new KeyedAggregate(
                            a, a.input().outputSchema(), arena, downstream, KeyedAggregate.DEFAULT_MAX_GROUPS);
                    finishers.add(aggregate::emit);
                    yield buildInput(a.input(), aggregate);
                }
                case WindowAssignOperator w -> {
                    WindowAssign assign = new WindowAssign(w, arena, downstream);
                    yield buildInput(w.input(), assign);
                }
                case WindowedAggregateOperator w -> {
                    WindowedAggregate aggregate = new WindowedAggregate(w, arena, downstream);
                    // A bounded source must not leave its final windows unemitted: that looks
                    // exactly like the query being wrong about its last period.
                    finishers.add(aggregate::finish);
                    windowed.add(aggregate);
                    yield buildInput(w.input(), aggregate);
                }
                case SinkOperator s -> buildInput(s.input(), downstream);
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
                    yield buildInput(l.input(), join);
                }
                case JoinOperator j -> {
                    // A join is where the plan stops being a chain. Both sides are built with the
                    // join as their downstream, and each side's scan registers its own entry point;
                    // there is no single head to hand back, so anything above the join reaches its
                    // inputs by name rather than by holding a processor.
                    SymmetricHashJoin join = new SymmetricHashJoin(j, arena, downstream, MAX_JOIN_STATE_SLABS);
                    joins.add(join);
                    buildInput(j.left(), join.leftInput());
                    buildInput(j.right(), join.rightInput());
                    yield row -> {
                        throw new IllegalStateException("rows must enter a join's inputs by stream name");
                    };
                }
            };
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
                            "the compute stage's arena is full; raise arena.slab.size or reduce the batch size");
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
                            "the projection's arena is full; raise arena.slab.size or reduce the batch size");
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
