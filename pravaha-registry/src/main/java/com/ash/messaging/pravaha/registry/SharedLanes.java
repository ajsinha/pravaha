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
package com.ash.messaging.pravaha.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.lane.LaneGroup;
import com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer;
import com.ash.messaging.pravaha.runtime.lane.LaneRunner;

/**
 * The multiplexed lanes a registry hosts queries on, and the admission control that decides which
 * one a registration lands on (W9-8).
 *
 * <p>Before this there was one shared lane and no decision: every hosted query on a node shared a
 * single inbox, arena and runner slot, however many there were. Now there are {@code laneCount}
 * of them, each a one-lane {@link LaneGroup} whose processor is a {@link LaneMultiplexer}, and a
 * registration is placed by one rule: <strong>the least loaded lane below the ceiling wins</strong>,
 * by pipeline count, lowest index on a tie. Pipeline count rather than lane time consumed, because a
 * query being placed has consumed nothing yet, so its cost is unknown at the one moment the decision
 * is made; and because a count is what the ceiling is expressed in.
 *
 * <p><strong>What the query reads no longer matters (LANE-2).</strong> There were two more rules:
 * a lane carried at most one query per stream, and a query reading two streams was never hosted.
 * Both existed because the lane dispatched by stream while each query was fed separately, so two
 * queries over {@code txn} on one lane each counted the other's rows (LANE-1), and a join's second
 * side had no stream of its own to arrive under. Each hosted input now has a route of its own, and a
 * reader shared by several queries on a lane writes one copy that all of them listen to (see {@link
 * LaneMultiplexer} and {@code SharedPartitionFeed}), so neither rule protects anything.
 *
 * <p>When no lane qualifies the answer is empty and the registry gives the query a <em>lane of its
 * own</em> rather than refusing it. Multiplexing is a memory optimisation; turning it on must never
 * make a node accept fewer queries than it accepts with it off, and a node with it off accepts this
 * query on a dedicated lane. A refusal would turn a sizing setting into an outage at the moment a
 * node is busiest. The cost of the fallback is the one multiplexing exists to avoid -- about one
 * inbox per query -- and it is visible, as {@link QueryRegistry#queriesOnOwnLanes()}.
 *
 * <p>Lanes are built on first use, so a node that hosts two queries holds two inboxes, not
 * {@code laneCount}. Not thread-safe; the registry calls it under its own monitor.
 */
final class SharedLanes implements AutoCloseable {

    private final LaneGroup[] lanes;
    private final int ceiling;
    private final LaneConfig config;
    private final MemoryAccess access;
    private final Supplier<LaneRunner> runner;

    SharedLanes(int laneCount, int ceiling, LaneConfig config, MemoryAccess access, Supplier<LaneRunner> runner) {
        if (laneCount < 1) {
            throw new IllegalArgumentException("multiplexing needs at least one shared lane, got " + laneCount);
        }
        if (ceiling < 1) {
            throw new IllegalArgumentException("a shared lane must be allowed at least one query, got " + ceiling);
        }
        this.lanes = new LaneGroup[laneCount];
        this.ceiling = ceiling;
        this.config = config;
        this.access = access;
        this.runner = runner;
    }

    /** Where a hosted query went: the lane's index, for an operator, and its group, for the engine. */
    record Placement(int index, LaneGroup group) {}

    /** The lane the next query should be hosted on, or empty for a lane of its own. */
    Optional<Placement> place() {
        int best = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int i = 0; i < lanes.length; i++) {
            int count = pipelinesOn(i);
            if (count >= ceiling) {
                continue;
            }
            if (count < bestCount) {
                best = i;
                bestCount = count;
            }
        }
        if (best < 0) {
            return Optional.empty();
        }
        return Optional.of(new Placement(best, built(best)));
    }

    private LaneGroup built(int index) {
        if (lanes[index] == null) {
            LaneGroup group = new LaneGroup(1, config, access, context -> new LaneMultiplexer());
            group.startOn(runner.get());
            lanes[index] = group;
        }
        return lanes[index];
    }

    private static LaneMultiplexer multiplexer(LaneGroup group) {
        return (LaneMultiplexer) group.lane(0).processor();
    }

    private int pipelinesOn(int index) {
        return lanes[index] == null ? 0 : multiplexer(lanes[index]).pipelineCount();
    }

    /**
     * Off-heap bytes every built shared lane holds -- its inbox and its arena -- summed. What the
     * queries hosted on them would otherwise each hold a lane's worth of.
     */
    long offHeapBytes() {
        long total = 0;
        for (LaneGroup group : lanes) {
            if (group != null) {
                for (long bytes : group.lane(0).offHeapBytes().values()) {
                    total += bytes;
                }
            }
        }
        return total;
    }

    /** Shared lanes built so far: the ones something has been placed on. */
    int built() {
        int count = 0;
        for (LaneGroup group : lanes) {
            if (group != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * One shared lane's backpressure, or nothing when that lane has not been built.
     *
     * <p>Per lane rather than per query, because on a shared lane that is the honest unit: the
     * lane's writers are every hosted query's, and its inbox depth is one queue they all wait
     * behind. A per-query figure taken from here would report the same number for every query on
     * the lane, which is true and reads as though each of them were the cause.
     */
    java.util.Optional<com.ash.messaging.pravaha.runtime.lane.LaneBackpressure.Snapshot> backpressureOn(int index) {
        LaneGroup group = index >= 0 && index < lanes.length ? lanes[index] : null;
        return group == null ? java.util.Optional.empty() : java.util.Optional.of(group.backpressure());
    }

    /** Pipelines on every configured lane, zero for one not built yet. */
    List<Integer> pipelinesPerLane() {
        List<Integer> counts = new ArrayList<>(lanes.length);
        for (int i = 0; i < lanes.length; i++) {
            counts.add(pipelinesOn(i));
        }
        return List.copyOf(counts);
    }

    /**
     * Stops every lane that was built.
     *
     * <p>After the queries on them and before the runner, for the reasons {@link QueryRegistry#close}
     * gives. Each is closed even if an earlier one refused, so one stuck lane does not leak the rest.
     */
    @Override
    public void close() {
        RuntimeException first = null;
        for (int i = 0; i < lanes.length; i++) {
            LaneGroup group = lanes[i];
            lanes[i] = null;
            if (group == null) {
                continue;
            }
            try {
                group.close();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
