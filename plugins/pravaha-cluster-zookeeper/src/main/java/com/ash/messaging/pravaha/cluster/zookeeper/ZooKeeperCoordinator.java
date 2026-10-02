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
package com.ash.messaging.pravaha.cluster.zookeeper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.zookeeper.CreateMode;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.ClusterErrors;
import com.ash.messaging.pravaha.cluster.Guarantees;
import com.ash.messaging.pravaha.cluster.LeaseGrantingCoordinator;
import com.ash.messaging.pravaha.cluster.Member;
import com.ash.messaging.pravaha.cluster.PartitionLeaseCoordinator;

/**
 * Membership and leadership through ZooKeeper, for deployments that already run one.
 *
 * <p>Membership is an ephemeral znode per node: it disappears when the session does, so a node that
 * dies, hangs, or is partitioned away leaves the membership without anyone having to notice. That is
 * the property a heartbeat protocol has to approximate and ZooKeeper simply has.
 *
 * <p>Leadership is a {@link LeaderLatch}, which is ZooKeeper's consensus rather than ours. This is
 * why the coordinator can claim {@link Guarantees#excludesSplitBrain()}: a minority partition cannot
 * hold a quorum, so it cannot hold the latch, so it knows it is not the leader. The socket
 * coordinator computes leadership from what it can see and therefore cannot make that claim.
 *
 * <p>The cost is honest and worth stating: an external service to run, operate and keep available,
 * and a cluster that stops electing when ZooKeeper is down. That is the trade for a guarantee that
 * partition assignment actually requires.
 */
public final class ZooKeeperCoordinator implements ClusterCoordinator, LeaseGrantingCoordinator {

    private static final Guarantees GUARANTEES = new Guarantees("zookeeper", true, true, true);

    private final CuratorFramework curator;
    private final String root;
    private final List<Consumer<Optional<Member>>> leadershipListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<Member>>> membershipListeners = new CopyOnWriteArrayList<>();
    private final PartitionLeaseCoordinator leases;

    private volatile List<Member> members = List.of();
    private LeaderLatch latch;
    private CuratorCache cache;

    public ZooKeeperCoordinator(CuratorFramework curator, String root) {
        this.curator = curator;
        this.root = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        this.leases = new ZooKeeperPartitionLeaseCoordinator(curator, this.root);
    }

    @Override
    public String mechanism() {
        return "zookeeper";
    }

    @Override
    public Guarantees guarantees() {
        return GUARANTEES;
    }

    @Override
    public void start(Member self) {
        try {
            if (curator.getState() == org.apache.curator.framework.imps.CuratorFrameworkState.LATENT) {
                curator.start();
            }

            // Ephemeral: tied to this node's session, so the membership corrects itself when a node
            // goes away for any reason at all -- including the ones it cannot report itself.
            curator.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(
                            membersPath() + "/" + self.id(),
                            self.address().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            cache = CuratorCache.build(curator, membersPath());
            cache.listenable().addListener((type, oldNode, newNode) -> refreshMembers());
            cache.start();
            refreshMembers();

            latch = new LeaderLatch(curator, root + "/leader", self.id());
            latch.addListener(new LeaderLatchListener() {
                @Override
                public void isLeader() {
                    leadershipListeners.forEach(listener -> listener.accept(Optional.of(self)));
                }

                @Override
                public void notLeader() {
                    // Losing leadership is a normal event, and an implementation that never reported
                    // it would let a former leader keep assigning partitions after a partition healed.
                    leadershipListeners.forEach(listener -> listener.accept(leader()));
                }
            });
            latch.start();
        } catch (Exception e) {
            throw new PravahaException(
                    ClusterErrors.COORDINATOR_UNAVAILABLE,
                    "cannot join the cluster through ZooKeeper at " + root + ": " + e.getMessage(),
                    e);
        }
    }

    private void refreshMembers() {
        try {
            List<Member> found = new ArrayList<>();
            for (String id : curator.getChildren().forPath(membersPath())) {
                byte[] data = curator.getData().forPath(membersPath() + "/" + id);
                String address = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                int colon = address.lastIndexOf(':');
                found.add(new Member(
                        id,
                        colon < 0 ? address : address.substring(0, colon),
                        colon < 0 ? 0 : Integer.parseInt(address.substring(colon + 1))));
            }
            found.sort(Comparator.comparing(Member::id));
            if (!found.equals(members)) {
                members = List.copyOf(found);
                membershipListeners.forEach(listener -> listener.accept(members));
            }
        } catch (Exception e) {
            // A transient read failure is not a membership change. Reporting an empty cluster because
            // one call failed would be worse than being briefly out of date -- it would look like
            // everybody had left.
        }
    }

    private String membersPath() {
        return root + "/members";
    }

    @Override
    public List<Member> members() {
        return members;
    }

    @Override
    public Optional<Member> leader() {
        try {
            if (latch == null) {
                return Optional.empty();
            }
            String id = latch.getLeader().getId();
            return members.stream().filter(member -> member.id().equals(id)).findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isLeader() {
        return latch != null && latch.hasLeadership();
    }

    @Override
    public void onLeadershipChange(Consumer<Optional<Member>> listener) {
        leadershipListeners.add(listener);
        listener.accept(leader());
    }

    @Override
    public void onMembershipChange(Consumer<List<Member>> listener) {
        membershipListeners.add(listener);
        listener.accept(members);
    }

    @Override
    public void close() {
        try {
            if (latch != null) {
                latch.close();
            }
        } catch (Exception e) {
            // Shutting down.
        }
        if (cache != null) {
            cache.close();
        }
        // The ephemeral member node goes with the session; Curator's lifecycle is the caller's,
        // because a deployment may share one client with the rest of its application.
        members = List.of();
    }

    @Override
    public PartitionLeaseCoordinator leases() {
        return leases;
    }
}
