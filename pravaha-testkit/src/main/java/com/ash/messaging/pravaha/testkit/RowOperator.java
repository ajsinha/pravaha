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
package com.ash.messaging.pravaha.testkit;

import com.ash.messaging.pravaha.common.row.BinaryRowView;

/**
 * A stage in a test pipeline.
 *
 * <p>Deliberately minimal and interpreted. Wave 2 replaces this with fused, generated operators
 * (design section 12); this exists so the harness can exercise the real row, arena and ring machinery
 * before those arrive, and so the differential tests in Wave 2 have an independent implementation to
 * compare generated code against.
 */
@FunctionalInterface
public interface RowOperator {

    /**
     * Processes one row.
     *
     * @param input the row, already positioned
     * @param output where to emit; may be called zero or more times
     */
    void process(BinaryRowView input, RowEmitter output);
}
