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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewSink;

/**
 * One computation that keeps running and keeps a view current.
 *
 * <p>This is the thing ADR-025 calls a registration, and the object the rest of the product hangs
 * off: a subscription attaches to one, the console lists them, a cluster assigns them to nodes, and
 * a parameter is classified against one. Until it existed, Pravaha was a library you could assemble
 * a query out of rather than a system you could give a query to.
 *
 * <p><strong>Names are not identity.</strong> A computation is identified by its fingerprint, and
 * may answer to several names -- two people who register the same question get one computation and
 * one set of state, which is the entire point of hashing the plan rather than the text. It is
 * released when the last name is dropped, not the first.
 *
 * <p>Feeding is explicit rather than owned: rows arrive through {@link #accept}, time advances
 * through {@link #advanceWatermark}. Whatever drives those -- a pump on a virtual thread, a test
 * handing over four rows -- is somebody else's decision, and keeping it out of here is what makes
 * the lifecycle testable without a source plugin and a container.
 */
public final class RegisteredQuery implements AutoCloseable {

    private final QueryFingerprint fingerprint;
    private final String sql;
    private static final System.Logger LOG = System.getLogger(RegisteredQuery.class.getName());

    private final Set<String> names = new LinkedHashSet<>();
    private final ServedView view;
    private final ViewSink sink;
    private final QueryExecution execution;
    private volatile SourceFeed feed = SourceFeed.NONE;
    private volatile AutoCloseable checkpointer;
    private final Instant registeredAt;
    private final java.util.List<com.ash.messaging.pravaha.sql.plan.ParameterPlacement> placements;

    private final AtomicLong rowsIn = new AtomicLong();
    private final AtomicLong watermarkNanos = new AtomicLong(Long.MIN_VALUE);

    private volatile QueryState state = QueryState.RUNNING;
    private volatile PravahaException failure;

    RegisteredQuery(
            QueryFingerprint fingerprint,
            String sql,
            String name,
            ServedView view,
            ViewSink sink,
            QueryExecution execution,
            Instant registeredAt,
            java.util.List<com.ash.messaging.pravaha.sql.plan.ParameterPlacement> placements) {
        this.fingerprint = fingerprint;
        this.sql = sql;
        this.view = view;
        this.sink = sink;
        this.execution = execution;
        this.registeredAt = registeredAt;
        this.placements = java.util.List.copyOf(placements);
        this.names.add(name);
    }

    public QueryFingerprint fingerprint() {
        return fingerprint;
    }

    /** The SQL of the registration that created this computation. */
    public String sql() {
        return sql;
    }

    /** Every name this computation answers to, oldest first. */
    public synchronized Set<String> names() {
        return Set.copyOf(names);
    }

    public QueryState state() {
        // A dead lane is a failed query, whoever noticed first. Lane.run records its throwable and
        // exits; QueryExecution.checkHealth would rethrow it, and nothing in the server ever called
        // it -- so a query whose lane had died went on reporting RUNNING to `pravaha queries`, to
        // the status page, to the health endpoint and to every SDK, with an empty log. Ten surfaces
        // agreeing on the wrong answer because none of them asked.
        //
        // Latched rather than computed. This used to return FAILED while leaving the field saying
        // RUNNING, and everything that decides whether a query may be acted on reads the field:
        // pause, resume and subscribe all succeeded on a query this method was already calling
        // dead, and pause relabelled the failure as PAUSED for as long as it lasted. One transition
        // recorded once is the difference between a getter that reports and a getter that lies to
        // its own object.
        if (state == QueryState.RUNNING) {
            java.util.Optional<Throwable> dead = execution.laneFailure();
            if (dead.isPresent()) {
                fail(
                        dead.get() instanceof PravahaException known
                                ? known
                                : new PravahaException(
                                        RegistryErrors.ILLEGAL_TRANSITION,
                                        "query '" + anyName() + "' stopped because its lane died: " + dead.get(),
                                        dead.get()));
            }
        }
        return state;
    }

    /**
     * Off-heap this computation holds, named by the part holding it.
     *
     * <p>What an operator asks when a node holding many queries is using more memory than expected,
     * and what W9-7 could not answer: the JVM reports a pool total with no names in it.
     */
    public java.util.Map<String, Long> offHeapBytes() {
        return execution.offHeapBytes();
    }

    /**
     * How much bounded state this query holds, against what it may.
     *
     * <p>The number an operator needs before `PRV-4001` rather than in it. A query at nine tenths of
     * its ceiling is the one worth knowing about, and until ADR-037 nothing could see it -- the
     * operators counted it and nothing carried the count anywhere.
     */
    public com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline.StateUsage stateUsage() {
        return execution.stateUsage();
    }

    /** What this query's state holds in the overflow tier, and what compaction has given back (ADR-044). */
    public com.ash.messaging.pravaha.state.SpillStatistics spillStatistics() {
        return execution.spillStatistics();
    }

    /**
     * What each operator of this query's plan has done: rows in, rows out, state bytes, the
     * watermark it has reached and its sampled share of the query's own time.
     *
     * <p>In plan-node order, so entry {@code i} is the node {@code GET
     * /api/v1/queries/{name}/plan} calls {@code ni}. Empty when the node runs with {@code
     * pravaha.metrics.operators} off, which is deliberately distinguishable from a plan whose
     * counters happen to be zero.
     */
    public java.util.List<com.ash.messaging.pravaha.runtime.exec.OperatorMetrics.Snapshot> operatorMetrics() {
        return execution.operatorMetrics();
    }

    /**
     * Which path each filter and projection chain of this query runs on: one line per chain,
     * starting {@code generated:} or {@code interpreted:}, with the reason (C-7).
     */
    public java.util.List<String> executionPaths() {
        return execution.executionPaths();
    }

    /**
     * How often and how long this query's writers had nowhere to put a row.
     *
     * <p>The lane's view, so on a shared lane it names every query whose writer waited there --
     * which is the point: a query that is fine can be the one waiting, because the lane it waits
     * on is being held by somebody else.
     */
    public com.ash.messaging.pravaha.runtime.lane.LaneBackpressure.Snapshot backpressure() {
        return execution.backpressure();
    }

