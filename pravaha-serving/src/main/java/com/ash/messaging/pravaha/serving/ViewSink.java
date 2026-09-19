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
import com.ash.messaging.pravaha.runtime.exec.RowOutput;

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
 *
 * <p><strong>And a commit takes whole batches.</strong> A lane writes through {@link #laneOutput},
 * which stages a batch's rows on the lane's own thread and applies them to the view in one step when
 * the lane reaches the end of the batch. A commit arrives from another thread -- the feed's timer, a
 * checkpoint, a caller -- and takes the view and the change log under the same lock that step
 * holds, so it publishes every batch applied before it and nothing of the one in progress (VIEW-1).
 * It used to apply each row as it was written, and a commit landing between an update's retraction
 * and its insert published the retraction alone: a reader saw the answer vanish, and a subscriber was
 * handed a batch that withdrew it.
 */
public final class ViewSink {

    private static final System.Logger LOG = System.getLogger(ViewSink.class.getName());

    private final ServedView view;
    private final StreamSchema schema;
    private final AtomicLong frontier = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong rowsApplied = new AtomicLong();

    /**
     * Held while a whole batch is applied, and while a commit publishes the view and takes the
     * change log, so the two never interleave (VIEW-1).
     *
     * <p>Once per batch and once per commit; never per row. The rows of a batch are staged without it
     * on the lane thread that owns them. Guards {@link #pending} and {@link #batchAudience}.
     */
    private final Object publishLock = new Object();

    // Changes applied since the last commit, and whoever wants to hear about them. Gathered rather
    // than delivered per row because a subscriber must see whole batches: between commits the view
    // holds a partly applied window, and a total read from it would be one nobody should act on.
    private final List<ViewChange> pending = new ArrayList<>();
    private final List<ViewChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Who this commit is for, decided once when its first batch is applied.
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
     * <p>Null between commits. Guarded by {@link #publishLock}; the list it holds is a snapshot
     * precisely so a concurrent {@code subscribe} cannot widen the audience of a commit already in
     * flight.
     */
    private List<ViewChangeListener> batchAudience;

    public ViewSink(ServedView view, StreamSchema schema) {
        this.view = view;
        this.schema = schema;
    }

    /**
     * A writer the engine can fill and commit, like any other sink's.
     *
     * <p>Each row is its own batch: applied to the view when the writer commits. For a caller that
     * writes rows one at a time and commits between them -- a test, an embedder. A lane writes
     * through {@link #laneOutput} instead, so that a commit never lands inside one of its batches.
     */
    public RowWriter begin() {
        return new StagedRow(null);
    }

    /**
     * An output for one lane: rows staged on the lane's thread, applied a whole batch at a time.
     *
     * <p>One per lane, like every lane's output, because the staging buffer belongs to the thread
     * filling it. The pipeline calls {@link RowOutput#endOfBatch()} at the end of each unit of the
     * lane's work -- an input batch, a watermark advance, a continuous aggregate's emission, end of
     * input -- and only then do the unit's rows reach the view, together. Rows staged by a lane that
     * dies mid-batch never do, which is right: half a batch is not an answer.
     */
    public RowOutput laneOutput() {
        return new LaneBatch();
    }

    /**
     * Publishes everything applied so far.
     *
     * <p>Called by whatever owns the query's frontier -- a checkpoint, a watermark, the end of a
     * batch. The view does not decide when it is consistent; the engine does, because only the
     * engine knows what a complete prefix of the input is.
     */
    public void commit(long committedFrontier) {
        commit(committedFrontier, false);
    }

    /**
     * Publishes everything applied so far, as of the furthest position any of it reflects.
     *
     * <p>The frontier is read under the same lock the commit takes, so it is the frontier of exactly
     * the batches this commit publishes. Reading it first and committing afterwards let a batch
     * applied in between be published under the frontier before it.
     */
    public void commitApplied() {
        commit(Long.MIN_VALUE, true);
    }

    private void commit(long requestedFrontier, boolean atApplied) {
        List<ViewChange> batch = List.of();
        List<ViewChangeListener> audience = null;
        long committedFrontier = requestedFrontier;
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
        // Timed from here to the last listener having its batch: applying the changes to the view and
        // delivering them to every subscriber and sink is what a commit costs, and a slow sink is part
        // of that cost because it runs on this thread. A commit with nothing in it is not timed -- an
        // idle query commits on every watermark tick, and averaging those in would report a latency
        // nobody waiting for a change ever experiences.
        long started = System.nanoTime();
        boolean changed = view.pendingChanges() > 0;
        boolean applied = false;
        try {
            synchronized (publishLock) {
                if (atApplied) {
                    committedFrontier = frontier.get();
                }
                try {
                    view.commit(committedFrontier);
                    applied = true;
                } finally {
                    // The end of the commit either way, and the audience it was staged for.
                    audience = batchAudience;
                    batchAudience = null;
                    if (!pending.isEmpty()) {
                        // Still cleared when nobody is listening: a sink with no subscribers must
                        // not accumulate a change log nobody will ever read.
                        batch = audience == null || audience.isEmpty() ? List.of() : List.copyOf(pending);
                        pending.clear();
                    }
                }
            }
        } finally {
            // Delivered outside the lock, so a slow listener holds up the next commit's callers
            // rather than the lane's next batch; to the audience the commit began with, not to
            // whoever is attached now (STRM-11).
            deliver(batch, audience, committedFrontier);
            if (applied && changed) {
                view.recordCommitNanos(System.nanoTime() - started);
            }
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
        synchronized (publishLock) {
            return pending.size();
        }
    }

    /** Applies one whole batch: to the view, to the change log, and to the counters, in one step. */
    private void apply(List<Object[]> values, long[] weights, long[] positions, int count) {
        if (count == 0) {
            return;
        }
        long furthest = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            furthest = Math.max(furthest, positions[i]);
        }
        synchronized (publishLock) {
            view.applyBatch(values, weights, positions, count);
            // STRM-11: decided once for the commit, not once per row. See batchAudience.
            List<ViewChangeListener> audience = batchAudience;
            if (audience == null) {
                audience = listeners.isEmpty() ? List.of() : List.copyOf(listeners);
                batchAudience = audience;
            }
            if (!audience.isEmpty()) {
                for (int i = 0; i < count; i++) {
                    pending.add(new ViewChange(values.get(i), weights[i]));
                }
            }
            frontier.accumulateAndGet(furthest, Math::max);
            rowsApplied.addAndGet(count);
        }
    }

    private void deliver(List<ViewChange> batch, List<ViewChangeListener> audience, long committedFrontier) {
        if (batch.isEmpty() || audience == null) {
            return;
        }
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

    /**
     * One lane's batch in progress: rows staged on the lane's thread, applied at its end.
     *
     * <p>Confined to the lane thread, so it takes no lock of its own; the one lock is taken once, by
     * {@link #endOfBatch}, to apply the batch whole.
     */
    private final class LaneBatch implements RowOutput {

        private final List<Object[]> values = new ArrayList<>();
        private long[] weights = new long[64];
        private long[] positions = new long[64];

        @Override
        public RowWriter begin() {
            return new StagedRow(this);
        }

        void stage(Object[] row, long weight, long position) {
            int at = values.size();
            if (at == weights.length) {
                weights = java.util.Arrays.copyOf(weights, at * 2);
                positions = java.util.Arrays.copyOf(positions, at * 2);
            }
            values.add(row);
            weights[at] = weight;
            positions[at] = position;
        }

        @Override
        public void endOfBatch() {
            int count = values.size();
            if (count == 0) {
                // Called on every idle cycle of the lane, so nothing staged has to cost nothing.
                return;
            }
            try {
                apply(values, weights, positions, count);
            } finally {
                values.clear();
                if (weights.length > 4096) {
                    // A watermark that fired a great many windows at once is not the size of the
                    // next batch; do not hold its buffers for ever.
                    weights = new long[64];
                    positions = new long[64];
                }
            }
        }
    }

    /** Collects values, then stages the finished row -- into a lane's batch, or as a batch of one. */
    private final class StagedRow implements RowWriter {

        private final LaneBatch batch;
        private Object[] values = new Object[schema.fields().size()];
        private long weight = 1;
        private long eventTime;
        private long sequence;

        StagedRow(LaneBatch batch) {
            this.batch = batch;
        }

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
            long position = Math.max(eventTime, sequence);
            if (batch != null) {
                batch.stage(values, weight, position);
            } else {
                apply(List.<Object[]>of(values), new long[] {weight}, new long[] {position}, 1);
            }
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
