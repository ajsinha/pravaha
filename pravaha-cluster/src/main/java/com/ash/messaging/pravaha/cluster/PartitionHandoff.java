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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Moves one virtual partition from the node that owns it to the node that should.
 *
 * <p>The sequence is the whole of the correctness argument, so it is written out rather than hidden
 * behind one call:
 *
 * <ol>
 *   <li><strong>The source's lease is checked.</strong> Not a formality: a handoff that started
 *       from a stale premise -- the source no longer actually holds what it is being asked to give
 *       up -- must be refused before anything moves, not discovered after.
 *   <li><strong>Source pauses.</strong> Nothing else can happen first. A snapshot of a partition
 *       that is still consuming input is a snapshot of a moving target.
 *   <li><strong>Source snapshots</strong>, state and offsets together.
 *   <li><strong>Target restores</strong>, and stays paused.
 *   <li><strong>The lease is transferred.</strong> This is where ADR-039 item 8's second slice
 *       replaces the first slice's belief with proof: {@link PartitionLeaseCoordinator#transfer}
 *       is a real, atomic compare-and-swap against whatever this mechanism uses for mutual
 *       exclusion -- the source's exact lease for the target's new one, in one operation with no
 *       window where nobody holds it -- and it fails, loudly and safely, if a rival handoff already
 *       moved the same partition somewhere else, which a purely local, in-process notion of
 *       "ownership moved" has no way to detect. <strong>Ownership only flips once this call
 *       actually succeeds, and the source's lease is gone the instant it does -- not released
 *       afterward as a separate step.</strong>
 *   <li><strong>Target resumes</strong> from the offsets that travelled with the state.
 * </ol>
 *
 * <p>Two windows are worth naming, because one is acceptable and the other never is.
 *
 * <p>Between the source pausing and the target resuming <em>nobody</em> is processing the
 * partition. That is the pause, budgeted at five seconds (NFR-6), and it costs latency for those
 * keys while the rest of the query runs on. Input is not lost: it accumulates at the source and is
 * read from the handed-over offsets.
 *
 * <p>At no point are <em>both</em> nodes processing it, and this is no longer merely a claim about
 * the order these calls happen to run in: the target cannot resume without a lease that {@link
 * PartitionLeaseCoordinator#transfer} grants atomically or not at all, so a rival attempt to hand
 * the same partition somewhere else concurrently fails at that step rather than succeeding and
 * leaving two nodes each believing they are the one true owner. The pause is a cost; double
 * ownership is a corruption. That is the same trade the mode/mechanism check in {@link
 * CoordinatorFactory} makes, one level up.
 *
 * <p>Failure before ownership actually moves (the lease transfer succeeds) rolls back: the source
 * still has its state, its offsets and its lease -- {@code transfer} either replaces it atomically
 * or leaves it exactly as it was, never something in between -- so it resumes and the move is
 * retried later. Failure after cannot roll back, because the lease coordinator itself now says the
 * target owns the partition, so it is reported for checkpoint recovery rather than papered over
 * here.
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
        /** The target's lease is granted and the assignment now genuinely agrees. Past the point of rollback. */
        OWNERSHIP_MOVED,
        /** Target is serving and the source has let go, of both its state and its lease. */
        COMPLETE,
        /** Rolled back; the source owns it still, and the move can be retried. */
        ROLLED_BACK,
        /** Failed past the rollback point. Needs recovery, not retry. */
        FAILED
    }

    private final PartitionAssignment.PartitionMove move;
    private final @Nullable PartitionOwner source;
    private final @Nullable PartitionOwner target;
    private final PartitionLeaseCoordinator leases;
    private final @Nullable PartitionLease sourceLease;
    private final Consumer<String> log;
    private volatile Stage stage = Stage.PENDING;
    private volatile @Nullable PartitionLease targetLease;

    /**
     * @param leases the fenced authority ownership is actually checked against, never merely
     *     assumed from local sequencing
     * @param sourceLease proof, checked before anything moves, that {@code move.from()} genuinely
     *     holds {@code move.partition()} right now. Must name the same partition and owner as {@code
     *     move}, or this refuses to start rather than hand off a partition on a false premise
     */
    public PartitionHandoff(
            PartitionAssignment.PartitionMove move,
            PartitionOwner source,
            PartitionOwner target,
            PartitionLeaseCoordinator leases,
            PartitionLease sourceLease,
            @Nullable Consumer<String> log) {
        this.move = Objects.requireNonNull(move, "move");
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.sourceLease = Objects.requireNonNull(sourceLease, "sourceLease");
        if (sourceLease.partition() != move.partition()) {
            throw new IllegalArgumentException("sourceLease names partition " + sourceLease.partition()
                    + " but this move is for partition " + move.partition());
        }
        if (!sourceLease.owner().id().equals(move.from().id())) {
            throw new IllegalArgumentException(
                    "sourceLease is held by " + sourceLease.owner().id()
                            + " but this move's source is " + move.from().id()
                            + " -- a handoff must start from the partition's actual, current owner");
        }
        this.log = log == null ? message -> {} : log;
    }

    /**
     * The pre-lease shape (ADR-039 item 8's first slice and earlier): the same six-step sequence,
     * fenced against a private, single-use {@link InMemoryPartitionLeaseCoordinator} that nothing
     * else can ever contend for -- which is another way of saying not fenced at all. Ownership here
     * still "moves" the moment this object's own stage flips, exactly as it always has.
     *
     * <p>Kept for exactly one reason: {@link Rebalancer} calls it, and {@link Rebalancer} is
     * explicitly out of scope for this slice ("Rebalancer and elastic rescale stay untouched. They
     * depend on handoff being trustworthy.") -- unchanged on purpose, so it is not this constructor's
     * place to make Rebalancer trustworthy by accident. Also tolerates {@code source} and {@code
     * target} being {@code null}, which {@code Rebalancer.run} relies on to build a placeholder
     * {@code Rebalancer.Outcome.stopped} entry for a move it never attempts; that placeholder is
     * never {@link #run}, so the null-tolerance costs nothing real. Every other caller should use
     * the six-argument
     * constructor, which is what a lease that means something actually requires.
     */
    @Deprecated
    public PartitionHandoff(
            PartitionAssignment.PartitionMove move,
            @Nullable PartitionOwner source,
            @Nullable PartitionOwner target,
            @Nullable Consumer<String> log) {
        this.move = move;
        this.source = source;
        this.target = target;
        this.leases = new InMemoryPartitionLeaseCoordinator();
        this.sourceLease = move == null || move.from() == null
                ? null
                : this.leases.acquire(move.partition(), move.from()).orElseThrow();
        this.log = log == null ? message -> {} : log;
    }

    public Stage stage() {
        return stage;
    }

    public PartitionAssignment.PartitionMove move() {
        return move;
    }

    /** The lease the target now holds, once {@link #run} has reached {@link Stage#OWNERSHIP_MOVED} or later. */
    public Optional<PartitionLease> targetLease() {
        return Optional.ofNullable(targetLease);
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

        if (!leases.isValid(java.util.Objects.requireNonNull(sourceLease))) {
            // Refused before anything moves, which is the entire point of checking a lease rather
            // than trusting the premise a caller handed in. A stale sourceLease means the source
            // does not actually hold this partition any more -- perhaps a previous handoff already
            // moved it, perhaps the source's session ended -- and pausing, snapshotting or
            // restoring on that premise would move state that was never this handoff's to move.
            stage = Stage.FAILED;
            throw new PravahaException(
                    ClusterErrors.HANDOFF_FAILED,
                    "cannot hand off partition " + partition + ": " + sourceLease
                            + " is no longer valid, so " + move.from().id()
                            + " does not currently hold what this handoff was asked to give up");
        }

        try {
            java.util.Objects.requireNonNull(source).pause(partition);
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

            java.util.Objects.requireNonNull(target).restore(snapshot);
            stage = Stage.TARGET_RESTORED;

            Optional<PartitionLease> granted =
                    leases.transfer(java.util.Objects.requireNonNull(sourceLease), move.to());
            if (granted.isEmpty()) {
                // The fencing check this whole slice exists for. A rival handoff -- or a target that
                // already believes, wrongly, that it owns this partition -- got here first, and the
                // lease coordinator is the one authority both sides cannot both win an argument with.
                throw new PravahaException(
                        ClusterErrors.HANDOFF_FAILED,
                        "cannot hand off partition " + partition + " to "
                                + move.to().id()
                                + ": another holder already has its lease. The state was restored on "
                                + move.to().id() + " but never resumed there, so nothing served it twice");
            }
            targetLease = granted.get();
            stage = Stage.OWNERSHIP_MOVED;
        } catch (RuntimeException failure) {
            // Everything above is reversible: the source still holds both state and offsets, and
            // (having never been superseded, or the failure would have said so above) still holds
            // its lease.
            rollBack(failure);
            return stage;
        }

        try {
            target.resume(partition);
            // No leases.release(sourceLease) here: transfer() above already atomically replaced it
            // with targetLease, so sourceLease is not the current grant any more and releasing it
            // would be a safe no-op at best. The local, mechanical release below is the source's
            // own copy of the *state*, which is a different thing releasing a lease would not do.
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
            java.util.Objects.requireNonNull(source).resume(move.partition());
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
