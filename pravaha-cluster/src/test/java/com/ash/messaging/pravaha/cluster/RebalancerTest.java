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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning a membership change into a sequence of handoffs.
 *
 * <p>The decisions being tested are about pace, not mechanism: one move at a time, stop on the first
 * failure, and refuse to start again too soon. All three make a rebalance slower and the cluster
 * more available while it happens.
 */
class RebalancerTest {

    private static Member member(String id) {
        return new Member(id, "host-" + id, 9070 + id.charAt(0) - 'a');
    }

    private static List<Member> members(String... ids) {
        List<Member> members = new ArrayList<>();
        for (String id : ids) {
            members.add(member(id));
        }
        return members;
    }

    /** Counts concurrent pauses so "one at a time" can be asserted rather than assumed. */
    private static final class CountingOwner implements PartitionOwner {
        private final AtomicInteger paused;
        private final AtomicInteger maxConcurrentlyPaused;
        private final List<Integer> handled = new ArrayList<>();
        private int failOnPartition = -1;

        CountingOwner(AtomicInteger paused, AtomicInteger max) {
            this.paused = paused;
            this.maxConcurrentlyPaused = max;
        }

        @Override
        public void pause(int partition) {
            int now = paused.incrementAndGet();
            maxConcurrentlyPaused.updateAndGet(previous -> Math.max(previous, now));
            handled.add(partition);
            if (partition == failOnPartition) {
                throw new IllegalStateException("cannot pause p" + partition);
            }
        }

        @Override
        public PartitionSnapshot snapshot(int partition) {
            return new PartitionSnapshot(partition, 1, Map.of("src", "o1"), Map.of("agg", new byte[16]));
        }

        @Override
        public void restore(PartitionSnapshot snapshot) {}

        @Override
        public void resume(int partition) {
            paused.decrementAndGet();
        }

        @Override
        public void release(int partition) {
            paused.decrementAndGet();
        }
    }

    @Test
    void onlyOnePartitionIsEverPausedAtATime() {
        AtomicInteger paused = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        Rebalancer rebalancer = new Rebalancer(node -> new CountingOwner(paused, max), null);

        PartitionAssignment before = PartitionAssignment.of(256, members("a", "b"));
        PartitionAssignment after = PartitionAssignment.of(256, members("a", "b", "c"));
        Rebalancer.Outcome outcome = rebalancer.rebalance(before, after);

        // Every move pauses a slice of the key space. Running them together makes the whole cluster
        // stutter at once, on a node that has just lost or gained a peer and is already busy.
        assertThat(max.get()).isEqualTo(1);
        assertThat(outcome.clean()).isTrue();
        assertThat(outcome.completed()).hasSize(before.movesTo(after).size());
    }

    @Test
    void nothingHappensWhenNoPartitionChangedHands() {
        AtomicInteger paused = new AtomicInteger();
        Rebalancer rebalancer = new Rebalancer(node -> new CountingOwner(paused, new AtomicInteger()), null);
        PartitionAssignment assignment = PartitionAssignment.of(64, members("a", "b"));

        Rebalancer.Outcome outcome = rebalancer.rebalance(assignment, PartitionAssignment.of(64, members("b", "a")));

        assertThat(outcome.completed()).isEmpty();
        assertThat(outcome.clean()).isTrue();
    }

    @Test
    void theFirstRolledBackMoveStopsTheRun() {
        AtomicInteger paused = new AtomicInteger();
        PartitionAssignment before = PartitionAssignment.of(64, members("a", "b"));
        PartitionAssignment after = PartitionAssignment.of(64, members("a", "b", "c"));
        int firstMoved = before.movesTo(after).get(0).partition();

        Rebalancer rebalancer = new Rebalancer(
                node -> {
                    CountingOwner owner = new CountingOwner(paused, new AtomicInteger());
                    owner.failOnPartition = firstMoved;
                    return owner;
                },
                null);

        Rebalancer.Outcome outcome = rebalancer.rebalance(before, after);

        // Grinding on after the first failure turns one problem into N, on a cluster that is already
        // telling you something is wrong. The partitions that did not move are fine where they are.
        assertThat(outcome.clean()).isFalse();
        assertThat(outcome.completed()).isEmpty();
        assertThat(outcome.stopped()).hasSize(before.movesTo(after).size());
    }

    @Test
    void rebalancingAgainTooSoonIsRefused() {
        AtomicInteger paused = new AtomicInteger();
        Rebalancer rebalancer = new Rebalancer(
                node -> new CountingOwner(paused, new AtomicInteger()),
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                null);
        PartitionAssignment two = PartitionAssignment.of(64, members("a", "b"));
        PartitionAssignment three = PartitionAssignment.of(64, members("a", "b", "c"));

        rebalancer.rebalance(two, three);

        // A flapping node would otherwise trigger a rebalance every time it appears and disappears,
        // and the cluster would spend its life moving state rather than answering queries.
        assertThatThrownBy(() -> rebalancer.rebalance(three, two))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9007")
                .hasMessageContaining("flapping");
    }

    @Test
    void aRebalanceIsAllowedOnceTheCooldownHasPassed() {
        AtomicInteger paused = new AtomicInteger();
        Rebalancer rebalancer = new Rebalancer(
                node -> new CountingOwner(paused, new AtomicInteger()), Duration.ofSeconds(5), Duration.ZERO, null);
        PartitionAssignment two = PartitionAssignment.of(64, members("a", "b"));
        PartitionAssignment three = PartitionAssignment.of(64, members("a", "b", "c"));

        rebalancer.rebalance(two, three);

        assertThat(rebalancer.rebalance(three, two).clean()).isTrue();
    }

    @Test
    void aPlanCanBeInspectedWithoutRunningIt() {
        PartitionAssignment before = PartitionAssignment.of(1024, members("a", "b", "c"));
        PartitionAssignment after = PartitionAssignment.of(1024, members("a", "b", "c", "d"));

        List<PartitionAssignment.PartitionMove> plan = Rebalancer.plan(before, after);
        Map<String, Integer> byTarget = Rebalancer.movesByTarget(plan);

        // "What would happen if I added a node" is a question an operator should be able to ask
        // without finding out by doing it.
        assertThat(byTarget).containsOnlyKeys("d");
        assertThat(byTarget.get("d")).isEqualTo(plan.size());
    }
}
