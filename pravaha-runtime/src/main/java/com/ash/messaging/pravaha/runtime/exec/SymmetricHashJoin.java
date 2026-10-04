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
package com.ash.messaging.pravaha.runtime.exec;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.state.RowStore;
import com.ash.messaging.pravaha.state.StateErrors;

/**
 * The bilinear join, executed one row at a time.
 *
 * <p>Design section 9.3 states the rule over batches: {@code Δ(A⋈B) = ΔA⋈I(B) + I(A)⋈ΔB + ΔA⋈ΔB}.
 * A streaming engine applies it a row at a time, where it collapses into something simpler than it
 * looks: a left row is matched against everything the right side holds and then added to the left
 * side's state, and symmetrically.
 *
 * <p>The third term -- the one everybody drops -- is not missing here; it is a consequence of the
 * ordering. Two rows that would have been in the same batch arrive one after the other, and whichever
 * lands second finds the first already in state and matches it. That is the same set of pairs the
 * {@code ΔA⋈ΔB} term produces, obtained by not having batches. It is worth stating because the
 * batched form is what the algebra module implements, and the two are tested against each other.
 *
 * <p>Retractions need no special handling and get none. An arriving row carries a weight; a stored
 * row carries a weight; the pair's weight is their product. A retraction is a weight of {@code -1},
 * so it produces exactly the negatives of the pairs its insert produced, and adds {@code -1} to its
 * side's state where it cancels the entry. An update -- retract old, insert new -- therefore
 * retracts precisely the old row's pairs and emits the new row's, with no update path in the code.
 *
 * <p><strong>State is bounded by a ceiling, not by a window, and that is a limitation rather than a
 * design.</strong> Both sides keep every row that could still match. Until windowed and versioned
 * joins land, the only protection is a row count that fails the query when crossed -- loudly, naming
 * the count -- because the alternative is a node that dies with no explanation at all.
 */
final class SymmetricHashJoin implements AutoCloseable {

    /** Slab size for join state. Large enough that a slab holds many rows, small enough to grow in steps. */
    private static final int STATE_SLAB_BYTES = 1 << 20;

    private final JoinOperator plan;

    private long outsideWindow;
    private long unmatchedEmitted;

    /** Rows released because they fell outside the join's match window. Not an error count. */
    private long evicted;

    private final RowStore store;
    private final JoinSide leftState;
    private final JoinSide rightState;
    private final int[] leftKeys;
    private final int[] rightKeys;
    private final StreamSchema leftSchema;
    private final StreamSchema rightSchema;
    private final RowArena arena;
    private final RowLayout outputLayout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final RowProcessor downstream;

    private long pairsEmitted;

    SymmetricHashJoin(JoinOperator plan, RowArena arena, RowProcessor downstream, int maxStateSlabs) {
        this(plan, arena, downstream, maxStateSlabs, null, 0);
    }

    /**
     * ADR-037 item B2: a join whose state would otherwise be refused at {@code maxStateSlabs} keeps
     * running, slower, once given somewhere to spill to.
     *
     * @param overflowAccess where state is carved from once {@code maxStateSlabs} of in-memory
     *     slabs are exhausted, or {@code null} for no overflow tier -- today's behaviour, unchanged
     * @param maxOverflowSlabs the ceiling on {@code overflowAccess} slabs, ignored when {@code
     *     overflowAccess} is {@code null}
     */
    SymmetricHashJoin(
            JoinOperator plan,
            RowArena arena,
            RowProcessor downstream,
            int maxStateSlabs,
            @Nullable MemoryAccess overflowAccess,
            int maxOverflowSlabs) {
        this.plan = plan;
        this.arena = arena;
        this.downstream = downstream;
        this.leftSchema = plan.left().outputSchema();
        this.rightSchema = plan.right().outputSchema();
        this.leftKeys = ordinals(plan.leftKeys());
        this.rightKeys = ordinals(plan.rightKeys());
        for (int ordinal : leftKeys) {
            JoinKeys.checkJoinable(leftSchema, ordinal, "left");
        }
        for (int ordinal : rightKeys) {
            JoinKeys.checkJoinable(rightSchema, ordinal, "right");
        }
        MemoryAccess access = MemoryAccess.best();
        this.store = new RowStore(access, STATE_SLAB_BYTES, maxStateSlabs, overflowAccess, maxOverflowSlabs);
        // The key index spills with the rows when there is a tier, under the same RAM ceiling (ADR-044).
        this.leftState =
                new JoinSide(store, access, leftSchema, leftKeys, maxStateSlabs, overflowAccess, maxOverflowSlabs);
        this.rightState =
                new JoinSide(store, access, rightSchema, rightKeys, maxStateSlabs, overflowAccess, maxOverflowSlabs);
        // J-1. A left row whose key is null matches nothing, and SQL says a LEFT join must still
        // emit it, null-padded on the right. It was dropped on arrival, so it was never in state
        // when eviction ran the outer-join callback: the row left no trace at all, and nothing
        // counted it as anything but one fewer row held. Only the left side of a LEFT join keeps
        // them -- anywhere else they are a leak, because nothing would ever read them.
        this.leftState.keepNullKeyedRows(plan.leftOuter());
        this.outputLayout = RowLayout.of(plan.outputSchema());
        this.writer = new BinaryRowWriter(outputLayout);
        this.view = new BinaryRowView(outputLayout);
    }

