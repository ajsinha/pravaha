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

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.common.arena.RowArena;

/**
 * Everything one lane owns, handed to its processor when the processor is built.
 *
 * <p>The point of this type is what it is <em>not</em>: nothing here is shared with another lane.
 * The arena is the lane's own, the virtual partitions are disjoint from every other lane's, and the
 * processor built from this context is built once per lane. That is the single-writer principle
 * (design section 13.1) expressed as an object rather than as a convention -- a shared field is
 * visible here, where a shared field hidden behind a factory would not be.
 *
 * <p>The virtual partitions are the lane's <strong>state slice</strong>. State itself arrives in
 * Wave 4; what a lane needs before then, and what rescaling later moves without rehashing a single
 * key (design section 21.1), is the partition assignment, and that exists now.
 */
public final class LaneContext {

    private final int laneId;
    private final RowArena arena;
    private final int[] virtualPartitions;
    private final LaneConfig config;
    private final LaneExchange.@Nullable Sender exchange;

    LaneContext(
            int laneId,
            RowArena arena,
            int[] virtualPartitions,
            LaneConfig config,
            LaneExchange.@Nullable Sender exchange) {
        this.laneId = laneId;
        this.arena = arena;
        this.virtualPartitions = virtualPartitions;
        this.config = config;
        this.exchange = exchange;
    }

    /** Zero-based, stable for the lane's lifetime, and what every per-lane metric is tagged with. */
    public int laneId() {
        return laneId;
    }

    /**
     * The lane's output arena.
     *
     * <p>Rewound after every batch, so a row allocated here is valid until the batch ends and no
     * longer. Anything that must outlive the batch copies (design section 8.5).
     */
    public RowArena arena() {
        return arena;
    }

    /** The virtual partitions assigned to this lane. A copy; the assignment is not the lane's to edit. */
    public int[] virtualPartitions() {
        return Arrays.copyOf(virtualPartitions, virtualPartitions.length);
    }

    public int virtualPartitionCount() {
        return virtualPartitions.length;
    }

    public LaneConfig config() {
        return config;
    }

    /**
     * How this lane sends a row to the lane that owns its key.
     *
     * <p>Empty for a single-lane query, which is the common case and needs no exchange at all: a
     * lane that owns every partition never repartitions anything. A processor that requires the
     * exchange should say so when it is built rather than discovering the absence per row.
     */
    public java.util.Optional<LaneExchange.Sender> exchange() {
        return java.util.Optional.ofNullable(exchange);
    }

    @Override
    public String toString() {
        return "LaneContext[lane=" + laneId + ", vpartitions=" + virtualPartitions.length + ", " + arena + "]";
    }
}
