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
package com.ash.messaging.pravaha.runtime.window;

import org.jspecify.annotations.Nullable;

/**
 * A temporary, on-heap copy of one accumulator -- what a window's slices are combined into when it
 * fires, and what a checkpoint entry is read through. The live state is never one of these: it is
 * off-heap, in {@link OffHeapAccumulators}.
 */
final class SliceAccumulator {

    final long[] values;

    /**
     * Per column, how many non-null values have been accumulated.
     *
     * <p>Needed twice over. {@code COUNT(col)} counts non-null values and was counting rows, and
     * {@code AVG} must divide by the non-null count rather than the row count -- and the caller
     * flattens a null to 0 before this class ever sees it, so the value cannot say.
     */
    final long[] nonNull;

    Object @Nullable [] keyValues;

    long count;

    SliceAccumulator(int columns) {
        this.values = new long[columns];
        this.nonNull = new long[columns];
    }
}
