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

/**
 * A lane's counters at one instant.
 *
 * <p>A snapshot rather than live accessors: the lane thread publishes these once per batch, so
 * reading six fields off a running lane would otherwise be reading six different instants and the
 * arithmetic between them would be nonsense.
 *
 * @param laneId which lane
 * @param rowsIn rows drained from the inbox
 * @param rowsOut rows the processor emitted; the ratio to {@code rowsIn} is the query's selectivity
 * @param batches how many batches were processed. {@code rowsIn / batches} is the effective batch
 *     size, which is the number the adaptive batching controller (design section 18.2) will steer.
 * @param idleCycles wait-strategy invocations. High against low {@code rowsIn} means a lane is
 *     burning a core on an idle stream and wants a cheaper wait strategy.
 * @param rejectedOffers producer attempts refused because the inbox was full -- the backpressure
 *     signal, and the primary capacity-planning number (design section 13.5)
 * @param arenaHighWaterBytes peak arena usage, which is what sizing the lane actually needs
 * @param inboxFill inbox occupancy from 0 to 1, against which the high and low watermarks are set
 * @param exchangedIn rows received from other lanes. Against {@code rowsIn} this is the share of the
 *     lane's work that arrived through a repartition, which is what decides whether an exchange is
 *     earning its cost.
 */
public record LaneMetrics(
        int laneId,
        long rowsIn,
        long rowsOut,
        long batches,
        long idleCycles,
        long rejectedOffers,
        long arenaHighWaterBytes,
        double inboxFill,
        long exchangedIn) {

    /** Rows per batch actually achieved. Zero before the first batch. */
    public double averageBatchSize() {
        return batches == 0 ? 0 : (double) rowsIn / batches;
    }
}
