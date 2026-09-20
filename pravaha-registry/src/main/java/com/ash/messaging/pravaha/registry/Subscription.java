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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * One consumer attached to a registered query (ADR-025, ADR-026).
 *
 * <p>A subscription is not the query. Many may attach to one computation, they come and go without
 * it noticing, and the computation outlives all of them -- which is the separation ADR-025 exists to
 * make, and the reason a dashboard reconnecting does not cost a warm-up.
 *
 * <p>The interesting part is what happens when a subscriber cannot keep up, because that is the case
 * that decides whether one slow consumer degrades everybody. Changes are buffered to a bound and
 * then handled per {@link SubscriptionOptions.Overflow}; the engine is never blocked. What is
 * dropped is always counted, because a subscriber quietly missing data is worse than one that fails.
 */
public final class Subscription implements AutoCloseable {

    private final String queryName;
    private final SubscriptionListener consumer;
    private final SubscriptionOptions options;
    private final SubscriptionFilter filter;
    private final int[] keyOrdinals;
    private final AutoCloseable detach;

    private final Deque<ViewChange> buffer = new ArrayDeque<>();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong conflated = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile PravahaException failure;

    /** The frontier of the snapshot this subscription started from; absent until it arrives. */
    private volatile Long snapshotFrontier;

    Subscription(
            String queryName,
            List<Integer> keyOrdinals,
            SubscriptionOptions options,
            SubscriptionFilter filter,
            Consumer<List<ViewChange>> consumer,
            java.util.function.Function<Subscription, AutoCloseable> attach) {
        this(queryName, keyOrdinals, options, filter, changesOnly(consumer), attach);
    }

    Subscription(
            String queryName,
            List<Integer> keyOrdinals,
            SubscriptionOptions options,
            SubscriptionFilter filter,
            SubscriptionListener consumer,
            java.util.function.Function<Subscription, AutoCloseable> attach) {
        this.queryName = queryName;
        this.consumer = consumer;
        this.options = options;
        this.filter = filter == null ? SubscriptionFilter.none() : filter;
        this.keyOrdinals = keyOrdinals.stream().mapToInt(Integer::intValue).toArray();
        this.detach = attach.apply(this);
    }

    /** Called by the engine on every commit. Must not block, and does not. */
    void onCommit(List<ViewChange> changes, long frontier) {
        if (closed.get()) {
            return;
        }
        List<ViewChange> batch;
        synchronized (buffer) {
            for (ViewChange change : changes) {
                // Filtered before buffering, not after. A subscriber watching one product type
                // should not have its buffer filled -- and its own rows conflated away -- by rows it
                // never asked for.
                if (filter.accepts(change)) {
                    admit(change);
                }
            }
            if (buffer.isEmpty()) {
                return;
            }
            batch = List.copyOf(buffer);
            buffer.clear();
        }
        try {
            consumer.onCommit(batch, frontier);
            delivered.addAndGet(batch.size());
        } catch (RuntimeException e) {
            // A consumer that throws has stopped consuming. Recording it and closing is better than
            // calling it again on the next commit, which turns one broken subscriber into a stream
            // of exceptions on the engine's own thread.
            threw(e);
        }
    }

    /**
     * Called by the engine once, before any commit, for a subscription started from a snapshot.
     *
     * <p>Filtered, but not buffered: the snapshot is the state the subscriber starts from, not a
     * backlog it has fallen behind on, so conflating or dropping any of it would start the copy
     * wrong. It is bounded by the view's own ceiling. Delivered even when empty, because an empty
     * snapshot is still where the copy starts.
     */
    void onSnapshot(List<ViewChange> rows, long frontier) {
        if (closed.get()) {
            return;
        }
        List<ViewChange> matching = new ArrayList<>(rows.size());
        for (ViewChange row : rows) {
            if (filter.accepts(row)) {
                matching.add(row);
            }
        }
        snapshotFrontier = frontier;
        try {
            consumer.onSnapshot(List.copyOf(matching), frontier);
            delivered.addAndGet(matching.size());
        } catch (RuntimeException e) {
            threw(e);
        }
    }

    /**
     * Ends this subscription with a reason of the engine's rather than the subscriber's.
     *
     * <p>For a cutover (ADR-046): the view this subscriber was following has been replaced, so
     * there are no more changes to it and there never will be. Said rather than simply closed,
     * because a stream that merely stops reads as "nothing is happening" -- which is exactly what a
     * dashboard would show for a view that is now answered by a different query.
     */
    void endBecause(PravahaException why) {
        if (closed.get()) {
            return;
        }
        failure = why;
        close();
    }

    private void threw(RuntimeException e) {
        failure = new PravahaException(
                RegistryErrors.QUERY_FAILED,
                "subscriber on '" + queryName + "' threw and has been detached: " + e.getMessage(),
                e);
        close();
    }

