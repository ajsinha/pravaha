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
package com.ash.messaging.pravaha.runtime.exec;

import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * A chain of filters and at most one projection, compiled to Java, as the pipeline runs it.
 *
 * <p>Declared here and implemented by generated classes in {@code pravaha-codegen}, which depends on
 * this module and not the other way round. The two methods are the chain's two halves -- the test
 * and the row it writes -- because the pipeline allocates an output row only for a row that passes,
 * exactly as the interpreted projection does, so the arena behaves the same on either path.
 */
public interface GeneratedRowStage {

    /** Whether the row at {@code row} passes every filter of the chain. */
    boolean test(MemoryRegion region, int row);

    /** Whether the chain ends in a projection; without one a passing row goes downstream unchanged. */
    boolean projects();

    /**
     * Writes the projection of the row at {@code row} into {@code out} at {@code outRow}, header
     * included. Called only for a row that passed {@link #test}, and only when {@link #projects}.
     */
    void project(MemoryRegion region, int row, MemoryRegion out, int outRow);
}
