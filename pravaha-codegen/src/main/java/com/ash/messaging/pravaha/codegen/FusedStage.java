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
package com.ash.messaging.pravaha.codegen;

import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * What a generated stage implements.
 *
 * <p>Batch-at-a-time rather than row-at-a-time. Handing the generated method a whole batch is what
 * makes its loop counted, which is what lets the JIT unroll and vectorise it; a per-row entry point
 * would put a call boundary in the middle of the hot loop and give most of the benefit back
 * (design section 13.4).
 *
 * <p>Offsets rather than objects, for the same reason the row layout exists: an object per row is
 * the allocation the whole design is built to avoid.
 */
public interface FusedStage {

    /**
     * Processes a batch.
     *
     * @param region where the input rows live
     * @param rowOffsets byte offsets of the input rows
     * @param count how many of {@code rowOffsets} are valid
     * @param out where output rows are written
     * @param outOffsets pre-allocated offsets for output rows
     * @param outBase index into {@code outOffsets} to start writing at
     * @return how many rows were emitted; may be fewer than {@code count} if rows were filtered
     */
    int process(MemoryRegion region, long[] rowOffsets, int count, MemoryRegion out, long[] outOffsets, int outBase);
}
