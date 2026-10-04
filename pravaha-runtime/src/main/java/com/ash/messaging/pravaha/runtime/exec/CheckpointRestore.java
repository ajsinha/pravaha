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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.lane.Lane;
import com.ash.messaging.pravaha.runtime.lane.LaneGroup;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

/**
 * One restore of a query's state from a checkpoint, all or nothing (RESTOREPART-1).
 *
 * <p>A checkpoint is restored in parts -- each lane's pipeline, one operator after another, then the
 * view -- and any part can refuse: a corrupt or foreign lane snapshot, a plan that changed, a view
 * format this engine no longer reads. The registry answers a refused restore by starting the query
 * from its sources, which is sound only if the query then holds <em>nothing</em>. It used to hold
 * whatever had been put back before the refusal: lane 0's windows beside an empty lane 1, or a
 * pipeline's windows beside its empty join, and replaying the sources into that counted every row
 * before the checkpoint twice in the parts that came back. The answer was wrong with nothing to say
 * so.
 *
 * <p>So before anything is put back, each part's current state is taken -- on its own lane, where
 * the state lives -- and a refusal puts every part back to it. For an execution that has not been
 * fed, which is the only kind a restore is for, that is empty state. Every operator's {@code
 * readFrom} replaces what it holds rather than merging into it, so putting a snapshot back is a
 * reset rather than an addition.
 *
 * <p>A part that refuses on its lane is caught there rather than left to kill the lane: the query is
 * about to run from its sources on that lane, and a lane that died restoring a checkpoint it was
 * never going to use would take the query to {@code FAILED} for a reason that no longer applies.
 */
final class CheckpointRestore {

    private final List<InterpretedPipeline> pipelines;
    private final LaneGroup lanes;
    private final @Nullable Supplier<byte[]> viewSnapshot;
    private final @Nullable Consumer<byte[]> viewRestore;

    /** Each pipeline's state before the restore; null for one the checkpoint holds nothing for. */
    private byte @Nullable [][] before;

    private byte @Nullable [] viewBefore;

    /** Whether a part may hold restored state that neither a success nor an undo accounts for. */
    private volatile boolean leftStateBehind;

    CheckpointRestore(
            List<InterpretedPipeline> pipelines,
            LaneGroup lanes,
            @Nullable Supplier<byte[]> viewSnapshot,
            @Nullable Consumer<byte[]> viewRestore) {
        this.pipelines = pipelines;
        this.lanes = lanes;
        this.viewSnapshot = viewSnapshot;
        this.viewRestore = viewRestore;
    }

    /**
     * Restores every part, or -- when one refuses -- none of them, and then throws the refusal.
     *
     * @param viewKey the key the view's contents travel under in the checkpoint
     */
    void apply(Checkpoint checkpoint, String viewKey, Duration timeout) {
        capture(checkpoint, viewKey, timeout);
        leftStateBehind = true;
        try {
            restoreEachPart(checkpoint, viewKey, timeout);
            leftStateBehind = false;
        } catch (RuntimeException refused) {
            try {
                undo(timeout);
            } catch (RuntimeException undoFailed) {
                refused.addSuppressed(undoFailed);
            }
            throw refused;
        }
    }

    /** Takes what each part holds now, which is what an undo puts back. */
    private void capture(Checkpoint checkpoint, String viewKey, Duration timeout) {
        before = new byte[pipelines.size()][];
        for (int index = 0; index < pipelines.size(); index++) {
            if (checkpoint.operatorState().get("lane-" + index) == null) {
                continue;
            }
            InterpretedPipeline pipeline = pipelines.get(index);
            AtomicReference<byte[]> taken = new AtomicReference<>();
            onLane(index, () -> taken.set(pipeline.snapshotState()), timeout, "take its state before a restore");
            before[index] = taken.get();
        }
        viewBefore =
                viewSnapshot != null && checkpoint.operatorState().get(viewKey) != null ? viewSnapshot.get() : null;
    }

    private void restoreEachPart(Checkpoint checkpoint, String viewKey, Duration timeout) {
        for (int index = 0; index < pipelines.size(); index++) {
            InterpretedPipeline pipeline = pipelines.get(index);
            byte[] state = checkpoint.operatorState().get("lane-" + index);
            if (state == null) {
                if (pipeline.isStateful()) {
                    // A stateful operator with nothing to restore is not a no-op. Skipping it
                    // resumes with empty accumulators beside restored source offsets, so every row
                    // before the checkpoint is gone and the query reports RUNNING over the gap.
                    throw new PravahaException(
                            RuntimeErrors.LANE_FAILED,
                            "the checkpoint holds no state for lane " + index + ", and this plan's lane " + index
                                    + " is stateful. Restoring the offsets without the accumulators would resume "
                                    + "past every row the checkpoint covered and answer from an empty operator.");
                }
                continue;
            }
            putBack(index, state, timeout, "restore its state");
        }
        byte[] view = checkpoint.operatorState().get(viewKey);
        if (view != null && viewRestore != null) {
            // After the lanes, so a view restored beside operator state is restored beside state
            // that is already back -- not beside state still arriving on another thread.
            viewRestore.accept(view);
        }
    }

    /**
     * Puts every part back to what it held before {@link #apply}.
     *
     * @throws PravahaException {@code PRV-3010} when a part cannot be put back; the state is then
     *     unknown and {@link #leftStateBehind()} stays true
     */
    void undo(Duration timeout) {
        if (before == null) {
            return;
        }
        leftStateBehind = true;
        try {
            for (int index = 0; index < before.length; index++) {
                byte[] state = before[index];
                if (state != null) {
                    putBack(index, state, timeout, "put its state back");
                }
            }
            if (viewBefore != null && viewRestore != null) {
                viewRestore.accept(viewBefore);
            }
        } catch (RuntimeException failed) {
            throw new PravahaException(
                    RuntimeErrors.LANE_FAILED,
                    "a restore that failed could not be undone, so this query holds state that is neither the "
                            + "checkpoint's nor empty. Starting it from its sources would count again every row "
                            + "the restored parts already hold; it is refused instead. Cause: " + failed.getMessage(),
                    failed);
        }
        leftStateBehind = false;
    }

    /** Whether a part may hold restored state that neither a finished restore nor an undo accounts for. */
    boolean leftStateBehind() {
        return leftStateBehind;
    }

    /** Replaces lane {@code index}'s pipeline state with {@code state}, on that lane. */
    private void putBack(int index, byte[] state, Duration timeout, String what) {
        InterpretedPipeline pipeline = pipelines.get(index);
        onLane(index, () -> pipeline.restoreState(state), timeout, what);
    }

    /**
     * Runs {@code task} on lane {@code index}'s own thread and waits for it, throwing what it threw.
     *
     * <p>Caught on the lane rather than left to escape there: an exception out of a control task
     * fails the lane, and a lane that died on a restore the query is about to abandon would take a
     * query that could have started from its sources to {@code FAILED}.
     */
    private void onLane(int index, Runnable task, Duration timeout, String what) {
        Lane lane = lanes.lane(index);
        AtomicReference<RuntimeException> thrown = new AtomicReference<>();
        long ticket = lane.submitControlTask(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });
        if (!lane.awaitControlTask(ticket, timeout)) {
            throw new IllegalStateException("lane " + index + " did not " + what + " within " + timeout);
        }
        lane.checkHealth();
        RuntimeException failed = thrown.get();
        if (failed != null) {
            throw failed;
        }
    }
}
