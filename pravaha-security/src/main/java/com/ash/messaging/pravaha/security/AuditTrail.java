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
package com.ash.messaging.pravaha.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

/**
 * The configured audit sink, with the most recent decisions kept where the node can read them back.
 *
 * <p><strong>Why a ring beside the sink, and not the sink read back.</strong> The durable trail is
 * whatever the deployment configured -- a JSON Lines file, or a sink of its own -- and its format
 * and retention belong to whoever reads it with their own tools. Reading that back to answer a
 * request would mean parsing a file that is written asynchronously (so the newest decisions are not
 * in it yet), that rotates underneath the reader, and that may not be a file at all. A bounded ring
 * written on the same call is none of those things: O(1) to record, in step with the decisions, and
 * the same whatever the durable sink is. What it gives up is history across a restart, and the read
 * API says so: it names its capacity, how many events it has evicted, and the oldest one it holds.
 *
 * <p><strong>Auditing never fails the thing it audits.</strong> Recording into the ring cannot
 * throw; a delegate that throws is counted ({@link #delegateFailures()}) and the call returns
 * normally, because an audit sink that is down must not turn into a query that is down.
 *
 * <p>Every event gets a sequence number, increasing by one per event and never reused. A page of the
 * trail is newest first, and its cursor is the sequence to continue below -- stable while new
 * decisions keep arriving, which an offset into a moving list would not be.
 */
public final class AuditTrail implements AuditSink {

    /** How many recent decisions a node keeps readable, unless configured otherwise. */
    public static final int DEFAULT_CAPACITY = 10_000;

    /** The most a single page returns, whatever was asked for. */
    public static final int MAX_PAGE = 500;

    private final AuditSink delegate;
    private final String durable;
    private final AuditEvent[] ring;
    private final Object lock = new Object();
    private final AtomicLong delegateFailures = new AtomicLong();

    /** The sequence the next event gets. The first event is 1. */
    private long next = 1;

    /**
     * @param delegate where events are recorded durably, or {@link AuditSink#NONE}
     * @param durable what the delegate is, in words an operator recognises: {@code file}, {@code
     *     memory}, the class of a custom sink
     * @param capacity how many recent events stay readable; the oldest is dropped past it
     */
    public AuditTrail(AuditSink delegate, String durable, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("an audit trail that keeps " + capacity + " events cannot be read");
        }
        this.delegate = delegate == null ? AuditSink.NONE : delegate;
        this.durable = durable == null || durable.isBlank() ? "none" : durable;
        this.ring = new AuditEvent[capacity];
    }

    @Override
    public void record(AuditEvent event) {
        if (event == null) {
            return;
        }
        synchronized (lock) {
            ring[(int) (next % ring.length)] = event;
            next++;
        }
        try {
            delegate.record(event);
        } catch (RuntimeException e) {
            // Counted and swallowed: see the class comment. The ring still has the event, so the
            // decision is readable even while the durable sink is failing.
            delegateFailures.incrementAndGet();
        }
    }

    /** The sink this trail records into durably. */
    public AuditSink delegate() {
        return delegate;
    }

    /** What the durable sink is, as the read API reports it. */
    public String durable() {
        return durable;
    }

    public int capacity() {
        return ring.length;
    }

    /** How many times the durable sink threw rather than recording. */
    public long delegateFailures() {
        return delegateFailures.get();
    }

    /** The durable sink's failure, as it reports it. */
    @Override
    public java.util.Optional<String> failure() {
        return delegate.failure();
    }

    /** What the durable sink did not record, and the events it threw on rather than recording. */
    @Override
    public long unrecorded() {
        return delegate.unrecorded() + delegateFailures.get();
    }

    /**
     * One page of the retained trail, newest first.
     *
     * @param filter which events; {@link Filter#ANY} for all
     * @param limit how many at most, clamped to 1..{@link #MAX_PAGE}
     * @param before continue below this sequence (a previous page's {@link Page#nextCursor()}), or
     *     0 to start from the newest
     */
    public Page read(Filter filter, int limit, long before) {
        int wanted = Math.max(1, Math.min(MAX_PAGE, limit));
        AuditEvent[] copy;
        long newest;
        synchronized (lock) {
            copy = ring.clone();
            newest = next - 1;
        }
        long oldest = Math.max(1, newest - ring.length + 1);
        long start = before > 0 ? Math.min(before - 1, newest) : newest;
        Filter test = filter == null ? Filter.ANY : filter;
        List<Entry> entries = new ArrayList<>();
        long nextCursor = 0;
        for (long sequence = start; sequence >= oldest; sequence--) {
            AuditEvent event = copy[(int) (sequence % copy.length)];
            if (event == null || !test.matches(event)) {
                continue;
            }
            if (entries.size() == wanted) {
                // One more match exists below the page, so there is a next page and it starts here.
                nextCursor = entries.get(entries.size() - 1).sequence();
                break;
            }
            entries.add(new Entry(sequence, event));
        }
        TreeSet<String> actions = new TreeSet<>();
        Instant oldestAt = null;
        int retained = 0;
        for (long sequence = oldest; sequence <= newest; sequence++) {
            AuditEvent event = copy[(int) (sequence % copy.length)];
            if (event == null) {
                continue;
            }
            retained++;
            actions.add(event.action());
            if (oldestAt == null) {
                oldestAt = event.at();
            }
        }
        return new Page(
                List.copyOf(entries),
                nextCursor,
                retained,
                ring.length,
                Math.max(0, newest - ring.length),
                oldestAt,
                List.copyOf(actions));
    }

    /** An event and the sequence it was recorded under. */
    public record Entry(long sequence, AuditEvent event) {}

    /**
     * A page of the trail.
     *
     * @param nextCursor the sequence to pass as {@code before} for the next page, or 0 when this is
     *     the last one
     * @param retained events held right now
     * @param evicted events recorded and since dropped from the ring -- still in the durable sink,
     *     if there is one, and no longer readable here
     * @param oldest when the oldest retained event happened, or null when nothing is retained
     * @param actions every action among the retained events, for a filter to offer
     */
    public record Page(
            List<Entry> entries,
            long nextCursor,
            int retained,
            int capacity,
            long evicted,
            @Nullable Instant oldest,
            List<String> actions) {}

    /**
     * Which events a read wants. Every field is optional; null means "any".
     *
     * @param since at or after
     * @param until before
     * @param principal the principal's id, exactly
     * @param target what was acted on -- a view, a stream, a query name -- ignoring case
     * @param action the action, exactly, such as {@code query} or {@code http.read}
     * @param allowed true for allows only, false for denials only
     */
    public record Filter(
            @Nullable Instant since,
            @Nullable Instant until,
            @Nullable String principal,
            @Nullable String target,
            @Nullable String action,
            @Nullable Boolean allowed) {

        public static final Filter ANY = new Filter(null, null, null, null, null, null);

        public Filter {
            principal = blankToNull(principal);
            target = blankToNull(target);
            action = blankToNull(action);
        }

        public boolean matches(AuditEvent event) {
            if (since != null && event.at().isBefore(since)) {
                return false;
            }
            if (until != null && !event.at().isBefore(until)) {
                return false;
            }
            if (principal != null && !principal.equals(event.principal().id())) {
                return false;
            }
            if (target != null
                    && (event.target() == null
                            || !target.toLowerCase(Locale.ROOT)
                                    .equals(event.target().toLowerCase(Locale.ROOT)))) {
                return false;
            }
            if (action != null && !action.equals(event.action())) {
                return false;
            }
            return allowed == null || allowed == event.allowed();
        }

        private static @Nullable String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
