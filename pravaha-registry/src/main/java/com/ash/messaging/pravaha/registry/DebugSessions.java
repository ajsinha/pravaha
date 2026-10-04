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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.runtime.exec.OperatorState;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewChange;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

/**
 * The time-travel debugger's sessions on one node: forked, bounded, expiring (ADR-048).
 *
 * <h2>A session is a resource, not a view of one</h2>
 *
 * <p>A debug session holds a whole second copy of a query -- its lanes, its arena, its operator
 * state, a reader per partition. Two of them cost twice that. So this class is mostly about the
 * three things that keep a diagnostic tool from becoming an outage: <strong>how many</strong> may
 * run ({@code pravaha.debug.sessions.max}), <strong>how long</strong> an abandoned one lives
 * ({@code pravaha.debug.session.ttl}), and <strong>who</strong> may open one at all.
 *
 * <p><strong>Administer, not read.</strong> Forking a query exposes its SQL, its input rows and its
 * operator state to whoever holds the session -- more than reading its view does, and more than
 * reading its plan does. So it takes the same permission dropping it takes, as a replacement does
 * (ADR-046), and reading a session's status takes it too: the status carries the SQL.
 *
 * <p>The count of open sessions is published as a gauge, because a node quietly holding six forks
 * of a large query has six times the state and nothing else says so.
 */