    /** Episodes this query's own pumps spent waiting for room, and how long they lasted in total. */
    public long[] pumpBackpressure() {
        return execution.pumpBackpressure();
    }

    /** The view this computation keeps current. */
    public ServedView view() {
        return view;
    }

    public StreamSchema outputSchema() {
        return view.schema();
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    /**
     * Whether a registration asked for this computation to keep a lane of its own ({@code lane =
     * 'dedicated'}), whatever the node's lane-sharing mode. A computation that is not dedicated may
     * still be on a lane of its own -- because sharing is off, or not yet on under {@code auto}.
     */
    public boolean dedicatedLane() {
        return dedicatedLane;
    }

    /** Marks this computation dedicated; see {@link #dedicatedLane()}. It is never moved, so this only records. */
    void dedicateLane() {
        dedicatedLane = true;
    }

    private volatile boolean dedicatedLane;

    /**
     * The shared lane this computation runs on, or empty when it has a lane of its own. Kept on the
     * computation rather than by fingerprint, because a replacement that moves a query between lanes
     * runs two computations with one fingerprint side by side.
     */
    public java.util.Optional<Integer> sharedLane() {
        return java.util.Optional.ofNullable(sharedLane);
    }

    /**
     * Where this computation runs, in one word: {@code dedicated} (a lane of its own because a
     * registration asked for it), {@code shared} (on the shared lane {@link #sharedLane()} names) or
     * {@code own} (a lane of its own because sharing is off, not yet on under {@code auto}, or every
     * shared lane was full when it was placed).
     */
    public String lanePlacement() {
        return dedicatedLane ? "dedicated" : sharedLane != null ? "shared" : "own";
    }

    void placedOnSharedLane(int lane) {
        sharedLane = lane;
    }

    private volatile Integer sharedLane;

    /**
     * Where each of this query's parameters had to be applied, and what that cost (ADR-032).
     *
     * <p>Empty for a query with no parameters. A {@code REGISTRATION} placement means this
     * computation exists per distinct binding; a {@code TAP} placement means the same filtering is
     * available for nothing by subscribing with it instead.
     */
    public java.util.List<com.ash.messaging.pravaha.sql.plan.ParameterPlacement> parameterPlacements() {
        return placements;
    }

    /**
     * Parameters that did not need to fork this computation.
     *
     * <p>What a console shows as a suggestion and an operator reads as "you are running N copies of
     * something that could be one".
     */
    public java.util.List<com.ash.messaging.pravaha.sql.plan.ParameterPlacement> avoidableForks() {
        return placements.stream()
                .filter(placement ->
                        placement.placement() == com.ash.messaging.pravaha.sql.plan.ParameterPlacement.Placement.TAP)
                .toList();
    }

    /** Rows accepted since registration. */
    public long rowsIn() {
        // Both halves, because rows arrive by two routes and an operator counting them does not
        // care which. accept() is the push path -- an embedder with its own loop, a test; the feed
        // is the pull path and writes into the lane's inbox without passing through accept() at
        // all, so counting only the first reports zero for every query a source is driving.
        return rowsIn.get() + feed.rowsFed();
    }

    /**
     * The watermark reached, or empty if this query derives none.
     *
     * <p>TIME-12. This reported only what {@link #advanceWatermark} had been told, and the engine
     * does not go through that method: when the registry arms watermark generation, the execution
     * derives and advances them on its own timer. So the field stayed at {@code Long.MIN_VALUE} for
     * every query on every node, and {@code pravaha_query_watermark_lag_seconds} — the engine's only
     * watermark instrument — read {@code NaN} whatever event time was doing.
     *
     * <p>Nothing was missing. {@code QueryExecution.watermarkNanos()} had the answer the whole time
     * and nobody asked it. The push path's value still wins when it is higher, because an embedder
     * driving watermarks by hand is a real caller and its number is not the execution's.
     */
    public Optional<Long> watermarkNanos() {
        long pushed = watermarkNanos.get();
        java.util.OptionalLong derived = execution.watermarkNanos();
        if (derived.isPresent()) {
            return Optional.of(pushed == Long.MIN_VALUE ? derived.getAsLong() : Math.max(pushed, derived.getAsLong()));
        }
        return pushed == Long.MIN_VALUE ? Optional.empty() : Optional.of(pushed);
    }

    /**
     * How many of this query's input partitions are excluded from its watermark, how often one has
     * been, and how often one reported a watermark below the lane's (TIME-8).
     *
     * <p>The three numbers that explain a watermark that is not moving, and the reason a stalled
     * query and a healthy one looked alike on every shipped surface. A lag gauge says a watermark
     * is behind; only these say whether a partition went quiet, how often, and whether a source is
     * reporting time that goes backwards. Zeroes on a query that derives no watermarks, which is a
     * different statement from "none idle" and is why the partition count is here too.
     */
    public com.ash.messaging.pravaha.runtime.time.WatermarkTracker.Diagnostics watermarkDiagnostics() {
        return execution.watermarkDiagnostics();
    }

    /** Why it failed, if it did. */
    public Optional<PravahaException> failure() {
        if (failure != null) {
            return Optional.of(failure);
        }
        return execution
                .laneFailure()
                .map(cause -> new PravahaException(
                        RegistryErrors.QUERY_FAILED,
                        "query '" + anyName() + "' stopped: its lane died with " + cause,
                        cause));
    }

    /**
     * Feeds one row in.
     *
     * <p>A paused query drops rows rather than buffering them. Buffering would turn a pause into a
     * memory commitment of unknown size, and the operator who paused it did so to stop it doing
     * work. What a pause promises is that the view keeps answering at the frontier it reached, not
     * that nothing is missed -- and saying so plainly is better than a queue that silently decides
     * how much of the stream a pause is worth.
     */
    public boolean accept(RowView row) {
        return accept(null, row);
    }

    /**
     * Hands a row to one named input.
     *
     * <p>Needed by anything with more than one: a join's two sides are two inboxes, and a row
     * arriving with no idea which side it is on cannot be placed. {@code QueryExecution} has had
     * this form all along and the registry never exposed it, so a two-stream query could not be fed
     * by pushing at all -- it could be registered, it would report RUNNING, and nothing could ever
     * reach it.
     *
     * @param streamName which input the row arrived on, or null for a query that reads only one
     */
    public boolean accept(String streamName, RowView row) {
        if (state != QueryState.RUNNING) {
            return false;
        }
        try {
            // Handed to the lane, which applies it on its own thread. A caller that must see the
            // row reflected in the view waits with awaitQuiescent; one that is feeding a stream
            // does not care, because the next read is later anyway.
            if (!(streamName == null ? execution.accept(row) : execution.accept(streamName, row))) {
                // The inbox is full. Backpressure, not failure -- the caller decides whether its
                // source can be slowed, and saying so is more useful than blocking its thread
                // inside a registry.
                return false;
            }
            rowsIn.incrementAndGet();
        } catch (PravahaException e) {
            fail(e);
            throw e;
        } catch (RuntimeException e) {
            PravahaException wrapped = new PravahaException(
                    RegistryErrors.QUERY_FAILED,
                    "query '" + anyName() + "' failed while processing a row: " + e.getMessage(),
                    e);
            fail(wrapped);
            throw wrapped;
        }
        return true;
    }

    /**
     * Waits until everything handed to {@link #accept} has been applied.
     *
     * <p>Here because the engine applies rows on the lane's thread, so a caller that feeds rows and
     * then reads the view is otherwise racing it. Feeding a live stream needs none of this; a test,
     * or anything that wants a definite answer at a definite moment, does.
     */
    public boolean awaitApplied(java.time.Duration timeout) {
        return execution.awaitQuiescent(timeout);
    }

    /**
     * Advances event time, which is what closes windows and makes results appear.
     *
     * <p>Committing the sink here rather than leaving it to the caller is deliberate. A watermark is
     * the statement that no earlier data is coming, so it is exactly the moment at which what the
     * view holds becomes an answer somebody may read. Splitting the two invites a caller to advance
     * without committing and wonder why the view is empty.
     */
    public void advanceWatermark(long nanos) {
        if (state != QueryState.RUNNING) {
            return;
        }
        try {
            execution.advanceWatermark(nanos);
            watermarkNanos.accumulateAndGet(nanos, Math::max);
            commitView();
        } catch (PravahaException e) {
            fail(e);
            throw e;
        }
    }

    /**
     * Attaches a consumer to this computation (ADR-025).
     *
     * <p>Subscriptions come and go without the computation noticing, and it outlives all of them.
     * That separation is why a dashboard reconnecting costs nothing: the state is warm because it
     * belongs to the query, not to whoever was watching.
     *
     * <p>Changes arrive per commit, never per row, so a subscriber never sees a half-applied window.
     * They carry weights: {@code -1} withdraws a row, which is how a late-data correction reaches a
     * consumer rather than as a special message type it has to recognise.
     *
     * <p>By default they are the changelog: what the query applied, weights verbatim. With {@link
     * SubscriptionOptions#followingTheAnswer()} they are the view's <em>answer</em> changing
     * (KEYEDWT-1): per commit, each row that left what a reader sees at {@code -1} and each that
     * entered it at {@code +1} -- so an upsert that replaces a key's row arrives as the old row
     * withdrawn and the new one added, and weights summed are exactly the view.
     */
    public Subscription subscribe(
            SubscriptionOptions options,
            java.util.function.Consumer<java.util.List<com.ash.messaging.pravaha.serving.ViewChange>> consumer) {
        refuseIfReplaced();
        // state(), not the field. Attaching a subscriber to a query whose lane has died gives it a
        // handle that will never deliver anything and never say why.
        if (state().isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION, "cannot subscribe to '" + anyName() + "': it is " + state());
        }
        return subscribe(options, SubscriptionFilter.none(), consumer);
    }

