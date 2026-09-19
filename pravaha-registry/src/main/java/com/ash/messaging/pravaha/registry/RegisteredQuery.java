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
     */
    public Subscription subscribe(
            SubscriptionOptions options,
            java.util.function.Consumer<java.util.List<com.ash.messaging.pravaha.serving.ViewChange>> consumer) {
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
        // state(), not the field. Attaching a subscriber to a query whose lane has died gives it a
        // handle that will never deliver anything and never say why.
        if (state().isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION, "cannot subscribe to '" + anyName() + "': it is " + state());
        }
        return new Subscription(
                anyName(),
                view.keyOrdinals(),
                options == null ? SubscriptionOptions.DEFAULT : options,
                filter,
                consumer,
                subscription -> sink.onCommit(subscription::onCommit));
    }

    /** Subscribes with the default buffer and conflation. */
    public Subscription subscribe(
            java.util.function.Consumer<java.util.List<com.ash.messaging.pravaha.serving.ViewChange>> consumer) {
        return subscribe(SubscriptionOptions.DEFAULT, consumer);
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
        AutoCloseable detach = sink.onCommit(delivery);
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
        lastCut = Math.max(lastCut, checkpoint.id());
        byte[] contents = checkpoint.operatorState().get(QueryExecution.SERVED_VIEW_STATE);
        checkpoint.operatorState().forEach((key, bytes) -> {
            if (key.startsWith(SinkDelivery.STATE_PREFIX)) {
                restoredSinks.put(
                        key.substring(SinkDelivery.STATE_PREFIX.length()),
                        SinkDelivery.Restored.decode(checkpoint.id(), bytes, contents));
            }
        });
    }

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

    /** Removes a name; returns true when none are left and the computation should be released. */
    synchronized boolean removeName(String name) {
        names.remove(name);
        return names.isEmpty();
    }

    /** Any one of this computation's names, for a log line or a wire response. */
    public synchronized String name() {
        return anyName();
    }

    synchronized String anyName() {
        return names.isEmpty() ? fingerprint.shortForm() : names.iterator().next();
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
        }
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

    @Override
    public String toString() {
        return "RegisteredQuery[" + anyName() + ", " + state + ", " + fingerprint + "]";
    }
}
