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
package com.ash.messaging.pravaha.serving;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.api.data.RowKind;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Where a query writes when its answer is meant to be read rather than shipped.
 *
 * <p>The other half of design section 17: the engine already produces a keyed changelog, so serving
 * it is a matter of applying that changelog to an index instead of writing it to a sink. A query
 * that serves and a query that writes differ in where this object sends the rows and nowhere else.
 *
 * <p>Rows are staged into values and applied on commit, because a view keys on the finished row and
 * a writer fills one a field at a time. The weight comes straight through -- a retraction is a
 * removal, an insert is an upsert -- so there is no translation layer to get wrong.
 *
 * <p><strong>Applying is not committing.</strong> Rows land in the view's overlay and become
 * readable when the frontier commits, which the engine does at a checkpoint boundary. Committing per
 * row would make every intermediate state of a batch readable, and a consistent read would then be
 * consistent with nothing.
 */
public final class ViewSink {

    private static final System.Logger LOG = System.getLogger(ViewSink.class.getName());

    private final ServedView view;
    private final StreamSchema schema;
    private final AtomicLong frontier = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong rowsApplied = new AtomicLong();

    // Changes staged since the last commit, and whoever wants to hear about them. Gathered rather
    // than delivered per row because a subscriber must see whole batches: between commits the view
    // holds a partly applied window, and a total read from it would be one nobody should act on.
    private final List<ViewChange> pending = new ArrayList<>();
    private final List<ViewChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Who this commit is for, decided once when its first row is staged.
     *
     * <p>STRM-11. The staging decision used to be {@code !listeners.isEmpty()} evaluated <em>per
     * row</em>, so a subscriber attaching between two rows of one commit was delivered the rows
     * after it attached and not the ones before — a fragment, arriving as a completed batch, which
     * is exactly what {@code USER_GUIDE.md}'s "a batch is a commit, never a partial window" promises
     * cannot happen. On a windowed query that is a partly-closed window presented as a closed one.
     *
     * <p>Deciding once per commit fixes both halves. A commit that began with subscribers stages
     * every one of its rows and delivers the whole thing. A commit that began with none stages
     * nothing, and a subscriber that attached midway through it hears about that commit not at all
     * and receives the next one entire — which is the correct boundary: a subscription starts at a
     * commit, never inside one.
     *
     * <p>Null between commits. Written on the lane thread that stages rows and read by the same
     * thread at drain, so the field itself needs no lock; the list it holds is a snapshot precisely
     * so a concurrent {@code subscribe} cannot widen the audience of a commit already in flight.
     */
    private volatile List<ViewChangeListener> batchAudience;

    public ViewSink(ServedView view, StreamSchema schema) {
        this.view = view;
        this.schema = schema;
    }

    /** A writer the engine can fill and commit, like any other sink's. */
    public RowWriter begin() {
        return new StagedRow();
    }

    /**
     * Publishes everything applied so far.
     *
     * <p>Called by whatever owns the query's frontier -- a checkpoint, a watermark, the end of a
     * batch. The view does not decide when it is consistent; the engine does, because only the
     * engine knows what a complete prefix of the input is.
     */
    public void commit(long committedFrontier) {
        // In a finally, because view.commit can throw. ServedView.commit applies and evicts and
        // *then* refuses with VIEW_TOO_LARGE, so the rows are in the view by the time it throws --
        // and the throw used to leave this method before pending was ever touched. StagedRow.commit
        // kept appending for as long as anything fed the query: 100 001 entries and climbing after
        // the first refusal.
        //
        // The comment that used to sit here had it exactly backwards. It warned about "a leak that
        // only appears in the deployments that never subscribe"; the no-listener path was the one
        // that cleared, and the leak needed a subscriber attached. Measured both ways on identical
        // input: 100 001 pending with a subscriber, 0 without (STRM-5).
        try {
            view.commit(committedFrontier);
        } finally {
            drainPending(committedFrontier);
        }
    }

    /**
     * Changes applied but not yet published, which should be zero between commits.
     *
     * <p>Worth being able to ask. When {@code view.commit} refused with {@code VIEW_TOO_LARGE} the
     * drain below was skipped, and this number climbed for as long as anything fed the query --
     * invisibly, because nothing exposed it (STRM-5).
     */
    public int pendingChanges() {
        synchronized (pending) {
            return pending.size();
        }
    }

