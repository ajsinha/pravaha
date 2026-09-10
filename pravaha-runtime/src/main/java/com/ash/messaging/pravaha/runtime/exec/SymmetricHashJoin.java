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
        this.store = new RowStore(access, STATE_SLAB_BYTES, maxStateSlabs);
        this.leftState = new JoinSide(store, access, leftSchema, leftKeys);
        this.rightState = new JoinSide(store, access, rightSchema, rightKeys);
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
        rightState.forEachMatch(
                row, leftKeys, leftSchema, (stored, storedWeight) -> emit(row, stored, weight * storedWeight));
        leftState.add(row, weight);
        checkCeiling(leftState, "left");
    }

    private void acceptRight(RowView row) {
        long weight = row.weight();
        leftState.forEachMatch(
                row, rightKeys, rightSchema, (stored, storedWeight) -> emit(stored, row, storedWeight * weight));
        rightState.add(row, weight);
        checkCeiling(rightState, "right");
    }

    /**
     * Writes one joined row: the left's columns, then the right's.
     *
     * <p>The event time is the later of the two, because a pair is not complete until both halves
     * have arrived and claiming otherwise would let a window close over a row it had not yet seen.
     */
    private void emit(RowView left, RowView right, long weight) {
        if (weight == 0) {
            return;
        }
        long handle = arena.allocate(outputLayout.rowSize(1024));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(
                    RuntimeErrors.ARENA_EXHAUSTED,
                    "the join's output arena is full; " + "raise arena.slab.size, or reduce the fan-out of this join");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        int width = plan.leftWidth();
        for (int i = 0; i < width; i++) {
            InterpretedPipeline.copyField(left, i, writer, i, plan.outputSchema());
        }
        for (int i = 0; i < rightSchema.fields().size(); i++) {
            InterpretedPipeline.copyField(right, i, writer, width + i, plan.outputSchema());
        }
        writer.weight(weight)
                .eventTimestampNanos(Math.max(left.eventTimestampNanos(), right.eventTimestampNanos()))
                .sequence(Math.max(left.sequence(), right.sequence()))
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pairsEmitted++;
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
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
}
