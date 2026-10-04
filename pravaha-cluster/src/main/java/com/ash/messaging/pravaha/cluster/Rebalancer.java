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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Turns "the membership changed" into a sequence of partition handoffs.
 *
 * <p>Given the old assignment and the new one, the moves are already determined ({@link
 * PartitionAssignment#movesTo}). What is left is judgement about <em>pace</em>, and the judgement is
 * that a rebalance should be slow.
 *
 * <p><strong>Moves run one at a time.</strong> Each one pauses a partition, so running ten in
 * parallel makes ten slices of the key space unavailable together, and a node that just lost a peer
 * is already the busiest it has been all day. Serial handoff turns a cliff into a ramp: the cluster
 * is degraded for longer and less at any moment. Rebalancing is not on the critical path of
 * anything, so the extra wall-clock buys availability at no cost worth naming.
 *
 * <p><strong>Failures stop the run.</strong> A handoff that rolls back leaves the source owning its
 * partition, which is a perfectly good place for it to be, so the safe response is to stop and let
 * the next membership change decide afresh. Grinding through the remaining moves after the first
 * failure means N failures instead of one, on a cluster already telling you something is wrong.
 *
 * <p><strong>A cooldown prevents oscillation</strong> (design section 15.3). A node that is flapping
 * would otherwise trigger a rebalance on every appearance and disappearance, and the cluster would
 * spend its life moving state rather than answering queries. Refusing to start again too soon is
 * what makes flapping cost one rebalance instead of many.
 */
public final class Rebalancer {

    /** The design's target pause per partition (NFR-6). */
    public static final Duration DEFAULT_PAUSE_BUDGET = Duration.ofSeconds(5);

    /** How long after a rebalance before another may start. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(1);

    private final Function<Member, PartitionOwner> owners;
    private final Duration pauseBudget;
    private final Duration cooldown;
    private final Consumer<String> log;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long lastFinishedNanos = Long.MIN_VALUE;

    public Rebalancer(Function<Member, PartitionOwner> owners, @Nullable Consumer<String> log) {
        this(owners, DEFAULT_PAUSE_BUDGET, DEFAULT_COOLDOWN, log);
    }

    /**
     * @param owners resolves a node to something that can move partition state on it. Keyed by node
     *     rather than by partition, because each move needs a handle to both ends and the partition
     *     only identifies one of them
     * @param pauseBudget reported, not enforced, per move
     * @param cooldown how long to refuse a new rebalance after one finishes
     */
    public Rebalancer(
            Function<Member, PartitionOwner> owners,
            Duration pauseBudget,
            @Nullable Duration cooldown,
            @Nullable Consumer<String> log) {
        this.owners = owners;
        this.pauseBudget = pauseBudget;
        this.cooldown = cooldown == null ? Duration.ZERO : cooldown;
        this.log = log == null ? message -> {} : log;
    }

    /** What one rebalance did. */
    public record Outcome(List<PartitionHandoff> completed, List<PartitionHandoff> stopped, Duration took) {

        public Outcome {
            completed = List.copyOf(completed);
            stopped = List.copyOf(stopped);
        }

        /** True if every planned move landed. */
        public boolean clean() {
            return stopped.isEmpty();
        }

        @Override
        public String toString() {
            return "Outcome[" + completed.size() + " moved, " + stopped.size() + " not, " + took.toMillis() + "ms]";
        }
    }

    /**
     * Moves the cluster from one assignment to the next, one partition at a time.
     *
     * @throws PravahaException if a rebalance is already running, or the cooldown has not elapsed.
     *     Both are refusals rather than waits: the caller is a membership watcher, and blocking it
     *     would queue rebalances behind each other, which is the oscillation the cooldown exists to
     *     stop
     */
    public Outcome rebalance(PartitionAssignment from, PartitionAssignment to) {
        List<PartitionAssignment.PartitionMove> moves = from.movesTo(to);
        if (moves.isEmpty()) {
            log.accept("membership changed but no partition changed hands; nothing to do");
            return new Outcome(List.of(), List.of(), Duration.ZERO);
        }
        long sinceLast = System.nanoTime() - lastFinishedNanos;
        if (lastFinishedNanos != Long.MIN_VALUE && sinceLast < cooldown.toNanos()) {
            throw new PravahaException(
                    ClusterErrors.REBALANCE_REFUSED,
                    "a rebalance finished " + Duration.ofNanos(sinceLast).toMillis() + "ms ago and the cooldown is "
                            + cooldown.toMillis() + "ms. Rebalancing again this soon usually means a node is "
                            + "flapping, and following it would spend the cluster's time moving state rather "
                            + "than answering queries");
        }
        if (!running.compareAndSet(false, true)) {
            throw new PravahaException(
                    ClusterErrors.REBALANCE_REFUSED,
                    "a rebalance is already running. Starting a second would pause partitions the first is "
                            + "in the middle of moving");
        }
        try {
            return run(moves);
        } finally {
            lastFinishedNanos = System.nanoTime();
            running.set(false);
        }
    }

    // ADR-039 item 8, second slice: PartitionHandoff's real, fenced constructor needs a
    // PartitionLeaseCoordinator and a proven current sourceLease, neither of which this class has
    // any notion of. Rebalancer is explicitly out of scope for that slice ("stays untouched...
    // depends on handoff being trustworthy"), so this keeps calling the deprecated, unfenced
    // constructor rather than acquiring real leases it has nowhere principled to get from -- doing
    // that properly is exactly the work of making Rebalancer itself trustworthy, which is not this
    // round's job.
    @SuppressWarnings("deprecation")
    private Outcome run(List<PartitionAssignment.PartitionMove> moves) {
        long startNanos = System.nanoTime();
        log.accept("rebalancing " + moves.size() + " partitions, one at a time");
        List<PartitionHandoff> completed = new ArrayList<>();
        List<PartitionHandoff> stopped = new ArrayList<>();

        for (int index = 0; index < moves.size(); index++) {
            PartitionAssignment.PartitionMove move = moves.get(index);
            PartitionHandoff handoff =
                    new PartitionHandoff(move, owners.apply(move.from()), owners.apply(move.to()), log);
            PartitionHandoff.Stage stage;
            try {
                stage = handoff.run(pauseBudget);
            } catch (PravahaException failure) {
                // Past rollback, or rollback itself failed. Either way the run ends here, and the
                // exception carries what happened to that partition.
                stopped.add(handoff);
                log.accept("rebalance abandoned after " + completed.size() + " of " + moves.size() + " moves: "
                        + failure.getMessage());
                throw failure;
            }
            if (stage == PartitionHandoff.Stage.COMPLETE) {
                completed.add(handoff);
                continue;
            }
            // Rolled back. The source still owns it, which is a fine place for it to be.
            stopped.add(handoff);
            for (int remaining = index + 1; remaining < moves.size(); remaining++) {
                stopped.add(new PartitionHandoff(moves.get(remaining), null, null, log));
            }
            log.accept("rebalance stopped after " + completed.size() + " of " + moves.size()
                    + " moves; the rest stay where they are and the next membership change will decide again");
            break;
        }
        Duration took = Duration.ofNanos(System.nanoTime() - startNanos);
        log.accept("rebalance finished: " + completed.size() + " moved in " + took.toMillis() + "ms");
        return new Outcome(completed, stopped, took);
    }

    /** The moves a membership change implies, without running them. For operators asking "what would happen". */
    public static List<PartitionAssignment.PartitionMove> plan(PartitionAssignment from, PartitionAssignment to) {
        return from.movesTo(to);
    }

    /** How much state a plan would move, by node. */
    public static Map<String, Integer> movesByTarget(List<PartitionAssignment.PartitionMove> moves) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        moves.forEach(move -> counts.merge(move.to().id(), 1, Integer::sum));
        return counts;
    }
}
