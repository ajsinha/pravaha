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
package com.ash.messaging.pravaha.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.exec.OperatorMetrics;
import com.ash.messaging.pravaha.runtime.exec.OperatorState;
import com.ash.messaging.pravaha.runtime.exec.OperatorStateReader;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewChange;
import com.ash.messaging.pravaha.serving.ViewSink;

/**
 * One query, forked from a checkpoint and stepped forward by hand (ADR-048, design section 16.4).
 *
 * <h2>What a fork is</h2>
 *
 * <p>A second computation of the same plan, on lanes of its own, restored from a retained
 * checkpoint, reading the same sources from the offsets that checkpoint recorded. It is a sibling
 * of a blue/green candidate (ADR-046): running, reading, keeping its own state, answering to
 * nothing. The differences are the three that make it a debugger rather than a deployment.
 *
 * <ul>
 *   <li><strong>No sink, ever.</strong> A {@link SinkDelivery} is attached by the registry when a
 *       registration names one; a fork is not a registration and this class never opens one. There
 *       is no flag to turn off and therefore nothing to leave on by accident.
 *   <li><strong>No reader can reach it.</strong> Its {@link ServedView} is built here, named for
 *       the session, and never put in the view catalogue -- so nothing resolves a name to it and
 *       nobody can subscribe to it. The live query's view is a different object; {@link
 *       #requireNotTheLiveView} says so out loud, because a fork that shared it would write the
 *       replay's answer into what production is reading.
 *   <li><strong>Nothing drives it but the caller.</strong> No feed, no periodic checkpointer, no
 *       watermark clock. A step happens because somebody asked for one, which is what makes the
 *       answers reproducible.
 * </ul>
 *
 * <h2>Why two runs agree</h2>
 *
 * <p>Determinism is not claimed, it is arranged, and it rests on four things:
 *
 * <ol>
 *   <li>the state is the same -- both runs restore the same checkpoint's bytes;
 *   <li>the input is the same -- both read the same partitions from the same offsets, and {@link
 *       ReplaySource} interleaves them round-robin in a fixed order rather than however two pumps
 *       happened to be scheduled;
 *   <li>time is the same -- event time moves only when a step says so, so no wall clock and no
 *       idle-partition tick can fire a window in one run and not the other;
 *   <li>the observation point is the same -- every step waits for the lane to drain and commits
 *       the view before it reports, so nothing is read half-applied.
 * </ol>
 *
 * <p>{@code DebugDeterminismTest} runs a session twice and compares the reports.
 */
public final class DebugSession implements AutoCloseable {

    /** How long a step waits for the lane to drain. Generous: a step is not on a hot path. */
    private static final Duration SETTLE = Duration.ofSeconds(30);

    /**
     * The most rows a single step will consume looking for a commit or a predicate.
     *
     * <p>A ceiling rather than "until it happens", because "until it happens" over a source with
     * ten million rows is a call that never returns and a session nobody can end.
     */
    static final long DEFAULT_SEARCH_CEILING = 10_000;

    /** The most entries one page of operator state may ask for. */
    static final int MAX_PAGE = 1_000;

    private final String id;
    private final String queryName;
    private final String sql;
    private final List<Integer> keyColumns;
    private final QueryFingerprint fingerprint;
    private final long checkpointId;
    private final String owner;
    private final Instant startedAt;

    private final QueryExecution execution;
    private final ServedView view;
    private final ViewSink sink;
    private final ReplaySource replay;
    private final DebugFeeder feeder;
    private final StreamSchema outputSchema;

    /** Every row this session has fed in, in order: the fixture's data. */
    private final List<ReplaySource.ReplayRow> consumed = new ArrayList<>();

    /**
     * Everything this session did to the fork, in order.
     *
     * <p>Rows alone would not reproduce it: a watermark advance fires a window, and a fixture that
     * replayed the rows and not the advances would end with the last window unemitted and a view
     * that is a row short of what the session actually saw.
     */
    private final List<Action> script = new ArrayList<>();

    /** One thing that happened to the fork: a row arriving, or event time moving. */
    record Action(ReplaySource.@Nullable ReplayRow row, long watermarkNanos) {

        static Action of(ReplaySource.ReplayRow row) {
            return new Action(row, 0);
        }

        static Action watermark(long nanos) {
            return new Action(null, nanos);
        }

        boolean isWatermark() {
            return row == null;
        }
    }

    /**
     * The highest watermark a step has pushed, or {@code Long.MIN_VALUE}.
     *
     * <p>Tracked here because a fork generates no watermarks of its own: nothing ticks, so the
     * execution's own tracker never sees one and would report "no watermark" after a step that
     * had just advanced event time by an hour.
     */
    private long pushedWatermark = Long.MIN_VALUE;

    private final long searchCeiling;
    private final long rowCeiling;

