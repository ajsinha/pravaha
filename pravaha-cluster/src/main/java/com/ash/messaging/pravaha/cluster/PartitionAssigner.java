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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * ADR-039 item 8, first slice: membership becomes a partition assignment, kept current as
 * membership changes, so a node can say which partitions are its own.
 *
 * <h2>The consistency model, stated before the code that implements it</h2>
 *
 * <p><strong>Assignment is derived, not coordinated.</strong> This class introduces no new
 * distributed state and writes nothing anywhere. It computes {@link PartitionAssignment#of} --
 * already a pure, deterministic function of a membership list -- every time {@link
 * ClusterCoordinator#onMembershipChange} fires, and does that independently on every node that
 * runs one. Two nodes that currently agree on membership always compute the identical assignment
 * without asking each other, exactly as {@link PartitionAssignment}'s own javadoc already
 * committed to; nothing here changes that model, it only connects it to a real {@link
 * ClusterCoordinator} instead of a hand-built {@code List<Member>} in a test.
 *
 * <p>A design that instead had the leader compute an assignment once and publish it (a second
 * znode, a second watch, a second thing to go stale) was considered and rejected: it adds a whole
 * second distributed value with its own propagation delay, on top of membership's, without
 * removing the one propagation delay that actually matters here -- see below. It would be more
 * moving parts for no stronger guarantee.
 *
 * <p><strong>What this does not claim.</strong> Because every node computes from whatever
 * membership view it has locally observed, two nodes can transiently disagree about who owns a
 * partition while their views of membership converge -- a node that has not yet observed a peer
 * join or leave computes yesterday's answer for a little longer than a node that has. This is true
 * of any coordinator built on eventually-delivered membership change notifications, ZooKeeper's
 * watches included, and consensus over <em>membership</em> (which {@link Guarantees#excludesSplitBrain()}
 * is actually about) does not by itself make two nodes' independently-computed <em>assignments</em>
 * agree at every instant. For that reason {@link #partitionsOwnedBySelf()} is a node's current
 * belief, not a lease: nothing in this class hands out a fencing token, and nothing in this round
 * wires this belief into deciding what a node actually serves. Making that transition safe under a
 * churning membership -- closing the disagreement window with a pause before the state moves -- is
 * exactly {@link PartitionOwner} and {@link PartitionHandoff}'s job, and is the next slice, not
 * this one. Consuming {@link #partitionsOwnedBySelf()} to gate real data serving before that exists
 * would be the "half-built handoff" ADR-039 warns is worse than none.
 */
public final class PartitionAssigner implements AutoCloseable {

    private final Member self;
    private final int partitions;
    private final List<Consumer<PartitionAssignment>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<PartitionAssignment> current = new AtomicReference<>();
    private final Consumer<List<Member>> onMembershipChange = this::recompute;

    /**
     * @param self the same {@link Member} this process passed to {@link ClusterCoordinator#start},
     *     needed because {@link ClusterCoordinator} has no notion of "self" separate from
     *     membership -- a coordinator's {@code members()} is a list of equals, and this class has
     *     to be told which one is the caller rather than guess
     * @param partitions the query's virtual partition count, fixed for its life (see {@link
     *     PartitionAssignment}); {@link PartitionAssignment#DEFAULT_PARTITIONS} is the default a
     *     caller with no reason to choose otherwise should use
     */
    public PartitionAssigner(ClusterCoordinator coordinator, Member self, int partitions) {
        this.self = self;
        this.partitions = partitions;
        coordinator.onMembershipChange(onMembershipChange);
    }

    private void recompute(List<Member> members) {
        if (members.isEmpty()) {
            // Between construction and this coordinator's own start(), or while it has lost every
            // peer including itself. PartitionAssignment.of refuses an empty membership outright
            // (an assignment over no nodes is not an empty assignment, it is nowhere to put the
            // work) -- correctly, so this class does not call it yet rather than translate that
            // refusal into a different one. current() stays empty until a real membership arrives.
            return;
        }
        PartitionAssignment assignment = PartitionAssignment.of(partitions, members);
        current.set(assignment);
        listeners.forEach(listener -> listener.accept(assignment));
    }

    /** The partition count every assignment this instance computes is fixed at. */
    public int partitions() {
        return partitions;
    }

    /**
     * The most recently computed assignment, or empty if membership has never been non-empty.
     *
     * <p>Not "as of now" in any stronger sense than "as of the last membership change this
     * process's {@link ClusterCoordinator} has told it about" -- see the class documentation.
     */
    public Optional<PartitionAssignment> current() {
        return Optional.ofNullable(current.get());
    }

    /**
     * The partitions {@code member} currently believes it owns, empty if no assignment has been
     * computed yet.
     */
    public List<Integer> partitionsOwnedBy(Member member) {
        return current().map(assignment -> assignment.partitionsOf(member)).orElse(List.of());
    }

    /** Convenience for the common case: what this process itself owns, right now, per {@link #current()}. */
    public List<Integer> partitionsOwnedBySelf() {
        return partitionsOwnedBy(self);
    }

    /** Called on every recomputation, including the first. Never called with an empty assignment. */
    public void onAssignmentChange(Consumer<PartitionAssignment> listener) {
        listeners.add(listener);
        PartitionAssignment snapshot = current.get();
        if (snapshot != null) {
            listener.accept(snapshot);
        }
    }

    /**
     * Stops recomputing. Does not close {@code coordinator} -- this class borrowed a membership
     * feed, it does not own the coordinator's lifecycle.
     */
    @Override
    public void close() {
        listeners.clear();
    }
}
