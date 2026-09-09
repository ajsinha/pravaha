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

import com.ash.messaging.pravaha.api.data.RowView;

/**
 * One stage of execution.
 *
 * <p>Push-based: a stage is handed a row and pushes whatever it produces to the next. That is the
 * opposite of Calcite's pull-based Enumerable convention and the reason the 1.0 draft's execution
 * model had to be replaced (design gap G1) -- pushing lets a batch stay a counted loop the JIT can
 * unroll, and lets a filter that rejects a row simply return without the next stage being involved
 * at all.
 *
 * <p>Interpreted. Wave 3 fuses a whole chain of these into one generated method; this interface is
 * what the fallback keeps implementing and what the differential tests check generated code against.
 */
@FunctionalInterface
public interface RowProcessor {

    /** Processes one row, pushing any output downstream. */
    void process(RowView row);

    /** Called when the input ends, so stateful stages can emit their final result. */
    default void finish() {}
}