    private long sequence;
    private volatile Instant lastUsed = Instant.now();
    private volatile boolean closed;
    private @Nullable DebugStep last;

    DebugSession(
            String id,
            RegisteredQuery query,
            long checkpointId,
            String owner,
            QueryExecution execution,
            ServedView view,
            ViewSink sink,
            ReplaySource replay,
            long searchCeiling,
            long rowCeiling) {
        this.id = id;
        this.queryName = query.name();
        this.sql = query.sql();
        this.keyColumns = List.copyOf(query.view().keyOrdinals());
        this.fingerprint = query.fingerprint();
        this.checkpointId = checkpointId;
        this.owner = owner;
        this.startedAt = Instant.now();
        this.execution = execution;
        this.view = view;
        this.sink = sink;
        this.replay = replay;
        this.outputSchema = query.outputSchema();
        this.searchCeiling = searchCeiling;
        this.rowCeiling = rowCeiling;
        this.feeder = new DebugFeeder(execution);
    }

    /**
     * Refuses a fork whose view is the live query's.
     *
     * <p>Called on the way in rather than trusted. Every other guarantee here -- no sink, no
     * catalogue entry, no subscriber -- is about things the fork does not attach; this one is about
     * the one object it would be easiest to pass in by mistake, and passing it in would mean the
     * replay's rows landing in the view production is reading. {@code DebugIsolationTest} seeds
     * exactly that mistake and requires this to catch it.
     */
    public static void requireNotTheLiveView(ServedView live, ServedView fork) {
        if (live == fork) {
            throw new IllegalStateException("a debug fork was built on the live query's own view. "
                    + "Stepping it would write the replay's rows into the answer production is reading, "
                    + "and retract rows the live query had put there. A fork gets a view of its own.");
        }
    }

    // ------------------------------------------------------------------ stepping

    /**
     * Advances the fork and reports what changed.
     *
     * <p>Synchronized, because a session is a resource two screens can hold at once and two
     * concurrent steps would interleave their rows and report each other's counters.
     */
    public synchronized DebugStep step(DebugStep.Request request) {
        requireOpen();
        lastUsed = Instant.now();
        byte[] before = view.snapshot();
        // What each operator had done before this step, so the report is the step's own work
        // rather than the session's running total. B6's counters are cumulative and published at
        // each batch boundary; a step ends with the lane drained, so both reads are consistent.
        Map<String, OperatorMetrics.Snapshot> beforeOperators = operatorsByNode();
        List<DebugStep.InputRow> rowsIn = new ArrayList<>();
        String stopped;
        boolean exhausted = false;

        switch (request.kind()) {
            case ROW, ROWS -> {
                long wanted = request.kind() == DebugStep.Kind.ROW ? 1 : request.count();
                exhausted = !consume(wanted, rowsIn);
                stopped = exhausted
                        ? "the sources have no more rows"
                        : rowsIn.size() + (rowsIn.size() == 1 ? " row" : " rows");
            }
            case WATERMARK -> {
                if (request.watermarkNanos() < pushedWatermark) {
                    throw new PravahaException(
                            DebugErrors.BAD_STEP,
                            "event time does not go backwards: this session is at " + pushedWatermark
                                    + " and was asked to step to " + request.watermarkNanos()
                                    + ". A watermark says no earlier record will arrive, and taking one back "
                                    + "would mean a window that has fired could fire again. Fork again from "
                                    + "the checkpoint to start over.");
                }
                execution.advanceWatermark(request.watermarkNanos());
                pushedWatermark = request.watermarkNanos();
                script.add(Action.watermark(request.watermarkNanos()));
                stopped = "event time at " + request.watermarkNanos();
            }
            case COMMIT -> {
                long taken = 0;
                while (taken < searchCeiling) {
                    if (!consume(1, rowsIn)) {
                        exhausted = true;
                        break;
                    }
                    taken++;
                    settle();
                    if (!view.changesSince(before, true).isEmpty()) {
                        break;
                    }
                }
                stopped = exhausted
                        ? "the sources ran out before the view changed"
                        : taken >= searchCeiling ? "the ceiling of " + searchCeiling + " rows" : "the view changed";
            }
            case UNTIL -> {
                ViewPredicate predicate = ViewPredicate.of(
                        request.column(),
                        java.util.Objects.requireNonNull(request.comparison(), "an until step has its comparison"),
                        java.util.Objects.requireNonNull(request.value(), "an until step has its value"),
                        outputSchema);
                long taken = 0;
                settle();
                boolean held = predicate.firstMatch(view.scan()).isPresent();
                while (!held && taken < searchCeiling) {
                    if (!consume(1, rowsIn)) {
                        exhausted = true;
                        break;
                    }
                    taken++;
                    settle();
                    held = predicate.firstMatch(view.scan()).isPresent();
                }
                stopped = held
                        ? "the view satisfies " + predicate
                        : exhausted
                                ? "the sources ran out before " + predicate + " held"
                                : "the ceiling of " + searchCeiling + " rows, without " + predicate + " holding";
            }
            default -> throw new PravahaException(DebugErrors.BAD_STEP, "this session cannot step by " + request);
        }

        settle();
        List<ViewChange> changes = view.changesSince(before, true);
        List<DebugStep.Operator> operators = new ArrayList<>();
        for (OperatorMetrics.Snapshot now : execution.operatorMetrics()) {
            OperatorMetrics.Snapshot then = beforeOperators.get(now.nodeId());
            operators.add(new DebugStep.Operator(
                    now.nodeId(),
                    now.operator(),
                    now.detail(),
                    now.rowsIn() - (then == null ? 0 : then.rowsIn()),
                    now.rowsOut() - (then == null ? 0 : then.rowsOut())));
        }
        OptionalLong watermark = watermark();
        last = new DebugStep(
                id,
                ++sequence,
                request.kind(),
                rowsIn,
                operators,
                changes,
                watermark,
                consumed.size(),
                view.size(),
                exhausted,
                stopped);
        lastUsed = Instant.now();
        return last;
    }

