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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-039 item 8, first slice: real membership becomes a real, kept-current partition assignment.
 *
 * <p>Against real {@link ClusterCoordinator} implementations this module already ships --
 * {@link SingleNodeCoordinator} and the real-socket {@link SocketCoordinator} -- rather than a
 * hand-rolled {@code List<Member>} standing in for one. {@code PartitionAssignmentTest} already
 * proves the pure computation; what only this class can prove is that a real coordinator's real
 * membership-change notifications actually drive it.
 */
@Timeout(60)
class PartitionAssignerTest {

    @Test
    void aSingleNodeOwnsEveryPartitionAsSoonAsItStarts() {
        SingleNodeCoordinator coordinator = new SingleNodeCoordinator();
        Member self = new Member("only", "localhost", 19200);
        try (coordinator;
                PartitionAssigner assigner = new PartitionAssigner(coordinator, self, 16)) {
            // Before start(): no membership has ever arrived, so there is nothing to own yet --
            // not zero partitions because it is unentitled, but because nobody has said who is here.
            assertThat(assigner.current()).isEmpty();
            assertThat(assigner.partitionsOwnedBySelf()).isEmpty();

            coordinator.start(self);

            assertThat(assigner.current()).isPresent();
            assertThat(assigner.partitionsOwnedBySelf()).hasSize(16);
            assertThat(assigner.current().orElseThrow().members()).containsExactly(self);
        }
    }

    @Test
    void twoRealSocketNodesConvergeOnTheIdenticalAssignmentOnceTheySeeEachOther() throws Exception {
        List<Member> peers = List.of(new Member("a", "127.0.0.1", 19201), new Member("b", "127.0.0.1", 19202));

        try (SocketCoordinator a = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(600));
                SocketCoordinator b = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(600));
                PartitionAssigner assignerA = new PartitionAssigner(a, peers.get(0), 64);
                PartitionAssigner assignerB = new PartitionAssigner(b, peers.get(1), 64)) {
            a.start(peers.get(0));
            b.start(peers.get(1));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline
                    && (assignerA.current().isEmpty()
                            || assignerB.current().isEmpty()
                            || assignerA.current().get().members().size() < 2)) {
                Thread.sleep(50);
            }

            // The claim PartitionAssignment's own javadoc makes -- "every node that agrees on the
            // membership computes the same answer without asking" -- proved against two real
            // processes discovering each other over real sockets, not asserted about one list.
            assertThat(assignerA.current()).isPresent();
            assertThat(assignerA.current()).isEqualTo(assignerB.current());

            // And ownership is a partition, not a coincidence: everything one owns, the other does
            // not, and together they cover the whole space.
            List<Integer> ownedByA = assignerA.partitionsOwnedBySelf();
            List<Integer> ownedByB = assignerB.partitionsOwnedBySelf();
            assertThat(ownedByA).doesNotContainAnyElementsOf(ownedByB);
            assertThat(ownedByA.size() + ownedByB.size()).isEqualTo(64);
        }
    }

    @Test
    void aNodeLeavingMovesOnlyItsOwnPartitionsNotEveryones() throws Exception {
        // The property PartitionAssignment.of exists for: rendezvous hashing, not partition %
        // nodeCount. A third node leaving must not reshuffle what the first two already owned.
        List<Member> peers = List.of(
                new Member("a", "127.0.0.1", 19203),
                new Member("b", "127.0.0.1", 19204),
                new Member("c", "127.0.0.1", 19205));

        SocketCoordinator c = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(600));
        try {
            try (SocketCoordinator a = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(600));
                    SocketCoordinator b = new SocketCoordinator(peers, Duration.ofMillis(100), Duration.ofMillis(600));
                    PartitionAssigner assignerA = new PartitionAssigner(a, peers.get(0), 256)) {
                a.start(peers.get(0));
                b.start(peers.get(1));
                c.start(peers.get(2));

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline
                        && assignerA
                                        .current()
                                        .map(assignment -> assignment.members().size())
                                        .orElse(0)
                                < 3) {
                    Thread.sleep(50);
                }
                List<Integer> beforeAOwned = assignerA.partitionsOwnedBySelf();
                assertThat(assignerA.current().orElseThrow().members()).hasSize(3);

                c.close();

                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline
                        && assignerA
                                        .current()
                                        .map(assignment -> assignment.members().size())
                                        .orElse(3)
                                > 2) {
                    Thread.sleep(50);
                }
                List<Integer> afterAOwned = assignerA.partitionsOwnedBySelf();
                assertThat(assignerA.current().orElseThrow().members()).hasSize(2);

                // 'a' only ever gains partitions c used to hold; it never loses one it already had.
                assertThat(afterAOwned).containsAll(beforeAOwned);
            }
        } finally {
            c.close();
        }
    }

    @Test
    void listenersHearEveryRecomputationIncludingTheFirst() {
        SingleNodeCoordinator coordinator = new SingleNodeCoordinator();
        Member self = new Member("only", "localhost", 19206);
        AtomicInteger calls = new AtomicInteger();
        try (coordinator;
                PartitionAssigner assigner = new PartitionAssigner(coordinator, self, 4)) {
            assigner.onAssignmentChange(assignment -> calls.incrementAndGet());
            assertThat(calls.get()).isZero(); // nothing to report before start()

            coordinator.start(self);
            assertThat(calls.get()).isEqualTo(1);

            // A late subscriber still gets told the current answer immediately, not left to wait
            // for the next membership change that may never come on a single-node cluster.
            AtomicInteger lateCalls = new AtomicInteger();
            assigner.onAssignmentChange(assignment -> lateCalls.incrementAndGet());
            assertThat(lateCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void aQuerysPartitionCountIsWhicheverThisAssignerWasBuiltWith() {
        SingleNodeCoordinator coordinator = new SingleNodeCoordinator();
        try (coordinator;
                PartitionAssigner assigner = new PartitionAssigner(
                        coordinator, new Member("only", "localhost", 19207), PartitionAssignment.DEFAULT_PARTITIONS)) {
            assertThat(assigner.partitions()).isEqualTo(1024);
        }
    }
}
