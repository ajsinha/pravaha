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

import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * What a lane runs.
 *
 * <p>Batch-at-a-time, and the signature is deliberately the first three parameters of the code
 * generator's {@code FusedStage}: a generated stage is adapted by a lambda that supplies its own
 * output region, and the interpreted chain by one that walks the rows.
 * The lane knows neither, which is what lets the same lane run generated code today and fall back to
 * the interpreter mid-query without the lane changing at all (design section 12.4).
 *
 * <p><strong>Confined to the lane thread.</strong> Every call happens on the lane's own thread, so
 * an implementation may hold whatever mutable state it likes and needs no synchronisation for it.
 * That guarantee is only worth anything if each lane gets its own instance, which is why lanes are
 * built from a {@link LaneProcessorFactory} rather than from a processor.
 */
public interface LaneProcessor extends AutoCloseable {

    /**
     * Processes one batch of input rows.
     *
     * <p>The rows are flyweights into {@code region}, and that region is reclaimed as soon as this
     * returns. Anything the processor keeps must be copied.
     *
     * @param region where the input rows live
     * @param rowOffsets byte offsets of the input rows within {@code region}
     * @param count how many of {@code rowOffsets} are valid
     * @return how many rows were emitted downstream, which is what the lane reports as its output
     *     rate; fewer than {@code count} when rows were filtered
     */
    int onBatch(MemoryRegion region, long[] rowOffsets, int count);

    /**
     * Called when the lane has stopped, on the lane thread, exactly once.
     *
     * <p>Runs even when the lane is stopping because the processor threw, so a half-built stage
     * still gets to release what it acquired.
     */
    @Override
    default void close() {}
}
