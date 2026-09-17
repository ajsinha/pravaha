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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Choosing a coordinator, and being refused the choices that are not safe.
 *
 * <p>The behaviour worth protecting is the refusal. Three mechanisms in a configuration file look
 * like three equivalent ways to do one job; two of them can leave a partitioned network with two
 * leaders and one cannot, and if nothing checks, the option with fewest moving parts gets picked and
 * the difference appears during an incident.
 */
@Timeout(60)
class CoordinatorFactoryTest {

    private static Configuration config(String... pairs) {
        var builder = Configuration.builder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            builder.set(pairs[i], pairs[i + 1]);
        }
        return builder.build();
    }

    @Test
    void theDefaultIsASingleNodeThatIsItsOwnLeader() {
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config())) {
            coordinator.start(new Member("only", "localhost", 9070));

            assertThat(coordinator.mechanism()).isEqualTo("single");
            assertThat(coordinator.isLeader()).isTrue();
            assertThat(coordinator.members()).hasSize(1);
        }
    }

    @Test
    void aSingleNodeReallyDoesExcludeSplitBrain() {
        // Not a cheeky claim: with one node there is no second node to disagree with it.
        //
        // This asserted the same thing through PARTITIONED once, on the reasoning that a single
        // node is a legitimate choice for it because it assigns every partition to itself. Between
        // ADR-038 and ADR-039 item 8's first slice, PARTITIONED was refused unconditionally, because
        // nothing computed an assignment at all (S-3): asserting through PARTITIONED would have
        // demonstrated a mode that started and did nothing, not the guarantee. Now that
        // PartitionAssigner makes the assignment real again (see
        // partitionedModeIsNowAllowedOnACoordinatorThatCanSupportIt), the guarantee itself is still
        // asserted directly here, on plain SINGLE, because that is what this case has always been
        // about and a guarantee belongs on its own the moment it can be checked either way.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(
                config("pravaha.cluster.mode", "SINGLE", "pravaha.cluster.mechanism", "single"))) {
            assertThat(coordinator.guarantees().excludesSplitBrain()).isTrue();
        }
    }

    @Test
    void partitionedModeIsNowAllowedOnACoordinatorThatCanSupportIt() {
        // S-3, resolved. This case used to assert the opposite -- that PARTITIONED x single was
        // refused even though a single node trivially satisfies it (one node cannot disagree with
        // itself about who owns what). That refusal was correct at the time for an entirely
        // different reason than "single can't do this": nothing in the build computed an assignment
        // at all, so PARTITIONED x single started, reported itself partitioned, and partitioned
        // nothing (S-3). ADR-039 item 8's first slice is what changes: PartitionAssigner turns real
        // membership into a real, continuously recomputed PartitionAssignment, so a node can now
        // genuinely say which partitions are its own, and PARTITIONED is no longer refused on any
        // mechanism this factory's own split-brain guard has already accepted.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(
                config("pravaha.cluster.mode", "PARTITIONED", "pravaha.cluster.mechanism", "single"))) {
            Member self = new Member("only", "localhost", 19077);
            coordinator.start(self);

            PartitionAssigner assigner = new PartitionAssigner(coordinator, self, 16);
            assertThat(assigner.partitionsOwnedBySelf())
                    .as("the one node in a single-node PARTITIONED cluster owns every partition")
                    .hasSize(16);
        }
    }

    @Test
    void partitionedModeIsRefusedOnACoordinatorWithoutConsensus() {
        // The rule this whole design exists for. Two owners of a partition means two nodes writing
        // the same aggregate, silently and durably.
        assertThatThrownBy(() -> CoordinatorFactory.create(config(
                        "pravaha.cluster.mode", "PARTITIONED",
                        "pravaha.cluster.mechanism", "socket",
                        "pravaha.cluster.socket.peers", "a=localhost:19071,b=localhost:19072")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9002")
                .hasMessageContaining("split-brain")
                // And it says what to do instead rather than only refusing.
                .hasMessageContaining("REPLICATED");
    }

    @Test
    void replicatedModeIsAllowedWithoutConsensus() {
        // A split brain here costs duplicated work and stale reads, not corrupted aggregates, so the
        // trade is the operator's to make.
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(config(
                "pravaha.cluster.mode", "REPLICATED",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a=localhost:19073,b=localhost:19074"))) {
            assertThat(coordinator.mechanism()).isEqualTo("socket");
            assertThat(coordinator.guarantees().excludesSplitBrain()).isFalse();
        }
    }

    @Test
    void anUnknownMechanismListsWhatIsAvailable() {
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mechanism", "zookeeper")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9001")
                .hasMessageContaining("single")
                .hasMessageContaining("socket");
    }

    @Test
    void theStartupLineSaysWhatWasChosenAndWhatItPromises() {
        Configuration configuration = config(
                "pravaha.cluster.mode", "REPLICATED",
                "pravaha.cluster.mechanism", "socket",
                "pravaha.cluster.socket.peers", "a=localhost:19075");
        try (ClusterCoordinator coordinator = CoordinatorFactory.create(configuration)) {
            String line = CoordinatorFactory.describe(configuration, coordinator);

            // An operator reading one line at startup should see the risk, not have to infer it.
            assertThat(line).contains("REPLICATED").contains("NO consensus").contains("development only");
        }
    }

    @Test
    void aSocketClusterNeedsItsPeerList() {
        assertThatThrownBy(() -> CoordinatorFactory.create(config("pravaha.cluster.mechanism", "socket")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9005")
                // There is no discovery, and the message says why rather than looking unfinished.
                .hasMessageContaining("discovery without");
    }

    @Test
    void aFailureTimeoutBelowTheHeartbeatIsRefused() {
        // Otherwise every peer looks dead between beats and the membership flaps, taking leadership
        // with it -- a cluster that spends its life electing.
        assertThatThrownBy(() -> new SocketCoordinator(
                        List.of(new Member("a", "localhost", 19076)), Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("longer than the heartbeat");
    }

    @Test
    void twoSocketNodesFindEachOtherAndAgreeOnALeader() throws Exception {
        List<Member> peers = List.of(new Member("a", "127.0.0.1", 19081), new Member("b", "127.0.0.1", 19082));
        AtomicReference<Optional<Member>> leaderOfA = new AtomicReference<>(Optional.empty());

        try (SocketCoordinator a = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(500));
                SocketCoordinator b = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(500))) {
            a.onLeadershipChange(leaderOfA::set);
            a.start(peers.get(0));
            b.start(peers.get(1));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline
                    && (a.members().size() < 2 || b.members().size() < 2)) {
                Thread.sleep(50);
            }

            assertThat(a.members()).as("each node should see both").hasSize(2);
            assertThat(b.members()).hasSize(2);
            // Lowest id wins, and both sides compute the same answer while they can see each other.
            assertThat(a.leader()).map(Member::id).contains("a");
            assertThat(b.leader()).map(Member::id).contains("a");
            assertThat(a.isLeader()).isTrue();
            assertThat(b.isLeader()).isFalse();
            assertThat(leaderOfA.get()).map(Member::id).contains("a");
        }
    }

    @Test
    void aNodeThatGoesAwayLeavesTheMembership() throws Exception {
        List<Member> peers = List.of(new Member("a", "127.0.0.1", 19083), new Member("b", "127.0.0.1", 19084));

        try (SocketCoordinator a = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(400))) {
            SocketCoordinator b = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(400));
            a.start(peers.get(0));
            b.start(peers.get(1));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && a.members().size() < 2) {
                Thread.sleep(50);
            }
            assertThat(a.members()).hasSize(2);

            b.close();

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && a.members().size() > 1) {
                Thread.sleep(50);
            }

            // And 'a' is still the leader, because it was already. What this test cannot show is the
            // case that matters: a *partition* would leave b believing it leads its own half, which
            // is exactly why this coordinator declares no consensus.
            assertThat(a.members()).hasSize(1);
            assertThat(a.isLeader()).isTrue();
        }
    }
}
