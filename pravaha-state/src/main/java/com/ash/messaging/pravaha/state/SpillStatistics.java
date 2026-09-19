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
package com.ash.messaging.pravaha.state;

/**
 * What the overflow tier holds for one or more {@link RowStore}s, and what compaction has done to it
 * (ADR-044).
 *
 * @param overflowBytesReserved overflow slab bytes held now -- the mapped files on disk
 * @param overflowBytesLive bytes in live blocks inside those slabs
 * @param compactions compaction passes that found a slab worth emptying
 * @param slabsReleased overflow slabs compaction released
 * @param blocksRelocated blocks compaction moved
 */
public record SpillStatistics(
        long overflowBytesReserved,
        long overflowBytesLive,
        long compactions,
        long slabsReleased,
        long blocksRelocated) {

    /** Nothing spilled, nothing compacted. */
    public static final SpillStatistics NONE = new SpillStatistics(0, 0, 0, 0, 0);

    /** One store's numbers. */
    public static SpillStatistics of(RowStore store) {
        return new SpillStatistics(
                store.overflowBytesReserved(),
                store.overflowBytesLive(),
                store.compactions(),
                store.slabsReleased(),
                store.blocksRelocated());
    }

    /** Two sets of numbers added together, for an operator or a pipeline holding several stores. */
    public SpillStatistics plus(SpillStatistics other) {
        return new SpillStatistics(
                overflowBytesReserved + other.overflowBytesReserved,
                overflowBytesLive + other.overflowBytesLive,
                compactions + other.compactions,
                slabsReleased + other.slabsReleased,
                blocksRelocated + other.blocksRelocated);
    }

    /**
     * How much of the overflow tier held is not live state: {@code 0} with nothing spilled. Measured
     * against whole slabs, so the unfilled tail of the slab being carved counts -- this is the
     * footprint question ("how much bigger are my files than my state"), where {@link
     * RowStore#overflowFragmentation} is the compaction trigger.
     */
    public double fragmentation() {
        return overflowBytesReserved == 0 ? 0 : 1.0 - (double) overflowBytesLive / overflowBytesReserved;
    }
}
