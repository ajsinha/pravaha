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

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.lane.LaneRunner;

/**
 * The lanes a registry's computations run on: the runner that steps every lane, and -- when
 * multiplexing is on -- the shared lanes registrations are placed on. Each {@link QueryRegistry}
 * owns one and calls it only under its own lock, so nothing here locks.
 */
final class RegistryLanes {

    /**
     * The threads every query's lane runs on, created on first use and shared by all of them.
     *
     * <p>ADR-027. A lane used to own a thread, so a thousand registrations were a thousand platform
     * threads -- which is what "fine at tens" meant and why it was true. A runner drives many lanes
     * from a fixed set of threads, sized by cores, so the count stops following the registrations.
     *
     * <p>Confinement is unchanged and is the reason this is a runner rather than a pool: a lane
     * belongs to one runner thread from the moment it is hosted until it is dropped, and a runner
     * steps its lanes one at a time. Nothing about the lock-free hot path changes.
     *
     * <p>Lazily created so a registry that never registers anything starts no threads, which is what
     * a great many of this project's tests are.
     */
    private volatile @Nullable LaneRunner runner;

    /**
     * Whether registrations share multiplexed lanes rather than each taking one of their own, and
     * if so, on how many lanes and at what ceiling. Null when every query gets a lane of its own.
     *
     * <p>W9-8. ADR-027 already removed the thread per query — {@link LaneRunner} drives many lanes
     * from a pool sized to the cores — so what is left on the table is the <em>inbox and arena</em>
     * a lane owns, about 1,024 KiB idle per query. Multiplexing shares one of each between every
     * pipeline on the lane.
     *
     * <p>A row carries the identity of its stream (W9-9), and a watermark advance no longer clamps
     * the lane's batch (W9-10). Which lane a registration lands on is {@link SharedLanes}'s
     * decision, and its javadoc gives the rules and why they are what they are.
     *
     * <p>Off unless asked for. A node reaches it through {@code pravaha.lane.multiplex.*}; an
     * embedder through {@link QueryRegistry#multiplexingLanes(int, int)}. It stays off by default
     * because sharing a lane shares its fate: a pipeline that throws kills the lane, and with it
     * every query on it, where a lane per query loses one.
     */
    private @Nullable SharedLanes shared;

    /** How many lanes and what ceiling {@link #shared} is built with, once multiplexing is on. */
    private int count;

    private int maxPerLane;

    /** Computations placed on lanes of their own before registrations start sharing: zero shares at once. */
    private int shareFrom;

    LaneRunner runner(LaneConfig config) {
        LaneRunner current = runner;
        if (current == null) {
            current = new LaneRunner(LaneRunner.defaultThreads(), "pravaha-lane-runner", config.waitStrategy());
            runner = current;
        }
        return current;
    }

    SharedLanes shared(LaneConfig config, MemoryAccess access, Supplier<LaneRunner> runnerOf) {
        SharedLanes current = shared;
        if (current == null) {
            current = new SharedLanes(count, maxPerLane, config, access, runnerOf);
            shared = current;
        }
        return current;
    }

    /** The number of shared lanes, zero when not multiplexing. */
    int count() {
        return count;
    }

    /** The per-lane ceiling in force, or zero when not multiplexing. */
    int maxPerLane() {
        return count == 0 ? 0 : maxPerLane;
    }

    int shareFrom() {
        return shareFrom;
    }

    /** Whether a registration is placed on a shared lane, given how many computations are hosted. */
    boolean shares(int hosted) {
        return count > 0 && hosted >= shareFrom;
    }

    void configure(int lanes, int maxQueriesPerLane, int from, boolean anyRegistered) {
        if (from < 0) {
            throw new IllegalArgumentException("sharing cannot start before the first query, got " + from);
        }
        if (lanes < 0) {
            throw new IllegalArgumentException("shared lane count cannot be negative, got " + lanes);
        }
        if (lanes > 0 && maxQueriesPerLane < 1) {
            throw new IllegalArgumentException(
                    "a shared lane must be allowed at least one query, got " + maxQueriesPerLane);
        }
        if (anyRegistered) {
            throw new IllegalStateException("queries are already registered on the lanes they were placed on; "
                    + "configure multiplexing before the first registration");
        }
        if (shared != null) {
            // Built by an earlier configuration and carrying nothing, since nothing is registered.
            shared.close();
            shared = null;
        }
        this.shareFrom = from;
        this.count = lanes;
        this.maxPerLane = lanes == 0 ? 0 : maxQueriesPerLane;
    }

    /**
     * Closes the shared lanes and then the runner, after the registry has closed its queries.
     *
     * <p>A hosted lane's final step is what releases its arena and inbox, and only its runner may
     * take that step. Closing the runner first would leave every lane unable to finish, and each
     * close would time out blaming a stall that never happened. The shared lanes go before the
     * runner and after the queries, for the same reason in both directions: a hosted query's close
     * only removes its pipelines and deliberately leaves the lane running for the queries still on
     * it, so somebody has to close the lane itself -- and it can only finish while its runner is
     * still stepping it.
     */
    void close() {
        SharedLanes closing = shared;
        shared = null;
        if (closing != null) {
            closing.close();
        }
        LaneRunner stopping = runner;
        runner = null;
        if (stopping != null) {
            stopping.close();
        }
    }
}
