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
        return state;
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

    /** The watermark reached, or empty if none has been declared. */
    public Optional<Long> watermarkNanos() {
        long value = watermarkNanos.get();
        return value == Long.MIN_VALUE ? Optional.empty() : Optional.of(value);
    }

    /** Why it failed, if it did. */
    public Optional<PravahaException> failure() {
        return Optional.ofNullable(failure);
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
        if (state != QueryState.RUNNING) {
            return false;
        }
        try {
            // Handed to the lane, which applies it on its own thread. A caller that must see the
            // row reflected in the view waits with awaitQuiescent; one that is feeding a stream
            // does not care, because the next read is later anyway.
            if (!execution.accept(row)) {
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
            sink.commit(sink.appliedFrontier());
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
        if (state.isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION, "cannot subscribe to '" + anyName() + "': it is " + state);
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
        if (state.isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION, "cannot subscribe to '" + anyName() + "': it is " + state);
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
        return sink.listenerCount();
    }

    /** Publishes what has been applied so far, without claiming time has moved. */
    public void commit() {
        if (state == QueryState.RUNNING) {
            sink.commit(sink.appliedFrontier());
        }
    }

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

    void pause() {
        requireLive("pause");
        state = QueryState.PAUSED;
        // A feed writes into the lane's inbox without passing through accept(), so a pause that
        // stopped only at accept() would not stop anything a source was pushing.
        feed.pause();
    }

    void resume() {
        if (state.isTerminal()) {
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
        if (state.isTerminal()) {
            throw new PravahaException(
                    RegistryErrors.ILLEGAL_TRANSITION,
                    "cannot " + action + " query '" + anyName() + "': it is " + state);
        }
    }

    private void fail(PravahaException cause) {
        this.failure = cause;
        this.state = QueryState.FAILED;
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
        }
    }

    /** Attaches the checkpointer for this computation. Called once, by the registry that started it. */
    void checkpointWith(AutoCloseable periodic) {
        this.checkpointer = periodic;
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
