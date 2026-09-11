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
import java.util.function.Consumer;

/**
 * Membership, leadership and assignment, behind one interface so a deployment can use the mechanism
 * it already operates (design section 21.2, ADR-009).
 *
 * <p>Three implementations ship: a single-node coordinator, a socket-based one for development, and
 * ZooKeeper. Others are discovered by {@link java.util.ServiceLoader}, so a deployment that runs etcd
 * or Consul can supply one without changing the engine.
 *
 * <p><strong>They are not interchangeable, and the interface says so.</strong> Every implementation
 * declares {@link #guarantees()}, and the engine checks them against what it is being asked to do.
 * A configuration file that offers three names of equal weight invites somebody to pick the one with
 * fewest moving parts, and the difference only appears when a network partitions -- which is the one
 * moment nobody is reading documentation.
 *
 * <p>The data plane does not route through the leader. Leadership decides <em>who assigns</em>
 * partitions and who coordinates checkpoints; the work itself is shared-nothing.
 */
public interface ClusterCoordinator extends AutoCloseable {

    /** How this mechanism is written in configuration -- {@code single}, {@code socket}, {@code zookeeper}. */
    String mechanism();

    /** What this implementation promises. The engine refuses work these do not cover. */
    Guarantees guarantees();

    /**
     * Joins the cluster and starts participating.
     *
     * @param self this node, as others should reach it
     */
    void start(Member self);

    /** Everyone currently believed to be up, including this node. */
    List<Member> members();

    /** The leader, if one is currently established. Empty during an election. */
    Optional<Member> leader();

    /** Whether this node is the leader right now. */
    boolean isLeader();

    /**
     * Called when leadership changes, with the new leader.
     *
     * <p>May be called with this node and then, later, with another: losing leadership is a normal
     * event and an implementation that never reported it would let a former leader keep assigning.
     */
    void onLeadershipChange(Consumer<Optional<Member>> listener);

    /** Called when the membership changes. */
    void onMembershipChange(Consumer<List<Member>> listener);

    @Override
    void close();
}