    /**
     * Feeds up to {@code wanted} rows, returning false when the sources ran dry first.
     *
     * <p>One at a time and in order, which is what "step by one row" has to mean. The rows are kept
     * as they were read, for the fixture: the export has to be able to state the input exactly, and
     * re-reading the source later would be reading a source that has moved on.
     */
    private boolean consume(long wanted, List<DebugStep.InputRow> into) {
        for (long taken = 0; taken < wanted; taken++) {
            if (consumed.size() >= rowCeiling) {
                throw new PravahaException(
                        DebugErrors.BAD_STEP,
                        "session " + id + " has consumed its ceiling of " + rowCeiling + " rows. A session keeps "
                                + "every row it has read so that it can be exported as a fixture, so it is bounded; "
                                + "export what you have, end it and fork again further on.");
            }
            java.util.Optional<ReplaySource.ReplayRow> next = replay.next();
            if (next.isEmpty()) {
                return false;
            }
            ReplaySource.ReplayRow row = next.get();
            feeder.feed(row);
            consumed.add(row);
            script.add(Action.of(row));
            List<String> text = new ArrayList<>();
            for (Object value : row.values()) {
                text.add(String.valueOf(value));
            }
            into.add(new DebugStep.InputRow(
                    row.stream(), row.partition(), row.offset(), row.weight(), row.eventTimeNanos(), text));
        }
        return true;
    }

    private void settle() {
        DebugFeeder.settle(execution, sink);
    }

    /**
     * What each operator of the fork's plan has done so far, by plan-node id.
     *
     * <p>The debugger reports B6's per-operator counters rather than keeping a second set of its
     * own (ADR-048): the numbers a session shows are then the same numbers {@code
     * GET /api/v1/queries/{name}/plan} shows, keyed by the same node ids, and there is one
     * definition of what "rows out of this operator" means.
     */
    private Map<String, OperatorMetrics.Snapshot> operatorsByNode() {
        Map<String, OperatorMetrics.Snapshot> byNode = new java.util.LinkedHashMap<>();
        for (OperatorMetrics.Snapshot each : execution.operatorMetrics()) {
            byNode.put(each.nodeId(), each);
        }
        return byNode;
    }

    /** Where this session's event time stands: what a step pushed, or what the execution derived. */
    private OptionalLong watermark() {
        if (pushedWatermark != Long.MIN_VALUE) {
            return OptionalLong.of(pushedWatermark);
        }
        return execution.watermarkNanos();
    }

    // ------------------------------------------------------------------ exporting

    /**
     * Writes this session out as a JUnit test (ADR-048, design section 16.4).
     *
     * <p>The whole session, not the current step: the rows it consumed, the watermarks it pushed,
     * in the order it did them. Running the generated test replays that script into a freshly
     * registered query and asserts the view.
     *
     * <p><strong>{@code expected} is rehearsed, not copied from this fork.</strong> The fork was
     * restored from a checkpoint, so its view also holds the answer to everything that came before
     * it; a fixture replaying only the script from empty would not reach that view and would fail
     * the moment it was run. {@link DebugSessions#export} therefore runs the script through a
     * second, empty execution of the same plan and passes what <em>that</em> ended with -- so the
     * generated test's expectation is one that has actually been produced by the code the test
     * runs, rather than one asserted and hoped for.
     *
     * @param name what to call it; turned into a class name, and refused if it cannot be one
     */
    synchronized FixtureExport export(String name, List<ViewChange> expected) {
        requireOpen();
        lastUsed = Instant.now();
        return FixtureWriter.write(
                FixtureExport.classNameFrom(name),
                id,
                com.ash.messaging.pravaha.security.ViewNames.localName(queryName), // registered anew by the test
                sql,
                keyColumns,
                checkpointId,
                inputSchemas(),
                List.copyOf(script),
                expected);
    }