    /**
     * Attaches a consumer that sees only the rows matching {@code filter}.
     *
     * <p>Applied at the tap, which costs nothing and shares everything: this computation is the same
     * one every other subscriber is reading, however differently they filter. It is sound here
     * because a filter may be applied at the tap exactly when the view carries the columns it names,
     * and {@link SubscriptionFilter} refuses one that names a column this view does not have rather
     * than ignoring it.
     */
    public Subscription subscribe(
            SubscriptionOptions options,
            SubscriptionFilter filter,
            java.util.function.Consumer<java.util.List<com.ash.messaging.pravaha.serving.ViewChange>> consumer) {
        return subscribeAs(null, options, filter, consumer);
    }

    /**
     * Attaches a consumer that asked for this computation by a particular one of its names.
     *
     * <p>STRM-14. A computation answers to every name registered over the same question, and
     * dropping one of them leaves it running under the others -- correctly, because somebody else
     * is still reading it. What was not correct is that a subscriber attached under the dropped
     * name went on being streamed: {@code drop} removed the name from the registry and the view
     * from the catalogue, {@code removeName} returned false because another name held the
     * computation, so nothing terminal happened, and the subscriber's loop tested only
     * {@code state().isTerminal()}. Two server responses to the same name at the same instant
     * contradicted each other -- one streaming rows, the other refusing the view as nonexistent --
     * and the authorization re-check went on asking the policy about a name it could no longer
     * have an opinion on.
     *
     * @param underName the name the caller asked for, or null for a caller that holds this object
     *     directly and has no name in mind
     */
    public Subscription subscribeAs(
            String underName,
            SubscriptionOptions options,
            SubscriptionFilter filter,
            java.util.function.Consumer<java.util.List<com.ash.messaging.pravaha.serving.ViewChange>> consumer) {
        refuseIfReplaced();
        // state(), not the field. Attaching a subscriber to a query whose lane has died gives it a
        // handle that will never deliver anything and never say why.
        if (state().isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION, "cannot subscribe to '" + anyName() + "': it is " + state());
        }
        SubscriptionOptions chosen = options == null ? SubscriptionOptions.DEFAULT : options;
        boolean answer = chosen.changes() == SubscriptionOptions.Changes.ANSWER;
        return new Subscription(
                underName == null ? anyName() : underName,
                view.keyOrdinals(),
                chosen,
                filter,
                consumer,
                subscription -> track(
                        subscription,
                        answer ? sink.onAnswer(subscription::onCommit) : sink.onCommit(subscription::onCommit)));
    }

    /**
     * Subscriptions open on this computation, so a cutover can tell them the view was replaced.
     *
     * <p>Removed as each one detaches, which is what keeps this from being a list of every
     * subscriber this query has ever had.
     */
    private final java.util.List<Subscription> subscriptions = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AutoCloseable track(Subscription subscription, AutoCloseable detach) {
        subscriptions.add(subscription);
        return () -> {
            subscriptions.remove(subscription);
            detach.close();
        };
    }

    /**
     * Ends every subscription on this computation, with the reason (ADR-046).
     *
     * <p>Called at a cutover, after the final commit has been delivered: a subscriber sees every
     * change the version it was following ever made, and is then told that the name it subscribed
     * to now answers a different question. Handing it the new version's changes instead would be a
     * copy that is a mixture of two queries with nothing to say so.
     */
    void endSubscriptions(PravahaException why) {
        for (Subscription subscription : subscriptions) {
            subscription.endBecause(why);
        }
    }

    /**
     * Ends the subscriptions opened under one of this computation's names, with the reason.
     *
     * <p>STRM-14. For a drop that does not release the computation: the name is gone and its view
     * with it, so a subscriber that asked for that name has nothing left to watch, while every
     * subscriber on a surviving name is correctly untouched -- which is the mirror case STRM-067
     * confirmed was already right, and the reason this is by name rather than wholesale.
     */
    /**
     * Closes this computation because the node is going down, not because anybody dropped it
     * (STRM-12).
     *
     * <p>One method rather than two calls at the caller, because the order is the point and the
     * reason is the whole fix. A node going down and a query being dropped are different events,
     * and only the first is worth reconnecting after: a graceful shutdown drains in-flight Flight
     * calls, so the stream used to end with {@code listener.completed()} -- "this stream is
     * finished" -- for a query that is journalled, comes back {@code RUNNING} and moves on without
     * the client that stopped. Said first, because {@link #close()} would otherwise say the query
     * was dropped and {@code endBecause} is a no-op once a subscription has ended: the first
     * reason there is the one reported, which is why this one has to be.
     */
    void closeForShutdown() {
        endSubscriptions(SubscriptionEndings.nodeStopping(anyName()));
        close();
    }

    void endSubscriptionsUnder(String name, PravahaException why) {
        for (Subscription subscription : subscriptions) {
            if (name.equals(subscription.queryName())) {
                subscription.endBecause(why);
            }
        }
    }

    /** How many subscriptions are open, which is what a cutover reports having ended. */
    int openSubscriptions() {
        return subscriptions.size();
    }

    /**
     * Attaches a consumer that starts from the view's state and then hears every commit after it,
     * with no gap and no overlap between the two (SUB-1).
     *
     * <p>{@link #subscribe} starts at the next commit boundary and carries no state, so a client
     * mirroring the view has to read it too -- and whichever it does first, the commit in flight at
     * the time reaches it by neither path. Here the listener is handed the view's committed rows at
     * a commit boundary, with that commit's frontier, and then each later commit whole; see {@link
     * com.ash.messaging.pravaha.serving.ViewSink#onCommitFromSnapshot} for why nothing falls between.
     *
     * <p>When a commit is in flight as this attaches, the snapshot belongs at its end, so this
     * commits what has been applied before returning -- what the feed's own timer would do a moment
     * later. The snapshot has normally arrived by the time this returns; it is certain to arrive
     * before any commit does.
     *
     * <p>The snapshot is filtered and never buffered or conflated. Commits after it go through {@code
     * options} like any subscription's: a subscriber keeping an exact copy should choose {@link
     * SubscriptionOptions.Overflow#FAIL}, since a conflated or dropped change is a copy gone wrong.
     */
    public Subscription subscribeFromSnapshot(
            SubscriptionOptions options, SubscriptionFilter filter, SubscriptionListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        refuseIfReplaced();
        if (state().isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION, "cannot subscribe to '" + anyName() + "': it is " + state);
        }
        SubscriptionOptions chosen = options == null ? SubscriptionOptions.DEFAULT : options;
        boolean answer = chosen.changes() == SubscriptionOptions.Changes.ANSWER;
        Subscription subscription = new Subscription(
                anyName(),
                view.keyOrdinals(),
                chosen,
                filter,
                listener,
                attached -> track(
                        attached, snapshotThen(answer, new com.ash.messaging.pravaha.serving.ViewChangeListener() {
                            @Override
                            public void onSnapshot(
                                    java.util.List<com.ash.messaging.pravaha.serving.ViewChange> rows, long at) {
                                attached.onSnapshot(rows, at);
                            }

                            @Override
                            public void onCommit(
                                    java.util.List<com.ash.messaging.pravaha.serving.ViewChange> changes, long at) {
                                attached.onCommit(changes, at);
                            }
                        })));
        if (sink.awaitingSnapshot()) {
            // Not needed for correctness -- the next commit from anywhere ends the wait -- but a
            // paused query's feed commits nothing, and nobody should wait on a timer for a boundary
            // this call can draw. commitView, not commit: no aggregate is asked to publish here.
            commitView();
        }
        return subscription;
    }

    /** Attaches {@code listener} from a snapshot: of the answer (KEYEDWT-1), or of the view's Z-set. */
    private AutoCloseable snapshotThen(boolean answer, com.ash.messaging.pravaha.serving.ViewChangeListener listener) {
        return answer ? sink.onAnswerFromSnapshot(listener) : sink.onCommitFromSnapshot(listener);
    }

    /** {@link #subscribeFromSnapshot(SubscriptionOptions, SubscriptionFilter, SubscriptionListener)}, unfiltered. */
    public Subscription subscribeFromSnapshot(SubscriptionListener listener) {
        return subscribeFromSnapshot(SubscriptionOptions.DEFAULT, SubscriptionFilter.none(), listener);
    }

    /** Subscribes with the default buffer and conflation. */
    public Subscription subscribe(
            java.util.function.Consumer<java.util.List<com.ash.messaging.pravaha.serving.ViewChange>> consumer) {
        return subscribe(SubscriptionOptions.DEFAULT, consumer);
    }

    /**
     * Waits until every open subscription has been handed what this computation committed to it.
     *
     * <p>Delivery runs on each subscription's own thread (STRM-8), so a commit returning is not a
     * subscriber having received it. This is how anything that needs "every subscriber has seen
     * this" can know rather than guess; the engine itself never calls it. Returns false if the
     * deadline passes first, or if a subscription closed with changes still waiting -- the same
     * answer {@link Subscription#awaitQuiet} gives, and for the same reason.
     */
    public boolean awaitSubscriptionsQuiet(java.time.Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Subscription subscription : subscriptions) {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !subscription.awaitQuiet(java.time.Duration.ofNanos(left))) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many consumers are attached.
     *
     * <p>Worth exposing rather than keeping private: it is what a console shows next to a query, and
     * it is how anything waiting for a subscription to be live can know rather than guess.
     */
    public int subscriberCount() {
        // Sinks listen on the same commit as subscribers do, and are not subscribers: a query
        // writing to a table has nobody attached to it, and a console that said otherwise would be
        // counting the table.
        return sink.listenerCount() - sinksAttached.get();
    }

    /** Sinks listening on this computation's commits (ADR-043); see {@link #attachSink}. */
    private final java.util.concurrent.atomic.AtomicInteger sinksAttached =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Attaches a sink to this computation's commits, and returns what detaches it.
     *
     * <p>The same hook a subscription uses, so a sink sees exactly what a subscriber sees: whole
     * commits, inserts and retractions in the order they were applied, never a half-applied window.
     */
    AutoCloseable attachSink(SinkDelivery delivery) {
        // An upsert sink follows the answer, a changelog sink the changelog (SINKKEYROWS-1).
        AutoCloseable detach = delivery.followsAnswer() ? sink.onRetainedAnswer(delivery) : sink.onCommit(delivery);
        sinkDeliveries.add(delivery);
        sinksAttached.incrementAndGet();
        java.util.concurrent.atomic.AtomicBoolean detached = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (detached.compareAndSet(false, true)) {
                detach.close();
                sinkDeliveries.remove(delivery);
                sinksAttached.decrementAndGet();
            }
        };
    }

    /** Publishes what has been applied so far, without claiming time has moved. */
    public void commit() {
        if (state != QueryState.RUNNING) {
            return;
        }
        // Publish before committing. An unwindowed aggregate has no event that says "your answer
        // changed" -- a window has its end, and this has nothing -- so whoever commits is also who
        // asks it to publish. The emission runs on the lane's thread and the commit picks it up on
        // the next pass, which is why a caller may see the previous answer once.
        execution.publishContinuousAggregates();
        commitView();
    }

    /**
     * Held for the whole of a view commit, including its delivery to every listener, and by a
     * checkpoint's output cut.
     *
     * <p>A view commit is two steps -- publish, then hand the batch to each listener -- and a sink
     * hears a commit only in the second. Without this, a checkpoint could snapshot a view that
     * already holds commit C while a sink has not yet been handed C, and prepare the sink's
     * transaction without it: after a restore the sink would lack C and nothing would replay it.
     *
     * <p>Never held while waiting for the lane. {@link #commit} publishes continuous aggregates
     * <em>before</em> taking it, because that waits for a task on the lane, and the lane may be
     * inside {@link #cutOutput} waiting for this.
     */
    private final Object commitLock = new Object();

    private void commitView() {
        synchronized (commitLock) {
            sink.commitApplied();
        }
    }

    /**
     * Cuts this computation's output for checkpoint {@code checkpointId}: on the lane's thread, at
     * the checkpoint's marker (see {@code QueryExecution.cuttingOutputWith}).
     *
     * <p><strong>The ordering argument, which is the whole of exactly-once output.</strong>
     *
     * <ol>
     *   <li>The lane is holding its input at the marker, so every row the checkpoint's offsets
     *       exclude has been applied to the view and none after has. The lane state snapshotted in
     *       the same task describes the same position.
     *   <li>Under {@link #commitLock}, the view is committed. That publishes exactly the rows before
     *       the marker and hands them to every listener, so each sink has now been
     *       <em>written</em> everything up to the cut and nothing past it. No other commit can be
     *       half-delivered, because every commit holds the same lock.
     *   <li>The view is snapshotted, still under the lock: the checkpoint's view is the view at the
     *       cut, not at whatever moment the checkpointing thread got round to it.
     *   <li>Each transactional sink is prepared, and its handle -- with every earlier one not yet
     *       committed -- goes into the checkpoint beside the view. The sink's next transaction is
     *       begun before the lock is released, so the next commit's rows go into it.
     *   <li>The checkpoint is stored, and only once {@code store} has returned is each handle
     *       committed ({@link #checkpointDurable}). A crash before that leaves a prepared transaction
     *       nobody commits except a restore from this checkpoint.
     *   <li>A restore puts back the view and the lane state from 2 and 3, commits every handle the
     *       checkpoint recorded -- idempotently, since the crash may have come after the commit --
     *       and tells the sink to abandon everything after it, which the replay writes again.
     * </ol>
     *
     * <p>So the sink's committed contents are, at every moment, the checkpointed view's, and the
     * replay after a restore starts from exactly that view. Neither a duplicate nor a gap has
     * anywhere to come from. What this does not do is make the view itself more right than the
     * checkpoint is: a sink matches the view, exactly.
     *
     * @return the view's snapshot and each attached sink's section, for the checkpoint
     */
    java.util.Map<String, byte[]> cutOutput(long checkpointId) {
        synchronized (commitLock) {
            sink.commitApplied();
            java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
            byte[] contents = view.snapshot();
            entries.put(QueryExecution.SERVED_VIEW_STATE, contents);
            for (SinkDelivery delivery : sinkDeliveries) {
                delivery.cut(checkpointId).ifPresent(section -> entries.put(SinkDelivery.stateKey(delivery), section));
            }
            // A sink the restored checkpoint knew and no registration has claimed yet: still owed its
            // recorded handles and still holding the restored view's contents, so it is carried as it
            // was rather than forgotten by the first checkpoint after a restart.
            restoredSinks.forEach(
                    (name, restored) -> entries.putIfAbsent(SinkDelivery.STATE_PREFIX + name, restored.carried()));
            // ADR-056: what this query has consumed of the answer it follows, at this same cut -- or,
            // before its feed has opened, what the restored checkpoint said, carried as it was.
            java.util.function.Supplier<byte[]> input = upstreamCut;
            byte[] consumed = input != null ? input.get() : restoredUpstreamInput;
            if (consumed != null) {
                entries.put(QueryChains.INPUT_STATE, consumed);
            }
            lastCut = Math.max(lastCut, checkpointId);
            return entries;
        }
    }

    /** Tells every attached sink that a checkpoint is durable, so it may commit what it prepared. */
    void checkpointDurable(com.ash.messaging.pravaha.state.checkpoint.Checkpoint checkpoint) {
        for (SinkDelivery delivery : sinkDeliveries) {
            delivery.durable(checkpoint.id());
        }
    }

    /**
     * Remembers what a restored checkpoint recorded about sinks, for the registrations that will
     * claim it -- the first as this computation starts, any other when its name is registered again.
     */
    void restoredFrom(com.ash.messaging.pravaha.state.checkpoint.Checkpoint checkpoint) {
        byte[] contents = checkpoint.operatorState().get(QueryExecution.SERVED_VIEW_STATE);
        // Every sink decoded before anything is recorded (RESTOREPART-1): one that cannot be read
        // throws with this query still recording no restore, so the caller can start it from its
        // sources rather than with half the sinks claiming a checkpoint the query does not hold.
        java.util.Map<String, SinkDelivery.Restored> sinks = new java.util.HashMap<>();
        checkpoint.operatorState().forEach((key, bytes) -> {
            if (key.startsWith(SinkDelivery.STATE_PREFIX)) {
                sinks.put(
                        key.substring(SinkDelivery.STATE_PREFIX.length()),
                        SinkDelivery.Restored.decode(checkpoint.id(), bytes, contents));
            }
        });
        lastCut = Math.max(lastCut, checkpoint.id());
        restored = true;
        restoredUpstreamInput = checkpoint.operatorState().get(QueryChains.INPUT_STATE);
        restoredSinks.putAll(sinks);
    }

    /** Whether this computation's state came back from a checkpoint. */
    boolean restored() {
        return restored;
    }

    /** What a restored checkpoint recorded of the answer this query follows, or null (ADR-056). */
    byte[] restoredUpstreamInput() {
        return restoredUpstreamInput;
    }

    /** Where each checkpoint's cut reads what this query has consumed of the answer it follows. */
    void cuttingUpstreamWith(java.util.function.Supplier<byte[]> cut) {
        this.upstreamCut = cut;
    }

    private volatile boolean restored;
    private volatile byte[] restoredUpstreamInput;
    private volatile java.util.function.Supplier<byte[]> upstreamCut;

    /** What the restored checkpoint recorded for the sink of registration {@code name}, taken once. */
    java.util.Optional<SinkDelivery.Restored> claimRestoredSink(String name) {
        return java.util.Optional.ofNullable(restoredSinks.remove(name));
    }

    /** Forgets restored sinks no registration claimed: names refused at recovery, or dropped. */
    void forgetUnclaimedSinks() {
        restoredSinks.clear();
    }

    /** The label a sink's next transaction begins with: one more than the newest cut known. */
    long nextTransactionLabel() {
        return lastCut + 1;
    }

    /** Whether this computation takes checkpoints at all, which a transactional sink needs. */
    boolean checkpointed() {
        return checkpointDirectory != null;
    }

    private final java.util.Map<String, SinkDelivery.Restored> restoredSinks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.List<SinkDelivery> sinkDeliveries = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long lastCut;

    synchronized void addName(String name) {
        names.add(name);
    }

    /**
     * Drops one of this computation's names: ends what was watching it, then forgets it.
     *
     * <p>Both steps, in this order, in one call, because the order is load-bearing and a caller
     * that got it wrong would fail silently (STRM-14). After {@link #removeName} this name is not
     * one the computation knows, so the subscriptions opened under it could no longer be found --
     * and they would go on being streamed rows under a name a read of the view refuses as
     * nonexistent, with the policy still being asked about a name it can no longer have an opinion
     * on. `QueryRegistry.drop` used to do the two steps itself with a comment saying which came
     * first; a comment is not a guarantee.
     *
     * <p>Only the subscriptions opened under <em>this</em> name end. A subscriber on a name that
     * still answers is untouched, which is `STRM-067`'s mirror case and the whole point of two
     * registrations sharing one computation.
     *
     * @return true when no names are left and the computation should be released, exactly as
     *     {@link #removeName} reports it
     */
    synchronized boolean dropName(String name) {
        endSubscriptionsUnder(name, SubscriptionEndings.dropped(name));
        boolean last = removeName(name);
        releaseIndexes(name);
        return last;
    }

    /**
     * The equality indexes each name declared (IDXSHR-1). A view shared by several names keeps the
     * union; dropping one name lets go of the indexes only it declared. Guarded by this.
     */
    private final java.util.Map<String, java.util.Set<Integer>> indexesByName = new java.util.HashMap<>();

    /**
     * Keeps an equality index over each of {@code ordinals} on this computation's view, declared by
     * {@code name}. Written down first, so a refusal part-way ({@code PRV-2074}) is undone by the
     * drop that unwinds the registration.
     */
    synchronized void declareIndexes(String name, java.util.List<Integer> ordinals) {
        if (ordinals.isEmpty()) {
            return;
        }
        indexesByName
                .computeIfAbsent(name, declared -> new java.util.LinkedHashSet<>())
                .addAll(ordinals);
        ordinals.forEach(view::index);
    }

    /** The equality indexes {@code name} declared on this computation, in declaration order. */
    synchronized java.util.List<Integer> indexesDeclaredBy(String name) {
        return java.util.List.copyOf(indexesByName.getOrDefault(name, java.util.Set.of()));
    }

    /**
     * Forgets which indexes {@code name} declared here, keeping them on the view: a cutover moved the
     * name to another computation, and this one keeps its indexes for a rollback.
     */
    synchronized void forgetIndexes(String name) {
        indexesByName.remove(name);
    }

    /** Drops every index only {@code name} declared (IDXSHR-1); one another name declares stays. */
    private void releaseIndexes(String name) {
        java.util.Set<Integer> declared = indexesByName.remove(name);
        if (declared == null) {
            return;
        }
        for (int ordinal : declared) {
            boolean stillDeclared = indexesByName.values().stream().anyMatch(others -> others.contains(ordinal));
            if (!stillDeclared) {
                view.dropIndex(ordinal);
            }
        }
    }

    /** Removes a name; returns true when none are left and the computation should be released. */
    synchronized boolean removeName(String name) {
        names.remove(name);
        if (names.isEmpty()) {
            // STRM-17. Kept precisely for the message that comes next: once the set is empty,
            // anyName() fell back to the fingerprint and the one refusal whose job is to tell
            // somebody which query they cannot subscribe to read "cannot subscribe to
            // 'a740dfd20964': it is DROPPED" -- an identifier that appears nowhere in their code.
            lastName = name;
        }
        return names.isEmpty();
    }

    /**
     * The name this computation last answered to, for a message raised after it stopped answering.
     *
     * <p>Not a name it can be reached by: it is out of {@link #names} and the registry has
     * forgotten it. It exists so a refusal can be about the word the caller used.
     */
    private String lastName;

    /** Any one of this computation's names, for a log line or a wire response. */
    public synchronized String name() {
        return anyName();
    }

    synchronized String anyName() {
        if (!names.isEmpty()) {
            return names.iterator().next();
        }
        return lastName != null ? lastName : fingerprint.shortForm();
    }

    /**
     * Stops advancing, keeping the view answering where it reached.
     *
     * <p>Idempotent, deliberately: pausing a paused query succeeds and changes nothing. These verbs
     * name the state you want the query in, not a transition you believe it is about to make, so a
     * script that pauses before maintenance need not know whether somebody already did. A query
     * that has failed or been dropped is refused, because there the state asked for is unreachable.
     *
     * <p>QA recorded the same-state case as an illegal transition that was not being refused. It is
     * the intended behaviour and nothing said so, which is the actual defect and is fixed in the
     * user guide and here.
     */
    void pause() {
        requireLive("pause");
        state = QueryState.PAUSED;
        // A feed writes into the lane's inbox without passing through accept(), so a pause that
        // stopped only at accept() would not stop anything a source was pushing.
        feed.pause();
    }

    void resume() {
        if (state().isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "query '" + anyName() + "' is " + state + " and cannot be resumed. A failed query is "
                            + "not restarted in place: whatever failed is still in the state it failed in, "
                            + "and restarting over it hides the cause");
        }
        state = QueryState.RUNNING;
        feed.resume();
    }

    private void requireLive(String action) {
        // state(), not the field: a lane that has died has not written to the field yet, and
        // refusing on a stale RUNNING is how a failed query got paused.
        if (state().isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "cannot " + action + " query '" + anyName() + "': it is " + state);
        }
    }

    private void fail(PravahaException cause) {
        this.failure = cause;
        this.state = QueryState.FAILED;
        // E-13. The view outlives the query, and until this line nothing downstream could tell that
        // its producer had died: a SELECT answered from the snapshot frozen at the moment of
        // failure, indistinguishable from live data. Marking the view is what lets ViewQuery refuse
        // rather than serve three-hour-old rows with a confident face.
        view.failed(cause);
    }

    @Override
    public void close() {
        if (state != QueryState.DROPPED) {
            state = QueryState.DROPPED;
            // The feed first, and the order is the whole point: closing the execution while a pump
            // is mid-write leaves it writing into a lane that has gone. Stop the rows, then stop
            // the thing they were going to.
            feed.close();
            // Before the execution, and for the same reason the feed is: a checkpoint taken while
            // the lanes are closing reads state that is half gone, and writes it down as if it were
            // whole. A bad checkpoint is worse than a missing one, because recovery trusts it.
            if (checkpointer != null) {
                try {
                    checkpointer.close();
                } catch (Exception e) {
                    // Its scheduler is a daemon, so a checkpointer that will not close cannot keep
                    // the JVM alive, and the execution below must be released either way.
                }
            }
            execution.close();
            // W-2. execution.close() is where each lane runs finish(), and finish() is what fires a
            // stateful query's final windows -- so the last windows of every query were emitted here
            // and then thrown away, because the feed thread is the only thing that commits and it
            // was closed two lines above.
            //
            // The ordering above is not the defect and must not be changed: closing the execution
            // while a pump is mid-write leaves it writing into a lane that has gone, which is the
            // failure that ordering was introduced to fix. What was missing is a commit *after* the
            // last emit, which is this.
            //
            // Not routed through advanceWatermark: that returns early unless the state is RUNNING,
            // and by here it is DROPPED by design -- no new work may be accepted, but what the
            // engine already produced still has to reach the view.
            try {
                commitView();
            } catch (RuntimeException e) {
                // A close must not throw on its way out. The query is going; a view that cannot
                // take its final commit is worth a line, not an exception nobody can act on.
                LOG.log(
                        System.Logger.Level.WARNING,
                        "could not commit the final windows of " + names + ": " + e.getMessage(),
                        e);
            }
            // STRM-12. After the final commit, so a subscriber sees every change this computation
            // ever made and is then told the query is gone -- and before this line nothing told it
            // anything. Nothing closed the Subscription and nothing removed it from the sink's
            // listeners, so three objects reported isClosed() == false with an empty failure(),
            // attached to a closed computation, waiting for changes that would never come, and
            // subscriberCount() -- which OPERATIONS.md offers as the operator's signal that nobody
            // is watching a query -- never returned to zero after any drop.
            //
            // endBecause is a no-op on a subscription that has already ended, so a shutdown's
            // NODE_STOPPING (set by QueryRegistry before it closes anything) wins over this, and a
            // cutover's VIEW_REPLACED wins over both. The first reason is the true one.
            endSubscriptions(SubscriptionEndings.dropped(anyName()));
        }
    }

    /**
     * The version this computation was replaced by, once a cutover has moved its name (ADR-046).
     *
     * <p>It keeps running -- that is what makes a rollback instant -- and it keeps answering
     * nobody: the name is the other version's now. A subscription opened here would follow a view
     * no reader can reach, so it is refused rather than served.
     */
    private volatile String replacedBy;

    void replacedBy(String version) {
        this.replacedBy = version;
    }

    /** Whether this computation has been replaced and is retained only so a rollback can be instant. */
    public boolean isRetiredByReplacement() {
        return replacedBy != null;
    }

    private void refuseIfReplaced() {
        String by = replacedBy;
        if (by != null) {
            throw new PravahaException(
                    com.ash.messaging.pravaha.backfill.BackfillErrors.VIEW_REPLACED,
                    "this version of '" + anyName() + "' was replaced by " + by + " at a cutover and is kept only "
                            + "so the replacement can be rolled back. Subscribe to the name, which the new "
                            + "version answers.");
        }
    }

    /**
     * Where each of this computation's sources has got to, by stream, read between rows.
     *
     * <p>What a cutover compares: two versions are at the same point in their input when every
     * partition of every stream they both read reports the same position (ADR-046).
     */
    public java.util.Map<String, java.util.List<String>> sourcePositions(java.time.Duration timeout) {
        return execution.sourcePositions(timeout);
    }

    /** Takes a checkpoint now, or empty when this computation is not checkpointed. */
    Optional<com.ash.messaging.pravaha.state.checkpoint.Checkpoint> checkpointNow() {
        return checkpointer instanceof com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer periodic
                ? Optional.of(periodic.checkpointNow())
                : Optional.empty();
    }

    /**
     * Numbers this computation's later checkpoints, and its sinks' transactions, above {@code id}.
     *
     * <p>For a cutover handing a sink over: the SPI's transaction labels only increase, so a sink
     * moving to a computation whose checkpoint ids start lower would re-use labels its own store
     * has already seen.
     */
    void continueLabelsAfter(long id) {
        lastCut = Math.max(lastCut, id);
        if (checkpointer instanceof com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer periodic) {
            periodic.continueAfter(id);
        }
    }

    /**
     * Records what a sink handed over at a cutover already holds, for the delivery about to claim
     * it -- the same channel a restored checkpoint uses, because it is the same question.
     */
    void carrySinkOver(String name, SinkDelivery.Restored restored) {
        restoredSinks.put(name, restored);
    }

    /** The most recent checkpoint failure, and how many there have been. */
    public Optional<String> lastCheckpointFailure() {
        return Optional.ofNullable(lastCheckpointFailure);
    }

    public long checkpointFailures() {
        return checkpointFailures.get();
    }

    /** Whether this computation is checkpointing at all; the two answers below are empty when not. */
    public boolean isCheckpointing() {
        return checkpointer instanceof com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
    }

    /** When this computation last stored a checkpoint, or empty if it never has or is not checkpointing. */
    public Optional<Instant> lastCheckpoint() {
        return checkpointer instanceof com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer periodic
                ? periodic.lastSuccess()
                : Optional.empty();
    }

    /** How long the last stored checkpoint took, or empty if there has been none. */
    public Optional<java.time.Duration> lastCheckpointDuration() {
        return checkpointer instanceof com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer periodic
                ? periodic.lastSuccessDuration()
                : Optional.empty();
    }

    /**
     * The physical plan this computation is running.
     *
     * <p>Read-only, for describing the query: the plan is the execution's and nothing here may change
     * it. Taken from the execution rather than re-planned from {@link #sql()}, because a query with
     * bound parameters has values in its plan that its text does not carry.
     */
    public com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan() {
        return execution.plan();
    }

    /**
     * Records that a checkpoint did not happen.
     *
     * <p>Kept rather than logged, because "has this query been checkpointing?" is a question an
     * operator asks about one query at a time and a log is the wrong shape to answer it. The count
     * matters as much as the message: one failure is a full disk, six hours of them is a query whose
     * recovery story is fiction.
     */
    void recordCheckpointFailure(String message) {
        lastCheckpointFailure = message;
        checkpointFailures.incrementAndGet();
    }

    private volatile java.nio.file.Path checkpointDirectory;
    private volatile String lastCheckpointFailure;
    private final AtomicLong checkpointFailures = new AtomicLong();

    /**
     * Attaches the checkpointer for this computation, and the directory it writes to.
     *
     * <p>Called once, by the registry that started it. The directory is kept rather than re-derived
     * from a name at drop time: a shared computation checkpoints under the name it was *started*
     * with, and by the time the last name is being dropped that name has already been removed --
     * so re-deriving it produced a directory that never existed and deleted nothing. Recording the
     * path the checkpointer was actually given removes the question.
     */
    void checkpointWith(AutoCloseable periodic, java.nio.file.Path directory) {
        this.checkpointer = periodic;
        this.checkpointDirectory = directory;
    }

    /** Where this computation's checkpoints are, or empty if it is not checkpointing. */
    java.util.Optional<java.nio.file.Path> checkpointDirectory() {
        return Optional.ofNullable(checkpointDirectory);
    }

    /** Attaches the feed opened for this computation. Called once, by the registry that started it. */
    void feedFrom(SourceFeed source) {
        this.feed = source == null ? SourceFeed.NONE : source;
    }

    /** What is attached to this query's inputs. */
    public SourceFeed feed() {
        return feed;
    }

    /**
     * Whether rows are still reaching this computation, source by source (FEED-1).
     *
     * <p>A source that fails mid-read stops its feed and leaves the query {@code RUNNING}, its view
     * answering at the frontier it reached; this is where that shows. Every name on the computation
     * reports the same feed, because they are one computation reading one set of sources.
     */
    public FeedStatus feedStatus() {
        return feed.status();
    }

    /**
     * The streams this computation reads, in plan order, spelled as {@link #accept(String,
     * RowView)} expects them.
     *
     * <p>What an embedder pushing a row into a stream needs to know: which computations that row
     * must reach. Without it the only way to find out is to offer the row and read the refusal --
     * and a refused {@code accept} fails the query it was offered to.
     */
    public java.util.List<String> sourceStreams() {
        return execution.streams();
    }

    @Override
    public String toString() {
        return "RegisteredQuery[" + anyName() + ", " + state + ", " + fingerprint + "]";
    }
}
