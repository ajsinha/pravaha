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

    /**
     * Listeners waiting for their snapshot at the end of the commit in flight (SUB-1).
     *
     * <p>A listener that asks for a snapshot while a commit is under way -- rows applied that no
     * commit has published -- cannot be given the committed state now: that state lacks the rows in
     * flight, and the commit publishing them was staged for an audience this listener is not in. It
     * waits here instead, and the commit that publishes those rows takes its snapshot and makes it a
     * listener in the same critical section, so the next commit is its first. Mutated only under
     * {@link #publishLock}; copy-on-write so {@link #listenerCount} can read it without the lock.
     */
    private final List<Handoff> joiners = new CopyOnWriteArrayList<>();

    /**
     * Listeners handed how the view's answer changed at each commit, rather than what was applied
     * (KEYEDWT-1): a subscription that asked to follow the answer. See {@link #onAnswer}. Mutated under {@link #publishLock}.
     */
    private final List<ViewChangeListener> answerListeners = new CopyOnWriteArrayList<>();

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
        List<Handoff> promoted = List.of();
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
        List<ViewChange> answerBatch = List.of();
        List<ViewChangeListener> answerAudience = List.of();
        try {
            synchronized (publishLock) {
                if (atApplied) {
                    // Never behind what the view already committed. A view restored from a
                    // checkpoint carries the frontier it was committed at, and until a row arrives
                    // nothing here has been applied at all: committing "what has been applied"
                    // then asked for Long.MIN_VALUE and was refused as a frontier going backwards
                    // -- on the feed's publish thread, which that refusal killed.
                    committedFrontier = Math.max(frontier.get(), view.committedFrontier());
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
                    // Whoever was waiting for this commit to end gets the view it produced, and is
                    // a listener from the next commit on -- in this critical section, so no commit
                    // can fall between the two (SUB-1). Taken even when view.commit threw: it
                    // applies before it refuses, so the committed state is still the one to start
                    // from.
                    // The answer's change, which the view computed inside its own commit: to every
                    // answer listener attached now. No STRM-11 audience to fix earlier, because the
                    // change is between two committed answers and none of it is staged per batch --
                    // a listener attached since the last commit started from that commit's answer.
                    // Before the joiners are promoted: their snapshot already holds this commit.
                    if (!answerListeners.isEmpty()) {
                        answerAudience = List.copyOf(answerListeners);
                        answerBatch = asChanges(view.takeAnswer());
                    }
                    promoted = promoteJoiners();
                }
            }
        } finally {
            // Delivered outside the lock, so a slow listener holds up the next commit's callers
            // rather than the lane's next batch; to the audience the commit began with, not to
            // whoever is attached now (STRM-11).
            deliver(batch, audience, committedFrontier);
            deliver(answerBatch, answerAudience, committedFrontier);
            for (Handoff handoff : promoted) {
                handoff.handOver();
            }
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
                    // STRM-1: a weight of zero is not a change, so it is not staged. The view adds
                    // it to the key's net weight, which moves nothing, and keeps the row it held;
                    // delivering it told a subscriber the opposite, because ViewChange.isRetraction
                    // is weight < 0 and the consumption model ViewChange recommends -- "ignore
                    // negative weights and overwrite by key" -- then wrote the zero-weight row's
                    // values over a copy of a view that had not changed. Skipped here rather than
                    // in the view, so the change stream and the view agree by construction instead
                    // of by both happening to keep the old row.
                    if (weights[i] != 0) {
                        pending.add(new ViewChange(values.get(i), weights[i]));
                    }
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

    /** A commit's answer change as weighted rows: what left at {@code -1}, then what entered at {@code +1}. */
    private static List<ViewChange> asChanges(AnswerChanges.Netted netted) {
        if (netted == null) {
            return List.of();
        }
        List<ViewChange> changes =
                new ArrayList<>(netted.leaving().size() + netted.entering().size());
        for (Object[] row : netted.leaving()) {
            changes.add(new ViewChange(row, -1L));
        }
        for (Object[] row : netted.entering()) {
            changes.add(new ViewChange(row, 1L));
        }
        return changes;
    }

    /**
     * Registers a listener for how the view's <em>answer</em> changes, from the next commit on
     * (KEYEDWT-1).
     *
     * <p>{@link #onCommit} hands a listener what the lanes applied, and for a keyed view that is not
     * the answer: an upsert that replaces a key's row arrives as a {@code +1} for the new row with no
     * {@code -1} for the old one (the view keeps both rows and shows the newer, VIEWW-1), and a row
     * retention evicts arrives as nothing at all. A subscriber summing weights -- which CONCEPTS §4
     * told it to do -- then holds two rows where the view shows one, and one it has evicted. Here
     * each commit is handed as the rows that left the answer at {@code -1} and the rows that entered
     * it at {@code +1}, computed by the view in its own commit, so the weights a subscriber sums are
     * exactly the rows a reader of the view sees.
     *
     * <p>Opt-in ({@code SubscriptionOptions.followingTheAnswer()}): the changelog stays what a plain
     * subscription and every sink are handed, because its weights passing through verbatim is a
     * documented contract (STRM-008 to STRM-012) that the answer does not keep.
     *
     * @return a handle that removes the listener
     */
    public AutoCloseable onAnswer(ViewChangeListener listener) {
        synchronized (publishLock) {
            view.answerWanted(true);
            answerListeners.add(listener);
        }
        return () -> removeAnswerListener(listener);
    }

    /**
     * {@link #onAnswer}, starting from the committed answer: each row a reader sees, once, at {@code
     * +1}, and then every commit after it as its answer change (KEYEDWT-1, SUB-1).
     *
     * <p>Attached exactly as {@link #onCommitFromSnapshot} attaches: with no commit in flight the
     * snapshot is the committed answer now; with one in flight it waits for that commit to end and
     * starts from the answer it published, so the rows in flight are in the snapshot. The snapshot
     * plus the changes is the view, at every commit after it.
     */
    public AutoCloseable onAnswerFromSnapshot(ViewChangeListener listener) {
        Handoff handoff = new Handoff(listener, true);
        boolean now;
        synchronized (publishLock) {
            view.answerWanted(true);
            now = batchAudience == null && view.pendingChanges() == 0;
            if (now) {
                handoff.capture(view.committedAnswer(), view.committedFrontier());
                answerListeners.add(handoff);
            } else {
                joiners.add(handoff);
            }
        }
        if (now) {
            handoff.handOver();
        }
        return () -> {
            handoff.close();
            synchronized (publishLock) {
                joiners.remove(handoff);
            }
            removeAnswerListener(handoff);
        };
    }

    private void removeAnswerListener(ViewChangeListener listener) {
        synchronized (publishLock) {
            answerListeners.remove(listener);
            if (answerListeners.isEmpty()) {
                view.answerWanted(false);
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

    /**
     * Registers a listener that starts from the view's committed state, with no gap and no overlap
     * between that state and the commits it hears about afterwards (SUB-1).
     *
     * <p>{@link #onCommit} alone cannot do this. A listener registered there joins at the next
     * commit boundary -- the audience of a commit is fixed when its first batch is applied (STRM-11)
     * -- and the committed state a caller reads beside it lacks the commit in flight. Whichever order
     * the two are done in, a commit can land in neither.
     *
     * <p>Here both are one step, under the lock every batch and every commit takes:
     *
     * <ul>
     *   <li><strong>No commit in flight</strong> -- nothing applied since the last commit. The
     *       committed state is everything applied, so it is the snapshot, taken now with its
     *       frontier; the listener is registered in the same critical section, so the next commit's
     *       first batch finds it in the audience.
     *   <li><strong>A commit in flight</strong> -- rows applied, not yet published, and staged for
     *       an audience fixed before this listener existed. The listener waits for that commit to
     *       end; the commit takes the snapshot, which now contains those rows, and registers it,
     *       inside its own critical section.
     * </ul>
     *
     * <p>Either way the snapshot is the view at some commit C, the listener is in the audience of
     * every commit after C and of none up to C, and every commit is delivered whole: the snapshot
     * plus the changes is the view, at every commit after it. Nothing is logged to do it -- the
     * snapshot is a copy of the committed rows, bounded by the view's own ceiling.
     *
     * <p>{@link ViewChangeListener#onSnapshot} is called exactly once and before any {@link
     * ViewChangeListener#onCommit}, on the subscribing thread or on the thread of the commit that
     * ended the one in flight. Something must commit for the second case to finish: the engine does
     * on its own schedule, and {@code RegisteredQuery} commits at once when it subscribes this way.
     *
     * @return a handle that removes the listener, whether or not its snapshot has been delivered
     */
    public AutoCloseable onCommitFromSnapshot(ViewChangeListener listener) {
        Handoff handoff = new Handoff(listener);
        boolean now;
        synchronized (publishLock) {
            now = batchAudience == null && view.pendingChanges() == 0;
            if (now) {
                handoff.capture(view.committedRows(), view.committedFrontier());
                listeners.add(handoff);
            } else {
                joiners.add(handoff);
            }
        }
        if (now) {
            handoff.handOver();
        }
        return () -> {
            handoff.close();
            listeners.remove(handoff);
            synchronized (publishLock) {
                joiners.remove(handoff);
            }
        };
    }

    /**
     * Whether a listener attached by {@link #onCommitFromSnapshot} is still waiting for the commit
     * in flight to end, so a caller that can commit knows a commit would hand it its snapshot.
     */
    public boolean awaitingSnapshot() {
        return !joiners.isEmpty();
    }

    /** Takes every waiting listener's snapshot and makes it a listener. Under the publish lock. */
    private List<Handoff> promoteJoiners() {
        if (joiners.isEmpty()) {
            return List.of();
        }
        List<Handoff> promoted = List.copyOf(joiners);
        joiners.clear();
        long at = view.committedFrontier();
        List<ViewChange> rows = null;
        List<ViewChange> answer = null;
        for (Handoff handoff : promoted) {
            if (handoff.answer) {
                answer = answer == null ? view.committedAnswer() : answer;
                handoff.capture(answer, at);
                view.answerWanted(true);
                answerListeners.add(handoff);
            } else {
                rows = rows == null ? view.committedRows() : rows;
                handoff.capture(rows, at);
                listeners.add(handoff);
            }
        }
        return promoted;
    }

    /** How many listeners are attached, counting those still waiting for their snapshot. */
    public int listenerCount() {
        return listeners.size() + joiners.size() + answerListeners.size();
    }

    /** Whether anybody is listening, which is worth knowing before doing work for them. */
    public boolean hasListeners() {
        return !listeners.isEmpty() || !joiners.isEmpty() || !answerListeners.isEmpty();
    }

    public long rowsApplied() {
        return rowsApplied.get();
    }

    public ServedView view() {
        return view;
    }

    /**
     * A listener attached with its snapshot, which it is handed before anything else.
     *
     * <p>The snapshot is captured under the publish lock, before the listener can be in any
     * commit's audience, and handed over after the lock is released -- by whichever comes first of
     * the thread that captured it and a commit delivering to this listener. The monitor on this
     * object orders the two, so a commit that races the handover waits for it rather than arriving
     * first. A slow listener holds up only its own deliveries, as before.
     */
    private static final class Handoff implements ViewChangeListener {

        private final ViewChangeListener target;
        private List<ViewChange> snapshot;
        private long snapshotFrontier;
        private boolean closed;

        /** Whether it follows the answer (KEYEDWT-1) rather than the changelog. */
        private final boolean answer;

        Handoff(ViewChangeListener target) {
            this(target, false);
        }

        Handoff(ViewChangeListener target, boolean answer) {
            this.target = target;
            this.answer = answer;
        }

        synchronized void capture(List<ViewChange> rows, long frontier) {
            snapshot = rows;
            snapshotFrontier = frontier;
        }

        synchronized void handOver() {
            List<ViewChange> rows = snapshot;
            if (rows == null || closed) {
                return;
            }
            snapshot = null;
            try {
                target.onSnapshot(rows, snapshotFrontier);
            } catch (RuntimeException | Error escaped) {
                // The same backstop deliver() is: a listener owns its failures.
                LOG.log(
                        System.Logger.Level.WARNING,
                        "a view change listener threw on its snapshot; the listener is responsible for "
                                + "its own failure and this is only the backstop",
                        escaped);
            }
        }

        synchronized void close() {
            closed = true;
            snapshot = null;
        }

        @Override
        public synchronized void onCommit(List<ViewChange> changes, long frontier) {
            if (closed) {
                return;
            }
            handOver();
            target.onCommit(changes, frontier);
        }
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
