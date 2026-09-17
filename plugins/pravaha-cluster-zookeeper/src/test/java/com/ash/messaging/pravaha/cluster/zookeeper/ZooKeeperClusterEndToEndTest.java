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

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.cluster.ClusterCoordinator;
import com.ash.messaging.pravaha.cluster.CoordinatorFactory;
import com.ash.messaging.pravaha.cluster.Member;
import com.ash.messaging.pravaha.cluster.PartitionAssigner;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-039 item 8, first slice, proved against a real ZooKeeper rather than asserted about
 * {@code ZooKeeperProviderTest}'s mocked collaborators or {@code StateZookeeperConfigTest}'s
 * unstarted client -- both of those say so explicitly: "whether Curator can talk to a real ensemble
 * is Curator's test, not ours." It is now also this module's, because {@code PartitionAssigner}
 * turning real membership into a real assignment is exactly the claim that only means something
 * against a real coordinator, and {@link ZooKeeperCoordinator} is the one mechanism in this codebase
 * {@code CoordinatorFactory} will actually let run {@code PARTITIONED} in production (the socket
 * coordinator is refused for it; the single-node one only ever has one member).
 *
 * <p>{@link TestingServer} is Curator's own embedded ZooKeeper, test-scope only (see this module's
 * {@code pom.xml}), so nothing here needs an external service running.
 */
@Timeout(120)
class ZooKeeperClusterEndToEndTest {

