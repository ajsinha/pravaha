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
package com.ash.messaging.pravaha.runtime.plan;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;

/**
 * Attaches window boundaries to each row.
 *
 * <p>The plan-level counterpart of SQL's {@code TABLE(TUMBLE(...))}: it adds {@code window_start}
 * and {@code window_end} to the row so that a plain {@code GROUP BY} above it becomes a windowed
 * aggregate. Keeping assignment and aggregation as separate operators is what lets one windowing
 * implementation serve every aggregate, and what makes the window visible in {@code EXPLAIN} rather
 * than hidden inside an aggregate's configuration.
 *
 * <p>No rows are held here and none are emitted late. Assignment is a pure function of a row's event
 * time -- which is exactly why the expensive part of windowing lives in the aggregate, where the
 * state is, and not here.
 *
 * @param eventTimeOrdinal the column carrying event time, resolved at planning so the runtime never
 *     looks a column up by name
 */
public record WindowAssignOperator(
        PhysicalOperator input, StreamSchema outputSchema, WindowSpec spec, int eventTimeOrdinal)
        implements PhysicalOperator {

    public WindowAssignOperator {
        if (eventTimeOrdinal < 0) {
            throw new IllegalArgumentException("the event-time column must be resolved at planning time");
        }
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public String label() {
        return "WindowAssign(" + spec.kind() + " size=" + spec.sizeNanos() / 1_000_000 + "ms slide="
                + spec.slideNanos() / 1_000_000 + "ms on "
                + outputSchema.field(eventTimeOrdinal).name() + ")";
    }

    @Override
    public String identity() {
        return "WindowAssign(" + spec + ", time=" + eventTimeOrdinal + ")";
    }
}
