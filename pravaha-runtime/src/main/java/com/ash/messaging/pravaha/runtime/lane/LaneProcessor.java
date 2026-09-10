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
     * Processes one batch that arrived on a named input.
     *
     * <p>Only a join has more than one input, and only a join needs to tell them apart -- the two
     * sides of {@code orders JOIN users} are different schemas going to different state. Everything
     * else ignores the number, which is why this defaults to the single-input form rather than
     * making every processor in the codebase carry a parameter it will never read.
     *
     * <p>A processor that does not override this and is nonetheless given a second input is a plan
     * that was built wrong, so it says so rather than quietly treating the right side as the left.
     *
     * @param input which of the lane's inputs the rows arrived on, counting from zero
     */
    default int onBatch(int input, MemoryRegion region, long[] rowOffsets, int count) {
        if (input != 0) {
            throw new IllegalStateException(getClass().getSimpleName() + " has one input; rows arrived on input "
                    + input + ", which means the plan and the lane disagree about this query's shape");
        }
        return onBatch(region, rowOffsets, count);
    }

    /**
     * Called on the lane thread when there is no work waiting.
     *
     * <p>For anything holding a record it could finish but has not been asked to. A lookup join
     * parks records on a network round trip and pushes them out when the next record arrives -- so
     * a stream that goes quiet would leave its last few unanswered for as long as the quiet lasts,
     * which is the same latency bug as a watermark that only advances on arrival.
     *
     * <p>Called on every idle cycle, so an implementation that has nothing to do must be cheap: a
     * lane with no work spins here.
     */
    default void onIdle() {}

    /**
     * Called when the lane has stopped, on the lane thread, exactly once.
     *
     * <p>Runs even when the lane is stopping because the processor threw, so a half-built stage
     * still gets to release what it acquired.
     */
    @Override
    default void close() {}
}
