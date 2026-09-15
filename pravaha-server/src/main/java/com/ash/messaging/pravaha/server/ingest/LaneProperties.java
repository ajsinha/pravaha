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
package com.ash.messaging.pravaha.server.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;

/**
 * What one registered query is allowed to cost in memory and how its lane waits.
 *
 * <p>These knobs existed and could not be reached. {@code QueryRegistry.executingWith} has always
 * taken a {@link LaneConfig}, and nothing on the server path ever called it — so every query on
 * every node ran with the library defaults, and five error messages told operators to "raise
 * {@code arena.slab.size}" for a setting that did not exist (PF-3).
 *
 * <p>The defaults here are the library's, so a node that sets nothing behaves exactly as before. What
 * changes is that a node can now be sized for the workload it has.
 *
 * <h2>Why this is the first item in ADR-036</h2>
 *
 * <p>The library defaults were chosen for the case this engine was first built for: one query, a
 * source that never stops, latency worth more than a core. They cost about **5 MiB per idle query** —
 * one 4 MiB arena slab, allocated eagerly, plus a 2048 × 512-byte inbox.
 *
 * <p>A thousand small continuous queries is the opposite workload and inherits those sizes anyway. At
 * 5 MiB each that is 5 GB off-heap before a row moves, which is the wall the stated target hits
 * first — sooner than the thread-per-query one, and less obviously.
 *
 * <p>A query reading a few thousand rows a second through a narrow projection does not need a 4 MiB
 * slab. Sized at 256 KiB with a 256-cell inbox, the same thousand queries cost a few hundred
 * megabytes. Nothing here picks that for an operator, because the right number depends on the widest
 * row and the deepest batch a query will see, and guessing it in the engine is how a default becomes
 * a mystery. `OPERATIONS.md` carries the arithmetic.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.lane")
public class LaneProperties {

    private final Inbox inbox = new Inbox();
    private final Arena arena = new Arena();

    /** Rows handed to an operator at once. */
    private int batchSize = LaneConfig.DEFAULT_BATCH_SIZE;

    /**
     * How a lane waits when its inbox is empty.
     *
     * <p>{@code BACKOFF_PARK} is the registry's default and the right one for many queries: spinning
     * costs a core per eleven idle queries, which is what made nine idle registrations burn 92% of
     * one. Left settable because a node running a single latency-critical query wants the opposite.
     */
    private WaitStrategy.Kind waitStrategy = WaitStrategy.Kind.BACKOFF_PARK;

    public Inbox getInbox() {
        return inbox;
    }

    public Arena getArena() {
        return arena;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public WaitStrategy.Kind getWaitStrategy() {
        return waitStrategy;
    }

    public void setWaitStrategy(WaitStrategy.Kind waitStrategy) {
        this.waitStrategy = waitStrategy;
    }

    /** The configured shape, as the engine wants it. */
    public LaneConfig toLaneConfig() {
        return LaneConfig.defaults()
                .withBatchSize(batchSize)
                .withInbox(inbox.cells, inbox.cellBytes)
                .withArena(arena.slabBytes, arena.maxSlabs)
                .withWaitStrategy(waitStrategy)
                .withThreads("pravaha-query", true);
    }

    /**
     * Bytes one idle query holds before it has read anything, which is what multiplies by a thousand.
     *
     * <p>The inbox, and only the inbox. It added the arena slab too, which was right when the slab
     * was allocated in `RowArena`'s constructor and wrong the moment W9-6 made it lazy -- so the
     * node logged 5,120 KiB per idle query where `NodeScaleTest` measures 1,024. An operator sizing
     * a node from that line would have budgeted five times what they needed (DOCS-9).
     *
     * <p>An idle query has never written a row, so it has no slab. An active one does, and what it
     * holds is `InterpretedPipeline`'s arena rather than the lane's -- measured at zero for both a
     * projection and a windowed aggregate, because operator output goes to the view rather than
     * through the lane's scratch.
     */
    public long idleBytesPerQuery() {
        return (long) inbox.cells * inbox.cellBytes;
    }

    /** The ring a lane reads rows from. One cell holds one row, so a cell must fit the widest one. */
    public static class Inbox {

        private int cells = 2048;
        private int cellBytes = 512;

        public int getCells() {
            return cells;
        }

        public void setCells(int cells) {
            this.cells = cells;
        }

        public int getCellBytes() {
            return cellBytes;
        }

        public void setCellBytes(int cellBytes) {
            this.cellBytes = cellBytes;
        }
    }

    /**
     * Off-heap rows a lane builds its output in.
     *
     * <p>The first slab is allocated when the lane starts; the rest are added on demand up to
     * {@code maxSlabs}. So {@code slabBytes} is what a query costs idle and
     * {@code slabBytes * maxSlabs} is its ceiling.
     */
    public static class Arena {

        private int slabBytes = 4 * 1024 * 1024;
        private int maxSlabs = 8;

        public int getSlabBytes() {
            return slabBytes;
        }

        public void setSlabBytes(int slabBytes) {
            this.slabBytes = slabBytes;
        }

        public int getMaxSlabs() {
            return maxSlabs;
        }

        public void setMaxSlabs(int maxSlabs) {
            this.maxSlabs = maxSlabs;
        }
    }
}