    /** Where the left input's rows go. */
    RowProcessor leftInput() {
        return this::acceptLeft;
    }

    /** Where the right input's rows go. */
    RowProcessor rightInput() {
        return this::acceptRight;
    }

    private void acceptLeft(RowView row) {
        long weight = row.weight();
        boolean[] matched = new boolean[1];
        rightState.forEachMatch(row, leftKeys, leftSchema, (stored, storedWeight) -> {
            if (emit(row, stored, weight * storedWeight)) {
                matched[0] = true;
            }
        });
        long entry = leftState.add(row, weight);
        if (matched[0] && entry != ArenaHandle.NULL) {
            // Recorded on the row itself, so that when it is eventually evicted the join knows
            // whether it ever found a partner without having to keep a second index of what did.
            leftState.markMatched(row);
        }
        checkCeiling(leftState, "left");
    }

    private void acceptRight(RowView row) {
        long weight = row.weight();
        leftState.forEachMatch(row, rightKeys, rightSchema, (stored, storedWeight) -> {
            if (emit(stored, row, storedWeight * weight)) {
                // The left row that matched is the one already in state, and marking it is what
                // stops an outer join emitting a null-padded duplicate of a row that did match.
                leftState.markMatched(stored);
            }
        });
        rightState.add(row, weight);
        checkCeiling(rightState, "right");
    }

    /**
     * Writes one joined row: the left's columns, then the right's.
     *
     * <p>The event time is the later of the two, because a pair is not complete until both halves
     * have arrived and claiming otherwise would let a window close over a row it had not yet seen.
     */
    private boolean emit(RowView left, RowView right, long weight) {
        if (weight == 0) {
            return false;
        }
        // The temporal predicate decides which pairs are in the answer, not merely how long state is
        // kept. Using it only for eviction would return every pair still in state -- correct pairs
        // plus whatever the retention horizon happened to allow -- so a query asking for matches
        // within five minutes would get matches within an hour and no indication of it.
        if (!plan.matchesInTime(left.eventTimestampNanos(), right.eventTimestampNanos())) {
            outsideWindow++;
            return false;
        }
        long handle = arena.allocate(outputLayout.rowSize(1024));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(
                    RuntimeErrors.ARENA_EXHAUSTED,
                    "the join's output arena is full; "
                            + "raise pravaha.lane.arena.slab-bytes, or reduce the fan-out of this join");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        int width = plan.leftWidth();
        for (int i = 0; i < width; i++) {
            RowStages.copyField(left, i, writer, i, plan.outputSchema());
        }
        for (int i = 0; i < rightSchema.fields().size(); i++) {
            RowStages.copyField(right, i, writer, width + i, plan.outputSchema());
        }
        writer.weight(weight)
                .eventTimestampNanos(Math.max(left.eventTimestampNanos(), right.eventTimestampNanos()))
                .sequence(Math.max(left.sequence(), right.sequence()))
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pairsEmitted++;
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        return true;
    }