public final class DebugSessions implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DebugSessions.class.getName());

    /** How many sessions one node will hold at once, unless configured otherwise. */
    public static final int DEFAULT_MAX_SESSIONS = 4;

    /** How long a session may go untouched before it is released. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    /** How many rows one session may consume in total, so its recorded script stays exportable. */
    public static final long DEFAULT_MAX_ROWS = 20_000;

    /** How long a fork waits for the checkpoint to be restored into its lane. */
    private static final Duration RESTORE_TIMEOUT = Duration.ofSeconds(30);

    private final QueryRegistry registry;
    private final AuditSink audit;
    private final int maxSessions;
    private final Duration ttl;
    private final long maxRows;
    private final long searchCeiling;

    /** Concurrent, so a step on one session never waits on a fork of another. */
    private final Map<String, DebugSession> byId = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong();

    DebugSessions(QueryRegistry registry, SecurityPolicy policy, AuditSink audit, Configuration configuration) {
        this.registry = registry;
        this.audit = audit == null ? AuditSink.NONE : audit;
        Configuration settings = configuration == null ? Configuration.builder().build() : configuration;
        this.maxSessions = (int) settings.getLong("pravaha.debug.sessions.max", DEFAULT_MAX_SESSIONS);
        this.ttl = settings.getDuration("pravaha.debug.session.ttl", DEFAULT_TTL);
        this.maxRows = settings.getLong("pravaha.debug.session.max-rows", DEFAULT_MAX_ROWS);
        this.searchCeiling = settings.getLong("pravaha.debug.step.max-rows", DebugSession.DEFAULT_SEARCH_CEILING);
    }

    // ------------------------------------------------------------------ forking

    /**
     * Forks a debug session from a query's checkpoint.
     *
     * @param typed the query's name as the caller wrote it, resolved in the caller's tenant (ADR-060)
     * @param checkpointId the checkpoint to fork from, or null for the newest retained one
     */
    public DebugSession.Status fork(String typed, Long checkpointId, Principal principal) {
        String name = QueryRegistry.engineName(principal, typed);
        expireStale();
        ContinuousQueryStatements.requireAdministrable(registry, audit, principal, name, "debug");
        RegisteredQuery query = registry.require(name);
        if (query.state().isTerminal()) {
            throw new PravahaException(
                    DebugErrors.QUERY_GONE,
                    "'" + name + "' is " + query.state() + ", so there is nothing running to fork. A debug "
                            + "session replays a query from a checkpoint it took while it was running.");
        }
        if (byId.size() >= maxSessions) {
            throw new PravahaException(
                    DebugErrors.TOO_MANY_SESSIONS,
                    "this node is already holding " + byId.size() + " debug sessions, which is the ceiling "
                            + "pravaha.debug.sessions.max sets. Each one is a second copy of a query's state, "
                            + "so the ceiling is memory rather than policy. End one (" + byId.keySet()
                            + ") or raise the setting.");
        }

        Checkpoint checkpoint = checkpointOf(query, name, checkpointId);
        List<String> streams = query.plan() == null ? List.of() : PlanSources.of(query.plan());
        ReplaySource replay = registry.feeds().replayFrom(name, streams, checkpoint.offsets());

        String id = "dbg-" + Long.toHexString(System.currentTimeMillis()) + "-" + ids.incrementAndGet();
        DebugSession session = null;
        QueryExecution execution = null;
        try {
            StreamSchema schema = query.outputSchema();
            // RETYPERESTORE-1: a fork of a checkpoint of another output schema is refused, PRV-4095.
            CheckpointSchemas.requireSame(checkpoint, schema, query.anyName());
            // The fork's own view: built here, named for the session, and never given to the view
            // catalogue -- so no reader resolves a name to it and no subscriber can attach.
            ServedView view = new ServedView(
                            "debug:" + id,
                            schema,
                            query.view().keyOrdinals(),
                            QueryRegistry.DEFAULT_MAX_KEYS,
                            query.view().retention())
                    .derivedFrom(streams);
            DebugSession.requireNotTheLiveView(query.view(), view);
            ViewSink sink = new ViewSink(view, schema);
            execution = forkExecution(query.plan(), view, sink);
            // Both halves or neither, exactly as a restart restores: the operator state and the
            // view together, beside source readers opened at the offsets that state describes.
            execution.restore(checkpoint, RESTORE_TIMEOUT);
            session = new DebugSession(
                    id, query, checkpoint.id(), principal.id(), execution, view, sink, replay, searchCeiling, maxRows);
        } catch (RuntimeException e) {
            if (session == null) {
                if (execution != null) {
                    execution.close();
                }
                replay.close();
            }
            throw e;
        }
        byId.put(id, session);
        LOG.log(
                System.Logger.Level.INFO,
                "debug session " + id + " forked from checkpoint " + checkpoint.id() + " of '" + name + "' by "
                        + principal.id() + "; sinks disabled, nothing reads its view");
        return session.status().shownTo(principal);
    }

    /**
     * A second execution of {@code plan} on lanes of its own, writing into {@code sink}.
     *
     * <p>Never on a shared lane, and that is the isolation design section 16.4 asks for "at the
     * lane-allocation level, not by convention": a fork of a query hosted on a multiplexed lane
     * would otherwise share an inbox and an arena with three hundred live registrations.
     *
     * <p>One lane, because a keyed aggregate's groups are partitioned across lanes -- "the groups"
     * only means something when there is one -- and measured whatever the node's {@code
     * pravaha.metrics.operators} says, because a session exists to report what each operator did
     * and that answer must not depend on a setting somebody did not turn on before the incident.
     */
    private QueryExecution forkExecution(
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan, ServedView view, ViewSink sink) {
        return QueryExecution.start(
                        plan,
                        1,
                        registry.laneConfig,
                        registry.access,
                        sink::laneOutput,
                        registry.lookups,
                        registry.laneRunner(),
                        true)
                .checkpointingViewWith(view::snapshot, view::restore)
                .checkingViewWith(ServedView::requireReadable);
    }

    /**
     * The checkpoint to fork from, or a refusal naming what there is.
     *
     * <p>Three different absences, and a debugger has to tell them apart: this node does not
     * checkpoint at all, this query has not taken one yet, and the id asked for has been pruned.
     * "No checkpoint" without saying which would send an operator to the wrong setting.
     */
    private Checkpoint checkpointOf(RegisteredQuery query, String name, Long wanted) {
        Path directory = query.checkpointDirectory()
                .orElseThrow(() -> new PravahaException(
                        DebugErrors.NO_CHECKPOINT,
                        "'" + name + "' is not being checkpointed, so there is no position to fork from. A "
                                + "debug session restores a checkpoint and replays the sources from the "
                                + "offsets it recorded; set pravaha.checkpoint.directory on this node and "
                                + "wait for one interval."));
        FileCheckpointStore store = new FileCheckpointStore(directory);
        List<Long> available = store.availableIds();
        if (available.isEmpty()) {
            throw new PravahaException(
                    DebugErrors.NO_CHECKPOINT,
                    "'" + name + "' has taken no checkpoint yet, so there is nothing to fork from. The first "
                            + "one is written pravaha.checkpoint.interval after the query started.");
        }
        Optional<Checkpoint> found = wanted == null ? store.latest() : store.load(wanted);
        return found.orElseThrow(() -> new PravahaException(
                DebugErrors.NO_CHECKPOINT,
                "'" + name + "' has no retained checkpoint " + wanted + ". This node keeps the newest "
                        + "pravaha.checkpoint.keep of them, and the ones it still has are " + available
                        + ". A debug session can only fork from a checkpoint that is still on disk."));
    }

    /** Which checkpoints of {@code typed}, in the caller's tenant, a session could be forked from, newest first. */
    public List<Long> checkpointsOf(String typed, Principal principal) {
        String name = QueryRegistry.engineName(principal, typed);
        ContinuousQueryStatements.requireAdministrable(registry, audit, principal, name, "debug-checkpoints");
        RegisteredQuery query = registry.require(name);
        return query.checkpointDirectory()
                .map(directory -> new FileCheckpointStore(directory).availableIds())
                .orElse(List.of());
    }

    // ------------------------------------------------------------------ using a session

    /** Advances one session and reports what changed. */
    public DebugStep step(String id, DebugStep.Request request, Principal principal) {
        return require(id, principal, "debug-step").step(request);
    }

    /** What state one session's fork holds, and how much of each. */
    public List<OperatorState.Slot> state(String id, Principal principal) {
        return require(id, principal, "debug-state").state();
    }

    /** One page of one operator's state, read without changing it. */
    public OperatorState.Page inspect(
            String id, String operatorId, String key, int offset, int limit, Principal principal) {
        return require(id, principal, "debug-inspect").inspect(operatorId, key, offset, limit);
    }

    /** The rows one session's fork has built, which nothing else can read. */
    public List<ViewChange> view(String id, Principal principal) {
        return require(id, principal, "debug-view").viewRows();
    }

    public Optional<DebugSession.Status> of(@Nullable String id, Principal principal) {
        DebugSession session = byId.get(id);
        if (session == null) {
            return Optional.empty();
        }
        ContinuousQueryStatements.requireAdministrable(registry, audit, principal, session.queryName(), "debug-status");
        return Optional.of(session.status().shownTo(principal));
    }

    /** Every session this principal may administer. Filtered, not refused: a listing is how you find one. */
    public List<DebugSession.Status> all(Principal principal) {
        expireStale();
        List<DebugSession.Status> visible = new ArrayList<>();
        for (DebugSession session : byId.values()) {
            if (registry.owners().mayAdminister(principal, session.queryName()).allowed()) {
                visible.add(session.status().shownTo(principal));
            }
        }
        return List.copyOf(visible);
    }

    /**
     * Writes a session out as a JUnit fixture, having first rehearsed it.
     *
     * <p>The rehearsal is the point. The session's own view was restored from a checkpoint, so it
     * holds the answer to everything before the fork as well as to the rows the session stepped;
     * a generated test replaying only those rows from empty would assert a view it could never
     * reach. So the script is run once more here, through a second execution of the same plan with
     * no checkpoint restored, and what <em>that</em> ends with is the expectation written into the
     * file -- an answer this engine has actually produced from exactly the input the file carries.
     */
    public FixtureExport export(String id, String name, Principal principal) {
        DebugSession session = require(id, principal, "debug-export");
        // FIX-2. A fixture replays the session's rows from empty state, so a session that stepped
        // only event time has nothing to replay: its fixture would assert an empty view, which
        // reproduces nothing. It was refused before, but as PRV-3022, a window operator walking from
        // the epoch -- the right refusal for the wrong reason. Refused here, by what is missing.
        if (session.script().stream().allMatch(DebugSession.Action::isWatermark)) {
            throw new PravahaException(
                    DebugErrors.BAD_STEP,
                    "debug session " + id + " has stepped no rows, only event time, and a fixture replays "
                            + "the rows a session stepped from empty state -- this one would assert an empty "
                            + "view and reproduce nothing. Step the rows the incident needs (debug step --step rows:N, "
                            + "or until:<column>:<op>:<value>), then export.");
        }
        List<ViewChange> expected = rehearse(session);
        FixtureExport export = session.export(name, expected);
        audit.record(com.ash.messaging.pravaha.security.AuditEvent.of(
                principal,
                "debug-export",
                session.queryName(),
                com.ash.messaging.pravaha.security.AccessDecision.allow(),
                export.className()));
        return export;
    }

    /** Runs a session's script through an empty execution of the same plan, and reports the view. */
    private List<ViewChange> rehearse(DebugSession session) {
        RegisteredQuery query = liveQuery(session);
        StreamSchema schema = query.outputSchema();
        ServedView view = new ServedView(
                "rehearse:" + session.id(),
                schema,
                query.view().keyOrdinals(),
                QueryRegistry.DEFAULT_MAX_KEYS,
                query.view().retention());
        ViewSink sink = new ViewSink(view, schema);
        QueryExecution execution = forkExecution(query.plan(), view, sink);
        try (DebugFeeder feeder = new DebugFeeder(execution)) {
            for (DebugSession.Action action : session.script()) {
                if (action.isWatermark()) {
                    execution.advanceWatermark(action.watermarkNanos());
                } else {
                    feeder.feed(java.util.Objects.requireNonNull(action.row(), "a row action has its row"));
                }
                // After each action, exactly as the generated fixture does: an expectation that
                // depended on committing less often than the test does would be an expectation the
                // test could miss.
                DebugFeeder.settle(execution, sink);
            }
            DebugFeeder.settle(execution, sink);
            return view.committedRows();
        } finally {
            execution.close();
        }
    }

    /** Ends a session and releases its fork. */
    public void end(String id, Principal principal) {
        // Held, not required-live: a session whose query has since been dropped is exactly the one
        // that most needs releasing, and a refusal here would leave its fork running with no way
        // to reach it but a restart.
        DebugSession session = held(id, principal, "debug-end");
        byId.remove(session.id());
        session.close();
        LOG.log(System.Logger.Level.INFO, "debug session " + session.id() + " ended by " + principal.id());
    }

    /** How many sessions are open on this node: the gauge an operator watches. */
    public int open() {
        return byId.size();
    }

    // ------------------------------------------------------------------ housekeeping

    /** The session, authorized, and proven to be a replay of a query that still exists. */
    private DebugSession require(String id, Principal principal, String action) {
        DebugSession session = held(id, principal, action);
        liveQuery(session);
        return session;
    }

    /** The session, authorized, whether or not the query it forked from is still there. */
    private DebugSession held(String id, Principal principal, String action) {
        expireStale();
        DebugSession session = byId.get(id);
        if (session == null) {
            throw new PravahaException(
                    DebugErrors.NO_SUCH_SESSION,
                    "there is no debug session '" + id + "' on this node. It was ended, or it expired after "
                            + ttl + " untouched -- a session holds a second copy of a query's state, so it is "
                            + "not kept for ever. Fork again from the checkpoint.");
        }
        ContinuousQueryStatements.requireAdministrable(registry, audit, principal, session.queryName(), action);
        return session;
    }

    /**
     * The query this session forked from, or a refusal if it has gone.
     *
     * <p>Dropped and replaced are both "gone" and both matter. A replacement moves the name to a
     * different computation (ADR-046), so the session is still a faithful replay of a query nobody
     * is running any more -- and a step reported against the name would describe the wrong one.
     * The fingerprint is what tells them apart: it names the computation, where the name names the
     * answer.
     */
    private RegisteredQuery liveQuery(DebugSession session) {
        RegisteredQuery query = registry.find(session.queryName()).orElse(null);
        if (query == null) {
            throw new PravahaException(
                    DebugErrors.QUERY_GONE,
                    "'" + session.queryName() + "' has been dropped since session " + session.id()
                            + " was forked, so there is nothing for it to be a replay of. Export what the "
                            + "session has if you still want it, then end it.");
        }
        if (!query.fingerprint().equals(session.fingerprint())) {
            throw new PravahaException(
                    DebugErrors.QUERY_GONE,
                    "'" + session.queryName() + "' answers a different computation than it did when session "
                            + session.id() + " was forked -- it has been replaced (ADR-046). Stepping on would "
                            + "report the old version's behaviour under a name that now answers the new one. "
                            + "Fork again from the new version's checkpoint.");
        }
        return query;
    }

    /**
     * Releases sessions nobody has touched for {@link #ttl}.
     *
     * <p>On the way in to every call rather than on a timer of its own. A registry that never
     * debugs anything then starts no thread, and a node whose only session was abandoned releases
     * it the next time anybody looks -- which is when its memory is wanted.
     */
    void expireStale() {
        Instant deadline = Instant.now().minus(ttl);
        for (DebugSession session : List.copyOf(byId.values())) {
            if (session.lastUsed().isBefore(deadline)) {
                byId.remove(session.id());
                session.close();
                LOG.log(
                        System.Logger.Level.INFO,
                        "debug session " + session.id() + " expired after " + ttl + " untouched; its fork is "
                                + "released");
            }
        }
    }

    /** The sessions held, by id, for a test that needs to look. */
    Map<String, DebugSession> sessions() {
        return new LinkedHashMap<>(byId);
    }

    @Override
    public void close() {
        for (DebugSession session : List.copyOf(byId.values())) {
            byId.remove(session.id());
            session.close();
        }
    }
}
