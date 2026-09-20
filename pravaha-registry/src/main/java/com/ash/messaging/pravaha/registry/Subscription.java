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
 *
 * <p><strong>The consumer runs on this subscription's own thread, never on the engine's</strong>
 * (STRM-8). {@link #onCommit} is called by the thread that committed the view -- a lane, a feed's
 * publish timer, a checkpoint -- and all it does is filter the changes into the buffer and wake the
 * delivery thread. It used to call the consumer itself: a consumer that slept two seconds made the
 * commit take two seconds and three of them made it take six, on the timer that drives every query
 * on that feed. A slow subscriber now falls behind alone, in its own buffer, and
 * {@link SubscriptionOptions.Overflow} decides what happens when that buffer fills -- which is what
 * {@code bufferRows} was always documented to bound and, while the buffer was drained inside every
 * commit, never could.
 *
 * <p>The cost of that is that delivery is no longer finished when {@code commit} returns. A caller
 * that needs it to be -- a test, an embedder stepping the engine by hand -- asks for it:
 * {@link #awaitQuiet}. {@link #pending} says how far behind the subscriber is right now.
 *
 * <p>The delivery thread is virtual and is started by the first change admitted, so a subscription
 * nothing has been committed to yet costs nothing, and a subscription is never more than one parked
 * virtual thread. It ends when the subscription closes.
 */
public final class Subscription implements AutoCloseable {

    private final String queryName;
    private final SubscriptionListener consumer;
    private final SubscriptionOptions options;
    private final SubscriptionFilter filter;
    private final int[] keyOrdinals;
    private final AutoCloseable detach;

    /**
     * Guards the buffer and everything the delivery thread waits on.
     *
     * <p>A lock and not a monitor, because the delivery thread is virtual: in Java 21 a virtual
     * thread blocked in {@code Object.wait} pins its carrier, so a thousand parked subscribers
     * would pin a thousand platform threads -- the cost {@code SubscriptionThreadCostTest} exists
     * to keep off this surface.
     */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    /** Signalled when there is something to deliver, or when the subscription is closing. */
    private final java.util.concurrent.locks.Condition work = lock.newCondition();

    /** Signalled when the buffer is empty and nothing is with the consumer. See {@link #awaitQuiet}. */
    private final java.util.concurrent.locks.Condition quiet = lock.newCondition();

    /**
     * The commits waiting for this subscriber, oldest first, each still whole.
     *
     * <p>A queue of commits rather than one list of changes, because a batch is a commit: a
     * subscriber that has fallen behind must receive two commits as two batches and not as one
     * merged batch that never happened. What the buffer holds is bounded in <em>changes</em>
     * across all of them -- {@link SubscriptionOptions#bufferRows} -- and overflow reaches across
     * them too, so the oldest change goes first whichever commit it arrived in.
     */
    private final Deque<Queued> buffer = new ArrayDeque<>();

    /** Changes across every queued commit: what {@code bufferRows} bounds. Guarded by {@link #lock}. */
    private int bufferedRows;

    /** A snapshot that has arrived and not been handed over yet. Guarded by {@link #lock}. */
    private List<ViewChange> waitingSnapshot;

    private long waitingSnapshotFrontier;

    /** Whether a batch is with the consumer right now. Guarded by {@link #lock}. */
    private boolean delivering;

    /** The delivery thread, started by the first change admitted. Guarded by {@link #lock}. */
    private Thread deliveryThread;

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

    /**
     * Called by the engine on every commit. Must not block, and does not.
     *
     * <p>Filters into the buffer and wakes the delivery thread; the consumer is not called from
     * here (STRM-8). Everything this does is bounded by the size of the commit and the buffer's
     * own ceiling, so the committing thread's cost is the same whether the subscriber is keeping
     * up or two seconds behind.
     */
    void onCommit(List<ViewChange> changes, long frontier) {
        if (closed.get()) {
            return;
        }
        lock.lock();
        try {
            Queued queued = new Queued(frontier);
            buffer.addLast(queued);
            for (ViewChange change : changes) {
                // Filtered before buffering, not after. A subscriber watching one product type
                // should not have its buffer filled -- and its own rows conflated away -- by rows it
                // never asked for.
                if (filter.accepts(change)) {
                    admit(queued, change);
                }
            }
            if (queued.changes.isEmpty()) {
                // Nothing in this commit was for this subscriber, so it is not a commit this
                // subscriber hears about at all.
                buffer.remove(queued);
                return;
            }
            startDelivery();
            work.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** One whole commit waiting for this subscriber. Guarded by {@link #lock}. */
    private static final class Queued {

        private final List<ViewChange> changes = new ArrayList<>();
        private final long frontier;

        Queued(long frontier) {
            this.frontier = frontier;
        }
    }

    /** Starts the delivery thread if this subscription has not needed one yet. Lock held. */
    private void startDelivery() {
        if (deliveryThread != null || closed.get()) {
            return;
        }
        deliveryThread = Thread.ofVirtual()
                .name("pravaha-subscriber-" + queryName + "-" + System.identityHashCode(this))
                .start(this::deliver);
    }

    /**
     * Hands the consumer whatever is waiting, one batch at a time, until the subscription closes.
     *
     * <p>A batch is one commit, oldest first, however far behind the subscriber has fallen: two
     * commits are two batches and never one merged batch of a commit that never happened. What
     * accumulates while the consumer is busy is the queue of them, bounded in changes by
     * {@code bufferRows}, with {@link SubscriptionOptions.Overflow} deciding what happens when it
     * is full.
     */
    private void deliver() {
        while (true) {
            List<ViewChange> snapshot;
            long snapshotAt;
            List<ViewChange> batch;
            long at;
            lock.lock();
            try {
                while (!closed.get() && waitingSnapshot == null && buffer.isEmpty()) {
                    delivering = false;
                    quiet.signalAll();
                    work.awaitUninterruptibly();
                }
                if (closed.get()) {
                    delivering = false;
                    quiet.signalAll();
                    return;
                }
                delivering = true;
                snapshot = waitingSnapshot;
                snapshotAt = waitingSnapshotFrontier;
                waitingSnapshot = null;
                if (snapshot != null) {
                    // The snapshot is handed over on its own, before any commit: it is the state
                    // the subscriber starts from, and a commit mixed into it would be a change
                    // applied to a copy that did not exist yet.
                    batch = null;
                    at = 0;
                } else {
                    // One commit, whole, oldest first.
                    Queued queued = buffer.removeFirst();
                    bufferedRows -= queued.changes.size();
                    batch = List.copyOf(queued.changes);
                    at = queued.frontier;
                }
            } finally {
                lock.unlock();
            }
            try {
                if (snapshot != null) {
                    consumer.onSnapshot(snapshot, snapshotAt);
                    delivered.addAndGet(snapshot.size());
                } else {
                    consumer.onCommit(batch, at);
                    delivered.addAndGet(batch.size());
                }
            } catch (RuntimeException e) {
                // A consumer that throws has stopped consuming. Recording it and closing is better
                // than calling it again on the next commit, which turns one broken subscriber into
                // a stream of exceptions -- and, before this ran on a thread of its own, a stream
                // of exceptions on the engine's.
                threw(e);
                return;
            }
        }
    }

    /**
     * Changes waiting for this subscriber: how far behind it is, in the unit {@code bufferRows}
     * bounds.
     */
    /** Whether {@link #close} threw away changes the subscriber had not received. Guarded by {@code lock}. */
    private boolean abandoned;

    public int pending() {
        lock.lock();
        try {
            return bufferedRows;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until nothing is waiting for this subscriber and nothing is with it.
     *
     * <p>What a caller that commits by hand uses in place of the delivery that used to finish
     * inside {@code commit}. It is not on the engine's path: no part of the engine calls it.
     *
     * <p>A closed subscription is quiet only if it had nothing left when it closed. {@link #close}
     * discards whatever was still waiting -- the subscription is over and the copy ends with it --
     * and answering "quiet" for that would tell a caller its subscriber received changes that were
     * thrown away. It returns false instead, which is the same answer it gives for a deadline that
     * passed with the subscriber behind, and means the same thing: not everything got there.
     *
     * @return false if the deadline passed with the subscriber still behind, or if the
     *     subscription closed while changes were still waiting
     */
    public boolean awaitQuiet(java.time.Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        lock.lock();
        try {
            if (closed.get()) {
                return !abandoned;
            }
            while (!closed.get() && (delivering || !buffer.isEmpty() || waitingSnapshot != null)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                quiet.awaitNanos(left);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
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
        // Queued for the delivery thread rather than handed over here, so that it and the commits
        // after it reach the consumer on one thread and in order (STRM-8). ViewSink hands the
        // snapshot over before any commit, so it is queued before any commit too.
        lock.lock();
        try {
            waitingSnapshot = List.copyOf(matching);
            waitingSnapshotFrontier = frontier;
            startDelivery();
            work.signalAll();
        } finally {
            lock.unlock();
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

    private void admit(Queued into, ViewChange change) {
        if (bufferedRows < options.bufferRows()) {
            into.changes.add(change);
            bufferedRows++;
            return;
        }
        switch (options.overflow()) {
            case CONFLATE -> {
                // Replace the waiting change for this key. A dashboard wants the current value and
                // does not care how many times it changed while nobody was looking.
                if (!replaceByKey(change)) {
                    dropOldest(into);
                    into.changes.add(change);
                    bufferedRows++;
                    dropped.incrementAndGet();
                } else {
                    conflated.incrementAndGet();
                }
            }
            case DROP_OLDEST -> {
                dropOldest(into);
                into.changes.add(change);
                bufferedRows++;
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

    /**
     * Replaces the oldest waiting change with the same key, wherever it is queued; false if no key
     * matched.
     *
     * <p>In place, so the commit it belonged to keeps its shape and its position: conflation
     * supersedes a change, it does not move one commit's row into another commit's batch.
     */
    private boolean replaceByKey(ViewChange change) {
        if (keyOrdinals.length == 0) {
            return false;
        }
        for (Queued queued : buffer) {
            for (int i = 0; i < queued.changes.size(); i++) {
                if (sameKey(queued.changes.get(i), change)) {
                    queued.changes.set(i, change);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drops the oldest waiting change, and the commit it was the last of, except {@code keep}.
     * Lock held.
     */
    private void dropOldest(Queued keep) {
        for (Iterator<Queued> it = buffer.iterator(); it.hasNext(); ) {
            Queued queued = it.next();
            if (queued.changes.isEmpty()) {
                continue;
            }
            queued.changes.remove(0);
            bufferedRows--;
            if (queued.changes.isEmpty() && queued != keep) {
                // A commit with nothing left of it is not a batch: delivering it empty would tell
                // the subscriber that a commit happened and hand it nothing. The commit being
                // filled right now is kept whatever it holds -- the change that displaced this one
                // goes into it next.
                it.remove();
            }
            return;
        }
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
            lock.lock();
            try {
                // Whatever was waiting is not delivered: this subscription is over, and the
                // delivery thread's next turn round the loop ends it. Recorded, so that
                // awaitQuiet can tell "there was nothing left" from "what was left is gone".
                abandoned = !buffer.isEmpty() || waitingSnapshot != null;
                buffer.clear();
                bufferedRows = 0;
                waitingSnapshot = null;
                deliveryThread = null;
                work.signalAll();
                quiet.signalAll();
            } finally {
                lock.unlock();
            }
            // Not joined. close() is called by a cutover and by a drop, on a control-plane thread,
            // and waiting there for a consumer that is two seconds into a batch would put the
            // engine back in a slow subscriber's hands by another door. A batch already with the
            // consumer therefore finishes after this returns; nothing new is started.
        }
    }

    @Override
    public String toString() {
        return "Subscription[" + queryName + ", " + filter + ", delivered=" + delivered() + ", conflated=" + conflated()
                + ", dropped=" + dropped() + (closed.get() ? ", closed" : "") + "]";
    }
}
