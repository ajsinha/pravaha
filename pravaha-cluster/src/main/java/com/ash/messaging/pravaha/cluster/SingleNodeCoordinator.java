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
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * A cluster of one, which is always its own leader.
 *
 * <p>The default, and not a placeholder. Most deployments are one node, embedded mode always is, and
 * every test is -- so this is the path most code actually takes. Making it a real implementation of
 * the same interface means the single-node case is not a special branch threaded through everything
 * that touches the cluster.
 *
 * <p>It claims to exclude split-brain, which is true rather than cheeky: with one node there is no
 * second node to disagree with it. It is therefore a legitimate choice for {@code PARTITIONED} mode,
 * where it assigns every partition to itself -- and, since ADR-039 item 8's second slice, can also
 * grant a real (if single-process) {@link PartitionLease} for each one, through {@link #leases()}.
 */
public final class SingleNodeCoordinator implements ClusterCoordinator, LeaseGrantingCoordinator {

    private static final Guarantees GUARANTEES = new Guarantees("single", true, false, true);

    private final List<Consumer<Optional<Member>>> leadershipListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<Member>>> membershipListeners = new CopyOnWriteArrayList<>();
    private final PartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();

    private volatile @Nullable Member self;

    @Override
    public String mechanism() {
        return "single";
    }

    @Override
    public Guarantees guarantees() {
        return GUARANTEES;
    }

    @Override
    public void start(Member self) {
        this.self = self;
        leadershipListeners.forEach(listener -> listener.accept(Optional.of(self)));
        membershipListeners.forEach(listener -> listener.accept(List.of(self)));
    }

    @Override
    public List<Member> members() {
        Member member = self;
        return member == null ? List.of() : List.of(member);
    }

    @Override
    public Optional<Member> leader() {
        return Optional.ofNullable(self);
    }

    @Override
    public boolean isLeader() {
        return self != null;
    }

    @Override
    public void onLeadershipChange(Consumer<Optional<Member>> listener) {
        leadershipListeners.add(listener);
        if (self != null) {
            listener.accept(Optional.of(self));
        }
    }

    @Override
    public void onMembershipChange(Consumer<List<Member>> listener) {
        membershipListeners.add(listener);
        if (self != null) {
            listener.accept(List.of(self));
        }
    }

    @Override
    public void close() {
        self = null;
    }

    @Override
    public PartitionLeaseCoordinator leases() {
        return leases;
    }
}