    /**
     * The committed frontier of the snapshot this subscription started from, once it has arrived.
     *
     * <p>Empty for a subscription that did not ask for one, and for one still waiting for the commit
     * in flight when it attached to end (SUB-1).
     */
    public java.util.OptionalLong snapshotFrontier() {
        Long at = snapshotFrontier;
        return at == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(at);
    }

    /** A plain consumer, which hears commits and was never offered a snapshot. */
    private static SubscriptionListener changesOnly(Consumer<List<ViewChange>> consumer) {
        return new SubscriptionListener() {
            @Override
            public void onSnapshot(List<ViewChange> rows, long frontier) {
                // Not reachable: a plain subscription is attached with ViewSink.onCommit.
            }

            @Override
            public void onCommit(List<ViewChange> changes, long frontier) {
                consumer.accept(changes);
            }
        };
    }

    private void admit(ViewChange change) {
        if (buffer.size() < options.bufferRows()) {
            buffer.add(change);
            return;
        }
        switch (options.overflow()) {
            case CONFLATE -> {
                // Replace the waiting change for this key. A dashboard wants the current value and
                // does not care how many times it changed while nobody was looking.
                if (!replaceByKey(change)) {
                    buffer.removeFirst();
                    buffer.add(change);
                    dropped.incrementAndGet();
                } else {
                    conflated.incrementAndGet();
                }
            }
            case DROP_OLDEST -> {
                buffer.removeFirst();
                buffer.add(change);
                dropped.incrementAndGet();
            }
            case FAIL -> {
                // Recorded and closed, not thrown. FAIL is this subscriber's answer to falling
                // behind -- it asked to be failed rather than lose a change -- and it is not a
                // statement about the query. Thrown from here it left admit(), left onCommit(),
                // escaped ViewSink.commit's listener loop mid-iteration and reached
                // advanceWatermark's catch, which failed the whole computation: two healthy
                // subscribers attached after this one received nothing, and the same three
                // subscribers on the same input got a different answer depending on attach order.
                //
                // This is what the consumer-threw path above already does, and the two arms should
                // not disagree about whether one subscriber can end a query everyone else reads.
                // The subscriber learns from failure() and isClosed(), which is its own channel.
                failure = new PravahaException(
                        RegistryErrors.QUERY_FAILED,
                        "subscriber on '" + queryName + "' fell more than " + options.bufferRows()
                                + " changes behind and asked to be failed rather than lose any. "
                                + "Reconnect and re-read the view to catch up");
                close();
            }
        }
    }

    /** Replaces a buffered change with the same key; false if no key matched. */
    private boolean replaceByKey(ViewChange change) {
        if (keyOrdinals.length == 0) {
            return false;
        }
        List<ViewChange> rewritten = new ArrayList<>(buffer.size());
        boolean replaced = false;
        for (Iterator<ViewChange> it = buffer.iterator(); it.hasNext(); ) {
            ViewChange existing = it.next();
            if (!replaced && sameKey(existing, change)) {
                rewritten.add(change);
                replaced = true;
            } else {
                rewritten.add(existing);
            }
        }
        if (replaced) {
            buffer.clear();
            buffer.addAll(rewritten);
        }
        return replaced;
    }

    private boolean sameKey(ViewChange left, ViewChange right) {
        Object[] a = left.values();
        Object[] b = right.values();
        for (int ordinal : keyOrdinals) {
            if (ordinal >= a.length || ordinal >= b.length) {
                return false;
            }
            if (!java.util.Objects.equals(a[ordinal], b[ordinal])) {
                return false;
            }
        }
        return true;
    }

    /** What this subscriber asked to see. */
    public SubscriptionFilter filter() {
        return filter;
    }

    /**
     * The name this subscription was opened under.
     *
     * <p>Not "a name of the computation": the two differ as soon as two registrations share one
     * (STRM-14). Dropping one of them leaves the computation running under the other, and only a
     * subscription that knows which word its client used can be ended when that word stops meaning
     * anything.
     */
    public String queryName() {
        return queryName;
    }

    /** Changes handed to the consumer. */
    public long delivered() {
        return delivered.get();
    }

    /** Changes superseded by a newer change for the same key. */
    public long conflated() {
        return conflated.get();
    }

    /** Changes lost because this subscriber fell behind. Never silent. */
    public long dropped() {
        return dropped.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Why it ended, if it ended badly. */
    public java.util.Optional<PravahaException> failure() {
        return java.util.Optional.ofNullable(failure);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                detach.close();
            } catch (Exception e) {
                // Detaching is bookkeeping; failing to do it must not mask why we are closing.
            }
            synchronized (buffer) {
                buffer.clear();
            }
        }
    }

    @Override
    public String toString() {
        return "Subscription[" + queryName + ", " + filter + ", delivered=" + delivered() + ", conflated=" + conflated()
                + ", dropped=" + dropped() + (closed.get() ? ", closed" : "") + "]";
    }
}
