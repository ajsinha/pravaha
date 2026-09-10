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

import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;

/**
 * How one lane is sized.
 *
 * <p>Every value here is a CPU-for-latency or memory-for-throughput trade that a deployment is
 * entitled to make differently, which is why none of them is baked into {@link Lane}. The defaults
 * are the ones design section 13.3 and 13.4 argue for: a 512-row batch, and a wait strategy that
 * spins while traffic flows and gives the core back when it does not.
 *
 * @param batchSize rows drained per iteration. Amortises the cursor traffic and, more importantly,
 *     hands the generated stage a counted loop the JIT can unroll (design section 13.4).
 * @param inboxCells cells in the lane's inbox, rounded up to a power of two. This is the lane's
 *     entire input buffer: there is no unbounded queue anywhere (NFR-9).
 * @param inboxCellBytes the largest input row the lane accepts
 * @param waitStrategy what the lane does when its inbox is empty
 * @param arenaSlabBytes slab size for the lane's output arena
 * @param arenaMaxSlabs slab ceiling, so a runaway query is bounded rather than fatal
 * @param threadNamePrefix lane threads are named {@code prefix-id}; they appear in every stack
 *     trace, flame graph and {@code top -H}, so the name is an operational feature
 * @param daemonThreads whether lane threads keep the JVM alive. False for the server, true when
 *     embedded in a host that owns its own shutdown.
 * @param shutdownTimeout how long {@link Lane#close()} waits for the loop to finish
 */
public record LaneConfig(
        int batchSize,
        int inboxCells,
        int inboxCellBytes,
        WaitStrategy.Kind waitStrategy,
        int arenaSlabBytes,
        int arenaMaxSlabs,
        String threadNamePrefix,
        boolean daemonThreads,
        Duration shutdownTimeout) {

    public static final int DEFAULT_BATCH_SIZE = 512;

    public LaneConfig {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be at least 1, got " + batchSize);
        }
        if (inboxCells < 2) {
            throw new IllegalArgumentException("inbox must have at least 2 cells, got " + inboxCells);
        }
        if (inboxCellBytes < 1) {
            throw new IllegalArgumentException("inbox cell size must be positive, got " + inboxCellBytes);
        }
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative, got " + shutdownTimeout);
        }
        java.util.Objects.requireNonNull(waitStrategy, "waitStrategy");
        java.util.Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
    }

    /** General production sizing. */
    public static LaneConfig defaults() {
        return new LaneConfig(
                DEFAULT_BATCH_SIZE,
                2048,
                512,
                WaitStrategy.Kind.SPIN_THEN_YIELD,
                RowArena.DEFAULT_SLAB_BYTES,
                8,
                "pravaha-lane",
                false,
                Duration.ofSeconds(5));
    }

    public LaneConfig withBatchSize(int size) {
        return new LaneConfig(
                size,
                inboxCells,
                inboxCellBytes,
                waitStrategy,
                arenaSlabBytes,
                arenaMaxSlabs,
                threadNamePrefix,
                daemonThreads,
                shutdownTimeout);
    }

    public LaneConfig withInbox(int cells, int cellBytes) {
        return new LaneConfig(
                batchSize,
                cells,
                cellBytes,
                waitStrategy,
                arenaSlabBytes,
                arenaMaxSlabs,
                threadNamePrefix,
                daemonThreads,
                shutdownTimeout);
    }

    public LaneConfig withWaitStrategy(WaitStrategy.Kind kind) {
        return new LaneConfig(
                batchSize,
                inboxCells,
                inboxCellBytes,
                kind,
                arenaSlabBytes,
                arenaMaxSlabs,
                threadNamePrefix,
                daemonThreads,
                shutdownTimeout);
    }

    public LaneConfig withArena(int slabBytes, int maxSlabs) {
        return new LaneConfig(
                batchSize,
                inboxCells,
                inboxCellBytes,
                waitStrategy,
                slabBytes,
                maxSlabs,
                threadNamePrefix,
                daemonThreads,
                shutdownTimeout);
    }

    public LaneConfig withThreads(String prefix, boolean daemon) {
        return new LaneConfig(
                batchSize,
                inboxCells,
                inboxCellBytes,
                waitStrategy,
                arenaSlabBytes,
                arenaMaxSlabs,
                prefix,
                daemon,
                shutdownTimeout);
    }

    public LaneConfig withShutdownTimeout(Duration timeout) {
        return new LaneConfig(
                batchSize,
                inboxCells,
                inboxCellBytes,
                waitStrategy,
                arenaSlabBytes,
                arenaMaxSlabs,
                threadNamePrefix,
                daemonThreads,
                timeout);
    }
}
