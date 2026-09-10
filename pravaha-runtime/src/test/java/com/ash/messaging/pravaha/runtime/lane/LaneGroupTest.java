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
package com.ash.messaging.pravaha.runtime.lane;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lanes, and the routing that decides which one a key belongs to.
 *
 * <p>{@link #sixteenLanesShareNothing} is the acceptance criterion for the lane model story, and it
 * is worth being precise about what it can and cannot prove. It proves the absence of *structural*
 * sharing -- no two lanes hold the same arena, thread, inbox or processor -- which is the failure
 * that would be introduced by a plausible-looking refactor and would never show up as a wrong
 * answer, only as scaling that quietly stops at four lanes. It does not prove the absence of false
 * sharing between separately-allocated fields; that is a cache-line question, it is P2-10, and it is
 * answered by a benchmark rather than by an assertion.
 */
// Bounded so a regression fails rather than hangs. Found the hard way: seeding "never drain the
// inbound exchange rings" did not fail this suite, it stalled it -- the feeding loop waited for inbox
// space that a blocked lane would never free. A hanging test in CI is worse than a failing one,
// because it looks like an infrastructure problem and gets retried rather than read.
@Timeout(60)
class LaneGroupTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(64, 32)
                .withBatchSize(16)
                .withArena(64 * 1024, 2)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("test-lane", true);
    }

    @Test
    void sixteenLanesShareNothing() {
        MemoryAccess access = MemoryAccess.best();
        List<LaneContext> contexts = new ArrayList<>();
        try (LaneGroup group = new LaneGroup(16, config(), access, context -> {
            contexts.add(context);
            return (region, offsets, count) -> count;
        })) {
            assertThat(group.laneCount()).isEqualTo(16);
            assertThat(contexts)
                    .as("a processor is built once per lane, never shared")
                    .hasSize(16);

            assertThat(distinctIdentities(contexts, LaneContext::arena))
                    .as("each lane must own its arena; a shared one would serialise every allocation")
                    .isEqualTo(16);
            assertThat(distinctIdentities(group.lanes(), Lane::thread)).isEqualTo(16);
            assertThat(distinctIdentities(group.lanes(), Lane::inboxRegion)).isEqualTo(16);

            // The partitions partition: every one assigned exactly once, none to two lanes.
            Set<Integer> allPartitions = new HashSet<>();
            int total = 0;
            for (LaneContext context : contexts) {
                for (int partition : context.virtualPartitions()) {
                    assertThat(allPartitions.add(partition))
                            .as("virtual partition %s is assigned to more than one lane", partition)
                            .isTrue();
                    total++;
                }
            }
            assertThat(total).isEqualTo(group.virtualPartitionCount());
            assertThat(allPartitions).hasSize(LaneGroup.DEFAULT_VIRTUAL_PARTITIONS);

            // No lane is starved of partitions: the largest and smallest shares differ by at most
            // one, which is what keeps a hot lane from being an artefact of the assignment.
            int min = contexts.stream()
                    .mapToInt(LaneContext::virtualPartitionCount)
                    .min()
                    .orElseThrow();
            int max = contexts.stream()
                    .mapToInt(LaneContext::virtualPartitionCount)
                    .max()
                    .orElseThrow();
            assertThat(max - min).isLessThanOrEqualTo(1);
        }
    }

    private static <T> int distinctIdentities(List<T> items, java.util.function.Function<T, Object> extractor) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        items.forEach(item -> seen.put(extractor.apply(item), Boolean.TRUE));
        return seen.size();
    }

    @Test
    void aKeyAlwaysRoutesToTheSameLaneAndTheKeysSpreadEvenly() {
        MemoryAccess access = MemoryAccess.best();
        try (LaneGroup group = new LaneGroup(8, config(), access, context -> (region, offsets, count) -> count)) {
            long[] perLane = new long[8];
            for (long i = 0; i < 100_000; i++) {
                // Keys whose low bits are constant, which is the common case rather than a contrived
                // one: sharded identifiers, millisecond timestamps, anything pre-multiplied. Masking
                // such a key straight into a partition lands every row on one lane, and the query
                // then runs at an eighth of its rate with seven idle threads and no error anywhere.
                long key = i * LaneGroup.DEFAULT_VIRTUAL_PARTITIONS;
                int lane = group.laneFor(key);
                assertThat(group.laneFor(key))
                        .as("routing must be a function of the key alone")
                        .isEqualTo(lane);
                perLane[lane]++;
            }
            // Within 10 % of even. A wide bar, which a hash that does not finalise fails outright
            // rather than narrowly.
            for (long count : perLane) {
                assertThat(count).isBetween(11_250L, 13_750L);
            }
        }
    }

    @Test
    void everyRowIsProcessedByTheLaneItsKeyRoutesTo() {
        MemoryAccess access = MemoryAccess.best();
        int lanes = 8;
        AtomicLongArray rowsPerLane = new AtomicLongArray(lanes);
        Set<Long> misrouted = ConcurrentHashMap.newKeySet();

        // The processor needs the group in order to recompute a key's partition, and the group
        // does not exist until its lanes are built. The holder is read only once rows flow, which
        // is strictly after start().
        java.util.concurrent.atomic.AtomicReference<LaneGroup> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (MemoryRegion scratch = access.allocate(64);
                LaneGroup group = new LaneGroup(lanes, config(), access, context -> {
                    int laneId = context.laneId();
                    Set<Integer> owned = new HashSet<>();
                    for (int partition : context.virtualPartitions()) {
                        owned.add(partition);
                    }
                    return (region, offsets, count) -> {
                        for (int i = 0; i < count; i++) {
                            long key = region.getLong((int) offsets[i]);
                            // The partition, recomputed on the lane that received the row: if it is
                            // not one this lane owns, the routing and the assignment disagree.
                            if (!owned.contains(holder.get().virtualPartitionFor(key))) {
                                misrouted.add(key);
                            }
                            rowsPerLane.incrementAndGet(laneId);
                        }
                        return count;
                    };
                })) {
            holder.set(group);
            group.start();
            for (long key = 0; key < 20_000; key++) {
                Lane lane = group.lane(group.laneFor(key));
                scratch.putLong(0, key);
                while (!lane.offer(scratch, 0, 8)) {
                    group.checkHealth();
                    Thread.onSpinWait();
                }
            }
            assertThat(group.awaitQuiescent(PATIENCE)).isTrue();
            group.checkHealth();

            assertThat(misrouted).isEmpty();
            long delivered = 0;
            for (int i = 0; i < lanes; i++) {
                assertThat(rowsPerLane.get(i)).as("lane %s received nothing", i).isPositive();
                delivered += rowsPerLane.get(i);
            }
            assertThat(delivered).isEqualTo(20_000);
            assertThat(group.totals().rowsIn()).isEqualTo(20_000);
        }
    }

    @Test
    void aFailedLaneLeavesItsSiblingsRunning() {
        // The payoff of sharing nothing: a lane that dies takes nothing with it. Whether the query
        // above it should also fail is a policy decision for the query lifecycle, not the lane's.
        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion scratch = access.allocate(64);
                LaneGroup group = new LaneGroup(4, config(), access, context -> {
                    if (context.laneId() == 2) {
                        return (region, offsets, count) -> {
                            throw new IllegalStateException("lane 2 is having a bad day");
                        };
                    }
                    return (region, offsets, count) -> count;
                })) {
            group.start();
            for (Lane lane : group.lanes()) {
                scratch.putLong(0, lane.laneId());
                while (!lane.offer(scratch, 0, 8)) {
                    Thread.onSpinWait();
                }
            }

            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (group.lane(2).state() != Lane.State.FAILED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(group.lane(2).state()).isEqualTo(Lane.State.FAILED);
            for (int i : new int[] {0, 1, 3}) {
                assertThat(group.lane(i).state()).isEqualTo(Lane.State.RUNNING);
                assertThat(group.lane(i).awaitQuiescent(PATIENCE)).isTrue();
                assertThat(group.lane(i).metrics().rowsIn()).isEqualTo(1);
            }
            assertThatThrownBy(group::checkHealth).hasMessageContaining("lane 2");
        }
    }

    @Test
    void aPartitionCountBelowTheLaneCountIsRejected() {
        MemoryAccess access = MemoryAccess.best();
        assertThatThrownBy(() -> new LaneGroup(8, 4, config(), access, c -> (r, o, n) -> n))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("virtual partitions");
    }

    @Test
    void theArenaBelongsToTheLaneAndIsClosedWithIt() {
        MemoryAccess access = MemoryAccess.best();
        List<RowArena> arenas = new ArrayList<>();
        LaneGroup group = new LaneGroup(4, config(), access, context -> {
            arenas.add(context.arena());
            return (region, offsets, count) -> count;
        });
        group.start();
        group.close();
        // A closed arena refuses to allocate; a leaked one would happily keep going.
        arenas.forEach(arena -> assertThatThrownBy(() -> arena.allocate(8)).isInstanceOf(IllegalStateException.class));
    }
}