    /**
     * Writes a left row with nulls where the right row's columns would be.
     *
     * <p>Emitted once, when the left row is evicted having never matched, and never retracted. That
     * is sound only because eviction happens after the watermark has passed the point where a match
     * could still arrive: at that moment "has not matched" and "will not match" are the same
     * statement. Emitting eagerly and retracting later -- which is what an unwindowed outer join
     * would have to do -- means every unmatched row produces two output rows and is held until it
     * does.
     */
    private void emitNullPadded(RowView left) {
        long handle = arena.allocate(outputLayout.rowSize(1024));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(
                    RuntimeErrors.ARENA_EXHAUSTED,
                    "the join's output arena is full while emitting an unmatched row; raise pravaha.lane.arena.slab-bytes");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        int width = plan.leftWidth();
        for (int i = 0; i < width; i++) {
            RowStages.copyField(left, i, writer, i, plan.outputSchema());
        }
        for (int i = 0; i < rightSchema.fields().size(); i++) {
            writer.setNull(width + i);
        }
        writer.weight(1L)
                .eventTimestampNanos(left.eventTimestampNanos())
                .sequence(left.sequence())
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        unmatchedEmitted++;
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    /** Left rows emitted with nulls because they never found a match. */
    long unmatchedEmitted() {
        return unmatchedEmitted;
    }

    /**
     * Every row one side of this join is holding, keyed by its join key, as text (ADR-048).
     *
     * <p>Read-only, and rendered as it walks: a stored row is a flyweight over the row store, and
     * the cursor moves to the next entry as soon as the visitor returns.
     */
    void describe(boolean left, java.util.function.BiConsumer<String, java.util.Map<String, String>> into) {
        JoinSide side = left ? leftState : rightState;
        com.ash.messaging.pravaha.api.data.StreamSchema schema = left ? leftSchema : rightSchema;
        int[] keys = left ? leftKeys : rightKeys;
        side.describeHeld((row, weight, matched) -> {
            java.util.Map<String, String> values = RowText.of(row, schema);
            values.put("weight", Long.toString(weight));
            values.put("matched", Boolean.toString(matched));
            into.accept(RowText.key(row, keys, schema), values);
        });
    }

    /** Distinct rows one side holds, for the state listing's count. */
    long distinctRowsHeld(boolean left) {
        return (left ? leftState : rightState).distinctRows();
    }

    /** What this join is called in a plan, so a state listing can name it. */
    String label() {
        return plan.label();
    }

    /** What either side may hold before it is refused, so the ceiling can be seen before it is hit. */
    long rowCeilingPerSide() {
        return plan.maxRowsPerSide();
    }

    private void checkCeiling(JoinSide side, String which) {
        if (side.distinctRows() > plan.maxRowsPerSide()) {
            throw new PravahaException(
                    StateErrors.STATE_TOO_LARGE,
                    "the " + which + " side of " + plan.label() + " holds " + side.distinctRows()
                            + " rows, past the ceiling of " + plan.maxRowsPerSide()
                            + ". Both sides of a stream-to-stream join keep every row that could still match, "
                            + "so an unwindowed join over unbounded streams grows without limit. Bound it with a "
                            + "window, a time-versioned right side, or a tighter key range.");
        }
    }

    long rowsHeldLeft() {
        return leftState.distinctRows();
    }

    long rowsHeldRight() {
        return rightState.distinctRows();
    }

    /** Writes both sides, left first. */
    void writeTo(java.io.DataOutputStream out) throws java.io.IOException {
        leftState.writeTo(out);
        rightState.writeTo(out);
    }

    /** Reads both sides back, in the order they were written. */
    void readFrom(java.io.DataInputStream in) throws java.io.IOException {
        leftState.readFrom(in);
        rightState.readFrom(in);
    }

    long keysHeldLeft() {
        return leftState.distinctKeys();
    }

    long keysHeldRight() {
        return rightState.distinctKeys();
    }

    long pairsEmitted() {
        return pairsEmitted;
    }

    /** Bytes the state store has taken from the operating system. */
    long stateBytes() {
        return store.bytesReserved();
    }

    /** Whether this join has spilled any state to its overflow tier. */
    boolean hasSpilled() {
        return store.hasSpilled() || leftState.indexHasSpilled() || rightState.indexHasSpilled();
    }

    /** How many overflow-tier slabs this join has used. */
    int overflowSlabsUsed() {
        return store.overflowSlabsUsed();
    }

    /**
     * Compacts the row store both sides share, if its overflow tier is at least {@code threshold}
     * fragmented (ADR-044). The join is the owner of every handle into that store -- each side's
     * bucket heads and chain links -- so it is the one that presents them. Called between batches,
     * on the lane thread.
     *
     * @return how many overflow slabs were released
     */
    int compactIfFragmented(double threshold) {
        // Each side's key index is a store of its own, which its map compacts; the rows are shared.
        int released = leftState.compactIndexIfFragmented(threshold) + rightState.compactIndexIfFragmented(threshold);
        if (!store.needsCompaction(threshold)) {
            return released;
        }
        return released
                + store.compactOverflow(threshold, relocation -> {
                    leftState.relocateRows(relocation);
                    rightState.relocateRows(relocation);
                });
    }

    /** RAM both sides' key indexes hold -- their slot tables and their stores, each up to the RAM ceiling. */
    long indexRamBytes() {
        return leftState.indexRamBytes() + rightState.indexRamBytes();
    }

    /** Bytes of both sides' key-index slot tables held in the overflow tier (ADR-044). */
    long indexTableBytesMapped() {
        return leftState.indexTableBytesMapped() + rightState.indexTableBytesMapped();
    }

    /** The overflow tier's numbers for this join's row store. */
    com.ash.messaging.pravaha.state.SpillStatistics spillStatistics() {
        return com.ash.messaging.pravaha.state.SpillStatistics.of(store)
                .plus(leftState.indexSpillStatistics())
                .plus(rightState.indexSpillStatistics());
    }

    /** The overflow tier's numbers for the rows alone, without the key indexes. */
    com.ash.messaging.pravaha.state.SpillStatistics rowSpillStatistics() {
        return com.ash.messaging.pravaha.state.SpillStatistics.of(store);
    }

    private static int[] ordinals(java.util.List<Integer> list) {
        int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    @Override
    public void close() {
        leftState.close();
        rightState.close();
        store.close();
    }

    /**
     * Releases state that can no longer match, given how far event time has advanced.
     *
     * <p>The horizon is {@code watermark - matchWithin}. A row older than that cannot be part of any
     * match this join promises: a watermark is the statement that nothing earlier is still to come,
     * so every partner still to arrive is later than the horizon, and a pair spanning more than the
     * match window is outside what the query asked for. Releasing it honours the definition rather
     * than losing data.
     *
     * <p>This is the difference between a bound that is <em>semantic</em> and one that is merely
     * operational. Evicting to stay under a row ceiling would drop rows the query did ask about, so
     * that ceiling fails loudly instead.
     */
    void advanceWatermark(long watermarkNanos) {
        if (watermarkNanos == Long.MIN_VALUE) {
            return;
        }
        long window = plan.matchWithinNanos();
        // Saturating: a watermark near the bottom of the range must not wrap into the future and
        // evict everything.
        long horizon = watermarkNanos - window;
        if (horizon > watermarkNanos) {
            return;
        }
        // An outer join's null-padded rows are emitted here, at the one moment when "has not matched"
        // and "will not match" mean the same thing.
        evicted += leftState.evictOlderThan(horizon, plan.leftOuter() ? this::emitNullPadded : null);
        evicted += rightState.evictOlderThan(horizon);
    }

    /**
     * Pairs that matched on the key but fell outside the query's time bounds.
     *
     * <p>Worth watching. A join whose matches are almost all rejected here is one whose temporal
     * predicate does not describe the data -- clocks disagreeing between two producers is the usual
     * cause -- and the symptom is an empty result that looks exactly like no data.
     */
    long outsideWindow() {
        return outsideWindow;
    }

    /** Rows released because they aged past the match window. */
    long evicted() {
        return evicted;
    }
}
