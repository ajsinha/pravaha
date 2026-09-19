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
import java.util.Set;
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
 * registration is placed by three rules, applied in this order:
 *
 * <ol>
 *   <li><strong>A query reading more than one stream is not hosted.</strong> A hosted pipeline
 *       subscribes by the stream id of its first input, and a shared lane has one inbox: a join's
 *       second side would have nowhere to arrive. It gets a lane of its own, as it always had.
 *   <li><strong>A lane carries at most one pipeline per stream.</strong> Dispatch is by stream, and
 *       each registration is fed by its own feed. Two queries over {@code txn} on one lane would
 *       each have every row copied in by both feeds and dispatched to both pipelines -- an
 *       aggregate counts every row twice. Measured, not supposed: a {@code COUNT(*)} handed one row
 *       answered two before this rule existed. Sharing an ingest between the queries on a lane is
 *       what would lift it, and that is the feed layer's change, not this one.
 *   <li><strong>Among the lanes that remain, the least loaded below the ceiling wins</strong>, by
 *       pipeline count, lowest index on a tie. Pipeline count rather than lane time consumed,
 *       because a query being placed has consumed nothing yet, so its cost is unknown at the one
 *       moment the decision is made; and because a count is what the ceiling is expressed in.
 * </ol>
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

    /**
     * The lane a query reading {@code streamIds} should be hosted on, or empty for a lane of its own.
     *
     * @param streamIds the ids of every stream the query reads
     */
    Optional<Placement> place(List<Integer> streamIds) {
        if (streamIds.size() != 1) {
            return Optional.empty();
        }
        int stream = streamIds.get(0);
        int best = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int i = 0; i < lanes.length; i++) {
            int count = pipelinesOn(i);
            if (count >= ceiling || streamsOn(i).contains(stream)) {
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

    private Set<Integer> streamsOn(int index) {
        return lanes[index] == null ? Set.of() : multiplexer(lanes[index]).streamIds();
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