    @Test
    void twoRealNodesJoinThroughARealZooKeeperAndAgreeOnAPartitionedAssignment() throws Exception {
        try (TestingServer zk = new TestingServer()) {
            Configuration configA = Configuration.builder()
                    .set("pravaha.cluster.mode", "PARTITIONED")
                    .set("pravaha.cluster.mechanism", "zookeeper")
                    .set("pravaha.cluster.zookeeper.connect", zk.getConnectString())
                    .set("pravaha.cluster.zookeeper.root", "/pravaha-e2e-a")
                    .build();
            Configuration configB = Configuration.builder()
                    .set("pravaha.cluster.mode", "PARTITIONED")
                    .set("pravaha.cluster.mechanism", "zookeeper")
                    .set("pravaha.cluster.zookeeper.connect", zk.getConnectString())
                    .set("pravaha.cluster.zookeeper.root", "/pravaha-e2e-a")
                    .build();

            Member a = new Member("node-a", "127.0.0.1", 19301);
            Member b = new Member("node-b", "127.0.0.1", 19302);

            // CoordinatorFactory.create, not `new ZooKeeperCoordinator(...)` directly: the claim
            // under test is that PARTITIONED mode reaches a real ZooKeeperCoordinator through the
            // same door PravahaNode uses, now that the split-brain guard accepts it.
            try (ClusterCoordinator coordinatorA = CoordinatorFactory.create(configA);
                    ClusterCoordinator coordinatorB = CoordinatorFactory.create(configB);
                    PartitionAssigner assignerA = new PartitionAssigner(coordinatorA, a, 128);
                    PartitionAssigner assignerB = new PartitionAssigner(coordinatorB, b, 128)) {
                assertThat(coordinatorA.mechanism()).isEqualTo("zookeeper");
                assertThat(coordinatorA.guarantees().excludesSplitBrain()).isTrue();

                coordinatorA.start(a);
                coordinatorB.start(b);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline
                        && (assignerA.current().isEmpty()
                                || assignerB.current().isEmpty()
                                || assignerA.current().get().members().size() < 2)) {
                    Thread.sleep(100);
                }

                assertThat(coordinatorA.members())
                        .as("each node sees both, through real ZooKeeper znodes")
                        .hasSize(2);
                assertThat(coordinatorB.members()).hasSize(2);

                // The claim PartitionAssignment's javadoc makes, proved against two real processes
                // that found each other through a real consensus service rather than a shared list
                // in one test method's stack frame.
                assertThat(assignerA.current()).isPresent().isEqualTo(assignerB.current());

                List<Integer> ownedByA = assignerA.partitionsOwnedBySelf();
                List<Integer> ownedByB = assignerB.partitionsOwnedBySelf();
                assertThat(ownedByA).isNotEmpty();
                assertThat(ownedByB).isNotEmpty();
                assertThat(ownedByA).doesNotContainAnyElementsOf(ownedByB);
                assertThat(ownedByA.size() + ownedByB.size()).isEqualTo(128);

                // And leadership -- ZooKeeper's own LeaderLatch, not a guess from what each side can
                // currently reach, which is exactly what makes excludesSplitBrain() true rather than
                // aspirational.
                assertThat(coordinatorA.isLeader() ^ coordinatorB.isLeader())
                        .as("exactly one of the two is leader")
                        .isTrue();
            }
        }
    }

    @Test
    void aNodeThatDisappearsHasItsPartitionsReassignedRatherThanOrphaned() throws Exception {
        try (TestingServer zk = new TestingServer()) {
            Member a = new Member("node-a", "127.0.0.1", 19303);
            Member b = new Member("node-b", "127.0.0.1", 19304);
            String root = "/pravaha-e2e-b";

            ClusterCoordinator coordinatorB = CoordinatorFactory.create(Configuration.builder()
                    .set("pravaha.cluster.mode", "PARTITIONED")
                    .set("pravaha.cluster.mechanism", "zookeeper")
                    .set("pravaha.cluster.zookeeper.connect", zk.getConnectString())
                    .set("pravaha.cluster.zookeeper.root", root)
                    .set("pravaha.cluster.zookeeper.session.timeout.millis", "3000")
                    .build());
            // Built directly against Curator, not through CoordinatorFactory, because what this
            // case needs is to end node a's ZooKeeper *session* -- and ZooKeeperCoordinator.close()
            // deliberately does not do that (its own comment: "Curator's lifecycle is the caller's,
            // because a deployment may share one client with the rest of its application"). Closing
            // only the coordinator leaves the session, and the ephemeral znode with it, alive: this
            // case's own first draft closed only the coordinator and timed out waiting for a
            // departure that Curator's client, still connected, never reported. Owning the client
            // here is what lets this case close *it*, which is the actual, session-ending
            // equivalent of a node dying uncleanly.
            org.apache.curator.framework.CuratorFramework curatorA =
                    org.apache.curator.framework.CuratorFrameworkFactory.builder()
                            .connectString(zk.getConnectString())
                            .sessionTimeoutMs(3_000)
                            .connectionTimeoutMs(10_000)
                            .retryPolicy(new org.apache.curator.retry.ExponentialBackoffRetry(1_000, 5))
                            .build();
            try (PartitionAssigner assignerB = new PartitionAssigner(coordinatorB, b, 32)) {
                coordinatorB.start(b);
                ClusterCoordinator coordinatorA = new ZooKeeperCoordinator(curatorA, root);
                coordinatorA.start(a);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline
                        && assignerB.current().map(x -> x.members().size()).orElse(0) < 2) {
                    Thread.sleep(100);
                }
                assertThat(assignerB.current().orElseThrow().members()).hasSize(2);

                // 'a' leaves without closing cleanly from the cluster's point of view -- the case a
                // heartbeat protocol has to approximate and an ephemeral znode simply has: the
                // session's death, not a message saying goodbye.
                coordinatorA.close();
                curatorA.close();

                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline
                        && assignerB.current().map(x -> x.members().size()).orElse(2) > 1) {
                    Thread.sleep(100);
                }
                assertThat(assignerB.current().orElseThrow().members())
                        .as("membership itself corrects, which is ZooKeeper's whole promise here")
                        .containsExactly(b);
                assertThat(assignerB.partitionsOwnedBySelf())
                        .as("every partition is now b's, not orphaned waiting for a rebalance that "
                                + "does not exist yet in this slice")
                        .hasSize(32);
            } finally {
                coordinatorB.close();
                curatorA.close();
            }
        }
    }
}
