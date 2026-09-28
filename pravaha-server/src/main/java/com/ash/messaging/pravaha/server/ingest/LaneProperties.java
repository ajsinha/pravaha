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
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
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
    private final Multiplex multiplex = new Multiplex();
    private final Backpressure backpressure = new Backpressure();

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

    public Multiplex getMultiplex() {
        return multiplex;
    }

    public Backpressure getBackpressure() {
        return backpressure;
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

    /**
     * Whether registered queries share lanes, how many, and how many to a lane (W9-8).
     *
     * <p>Off by default. Sharing a lane shares its inbox -- the megabyte a query otherwise holds
     * idle -- and it shares its fate: a pipeline that throws takes its lane down, and on a shared
     * lane that is every query on it, where a lane per query loses one. W9-11 records that a node
     * already reaches its query target without this, so it is a memory optimisation a deployment
     * chooses, not a default it inherits.
     *
     * <p>When on, a registration is placed on the least loaded of {@code lanes} shared lanes that is
     * below {@code maxQueriesPerLane} and carries no other query over the same stream; one that fits
     * nowhere -- or reads more than one stream -- gets a lane of its own instead of being refused.
     * The registry's {@code SharedLanes} gives the reasons for each rule.
     */
    public static class Multiplex {

        /** Design section 13.7: ten thousand queries on a node sized by cores is about 300 a lane. */
        public static final int DEFAULT_MAX_QUERIES_PER_LANE = 300;

        /** Queries a node hosts on lanes of their own before {@code auto} starts sharing. */
        public static final int DEFAULT_AUTO_FROM = 64;

        /**
         * {@code auto} (the default), {@code true} or {@code false}. Sharing a lane saves its inbox and arena,
         * about 1 MiB idle per query, and costs isolation: a slow or failing query holds up the others on
         * its lane. {@code auto} keeps the isolation while a node is small and takes the saving once it is
         * not -- the first {@link #autoFrom} computations each own a lane, and registrations after that
         * share. Queries already running are never moved.
         */
        private String enabled = "auto";

        private int autoFrom = DEFAULT_AUTO_FROM;

        /** Zero means one per lane-runner thread, which is one per available processor. */
        private int lanes;

        private int maxQueriesPerLane = DEFAULT_MAX_QUERIES_PER_LANE;

        public String getEnabled() {
            return enabled;
        }

        public void setEnabled(String enabled) {
            this.enabled = enabled;
        }

        public int getAutoFrom() {
            return autoFrom;
        }

        public void setAutoFrom(int autoFrom) {
            this.autoFrom = autoFrom;
        }

        /** {@code auto}, {@code true} or {@code false}; anything else is refused by name. */
        public String mode() {
            String mode = enabled == null ? "auto" : enabled.strip().toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("auto") && !mode.equals("true") && !mode.equals("false")) {
                throw new IllegalArgumentException(
                        "pravaha.lane.multiplex.enabled is auto, true or false, not '" + enabled + "'");
            }
            if (mode.equals("auto") && autoFrom < 0) {
                throw new IllegalArgumentException(
                        "pravaha.lane.multiplex.auto-from cannot be negative, got " + autoFrom);
            }
            return mode;
        }

        /** How many computations own a lane before sharing starts: zero with {@code true}. */
        public int shareFrom() {
            return mode().equals("auto") ? autoFrom : 0;
        }

        public int getLanes() {
            return lanes;
        }

        public void setLanes(int lanes) {
            this.lanes = lanes;
        }

        public int getMaxQueriesPerLane() {
            return maxQueriesPerLane;
        }

        public void setMaxQueriesPerLane(int maxQueriesPerLane) {
            this.maxQueriesPerLane = maxQueriesPerLane;
        }

        /**
         * The shared lane count the registry is given: zero when off, the configured count when set,
         * and one per available processor when left at zero -- the lane runner has a thread per
         * processor, so each can step a shared lane without any waiting on another.
         */
        public int effectiveLanes() {
            if (mode().equals("false")) {
                return 0;
            }
            if (lanes < 0) {
                throw new IllegalArgumentException("pravaha.lane.multiplex.lanes cannot be negative, got " + lanes);
            }
            if (maxQueriesPerLane < 1) {
                throw new IllegalArgumentException(
                        "pravaha.lane.multiplex.max-queries-per-lane must be at least 1, got " + maxQueriesPerLane);
            }
            return lanes > 0 ? lanes : com.ash.messaging.pravaha.runtime.lane.LaneRunner.defaultThreads();
        }
    }

    /**
     * When a source is paused because its query's inbox is filling, and when it is let go again.
     *
     * <p>{@link BackpressurePolicy}'s own javadoc has said since it was written that these are
     * configuration, "because the right gap depends on how expensive a pause is for the source: a
     * Kafka consumer pause is nearly free, an Aerospike scan throttle is not" -- and no key bound
     * them, so every deployment ran the design's 0.8/0.5 whatever it put in its YAML.
     * TROUBLESHOOTING told an operator diagnosing a paused source to check
     * {@code pravaha.lane.backpressure.high-watermark}, which nothing read.
     *
     * <p>The pair is validated by {@code BackpressurePolicy}'s own constructor rather than by a
     * second copy of the rule here: a high watermark outside (0, 1] and a low watermark at or above
     * it are refused at startup, naming the key, instead of being clamped into something that runs.
     * Equal watermarks in particular pause and resume a saturated source on alternate polls, which
     * costs more than the backpressure saves.
     */
    public static class Backpressure {

        private double highWatermark = BackpressurePolicy.defaults().highWatermark();
        private double lowWatermark = BackpressurePolicy.defaults().lowWatermark();

        public double getHighWatermark() {
            return highWatermark;
        }

        public void setHighWatermark(double highWatermark) {
            this.highWatermark = highWatermark;
        }

        public double getLowWatermark() {
            return lowWatermark;
        }

        public void setLowWatermark(double lowWatermark) {
            this.lowWatermark = lowWatermark;
        }

        /** The configured pair, or the reason it cannot be used, named by key. */
        public BackpressurePolicy policy() {
            try {
                return new BackpressurePolicy(highWatermark, lowWatermark);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "pravaha.lane.backpressure.high-watermark="
                                + highWatermark + " with pravaha.lane.backpressure.low-watermark=" + lowWatermark
                                + " cannot be used: " + e.getMessage(),
                        e);
            }
        }
    }
}
