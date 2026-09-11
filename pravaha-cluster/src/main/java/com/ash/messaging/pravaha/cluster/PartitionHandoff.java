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
package com.ash.messaging.pravaha.cluster;

import java.time.Duration;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Moves one virtual partition from the node that owns it to the node that should.
 *
 * <p>The sequence is the whole of the correctness argument, so it is written out rather than hidden
 * behind one call:
 *
 * <ol>
 *   <li><strong>Source pauses.</strong> Nothing else can happen first. A snapshot of a partition
 *       that is still consuming input is a snapshot of a moving target.
 *   <li><strong>Source snapshots</strong>, state and offsets together.
 *   <li><strong>Target restores</strong>, and stays paused.
 *   <li><strong>Ownership flips</strong>, and only now.
 *   <li><strong>Target resumes</strong> from the offsets that travelled with the state.
 *   <li><strong>Source releases</strong> its copy.
 * </ol>
 *
 * <p>Two windows are worth naming, because one is acceptable and the other never is.
 *
 * <p>Between steps 1 and 5 <em>nobody</em> is processing the partition. That is the pause, budgeted
 * at five seconds (NFR-6), and it costs latency for those keys while the rest of the query runs on.
 * Input is not lost: it accumulates at the source and is read from the handed-over offsets.
 *
 * <p>At no point are <em>both</em> nodes processing it. The alternative ordering, starting the
 * target before stopping the source to shorten the pause, would mean two nodes updating one
 * aggregate, and the damage from that is silent and durable. The pause is a cost; double ownership
 * is a corruption. That is the same trade the mode/mechanism check in {@link CoordinatorFactory}
 * makes, one level up.
 *
 * <p>Failure before step 4 rolls back: the source still has its state and its offsets, so it resumes
 * and the move is retried later. Failure after step 4 cannot roll back, because the target owns the
 * partition and the assignment says so, so it is reported for checkpoint recovery rather than
 * papered over here. Step 6 is deliberately last and deliberately separate: until the target is
 * actually serving, the source's copy is the only thing standing between a failed handoff and lost
 * state.
 */
public final class PartitionHandoff {

    /** Where a handoff got to. Exposed because "it failed" is not actionable but "it failed here" is. */
    public enum Stage {
        /** Not started. */
        PENDING,
        /** Source has stopped processing. Rollback is possible. */
        SOURCE_PAUSED,
        /** State captured. Rollback is possible. */
        SNAPSHOT_TAKEN,
        /** Target holds the state, still paused. Rollback is possible. */
        TARGET_RESTORED,
        /** Ownership has moved. Past the point of rollback. */
        OWNERSHIP_MOVED,
        /** Target is serving and the source has let go. */
        COMPLETE,
        /** Rolled back; the source owns it still, and the move can be retried. */
        ROLLED_BACK,
        /** Failed past the rollback point. Needs recovery, not retry. */
        FAILED
    }

    private final PartitionAssignment.PartitionMove move;
    private final PartitionOwner source;
    private final PartitionOwner target;
    private final Consumer<String> log;
    private volatile Stage stage = Stage.PENDING;

    public PartitionHandoff(
            PartitionAssignment.PartitionMove move,
            PartitionOwner source,
            PartitionOwner target,
            Consumer<String> log) {
        this.move = move;
        this.source = source;
        this.target = target;
        this.log = log == null ? message -> {} : log;
    }

    public Stage stage() {
        return stage;
    }

    public PartitionAssignment.PartitionMove move() {
        return move;
    }

    /**
     * Runs the sequence.
     *
     * @param pauseBudget how long the partition may be unavailable. Exceeding it does not abort a
     *     handoff that is already past the rollback point, because aborting there would be worse
     *     than being slow, but it is reported: a rebalance that quietly blows its budget looks
     *     exactly like a healthy one
     * @return the stage reached
     * @throws PravahaException if the handoff failed past the point where rolling back is possible,
     *     or if rollback itself failed. Both leave a partition unserved and neither is a retry
     */
    public Stage run(Duration pauseBudget) {
        int partition = move.partition();
        long startNanos = System.nanoTime();
        try {
            source.pause(partition);
            stage = Stage.SOURCE_PAUSED;

            PartitionSnapshot snapshot = source.snapshot(partition);
            if (snapshot == null || snapshot.partition() != partition) {
                throw new PravahaException(
                        ClusterErrors.HANDOFF_FAILED,
                        "the source returned a snapshot of "
                                + (snapshot == null ? "nothing" : "partition " + snapshot.partition())
                                + " when asked for partition " + partition
                                + ". Restoring it would file one partition's state under another's name");
            }
            stage = Stage.SNAPSHOT_TAKEN;

            target.restore(snapshot);
            stage = Stage.TARGET_RESTORED;
        } catch (RuntimeException failure) {
            // Everything above is reversible: the source still holds both state and offsets.
            rollBack(failure);
            return stage;
        }

        try {
            stage = Stage.OWNERSHIP_MOVED;
            target.resume(partition);
            source.release(partition);
            stage = Stage.COMPLETE;
        } catch (RuntimeException failure) {
            stage = Stage.FAILED;
            throw new PravahaException(
                    ClusterErrors.HANDOFF_FAILED,
                    "handoff of partition " + partition + " to " + move.to().id()
                            + " failed after ownership moved. The partition is not being served and must be "
                            + "recovered from its checkpoint. It must not be handed back to "
                            + move.from().id()
                            + ", which may already have released its copy",
                    failure);
        }

        Duration taken = Duration.ofNanos(System.nanoTime() - startNanos);
        if (pauseBudget != null && taken.compareTo(pauseBudget) > 0) {
            log.accept("handoff " + move + " took " + taken.toMillis() + "ms, over the " + pauseBudget.toMillis()
                    + "ms budget. Those keys were unavailable for that long");
        } else {
            log.accept("handoff " + move + " complete in " + taken.toMillis() + "ms");
        }
        return stage;
    }

    private void rollBack(RuntimeException failure) {
        Stage failedAt = stage;
        try {
            source.resume(move.partition());
            stage = Stage.ROLLED_BACK;
            log.accept("handoff " + move + " failed at " + failedAt + " (" + failure.getMessage() + "); rolled back, "
                    + move.from().id() + " still owns it");
        } catch (RuntimeException rollbackFailure) {
            stage = Stage.FAILED;
            throw new PravahaException(
                    ClusterErrors.HANDOFF_FAILED,
                    "partition " + move.partition() + " is paused on "
                            + move.from().id()
                            + " and could not be resumed after a failed handoff (" + failure.getMessage()
                            + "). It is not being served",
                    rollbackFailure);
        }
    }
}