    private void drainPending(long committedFrontier) {
        // The audience this commit was staged for, and the end of the commit either way.
        List<ViewChangeListener> audience = batchAudience;
        batchAudience = null;
        if (audience == null || audience.isEmpty()) {
            // Still cleared: a sink with no subscribers must not accumulate a change log nobody
            // will ever read.
            synchronized (pending) {
                pending.clear();
            }
            return;
        }
        List<ViewChange> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            batch = List.copyOf(pending);
            pending.clear();
        }
        // Delivered to the audience the commit began with, not to whoever is attached now: a
        // subscriber that arrived midway through this commit must receive the next one entire
        // rather than the tail of this one (STRM-11).
        for (ViewChangeListener listener : audience) {
            try {
                listener.onCommit(batch, committedFrontier);
            } catch (RuntimeException | Error escaped) {
                // One listener cannot end the commit every other listener is waiting for. Escaping
                // here stopped the loop mid-iteration, so whether a subscriber received its batch
                // depended on where it sat in the listener list -- and the throw went on to fail
                // the whole query (STRM-2).
                //
                // A listener is expected to handle its own failure; Subscription records it and
                // closes. This is the backstop for one that does not, and it is deliberately last:
                // it cannot tell which subscriber threw, so it says what it can and keeps going.
                LOG.log(
                        System.Logger.Level.WARNING,
                        "a view change listener threw and was skipped for this commit; the listener "
                                + "is responsible for its own failure and this is only the backstop",
                        escaped);
            }
        }
    }

    /**
     * Registers a listener for committed changes.
     *
     * @return a handle that removes the listener. A subscriber that goes away without removing
     *     itself would keep this sink assembling change batches for nobody
     */
    public AutoCloseable onCommit(ViewChangeListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** How many listeners are attached. */
    public int listenerCount() {
        return listeners.size();
    }

    /** Whether anybody is listening, which is worth knowing before doing work for them. */
    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    public long rowsApplied() {
        return rowsApplied.get();
    }

    public ServedView view() {
        return view;
    }

    /** Collects values, then applies the finished row. */
    private final class StagedRow implements RowWriter {

        private Object[] values = new Object[schema.fields().size()];
        private long weight = 1;
        private long eventTime;
        private long sequence;

        @Override
        public StreamSchema schema() {
            return schema;
        }

        @Override
        public RowWriter setNull(int ordinal) {
            values[ordinal] = null;
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            values[ordinal] = value;
            return this;
        }

        // A BigDecimal, matching every other way a value enters a view (finding TY-19). This staged
        // the two raw limbs as a long[], which nothing that reads a view back out understands: the
        // value writer on the way out now produces a BigDecimal and the scan path materialises one,
        // so a view fed through this sink and a view fed through applyValues held two different
        // classes in the same column. The one that arrived through here failed on the way out.
        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            values[ordinal] = com.ash.messaging.pravaha.common.row.Decimals.toBigDecimal(
                    high,
                    low,
                    ((com.ash.messaging.pravaha.api.data.DecimalType)
                                    schema.field(ordinal).type())
                            .scale());
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            values[ordinal] = value == null ? null : value.clone();
            return this;
        }

        @Override
        public RowWriter rowKind(RowKind kind) {
            this.weight = kind == RowKind.DELETE || kind == RowKind.UPDATE_BEFORE ? -1 : 1;
            return this;
        }

        @Override
        public RowWriter weight(long value) {
            this.weight = value;
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            this.eventTime = nanos;
            return this;
        }

        @Override
        public RowWriter sequence(long value) {
            this.sequence = value;
            return this;
        }

        @Override
        public int commit() {
            view.applyValues(values, weight, Math.max(eventTime, sequence));
            // STRM-11: decided once for the commit, not once per row. See batchAudience.
            List<ViewChangeListener> audience = batchAudience;
            if (audience == null) {
                audience = listeners.isEmpty() ? List.of() : List.copyOf(listeners);
                batchAudience = audience;
            }
            if (!audience.isEmpty()) {
                synchronized (pending) {
                    pending.add(new ViewChange(values, weight));
                }
            }
            frontier.accumulateAndGet(Math.max(eventTime, sequence), Math::max);
            rowsApplied.incrementAndGet();
            values = new Object[schema.fields().size()];
            weight = 1;
            return 0;
        }

        @Override
        public void abort() {
            values = new Object[schema.fields().size()];
            weight = 1;
        }
    }

    /** The furthest input position any applied row reflects. */
    public long appliedFrontier() {
        return frontier.get();
    }
}