    // ------------------------------------------------------------------ inspecting

    /** What state this fork's operators hold, and how much of each. */
    public synchronized List<OperatorState.Slot> state() {
        requireOpen();
        lastUsed = Instant.now();
        return OperatorStateReader.slots(execution, SETTLE);
    }

    /**
     * One page of one operator's state, read on the lane that owns it and changing nothing.
     *
     * @param limit capped at {@link #MAX_PAGE}: an unbounded page of a join holding ten million
     *     rows is a request that takes the node down rather than a request that fails
     */
    public synchronized OperatorState.Page inspect(String operatorId, String key, int offset, int limit) {
        requireOpen();
        lastUsed = Instant.now();
        if (offset < 0) {
            throw new PravahaException(DebugErrors.BAD_STEP, "a page cannot start at " + offset);
        }
        if (limit < 1 || limit > MAX_PAGE) {
            throw new PravahaException(
                    DebugErrors.BAD_STEP,
                    "a page of " + limit + " entries is not one this node will build; ask for between 1 and " + MAX_PAGE
                            + " and page through the rest.");
        }
        try {
            return OperatorStateReader.page(execution, operatorId, key, offset, limit, SETTLE);
        } catch (IllegalArgumentException e) {
            throw new PravahaException(DebugErrors.BAD_STEP, String.valueOf(e.getMessage()), e);
        }
    }

    /** The view this fork has built, as rows. Never the live query's: see the class comment. */
    public synchronized List<ViewChange> viewRows() {
        requireOpen();
        return view.committedRows();
    }

    // ------------------------------------------------------------------ state of the session

    /** What a surface shows about a session without stepping it. */
    public record Status(
            String id,
            String query,
            String sql,
            long checkpointId,
            String owner,
            Instant startedAt,
            Instant lastUsedAt,
            long steps,
            long rowsConsumed,
            int viewSize,
            OptionalLong watermarkNanos,
            boolean sinksDisabled,
            List<String> streams) {

        /** This status with its query named as {@code principal} is shown it (ADR-060). */
        public Status shownTo(com.ash.messaging.pravaha.security.Principal principal) {
            return new Status(
                    id,
                    com.ash.messaging.pravaha.security.ViewNames.shown(principal, query),
                    sql,
                    checkpointId,
                    owner,
                    startedAt,
                    lastUsedAt,
                    steps,
                    rowsConsumed,
                    viewSize,
                    watermarkNanos,
                    sinksDisabled,
                    streams);
        }
    }

    public synchronized Status status() {
        return new Status(
                id,
                queryName,
                sql,
                checkpointId,
                owner,
                startedAt,
                lastUsed,
                sequence,
                consumed.size(),
                view.size(),
                execution.watermarkNanos(),
                true,
                execution.streams());
    }

    public String id() {
        return id;
    }

    public String queryName() {
        return queryName;
    }

    public String owner() {
        return owner;
    }

    Instant lastUsed() {
        return lastUsed;
    }

    QueryFingerprint fingerprint() {
        return fingerprint;
    }

    String sql() {
        return sql;
    }

    List<Integer> keyColumns() {
        return keyColumns;
    }

    long checkpointId() {
        return checkpointId;
    }

    StreamSchema outputSchema() {
        return outputSchema;
    }

    /** Every row this session has fed in, in the order it fed them: the fixture's input. */
    synchronized List<ReplaySource.ReplayRow> consumedRows() {
        return List.copyOf(consumed);
    }

    /** The schema each stream's rows were read with, which the fixture has to declare. */
    synchronized Map<String, StreamSchema> inputSchemas() {
        return feeder.schemas();
    }

    /** Everything this session did to the fork, in order: the fixture's script. */
    synchronized List<Action> script() {
        return List.copyOf(script);
    }

    /** The last step's report, for a surface that reconnects. */
    public synchronized java.util.Optional<DebugStep> lastStep() {
        return java.util.Optional.ofNullable(last);
    }

    /** Whether this session's rows are still flowing into a fork nothing else can see. */
    public boolean isOpen() {
        return !closed;
    }

    private void requireOpen() {
        if (closed) {
            throw new PravahaException(
                    DebugErrors.NO_SUCH_SESSION,
                    "debug session " + id + " has ended. A session holds a whole second copy of a query's "
                            + "state, so it is released when it is ended or when it expires; fork again from "
                            + "the checkpoint to carry on.");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            replay.close();
        } catch (RuntimeException e) {
            // A reader that will not close must not stop the execution being released; the
            // execution is the expensive half.
        }
        execution.close();
        feeder.close();
    }
}
